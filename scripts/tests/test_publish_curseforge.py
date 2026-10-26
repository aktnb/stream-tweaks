"""CurseForge optional/required publication with real immutable bundle fixtures."""
import copy
import hashlib
import io
import json
import os
import pathlib
import re
import unittest
from contextlib import redirect_stdout, redirect_stderr
import urllib.parse
from unittest.mock import patch

from scripts import publish_curseforge as cf, publish_github as pg, publish_modrinth as pm
from scripts.tests import test_publish_github as gh_fixtures
from scripts.tests import test_publish_modrinth as mr_fixtures
from scripts.tests import test_release_bundle as bundle_fixtures


class FakeCurseForge:
    def __init__(self):
        self.versions = [{'id': 101, 'gameVersionTypeID': 1, 'name': '26.2'},
                         {'id': 102, 'gameVersionTypeID': 2, 'name': 'Fabric'},
                         {'id': 103, 'gameVersionTypeID': 2, 'name': 'NeoForge'},
                         {'id': 104, 'gameVersionTypeID': 3, 'name': 'Client'}]
        self.files, self.calls = {}, []
        self.next_id = 500
        self.status = 4
        self.lose_response, self.fail_post, self.hide_files = False, False, False
        self.hide_details = False
        self.bad_pagination = False

    def request(self, api, method, path, data=None, *, filename=None, content=None):
        self.calls.append((api, method, path, copy.deepcopy(data)))
        if (api, method, path) == ('upload', 'GET', '/api/game/versions'):
            return copy.deepcopy(self.versions)
        if (api, method, path) == ('core', 'GET', '/v1/mods/1234'):
            return {'data': {'id': 1234, 'gameId': 432}}
        parsed = urllib.parse.urlsplit(path)
        if api == 'core' and method == 'GET' and parsed.path == '/v1/mods/1234/files':
            query = urllib.parse.parse_qs(parsed.query)
            start = int(query['index'][0])
            rows = [] if self.hide_files else list(self.files.values())
            page = rows[start:start + 50]
            return {'data': copy.deepcopy(page), 'pagination': {
                'index': start if not self.bad_pagination else start + 1,
                'pageSize': 50, 'resultCount': len(page), 'totalCount': len(rows)}}
        if api == 'core' and method == 'GET' and parsed.path.startswith('/v1/mods/1234/files/'):
            file_id = int(parsed.path.rsplit('/', 1)[1])
            if self.hide_details or file_id not in self.files:
                raise cf.ApiError(404)
            return {'data': copy.deepcopy(self.files[file_id])}
        if (api, method, path) == ('upload', 'POST', '/api/projects/1234/upload-file'):
            if self.fail_post:
                raise cf.ApiError(None)
            self.next_id += 1
            names = [v['name'] for v in self.versions if v['id'] in data['gameVersions']]
            self.files[self.next_id] = {'id': self.next_id, 'modId': 1234, 'gameId': 432,
                'fileName': filename, 'displayName': data['displayName'], 'fileLength': len(content),
                'releaseType': {'release': 1, 'beta': 2, 'alpha': 3}[data['releaseType']],
                'gameVersions': names, 'hashes': [{'algo': 1, 'value': hashlib.sha1(content).hexdigest()}],
                'fileStatus': self.status, 'isAvailable': self.status in {4, 10}}
            if self.lose_response:
                self.lose_response = False
                raise cf.ApiError(None)
            return {'id': self.next_id}
        raise AssertionError((api, method, path))


