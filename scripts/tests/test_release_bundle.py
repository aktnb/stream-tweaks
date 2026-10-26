"""Immutable bundle retry and corruption tests, using real metadata/classfile fixtures."""
import copy
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
CATALOG = pathlib.Path(__file__).parent / "fixtures/support-targets-26.2.json"
sys.path.insert(0, str(SCRIPTS))
import release_bundle as rb
import release_plan as rp
import validate_targets as vt
from scripts.tests import test_verify_artifacts as jar_fixtures


class ReleaseBundleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = pathlib.Path(temporary.name)
        self.root = self.directory / 'source'
        self.root.mkdir()
        self.output = self.directory / 'bundle'
        self.props = vt.load_properties(SCRIPTS.parent / 'gradle.properties')
        self.plan = rp.build_plan(vt.load_catalog(CATALOG), version=self.props['mod_version'], minecraft='26.2',
                                  channel='release', source_branch='26.2', source_sha='a' * 40,
                                  loaders=['fabric', 'neoforge'])
        (self.root / 'gradle.properties').write_text(''.join(f'{k}={v}\n' for k, v in self.props.items()))
        (self.root / 'CHANGELOG.md').write_text(
            '# Changes\n\n## [Unreleased]\n\n### Added\n- Next\n\n'
            f"## [{self.props['mod_version']}]\n\n### Fixed\n- Common\n- [fabric] Fabric fix\n- [neoforge] Neo fix\n")
        fixture = jar_fixtures.ArtifactTest()
        fixture.props = self.props
        for row in self.plan['targets']:
            directory = self.root / row['loader'] / 'build/libs'
            directory.mkdir(parents=True)
            (directory / row['filename']).write_bytes(jar_fixtures.jar_bytes(fixture.entries(row['loader'])))
        self.provenance = {
            'source_sha': 'a' * 40,
            'java': {'major': 25, 'runtime': '25.0.3+9', 'vendor': 'Test fixture'},
            'gradle_jvm': {'major': 25, 'runtime': '25.0.3+9', 'vendor': 'Test fixture'},
            'gradle_version': '9.5.1',
            'plugins': {'net.fabricmc.fabric-loom': '1.17.21'},
            'dependencies': [{'loader': loader, 'configuration': 'runtimeClasspath', 'module': 'fixture:library:1.0',
                              'filename': 'library-1.0.jar', 'sha256': 'b' * 64} for loader in ['fabric', 'neoforge']],
            'build_checks': [':fabric:build', ':fabric:check', ':fabric:test', ':neoforge:build', ':neoforge:check'],
            'inputs': {'wrapper_sha256': 'c' * 64, 'verification_metadata_sha256': 'd' * 64},
            'workflow': {'repository': 'aktnb/stream-tweaks', 'run_id': '123', 'run_attempt': '1'},
        }

    def create(self):
        return rb.create_bundle(self.plan, root=self.root, output=self.output, provenance=self.provenance,
                                expected_source_sha='a' * 40)

    def verify(self, **options):
        return rb.verify_bundle(self.output, **({'expected_source_sha': 'a' * 40} | options))

    def rewrite_manifest(self, manifest):
        (self.output / rb.MANIFEST).write_text(rb.canonical_json(manifest))

    def test_bundle_preserves_plan_all_targets_and_hashes(self):
        manifest = self.create()
        self.assertEqual(manifest, self.verify(expected_plan=self.plan))
        self.assertEqual(manifest['plan'], self.plan)
        self.assertEqual(len(manifest['artifacts']), 2)
        self.assertEqual(manifest['provenance']['dependencies'], self.provenance['dependencies'])

    def test_reuse_never_mutates_or_rebuilds_files(self):
        self.create()
        before = {p.name: p.read_bytes() for p in self.output.iterdir()}
        for _ in range(2):
            self.verify()
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.output.iterdir()})
        with self.assertRaises(rb.BundleError):
            self.create()
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.output.iterdir()})

    def test_missing_artifact_stops_without_rebuild(self):
        self.create()
        jar = self.output / self.plan['targets'][0]['filename']
        jar.unlink()
        with self.assertRaises(OSError):
            self.verify()
        self.assertFalse(jar.exists())

    def test_altered_jar_notes_properties_rejected(self):
        self.create()
        for name in [self.plan['targets'][0]['filename'], 'notes-combined.md', rb.PROPERTIES]:
            path = self.output / name
            original = path.read_bytes()
            path.write_bytes(original + b'changed')
            with self.subTest(name=name), self.assertRaises((rb.BundleError, vt.ValidationError)):
                self.verify()
            path.write_bytes(original)

    def test_wrong_source_expected_plan_or_trusted_manifest_hash_rejected(self):
        self.create()
        digest = hashlib.sha256((self.output / rb.MANIFEST).read_bytes()).hexdigest()
        self.verify(expected_manifest_sha256=digest)
        for options in [{'expected_source_sha': 'b' * 40}, {'expected_manifest_sha256': 'b' * 64},
                        {'expected_plan': self.plan | {'source_sha': 'b' * 40}}]:
            with self.subTest(options=options), self.assertRaises(rb.BundleError):
                self.verify(**options)

    def test_no_partial_bundle_when_second_target_missing(self):
        (self.root / 'neoforge/build/libs' / self.plan['targets'][1]['filename']).unlink()
        with self.assertRaises(vt.ValidationError):
            self.create()
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.directory.glob('.release-bundle-*')))

    def test_wrong_metadata_rejected_even_with_matching_filename(self):
        fixture = jar_fixtures.ArtifactTest()
        fixture.props = self.props | {'minecraft_version': '26.1.2'}
        path = self.root / 'fabric/build/libs' / self.plan['targets'][0]['filename']
        path.write_bytes(jar_fixtures.jar_bytes(fixture.entries('fabric')))
        with self.assertRaises(vt.ValidationError):
            self.create()
        self.assertFalse(self.output.exists())

    def test_sources_dev_are_never_substituted(self):
        path = self.root / 'fabric/build/libs' / self.plan['targets'][0]['filename']
        path.rename(path.with_name(path.stem + '-sources.jar'))
        with self.assertRaises(vt.ValidationError):
            self.create()

    def test_symlink_extra_file_or_subdirectory_rejected(self):
        self.create()
        extra = self.output / 'publish-state.json'
        extra.write_text('{}')
        with self.assertRaises(rb.BundleError):
            self.verify()
        extra.unlink()
        extra.mkdir()
        with self.assertRaises(rb.BundleError):
            self.verify()
        extra.rmdir()
        note = self.output / 'notes-combined.md'
        content = note.read_bytes()
        note.unlink()
        outside = self.directory / 'outside.md'
        outside.write_bytes(content)
        note.symlink_to(outside)
        with self.assertRaises(rb.BundleError):
            self.verify()

    def test_manifest_path_traversal_duplicate_targets_and_extra_fields_rejected(self):
        manifest = self.create()
        for edit in [lambda m: m['notes'][0].update(filename='../outside'),
                     lambda m: m['artifacts'].append(copy.deepcopy(m['artifacts'][0])),
                     lambda m: m.update(publish_state={}),
                     lambda m: m.update(schema_version=True),
                     lambda m: m['provenance'].update(source_sha='b' * 40),
                     lambda m: m['provenance'].update(build_checks=[]),
                     lambda m: m['provenance']['java'].update(runtime='21.0.9'),
                     lambda m: m['provenance']['workflow'].update(repository='attacker/fork')]:
            changed = copy.deepcopy(manifest)
            edit(changed)
            self.rewrite_manifest(changed)
            with self.subTest(edit=edit), self.assertRaises(rb.BundleError):
                self.verify()
        self.rewrite_manifest(manifest)

    def test_saved_plan_is_checked_and_duplicate_json_key_rejected(self):
        self.create()
        (self.output / rb.PLAN).write_text('{}')
        with self.assertRaises(rb.BundleError):
            self.verify()
        (self.output / rb.PLAN).write_text(rp.canonical_json(self.plan))
        path = self.output / rb.MANIFEST
        path.write_text(path.read_text().replace('"schema_version": 1', '"schema_version": 1, "schema_version": 1'))
        with self.assertRaises(vt.ValidationError):
            self.verify()

    def test_existing_changelog_filter_is_reused(self):
        self.create()
        fabric = (self.output / 'notes-fabric.md').read_text()
        neo = (self.output / 'notes-neoforge.md').read_text()
        self.assertIn('Common', fabric)
        self.assertIn('Fabric fix', fabric)
        self.assertNotIn('Neo fix', fabric)
        self.assertIn('Neo fix', neo)
        self.assertNotIn('Fabric fix', neo)
        beta = self.plan | {'channel': 'beta', 'mod_version': '0.3.4-beta.1'}
        self.assertIn('Next', rb.generate_notes((self.root / 'CHANGELOG.md').read_text(), beta)['notes-fabric.md'])

    def test_cli_verifies_saved_bundle(self):
        self.create()
        result = subprocess.run([sys.executable, str(SCRIPTS / 'release_bundle.py'), 'verify', '--bundle',
                                 str(self.output), '--source-sha', 'a' * 40], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['plan'], self.plan)

    def test_collect_only_records_actually_resolved_dependencies(self):
        (self.root / 'build.gradle').write_text("plugins { id 'fabric.loom' version '1.17.21' }")
        directory = self.root / 'gradle/wrapper'
        directory.mkdir(parents=True)
        (directory / 'gradle-wrapper.properties').write_text(
            'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip\n'
            f"distributionSha256Sum={'c' * 64}\n")
        (self.root / 'gradle/verification-metadata.xml').write_text('not-an-inventory-of-resolved-dependencies')
        resolved = {k: self.provenance[k] for k in ['java', 'gradle_jvm', 'gradle_version', 'dependencies', 'build_checks']}
        collected = rb.collect_provenance(self.root, resolved, self.plan, run_id='123', run_attempt='1')
        self.assertEqual(collected['dependencies'], resolved['dependencies'])
        self.assertEqual(collected['plugins'], {'fabric.loom': '1.17.21'})

    def test_create_cli_rejects_wrong_sha_and_dirty_checkout_before_reading_inputs(self):
        # A disposable Git fixture never changes the working repository or uses its signing identity.
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Bundle Fixture',
                        '-c', 'user.email=bundle@example.invalid', '-c', 'commit.gpgsign=false',
                        'commit', '--allow-empty', '-q', '-m', 'fixture'], check=True)
        sha = subprocess.check_output(['git', '-C', str(self.root), 'rev-parse', 'HEAD'], text=True).strip()
        args = [sys.executable, str(SCRIPTS / 'release_bundle.py'), 'create', '--root', str(self.root),
                '--plan', 'missing.json', '--resolved-provenance', 'missing.json', '--output', str(self.output),
                '--run-id', '1', '--run-attempt', '1']
        for source_sha, message in [('a' * 40, 'checkout HEAD'), (sha, 'uncommitted or untracked')]:
            result = subprocess.run(args + ['--source-sha', source_sha], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(message, result.stderr)
        self.assertFalse(self.output.exists())

    def test_release_workflow_build_is_read_only_and_shell_blocks_are_syntactically_valid(self):
        workflow = (SCRIPTS.parent / '.github/workflows/old_release.yml.disabled').read_text()
        build = re.search(r'^  bundle-build:\n(.*?)(?=^  [a-zA-Z][\w-]*:|\Z)', workflow, re.M | re.S)[1]
        self.assertNotIn('inputs.operation', workflow)
        self.assertNotIn('prepare-new', workflow)
        self.assertIn('plan-artifact: ${{ steps.fixed-plan.outputs.plan-artifact }}', workflow)
        self.assertIn('name: ${{ needs.plan.outputs.plan-artifact }}', build)
        self.assertIn('needs: [plan]', build)
        self.assertIn('contents: read', build)
        self.assertNotIn('secrets.', build)
        self.assertNotIn('contents: write', build)
        self.assertNotIn('--write-verification-metadata', build)
        self.assertIn('--dependency-verification=strict', build)
        self.assertIn('release_bundle.py verify', build)
        self.assertIn('if-no-files-found: error', build)
        for match in re.finditer(r'^        run: \|\n((?:^          .*\n|^\n)+)', workflow, re.M):
            script = '\n'.join(line[10:] if line else '' for line in match[1].splitlines())
            self.assertNotIn('${{', script)
            result = subprocess.run(['bash', '-n'], input=script, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_public_api_can_be_imported_as_package_without_sys_path_hacks(self):
        result = subprocess.run([sys.executable, '-c', 'from scripts import release_bundle; '
                                 'assert callable(release_bundle.verify_bundle)'], cwd=SCRIPTS.parent,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
