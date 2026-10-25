"""Remote-state retry tests with actual immutable bundle/JAR fixtures and no network."""
import copy
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import unittest
import urllib.parse

from scripts import publish_github as pg
from scripts.tests import test_release_bundle as bundle_fixtures


class FakeGitHub:
    def __init__(self):
        self.ref = None
        self.annotations = {}
        self.release = None
        self.files = {}
        self.raw = {}
        self.calls = []
        self.lose = set()
        self.fail_before = set()
        self.next_id = 10
        self.run = {'id': 123, 'event': 'workflow_dispatch', 'conclusion': 'success', 'head_sha': 'a' * 40,
                    'path': '.github/workflows/release.yml', 'repository': {'full_name': pg.REPOSITORY},
                    'head_repository': {'full_name': pg.REPOSITORY}, 'head_branch': '26.2'}

    def request(self, method, path, data=None, *, binary=False):
        self.calls.append((method, path, copy.deepcopy(data)))
        route = path.removeprefix(pg.BASE)
        parsed = urllib.parse.urlsplit(route)
        if (method, parsed.path) in self.fail_before:
            raise pg.ApiError(None)
        if route.startswith('/git/ref/tags/'):
            if self.ref is None:
                raise pg.ApiError(404)
            result = self.ref
        elif method == 'GET' and route.startswith('/git/tags/'):
            result = self.annotations[route.rsplit('/', 1)[-1]]
        elif method == 'POST' and route == '/git/tags':
            sha = 'c' * 40
            result = {'sha': sha, 'tag': data['tag'], 'object': {'type': 'commit', 'sha': data['object']}}
            self.annotations[sha] = result
        elif method == 'POST' and route == '/git/refs':
            result = {'ref': data['ref'], 'object': {'type': 'tag', 'sha': data['sha']}}
            self.ref = result
        elif method == 'GET' and parsed.path == '/releases':
            result = [self.release] if self.release else []
        elif method == 'POST' and route == '/releases':
            self.release = dict(data, id=1)
            result = self.release
        elif method == 'GET' and parsed.path == '/releases/1/assets':
            result = list(self.files.values())
        elif method == 'POST' and parsed.path == '/releases/1/assets':
            name = urllib.parse.parse_qs(parsed.query)['name'][0]
            if name in self.files:
                raise pg.ApiError(422)
            self.next_id += 1
            result = {'id': self.next_id, 'name': name, 'size': len(data), 'state': 'uploaded',
                      'digest': 'sha256:' + hashlib.sha256(data).hexdigest()}
            self.files[name] = result
            self.raw[self.next_id] = data
        elif method == 'GET' and route.startswith('/releases/assets/'):
            result = self.raw[int(route.rsplit('/', 1)[-1])]
        elif method == 'PATCH' and route == '/releases/1':
            self.release.update(data)
            result = self.release
        elif method == 'GET' and route == '/actions/runs/123':
            result = self.run
        else:
            raise AssertionError((method, route))
        if (method, parsed.path) in self.lose:
            self.lose.remove((method, parsed.path))
            raise pg.ApiError(None)
        return copy.deepcopy(result)


