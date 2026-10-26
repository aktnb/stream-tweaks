"""Contracts for the active Build workflow and the disabled workflow archive."""
import pathlib
import re
import os
import itertools
import subprocess
import textwrap
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / '.github/workflows'


class BuildWorkflowTest(unittest.TestCase):
    def test_only_new_build_is_active_and_previous_workflows_are_archived(self):
        active = {path.name for pattern in ('*.yml', '*.yaml') for path in WORKFLOWS.glob(pattern)}
        self.assertEqual(active, {'build.yml'})
        for name in ('build', 'release', 'target-build', 'validate-all'):
            with self.subTest(name=name):
                self.assertTrue((WORKFLOWS / ('old_' + name + '.yml.disabled')).is_file())

    def test_build_runs_on_pr_creation_commits_and_reopening_only(self):
        workflow = (WORKFLOWS / 'build.yml').read_text()
        triggers = workflow.split('\non:\n', 1)[1].split('\nconcurrency:', 1)[0]
        self.assertIn('pull_request:', triggers)
        self.assertIn('types: [opened, synchronize, reopened]', triggers)
        self.assertNotIn('branches:', triggers)
        self.assertNotIn('push:', triggers)
        self.assertNotIn('workflow_dispatch:', triggers)

    def test_build_checks_both_loaders_and_shared_tests_without_formatting(self):
        workflow = (WORKFLOWS / 'build.yml').read_text()
        jobs = dict(re.findall(r'^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                               workflow.split('\njobs:\n', 1)[1], re.M | re.S))
        self.assertEqual(set(jobs), {'fabric', 'neoforge', 'ci-ok'})
        self.assertNotIn('needs:', jobs['fabric'])
        self.assertNotIn('needs:', jobs['neoforge'])
        self.assertIn('name: CI OK', workflow)
        self.assertEqual(workflow.count('./gradlew'), 2)
        self.assertIn('./gradlew :fabric:assemble :fabric:test', jobs['fabric'])
        self.assertIn('./gradlew :neoforge:assemble', jobs['neoforge'])
        self.assertNotIn(':fabric:test', jobs['neoforge'])
        for name in ('fabric', 'neoforge'):
            self.assertIn('--dependency-verification=strict --stacktrace', jobs[name])
        for forbidden in ('spotless', 'checkstyle', './gradlew build', 'upload-artifact', 'ci_targets.py'):
            self.assertNotIn(forbidden, workflow)

    def test_ci_ok_requires_success_from_both_loaders(self):
        workflow = (WORKFLOWS / 'build.yml').read_text()
        gate = workflow.split('\n  ci-ok:\n', 1)[1]
        self.assertIn('if: always()', gate)
        self.assertIn('needs: [fabric, neoforge]', gate)
        self.assertIn('FABRIC_RESULT: ${{ needs.fabric.result }}', gate)
        self.assertIn('NEOFORGE_RESULT: ${{ needs.neoforge.result }}', gate)
        script = textwrap.dedent(gate.split('        run: |\n', 1)[1])
        for fabric, neoforge in itertools.product(('success', 'failure', 'cancelled', 'skipped'), repeat=2):
            with self.subTest(fabric=fabric, neoforge=neoforge):
                result = subprocess.run(['bash', '-e', '-c', script],
                                        env={**os.environ, 'FABRIC_RESULT': fabric, 'NEOFORGE_RESULT': neoforge},
                                        capture_output=True)
                self.assertEqual(result.returncode == 0, fabric == neoforge == 'success')

    def test_build_has_no_publication_access_and_pins_external_actions(self):
        workflow = (WORKFLOWS / 'build.yml').read_text()
        self.assertIn('permissions:\n  contents: read\n', workflow)
        for forbidden in ('secrets.', ': write', 'pull_request_target:', '--write-verification-metadata'):
            self.assertNotIn(forbidden, workflow)
        self.assertEqual(workflow.count('persist-credentials: false'), workflow.count('uses: actions/checkout@'))
        for action in re.findall(r'uses: (actions/[^\s]+)', workflow):
            self.assertRegex(action, r'@([0-9a-f]{40})$')
        self.assertIn('uses: ./.github/actions/setup-build', workflow)


if __name__ == '__main__':
    unittest.main()
