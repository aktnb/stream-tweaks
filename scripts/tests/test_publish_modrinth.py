"""Retry publication against remote mocks, preserving real bundle/JAR verification."""
import copy
import hashlib
import json
import pathlib
import os
import re
import shutil
import subprocess
import unittest
import urllib.parse

from scripts import publish_github as pg, publish_modrinth as pm
from scripts.tests import test_publish_github as github_fixtures
from scripts.tests import test_release_bundle as bundle_fixtures


class FakeModrinth:
    def __init__(self):
        self.versions = []
        self.calls = []
        self.fail_loader = set()
        self.lose_loader = set()
        self.hide_versions = False

    def request(self, method, path, data=None, *, filename=None, content=None):
        self.calls.append((method, path, copy.deepcopy(data)))
        if method == 'GET' and path.startswith('/project/'):
            return [] if self.hide_versions else copy.deepcopy(self.versions)
        if method == 'GET' and path.startswith('/version_file/'):
            digest = path.split('/')[2].split('?')[0]
            self.assert_algorithm(path)
            for v in ([] if self.hide_versions else self.versions):
                if v['files'][0]['hashes']['sha512'] == digest:
                    return copy.deepcopy(v)
            raise pm.ApiError(404)
        if method == 'POST' and path == '/version':
            loader = data['loaders'][0]
            if loader in self.fail_loader:
                raise pm.ApiError(None)
            v = {k: copy.deepcopy(value) for k, value in data.items() if k not in {'file_parts', 'primary_file'}}
            v.update(id='version' + str(len(self.versions) + 1), files=[{
                'filename': filename, 'size': len(content), 'primary': True, 'file_type': None,
                'hashes': {'sha512': hashlib.sha512(content).hexdigest()}}])
            self.versions.append(v)
            if loader in self.lose_loader:
                self.lose_loader.remove(loader)
                raise pm.ApiError(None)
            return copy.deepcopy(v)
        raise AssertionError((method, path))

    @staticmethod
    def assert_algorithm(path):
        if urllib.parse.parse_qs(urllib.parse.urlsplit(path).query) != {'algorithm': ['sha512']}:
            raise AssertionError('hash API must use SHA512')