class CurseForgePublishTest(unittest.TestCase):
    def setUp(self):
        self.fixture = bundle_fixtures.ReleaseBundleTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.github, self.modrinth, self.curseforge = gh_fixtures.FakeGitHub(), mr_fixtures.FakeModrinth(), FakeCurseForge()
        self.state_output = self.fixture.directory / 'publish-state.json'
        self.options = {'expected_source_sha': 'a' * 40}

    def create(self, *, configured=True, required=False):
        if configured:
            for target in self.fixture.plan['targets']:
                target['destinations'].append({'name': 'curseforge', 'project': '1234', 'required': required})
        self.fixture.create()
        self.bundle = self.fixture.output
        self.options['expected_manifest_sha256'] = hashlib.sha256((self.bundle / pg.rb.MANIFEST).read_bytes()).hexdigest()

    def publish(self, client=True):
        return pm.publish_and_finalize(self.modrinth, self.github, self.bundle, **self.options,
                    curseforge_client=self.curseforge if client else None, state_output=self.state_output)

    def state(self):
        return json.loads(self.state_output.read_text())

    def posts(self):
        return [c for c in self.curseforge.calls if c[1] == 'POST']

    def test_unconfigured_does_not_construct_client_or_make_any_cf_request(self):
        self.create(configured=False)
        with patch.object(cf, 'client_from_environment', side_effect=AssertionError('must not construct')):
            state = self.publish(client=False)
        self.assertEqual(self.curseforge.calls, [])
        self.assertEqual(state['github']['status'], 'published')
        self.assertTrue(all(v['curseforge']['status'] == 'skipped' for v in state['targets'].values()))

    def test_new_and_existing_match_reuses_ids_and_preserves_bundle(self):
        self.create(required=True)
        before = {p.name: p.read_bytes() for p in self.bundle.iterdir()}
        state = self.publish()
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual(state['targets']['26.2-fabric']['curseforge']['external_id'], '501')
        self.curseforge.calls.clear()
        self.publish()
        self.assertEqual(self.posts(), [])
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.bundle.iterdir()} )
        self.assertIn('publish-state-26.2-fabric-curseforge-submitted.json', self.github.files)

    def test_unknown_mc_loader_client_or_duplicate_id_stops_before_post(self):
        self.create(required=True)
        originals = copy.deepcopy(self.curseforge.versions)
        for name in ['26.2', 'Fabric', 'Client']:
            self.curseforge.versions = [r for r in originals if r['name'] != name]
            with self.subTest(name=name), self.assertRaises(cf.PublishError):
                cf.resolve_target_metadata(self.curseforge, '26.2', 'fabric')
            self.assertEqual(self.posts(), [])
        self.curseforge.versions = originals + [copy.deepcopy(originals[0])]
        with self.assertRaises(pm.PublishError):
            self.publish()
        self.assertEqual(self.posts(), [])
        self.assertTrue(self.github.release['draft'])

    def test_existing_wrong_hash_project_loader_mc_and_filename_fail_without_repost(self):
        self.create(required=True)
        self.publish()
        file = self.curseforge.files[501]
        original = copy.deepcopy(file)
        changes = [{'hashes': [{'algo': 1, 'value': '0' * 40}]}, {'modId': 999},
                   {'gameVersions': ['26.2', 'NeoForge', 'Client']},
                   {'gameVersions': ['26.1.2', 'Fabric', 'Client']}, {'fileName': 'wrong.jar'}]
        for values in changes:
            file.clear()
            file.update(copy.deepcopy(original) | values)
            self.curseforge.calls.clear()
            with self.subTest(values=values), self.assertRaises(pm.PublishError):
                self.publish()
            self.assertEqual(self.state()['targets']['26.2-fabric']['curseforge']['status'], 'mismatch')
            self.assertEqual(self.posts(), [])

    def test_pending_review_persists_id_and_rerun_does_not_post_until_approved(self):
        self.create(required=True)
        self.curseforge.status = 3
        with self.assertRaises(pm.PublishError):
            self.publish()
        self.assertTrue(self.github.release['draft'])
        self.assertEqual(self.state()['targets']['26.2-fabric']['curseforge']['status'], 'pending_review')
        self.assertIn('publish-state-26.2-fabric-curseforge-pending_review.json', self.github.files)
        # Review APIs can withhold the binary hash; this never becomes verified.
        for file in self.curseforge.files.values():
            file['hashes'] = []
        self.curseforge.calls.clear()
        with self.assertRaises(pm.PublishError):
            self.publish()
        self.assertEqual(self.posts(), [])
        for target, file in zip(self.fixture.plan['targets'], self.curseforge.files.values()):
            file.update(fileStatus=4, isAvailable=True, hashes=[{'algo': 1, 'value':
                hashlib.sha1((self.bundle / target['filename']).read_bytes()).hexdigest()}])
        self.publish()
        self.assertFalse(self.github.release['draft'])
        self.assertEqual(self.posts(), [])

    def test_response_loss_requeries_same_file_without_duplicate_upload(self):
        self.create(required=True)
        self.curseforge.lose_response = True
        self.publish()
        self.assertEqual(len(self.posts()), 2)
        self.curseforge.calls.clear()
        self.publish()
        self.assertEqual(self.posts(), [])

    def test_unknown_post_remains_stopped_even_if_local_state_deleted(self):
        self.create(required=True)
        self.curseforge.fail_post = True
        with self.assertRaises(pm.PublishError):
            self.publish()
        count = len(self.posts())
        self.state_output.unlink()
        self.curseforge.fail_post = False
        with self.assertRaises(pm.PublishError):
            self.publish()
        self.assertEqual(len(self.posts()), count)
        self.assertTrue(self.github.release['draft'])

    def test_submitted_id_survives_invisible_file_and_uses_exact_id_later(self):
        self.create(required=True)
        self.curseforge.hide_files = self.curseforge.hide_details = True
        with self.assertRaises(pm.PublishError):
            self.publish()
        self.assertEqual(self.state()['targets']['26.2-fabric']['curseforge']['external_id'], '501')
        count = len(self.posts())
        self.curseforge.hide_details = False
        self.publish()
        self.assertEqual(len(self.posts()), count)

    def test_optional_missing_secrets_or_pending_does_not_block_required_destinations(self):
        self.create(required=False)
        with patch.dict(os.environ, {'CURSEFORGE_TOKEN': '', 'CURSEFORGE_API_KEY': ''}):
            state = self.publish(client=False)
        self.assertEqual(state['github']['status'], 'published')
        self.assertEqual(state['targets']['26.2-fabric']['curseforge']['status'], 'unknown')
        self.assertEqual(self.posts(), [])

    def test_optional_pending_review_is_reported_but_finalizes_github(self):
        self.create(required=False)
        self.curseforge.status = 3
        state = self.publish()
        self.assertEqual(state['github']['status'], 'published')
        self.assertEqual(state['targets']['26.2-fabric']['curseforge']['status'], 'pending_review')

    def test_planned_optional_can_be_completed_after_github_publication_without_plan_change(self):
        self.create(required=False)
        before = {p.name: p.read_bytes() for p in self.bundle.iterdir()}
        with patch.dict(os.environ, {'CURSEFORGE_TOKEN': '', 'CURSEFORGE_API_KEY': ''}):
            self.publish(client=False)
        self.assertFalse(self.github.release['draft'])
        result = self.publish()
        self.assertEqual(len(self.posts()), 2)
        self.assertTrue(all(t['curseforge']['status'] == 'verified' for t in result['targets'].values()))
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.bundle.iterdir()})

    def test_optional_pending_can_be_queried_and_completed_after_finalization(self):
        self.create(required=False)
        self.curseforge.status = 3
        self.publish()
        self.assertFalse(self.github.release['draft'])
        self.curseforge.calls.clear()
        self.publish()
        self.assertEqual(self.posts(), [])
        for file in self.curseforge.files.values():
            file.update(fileStatus=10, isAvailable=True)
        result = self.publish()
        self.assertTrue(all(t['curseforge']['status'] == 'verified' for t in result['targets'].values()))
        self.assertEqual(self.posts(), [])

    def test_after_finalization_required_attempts_and_unplanned_cf_are_still_rejected(self):
        self.create(required=True)
        manifest = pg.rb.verify_bundle(self.bundle, **self.options)
        pg.stage_bundle(self.github, self.bundle, **self.options)
        self.github.release['draft'] = False
        for destination in ['modrinth', 'curseforge']:
            record = pg.publication_record(manifest, self.options['expected_manifest_sha256'],
                                           '26.2-fabric', destination, 'attempt')
            with self.subTest(destination=destination), self.assertRaisesRegex(pg.PublishError, 'after finalization'):
                pg.append_publication_record(self.github, 1, manifest,
                                             self.options['expected_manifest_sha256'], record)
        no_cf = copy.deepcopy(manifest)
        for target in no_cf['plan']['targets']:
            target['destinations'] = target['destinations'][:2]
        with self.assertRaises(pg.PublishError):
            pg.publication_record(no_cf, self.options['expected_manifest_sha256'],
                                  '26.2-fabric', 'curseforge', 'attempt')

    def test_required_missing_core_key_stops_without_upload(self):
        self.create(required=True)
        with patch.dict(os.environ, {'CURSEFORGE_TOKEN': 'fixture-upload-only', 'CURSEFORGE_API_KEY': ''}), \
                self.assertRaises(pm.PublishError):
            self.publish(client=False)
        self.assertTrue(self.github.release['draft'])
        self.assertEqual(self.posts(), [])

    def test_journal_write_failure_prevents_any_cf_upload(self):
        self.create(required=True)
        original = pg.append_publication_record
        def fail_cf(*args, **kwargs):
            if args[-1]['destination'] == 'curseforge':
                raise pg.PublishError('fixture journal unavailable')
            return original(*args, **kwargs)
        with patch.object(pg, 'append_publication_record', side_effect=fail_cf), self.assertRaises(pm.PublishError):
            self.publish()
        self.assertEqual(self.posts(), [])

    def test_cf_only_status_does_not_expand_modrinth_journal(self):
        self.create()
        manifest = pg.rb.verify_bundle(self.bundle, **self.options)
        for status in ['submitted', 'pending_review']:
            with self.subTest(status=status), self.assertRaises(pg.PublishError):
                pg.publication_record(manifest, self.options['expected_manifest_sha256'],
                                      '26.2-fabric', 'modrinth', status, external_id='123')
        with self.assertRaises(pg.PublishError):
            pg.publication_record(manifest, self.options['expected_manifest_sha256'],
                                  '26.2-fabric', 'curseforge', 'submitted', external_id='notNumeric')

    def test_listing_paginates_51_items_and_rejects_bad_or_truncated_pages(self):
        for i in range(51):
            self.curseforge.files[i + 1] = {'id': i + 1}
        self.assertEqual(len(cf.project_files(self.curseforge, '1234')), 51)
        self.assertEqual([c[2] for c in self.curseforge.calls], [
            '/v1/mods/1234/files?index=0&pageSize=50', '/v1/mods/1234/files?index=50&pageSize=50'])
        self.curseforge.bad_pagination = True
        with self.assertRaises(cf.PublishError):
            cf.project_files(self.curseforge, '1234')

    def test_upload_and_core_credentials_are_host_scoped_and_redirects_forbidden(self):
        client = cf.CurseForgeClient('upload-fixture', 'core-fixture')
        requests = []
        class Response:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def read(self):
                return b'[]'
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                return Response()
        with patch.object(cf.urllib.request, 'build_opener', return_value=Opener()):
            client.request('upload', 'GET', '/api/game/versions')
            client.request('core', 'GET', '/v1/mods/1234')
        self.assertEqual(requests[0].get_header('X-api-token'), 'upload-fixture')
        self.assertIsNone(requests[0].get_header('X-api-key'))
        self.assertEqual(requests[1].get_header('X-api-key'), 'core-fixture')
        self.assertIsNone(requests[1].get_header('X-api-token'))
        with self.assertRaises(cf.ApiError):
            cf.NoRedirect().redirect_request(None, None, 302, None, None, 'https://other.invalid/')

    def test_workflow_cf_secrets_are_only_on_publish_step(self):
        workflow = (pathlib.Path(__file__).parents[2] / '.github/workflows/old_release.yml.disabled').read_text()
        publish = workflow.split('      - id: publish', 1)[1].split('      - name:', 1)[0]
        for name in ['CURSEFORGE_TOKEN', 'CURSEFORGE_API_KEY']:
            secret = '${{ secrets.' + name + ' }}'
            self.assertEqual(workflow.count(secret), 1)
            self.assertIn(secret, publish)

    def test_preflight_cli_is_get_only_and_publish_cli_uses_saved_optional_contract(self):
        self.create(required=False)
        args = ['--bundle', str(self.bundle), '--source-sha', self.options['expected_source_sha'],
                '--manifest-sha256', self.options['expected_manifest_sha256']]
        output = io.StringIO()
        with patch.object(cf, 'client_from_environment', return_value=self.curseforge), redirect_stdout(output):
            self.assertEqual(cf.main(['preflight'] + args), 0)
        self.assertEqual([t['status'] for t in json.loads(output.getvalue())['targets']], ['absent', 'absent'])
        self.assertEqual(self.posts(), [])
        self.assertIsNone(self.github.release)
        with patch.object(pm, 'ModrinthClient', return_value=self.modrinth), \
                patch.object(pg, 'GitHubClient', return_value=self.github), \
                patch.object(cf, 'client_from_environment', return_value=self.curseforge), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(pm.main(['publish'] + args + ['--state-output', str(self.state_output)]), 0)
        self.assertFalse(self.github.release['draft'])
        self.assertEqual(len(self.posts()), 2)


if __name__ == '__main__':
    unittest.main()