class GitHubPublishTest(unittest.TestCase):
    def setUp(self):
        self.fixture = bundle_fixtures.ReleaseBundleTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.create()
        self.bundle = self.fixture.output
        self.client = FakeGitHub()
        self.options = {'expected_source_sha': 'a' * 40,
                        'expected_manifest_sha256': hashlib.sha256((self.bundle / pg.rb.MANIFEST).read_bytes()).hexdigest()}

    def stage(self):
        return pg.stage_bundle(self.client, self.bundle, **self.options)

    def mutations(self):
        return [(method, path) for method, path, _ in self.client.calls if method != 'GET']

    def test_new_tag_and_all_assets_staged_then_second_run_has_no_writes(self):
        result = self.stage()
        self.assertEqual(result['github']['status'], 'staged')
        self.assertTrue(self.client.release['draft'])
        self.assertEqual(set(self.client.files), {p.name for p in self.bundle.iterdir()})
        self.client.calls.clear()
        self.assertEqual(self.stage(), result)
        self.assertEqual(self.mutations(), [])

    def test_existing_same_annotated_tag_reused_wrong_sha_and_lightweight_rejected(self):
        self.stage()
        self.client.annotations['c' * 40]['object']['sha'] = 'b' * 40
        self.client.calls.clear()
        with self.assertRaisesRegex(pg.PublishError, 'different source'):
            self.stage()
        self.assertEqual(self.mutations(), [])
        self.client.ref['object']['type'] = 'commit'
        with self.assertRaisesRegex(pg.PublishError, 'not annotated'):
            self.stage()

    def test_wrong_existing_asset_fails_before_any_missing_upload(self):
        self.stage()
        missing = next(iter(self.client.files))
        self.client.files.pop(missing)
        changed = next(iter(self.client.files.values()))
        wrong = b'x' * changed['size']
        self.client.raw[changed['id']] = wrong
        changed['digest'] = 'sha256:' + hashlib.sha256(wrong).hexdigest()
        self.client.calls.clear()
        with self.assertRaises(pg.PublishError):
            self.stage()
        self.assertEqual(self.mutations(), [])

    def test_only_missing_assets_uploaded_without_overwrite(self):
        self.stage()
        missing = next(iter(self.client.files))
        self.client.files.pop(missing)
        self.client.calls.clear()
        self.stage()
        self.assertEqual(len(self.mutations()), 1)
        self.assertIn('name=' + urllib.parse.quote(missing, safe=''), self.mutations()[0][1])

    def test_upload_success_response_lost_is_recognized_without_duplicate_post(self):
        self.client.lose.add(('POST', '/releases/1/assets'))
        self.stage()
        posts = [path for method, path in self.mutations() if '/assets?' in path]
        self.assertEqual(len(posts), len(self.client.files))
        self.assertEqual(len(posts), len(set(posts)))

    def test_create_release_and_tag_ref_lost_responses_requeried(self):
        self.client.lose.update({('POST', '/git/refs'), ('POST', '/releases')})
        self.stage()
        self.assertEqual(sum(path == pg.BASE + '/releases' for _, path in self.mutations()), 1)

    def test_unknown_upload_stops_without_repost_or_deletion(self):
        self.client.fail_before.add(('POST', '/releases/1/assets'))
        with self.assertRaisesRegex(pg.PublishError, 'outcome unknown'):
            self.stage()
        self.assertEqual(sum('/assets?' in path for _, path in self.mutations()), 1)
        self.assertFalse(any(method == 'DELETE' for method, _ in self.mutations()))

    def test_lost_tag_object_without_ref_stops_and_never_creates_release(self):
        self.client.lose.add(('POST', '/git/tags'))
        with self.assertRaisesRegex(pg.PublishError, 'tag creation outcome'):
            self.stage()
        self.assertIsNone(self.client.release)
        self.assertIsNone(self.client.ref)
        self.assertFalse(any(path.endswith('/git/refs') for _, path in self.mutations()))

    def test_incomplete_asset_and_unexpected_asset_or_release_channel_stop(self):
        self.stage()
        asset = next(iter(self.client.files.values()))
        asset['state'] = 'starter'
        with self.assertRaisesRegex(pg.PublishError, 'upload incomplete'):
            self.stage()
        asset['state'] = 'uploaded'
        self.client.files['unexpected'] = dict(asset, name='unexpected')
        with self.assertRaisesRegex(pg.PublishError, 'unexpected release assets'):
            self.stage()
        self.client.files.pop('unexpected')
        self.client.release['prerelease'] = True
        with self.assertRaisesRegex(pg.PublishError, 'channel mismatch'):
            self.stage()

    def test_restore_exact_binary_bundle_and_no_remote_mutations(self):
        self.stage()
        self.client.calls.clear()
        output = self.fixture.directory / 'restored'
        plan = pg.restore_bundle(self.client, self.fixture.plan['tag'], output, **self.options)
        self.assertEqual(plan, self.fixture.plan)
        self.assertEqual({p.name: p.read_bytes() for p in output.iterdir()},
                         {p.name: p.read_bytes() for p in self.bundle.iterdir()})
        self.assertEqual(self.mutations(), [])

    def test_restore_missing_or_wrong_manifest_stops_without_rebuild(self):
        self.stage()
        for options in [self.options | {'expected_manifest_sha256': 'b' * 64}, self.options]:
            if options == self.options:
                self.client.files.pop(self.fixture.plan['targets'][0]['filename'])
            output = self.fixture.directory / 'restored'
            self.client.calls.clear()
            with self.assertRaises(pg.PublishError):
                pg.restore_bundle(self.client, self.fixture.plan['tag'], output, **options)
            self.assertFalse(output.exists())
            self.assertEqual(self.mutations(), [])

    def test_local_missing_tampered_or_wrong_sha_has_zero_api_calls(self):
        with self.assertRaises(pg.rb.BundleError):
            pg.stage_bundle(self.client, self.bundle, **(self.options | {'expected_source_sha': 'b' * 40}))
        jar = self.bundle / self.fixture.plan['targets'][0]['filename']
        jar.unlink()
        with self.assertRaises(OSError):
            self.stage()
        self.assertEqual(self.client.calls, [])

    def test_finalize_requires_all_selected_required_destinations_and_rechecks_assets(self):
        with self.assertRaisesRegex(pg.PublishError, 'required publication'):
            pg.finalize_release(self.client, self.bundle, required_successes=[], **self.options)
        self.assertEqual(self.client.calls, [])
        receipts = [(t['id'], d['name']) for t in self.fixture.plan['targets'] for d in t['destinations'] if d['required']]
        self.client.lose.add(('PATCH', '/releases/1'))
        result = pg.finalize_release(self.client, self.bundle, required_successes=receipts, **self.options)
        self.assertEqual(result['github']['status'], 'published')
        self.assertFalse(self.client.release['draft'])

    def test_fork_pr_failed_wrong_workflow_or_sha_artifacts_rejected(self):
        pg.validate_build_run(self.client, '123', 'a' * 40)
        original = copy.deepcopy(self.client.run)
        for change in [{'event': 'pull_request'}, {'conclusion': 'failure'}, {'head_sha': 'b' * 40},
                       {'path': '.github/workflows/build.yml'}, {'head_repository': {'full_name': 'attacker/fork'}},
                       {'head_branch': 'feature/untrusted'}]:
            self.client.run = original | change
            with self.subTest(change=change), self.assertRaises((pg.PublishError, pg.vt.ValidationError)):
                pg.validate_build_run(self.client, '123', 'a' * 40)

    def test_redirect_does_not_forward_authorization_and_rejects_untrusted_hosts(self):
        request = pg.urllib.request.Request('https://api.github.com/example', headers={'Authorization': 'Bearer secret'})
        handler = pg.DownloadRedirect()
        redirected = handler.redirect_request(request, None, 302, '', {},
                                               'https://release-assets.githubusercontent.com/signed?key=value')
        self.assertFalse(redirected.has_header('Authorization'))
        with self.assertRaises(pg.PublishError):
            handler.redirect_request(request, None, 302, '', {}, 'https://attacker.invalid/asset')

    def test_cli_errors_never_print_token_or_server_response(self):
        result = subprocess.run([sys.executable, str(pathlib.Path(pg.__file__)), 'restore', '--source-sha', 'a' * 40,
                                 '--manifest-sha256', 'b' * 64, '--tag', 'invalid', '--output', '/tmp/not-created'],
                                env={'PATH': '/usr/bin:/bin', 'GITHUB_TOKEN': ''}, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('publication stopped', result.stderr)

    def test_operational_workflow_only_accepts_verified_runs_and_canonical_tag_lock(self):
        workflow = (pathlib.Path(pg.__file__).parent.parent / '.github/workflows/release.yml').read_text()
        gate = re.search(r'^  github-inputs:\n(.*?)(?=^  [a-zA-Z][\w-]*:|\Z)', workflow, re.M | re.S)[1]
        stage = re.search(r'^  github-stage:\n(.*?)(?=^  [a-zA-Z][\w-]*:|\Z)', workflow, re.M | re.S)[1]
        self.assertIn('contents: read', gate)
        self.assertNotIn('contents: write', gate)
        self.assertNotIn('gh.validate_build_run', gate)
        self.assertNotIn('inputs.operation', workflow)
        self.assertNotIn('      operation:', workflow)
        self.assertIn('contents: write', stage)
        self.assertIn('group: release-${{ needs.github-inputs.outputs.tag }}', stage)
        self.assertIn('cancel-in-progress: false', stage)
        self.assertIn('release_bundle.py verify', stage)
        self.assertIn("workflow = manifest['provenance']['workflow']", stage)
        self.assertIn("workflow['run_id'] != os.environ['RELEASE_RUN_ID']", stage)
        self.assertNotIn('./gradlew', stage)
        self.assertIn('rp.reuse_plan', stage)
        self.assertNotIn('rp.build_plan', gate)
        self.assertIn('--plan "$RUNNER_TEMP/github-expected-plan.json"', stage)
        self.assertNotIn('  release:\n', workflow)
        self.assertNotIn('mc-publish@', workflow)
        for match in re.finditer(r'^        run: \|\n((?:^          .*\n|^\n)+)', workflow, re.M):
            script = '\n'.join(line[10:] if line else '' for line in match[1].splitlines())
            self.assertNotIn('${{', script)
            result = subprocess.run(['bash', '-n'], input=script, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