class ModrinthPublishTest(unittest.TestCase):
    def setUp(self):
        self.fixture = bundle_fixtures.ReleaseBundleTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.create()
        self.bundle = self.fixture.output
        self.github = github_fixtures.FakeGitHub()
        self.modrinth = FakeModrinth()
        self.options = {'expected_source_sha': 'a' * 40,
                        'expected_manifest_sha256': hashlib.sha256((self.bundle / pg.rb.MANIFEST).read_bytes()).hexdigest()}
        self.state_output = self.fixture.directory / 'publish-state.json'

    def publish(self, *, finalize=True):
        fn = pm.publish_and_finalize if finalize else pm.reconcile_modrinth
        return fn(self.modrinth, self.github, self.bundle, **self.options, state_output=self.state_output)

    def posts(self, loader=None):
        return [data for method, _, data in self.modrinth.calls if method == 'POST'
                and (loader is None or data['loaders'] == [loader])]

    def test_new_publish_then_rerun_reuses_versions_without_post_and_bundle_unchanged(self):
        before = {p.name: p.read_bytes() for p in self.bundle.iterdir()}
        result = self.publish()
        self.assertEqual(len(self.posts()), 2)
        self.assertFalse(self.github.release['draft'])
        self.assertEqual(result['github']['status'], 'published')
        self.assertEqual(result['targets']['26.2-fabric']['modrinth']['status'], 'verified')
        self.modrinth.calls.clear()
        again = self.publish()
        self.assertEqual(self.posts(), [])
        self.assertEqual(again['targets'], result['targets'])
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.bundle.iterdir()})
        state = json.loads(self.state_output.read_text())
        self.assertEqual(state['manifest_sha256'], self.options['expected_manifest_sha256'])
        self.assertEqual(state['targets']['26.2-neoforge']['modrinth']['external_id'], 'version2')

    def test_partial_success_then_external_recovery_retries_only_missing_target(self):
        self.modrinth.fail_loader.add('neoforge')
        with self.assertRaisesRegex(pm.PublishError, 'outcome unknown'):
            self.publish()
        self.assertTrue(self.github.release['draft'])
        self.assertEqual(len(self.posts('fabric')), 1)
        self.assertEqual(len(self.posts('neoforge')), 1)
        self.assertEqual(json.loads(self.state_output.read_text())['targets']['26.2-fabric']['modrinth']['status'], 'verified')
        # Unknown is not absence: a later re-query cannot authorize a second POST.
        self.modrinth.fail_loader.clear()
        with self.assertRaisesRegex(pm.PublishError, 'previous attempt'):
            self.publish()
        self.assertEqual(len(self.posts('neoforge')), 1)
        # Simulate the first request becoming visible, not another adapter POST.
        manifest = pg.rb.verify_bundle(self.bundle, **self.options)
        target = manifest['plan']['targets'][1]
        raw = (self.bundle / target['filename']).read_bytes()
        self.modrinth.request('POST', '/version', pm.version_metadata(self.bundle, manifest['plan'], target),
                              filename=target['filename'], content=raw)
        self.modrinth.calls.clear()
        self.publish()
        self.assertEqual(self.posts(), [])
        self.assertFalse(self.github.release['draft'])

    def test_lost_post_response_is_verified_without_duplicate_post(self):
        self.modrinth.lose_loader.add('fabric')
        self.publish()
        self.assertEqual(len(self.posts('fabric')), 1)
        self.assertEqual(len(self.posts()), 2)

    def test_undispatched_second_target_is_published_after_first_receipt_recovery(self):
        # A run may stop between targets (before NeoForge's POST and attempt).
        original = pg.append_publication_record
        def stop_after_fabric_receipt(*args, **kwargs):
            result = original(*args, **kwargs)
            record = args[-1]
            if record['target_id'] == '26.2-fabric' and record['status'] == 'verified':
                raise OSError('process stopped after durable receipt')
            return result
        from unittest.mock import patch
        with patch.object(pg, 'append_publication_record', side_effect=stop_after_fabric_receipt):
            with self.assertRaises(OSError):
                self.publish()
        self.modrinth.calls.clear()
        self.publish()
        self.assertEqual(len(self.posts()), 1)
        self.assertEqual(self.posts()[0]['loaders'], ['neoforge'])

    def test_wrong_project_loader_mc_hash_or_contract_stops_before_any_post(self):
        self.publish(finalize=False)
        original = copy.deepcopy(self.modrinth.versions)
        mutations = [('project_id', 'wrong'), ('loaders', ['neoforge']), ('game_versions', ['26.1.2']),
                     ('version_type', 'beta'), ('environment', 'client_and_server'), ('changelog', 'wrong'),
                     ('dependencies', []), ('files', [dict(original[0]['files'][0], hashes={'sha512': '0' * 128})])]
        for key, value in mutations:
            with self.subTest(key=key):
                self.modrinth.versions = copy.deepcopy(original)
                self.modrinth.versions[0][key] = value
                self.modrinth.calls.clear()
                with self.assertRaises(pm.PublishError):
                    self.publish()
                self.assertEqual(self.posts(), [])
                self.assertTrue(self.github.release['draft'])

    def test_unknown_attempt_survives_loss_of_local_state(self):
        self.modrinth.fail_loader.add('fabric')
        with self.assertRaises(pm.PublishError):
            self.publish()
        self.state_output.unlink()
        self.modrinth.fail_loader.clear()
        with self.assertRaisesRegex(pm.PublishError, 'previous attempt'):
            self.publish()
        self.assertEqual(len(self.posts('fabric')), 1)
        self.assertTrue(self.github.release['draft'])

    def test_journal_save_failure_prevents_external_post(self):
        pg.stage_bundle(self.github, self.bundle, **self.options)
        self.github.fail_before.add(('POST', '/releases/1/assets'))
        with self.assertRaises(pg.PublishError):
            self.publish()
        self.assertEqual(self.posts(), [])

    def test_lost_journal_upload_response_requeried_without_duplicate_attempt(self):
        pg.stage_bundle(self.github, self.bundle, **self.options)
        self.github.lose.add(('POST', '/releases/1/assets'))
        self.publish()
        names = [urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)['name'][0]
                 for method, path, _ in self.github.calls if method == 'POST' and '/assets?' in path]
        attempts = [n for n in names if n.endswith('-attempt.json')]
        self.assertEqual(len(attempts), 2)
        self.assertEqual(len(set(attempts)), 2)

    def test_unknown_record_or_altered_receipt_stops(self):
        self.publish(finalize=False)
        name = next(n for n in self.github.files if n.endswith('-verified.json'))
        asset = self.github.files[name]
        record = json.loads(self.github.raw[asset['id']])
        record['manifest_sha256'] = '0' * 64
        raw = pg.rb.canonical_json(record).encode()
        self.github.raw[asset['id']] = raw
        asset.update(size=len(raw), digest='sha256:' + hashlib.sha256(raw).hexdigest())
        self.modrinth.calls.clear()
        with self.assertRaises(pg.PublishError):
            self.publish()
        self.assertEqual(self.posts(), [])

    def test_other_required_destination_prevents_finalize(self):
        manifest_path = self.bundle / pg.rb.MANIFEST
        manifest = json.loads(manifest_path.read_text())
        for target in manifest['plan']['targets']:
            target['destinations'].append({'name': 'curseforge', 'project': '1234', 'required': True})
        plan = manifest['plan']
        (self.bundle / pg.rb.PLAN).write_text(pg.rp.canonical_json(plan))
        manifest_path.write_text(pg.rb.canonical_json(manifest))
        self.options['expected_manifest_sha256'] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(pm.PublishError, 'other required'):
            self.publish()
        self.assertTrue(self.github.release['draft'])

    def test_wrong_source_or_manifest_stops_before_any_network(self):
        self.options['expected_source_sha'] = 'b' * 40
        with self.assertRaises(pg.rb.BundleError):
            self.publish()
        self.assertEqual(self.github.calls, [])
        self.assertEqual(self.modrinth.calls, [])

    def test_restore_ignores_valid_journal_but_returns_identical_bundle_only(self):
        self.publish(finalize=False)
        restored = self.fixture.directory / 'restored'
        pg.restore_bundle(self.github, self.fixture.plan['tag'], restored, **self.options)
        self.assertEqual({p.name: p.read_bytes() for p in self.bundle.iterdir()},
                         {p.name: p.read_bytes() for p in restored.iterdir()})
        self.assertTrue(any(name.startswith('publish-state-') for name in self.github.files))

    def test_workflow_cutover_has_single_publish_path_and_token_is_step_scoped(self):
        workflow = (pathlib.Path(pm.__file__).parent.parent / '.github/workflows/release.yml').read_text()
        self.assertNotIn('mc-publish@', workflow)
        self.assertNotIn('legacy', workflow)
        before_publish, publish_job = workflow.split('\n  publish:\n', 1)
        self.assertNotIn('MODRINTH_TOKEN:', before_publish)
        self.assertNotIn('inputs.operation', workflow)
        self.assertNotIn('      operation:', workflow)
        self.assertIn('name: release-publish', publish_job)
        self.assertEqual(publish_job.count('      - id: publish\n'), 1)
        publish_step = publish_job.split('      - id: publish\n', 1)[1]
        self.assertIn('publish_modrinth.py publish', publish_step)
        self.assertIn("steps.publish.outcome == 'success'", publish_step)
        self.assertIn('if: always()', publish_step)
        self.assertIn('MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}', publish_step)
        self.assertNotIn('rp.build_plan', workflow.split('  github-inputs:\n', 1)[1])

    def test_actual_workflow_identity_scripts_validate_release_bundle_and_stage_plan(self):
        # The dispatch path always builds a new plan from the main source checkout.
        self.fixture.plan = pg.rp.validate_plan(self.fixture.plan | {'source_branch': 'main'})
        shutil.rmtree(self.bundle)
        self.fixture.create()
        self.options['expected_manifest_sha256'] = hashlib.sha256(
            (self.bundle / pg.rb.MANIFEST).read_bytes()).hexdigest()
        workflow = (pathlib.Path(pm.__file__).parent.parent / '.github/workflows/release.yml').read_text()
        gate = re.search(r'^  github-inputs:\n(.*?)(?=^  [a-zA-Z][\w-]*:|\Z)', workflow, re.M | re.S)[1]
        gate_code = re.search(r"          python3 - <<'PY'\n(.*?)^          PY", gate, re.M | re.S)[1]
        gate_code = '\n'.join(line[10:] for line in gate_code.splitlines())
        bundle_artifact = 'release-bundle-123-' + 'a' * 40 + '-attempt-1'
        env = dict(os.environ, VERSION=self.fixture.plan['mod_version'], CHANNEL='release', LOADERS='both',
                   SOURCE_BRANCH='main', SOURCE_REF='refs/heads/main', CURSEFORGE_PROJECT='',
                   CURSEFORGE_REQUIRED='false',
                   PLAN_RESULT='success', BUNDLE_BUILD_RESULT='success', SOURCE_SHA='a' * 40,
                   MANIFEST_SHA256=self.options['expected_manifest_sha256'],
                   BUNDLE_BUILD_MANIFEST_SHA256=self.options['expected_manifest_sha256'],
                   BUNDLE_BUILD_ARTIFACT=bundle_artifact,
                   RUN_ID='123', RUN_ATTEMPT='1',
                   REPOSITORY='aktnb/stream-tweaks',
                   RUNNER_TEMP=str(self.fixture.directory), GITHUB_OUTPUT=str(self.fixture.directory / 'outputs'))
        shutil.copytree(self.bundle, self.fixture.directory / 'release-bundle')
        root = pathlib.Path(pm.__file__).parent.parent
        result = subprocess.run([__import__('sys').executable, '-c', gate_code], env=env, cwd=root,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        tag_ref_env = env | {'SOURCE_REF': 'refs/tags/main'}
        tag_ref_result = subprocess.run([__import__('sys').executable, '-c', gate_code], env=tag_ref_env, cwd=root,
                                        capture_output=True, text=True)
        self.assertNotEqual(tag_ref_result.returncode, 0)
        outputs = dict(line.split('=', 1) for line in (self.fixture.directory / 'outputs').read_text().splitlines())
        identity = json.loads(outputs['expected-identity'])
        self.assertEqual(identity['source_branch'], 'main')
        self.assertEqual(outputs['bundle-artifact'], bundle_artifact)
        shutil.copytree(self.bundle, self.fixture.directory / 'github-bundle')
        env.update(EXPECTED_IDENTITY=outputs['expected-identity'], TAG=outputs['tag'],
                   RELEASE_RUN_ID='123', RELEASE_RUN_ATTEMPT='1',
                   RELEASE_REPOSITORY='aktnb/stream-tweaks')
        block = workflow.split('      - name: Verify bundle and stage missing assets only', 1)[1]
        block = block.split('      - name: Summarize publication state', 1)[0]
        code = re.search(r"          python3 - <<'PY'\n(.*?)^          PY", block, re.M | re.S)[1]
        code = '\n'.join(line[10:] for line in code.splitlines())
        result = subprocess.run([__import__('sys').executable, '-c', code], env=env, cwd=root,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        expected_plan = self.fixture.directory / 'github-expected-plan.json'
        self.assertEqual(pg.rp.load_plan(expected_plan), self.fixture.plan)
        requested = json.loads(env['EXPECTED_IDENTITY'])
        requested['loaders'] = ['fabric']
        env['EXPECTED_IDENTITY'] = json.dumps(requested)
        result = subprocess.run([__import__('sys').executable, '-c', code], env=env, cwd=root,
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('target set mismatch', result.stderr)

    def test_project_versions_is_unpaginated_including_100_and_101_rows(self):
        for count in [100, 101]:
            with self.subTest(count=count):
                self.modrinth.versions = [{'id': str(i), 'version_number': 'older-' + str(i)} for i in range(count)]
                self.modrinth.calls.clear()
                self.assertEqual(len(pm.project_versions(self.modrinth, 'jWHdryID')), count)
                self.assertEqual(self.modrinth.calls, [('GET', '/project/jWHdryID/version', None)])


if __name__ == '__main__':
    unittest.main()
