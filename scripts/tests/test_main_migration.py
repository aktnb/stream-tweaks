"""Migration preserves old-branch validation while main becomes the only publication source."""
import copy
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURES = pathlib.Path(__file__).parent / "fixtures"
sys.path.insert(0, str(ROOT / "scripts"))
import ci_targets as ci
import release_plan as rp
import resolve_targets as resolver
import validate_targets as vt


class MainMigrationTest(unittest.TestCase):
    def setUp(self):
        self.old_path = FIXTURES / "support-targets-26.2.json"
        self.main_path = FIXTURES / "support-targets-main.json"
        self.old = vt.load_catalog(self.old_path)
        self.migrated = vt.load_catalog(self.main_path)
        self.sha = "a" * 40
        self.props = vt.load_properties(ROOT / "gradle.properties")

    def test_preparation_pr_uses_existing_26_2_base_without_bootstrap(self):
        catalog, bootstrap = ci.load_ci_catalog(self.old_path, self.main_path)
        self.assertFalse(bootstrap)
        self.assertEqual(catalog, self.old)
        plan = ci.plan(catalog, "26.2", self.sha, {"fabric", "neoforge"})
        ci.validate_plan(plan)
        vt.validate_properties(plan["targets"], self.props, source_branch="26.2")
        self.assertEqual(len(plan["matrix"]["include"]), 2)

    def test_merged_26_2_push_remains_ci_only_and_rollback_pr_needs_no_bypass(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "outputs"
            env = os.environ | {"EVENT_NAME": "push", "SOURCE_BRANCH": "26.2", "SOURCE_SHA": self.sha,
                                "CATALOG": str(self.main_path), "FALLBACK_CATALOG": "",
                                "FABRIC": "true", "NEOFORGE": "true", "GITHUB_OUTPUT": str(output)}
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci_targets.py"), "plan"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
            plan = json.loads(outputs["plan"])
            self.assertEqual(plan["validation_source_branch"], "26.2")
            self.assertEqual(plan["validation_source_sha"], self.sha)
            self.assertEqual(plan["publication_source_branch"], "main")
            self.assertEqual(plan["targets"], self.migrated["targets"])
            needs = {name: {"result": "success"}
                     for name in ["changes", "format", "test", "script-tests", "build"]}
            needs["changes"]["outputs"] = outputs
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci_targets.py"), "aggregate"],
                                    env=env | {"PLAN": outputs["plan"], "NEEDS": json.dumps(needs)},
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            # A rollback PR reads this same migrated base, not its reverted merge catalog.
            catalog, bootstrap = ci.load_ci_catalog(self.main_path, self.old_path)
            self.assertFalse(bootstrap)
            ci.validate_plan(ci.plan(catalog, "26.2", self.sha, {"fabric", "neoforge"}))

    def test_old_branch_validation_exception_is_narrow_and_aliases_are_strict(self):
        for branch in ["26.1", "mc/26.2", "feature/topic"]:
            with self.subTest(branch=branch), self.assertRaises(vt.ValidationError):
                ci.plan(self.migrated, branch, self.sha, {"fabric"})
        for change in ["not-authority", "maintenance"]:
            catalog = copy.deepcopy(self.migrated)
            if change == "not-authority":
                catalog["authority_branch"] = "26.2"
            else:
                for row in catalog["targets"]:
                    row["lifecycle"] = "maintenance"
            with self.subTest(change=change), self.assertRaises(vt.ValidationError):
                ci.plan(catalog, "26.2", self.sha, {"fabric"})
        original = ci.plan(self.migrated, "26.2", self.sha, {"fabric", "neoforge"})
        edits = [lambda p: p.update(validation_source_branch="main"),
                 lambda p: p.update(validation_source_sha="b" * 40),
                 lambda p: p.update(publication_source_branch="26.2"),
                 lambda p: p.update(authority_branch="26.2"),
                 lambda p: p["targets"][0].update(source_branch="26.2")]
        for edit in edits:
            changed = copy.deepcopy(original)
            edit(changed)
            with self.subTest(edit=edit), self.assertRaises(vt.ValidationError):
                ci.validate_plan(changed)
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "source").mkdir()
            properties = self.props | {"minecraft_version": "26.1"}
            (root / "source/gradle.properties").write_text("".join(f"{k}={v}\n" for k, v in properties.items()))
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci_targets.py"), "checkout"],
                                    cwd=root, env=os.environ | {"PLAN": json.dumps(original),
                                                               "TARGET": "26.2-fabric", "BUILD_MODE": "build",
                                                               "GITHUB_OUTPUT": str(root / "outputs")},
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("minecraft_version mismatch", result.stderr)

    def test_all_supported_never_substitutes_old_branch_for_publication_source(self):
        dormant = ci.plan(self.migrated, "26.2", self.sha, {"fabric", "neoforge"})
        resolution = {"schema_version": 1, "authority_branch": "main", "catalog_sha": "b" * 40,
                      "sources": [dormant]}
        with self.assertRaises(vt.ValidationError):
            resolver.validate_resolution(resolution)

    def test_main_pr_and_single_target_and_all_targets_use_main_source(self):
        catalog, bootstrap = ci.load_ci_catalog(self.main_path, self.old_path)
        self.assertFalse(bootstrap)
        for target in [None, "26.2-fabric", "26.2-neoforge"]:
            with self.subTest(target=target):
                plan = ci.plan(catalog, "main", self.sha, {"fabric", "neoforge"}, target)
                ci.validate_plan(plan)
                vt.validate_properties(plan["targets"], self.props, source_branch="main")
                self.assertEqual(plan["source_branch"], "main")
        remote = Mock(return_value=self.sha)
        resolution = resolver.resolve_targets(catalog, "b" * 40, "main", None, remote)
        remote.assert_called_once_with("main")
        groups, builds = resolver.matrices(resolution)
        self.assertEqual(len(groups["include"]), 1)
        self.assertEqual(len(builds["include"]), 2)
        with self.assertRaises(vt.ValidationError):
            resolver.resolve_targets(catalog, "b" * 40, "26.2", None, remote)

    def test_new_release_has_one_source_but_saved_old_release_can_resume_unchanged(self):
        request = dict(version="0.3.4-beta.1", minecraft="26.2", channel="beta", source_branch="26.2",
                       source_sha=self.sha, loaders=["fabric", "neoforge"])
        old_plan = rp.build_plan(self.old, **request)
        with self.assertRaises(rp.PlanError):
            rp.build_plan(self.migrated, **request)
        new_plan = rp.build_plan(self.migrated, **(request | {"source_branch": "main"}))
        self.assertEqual(new_plan["source_branch"], "main")
        self.assertEqual({row["source_branch"] for row in self.migrated["targets"]}, {"main"})
        self.assertEqual(rp.canonical_json(old_plan), rp.canonical_json(rp.reuse_plan(old_plan, **request)))


if __name__ == "__main__":
    unittest.main()
