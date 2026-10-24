"""Contract and failure fixtures for the support catalog/resolver."""
from __future__ import annotations

import copy
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent
CATALOG = pathlib.Path(__file__).parent / "fixtures/support-targets-26.2.json"
sys.path.insert(0, str(SCRIPTS))
import validate_targets as vt


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = vt.load_catalog(CATALOG)

    def assert_invalid(self, edit):
        data = copy.deepcopy(self.catalog)
        edit(data)
        with self.assertRaises(vt.ValidationError):
            vt.validate_catalog(data)

    def test_current_targets_and_modmenu_difference(self):
        rows = vt.select_targets(self.catalog)
        self.assertEqual([row["id"] for row in rows], ["26.2-fabric", "26.2-neoforge"])
        self.assertIn("modmenu", rows[0]["features"])
        self.assertNotIn("modmenu", rows[1]["features"])
        for row in rows:
            self.assertEqual(row["verification"]["runtime_smoke"],
                             {"status": "unverified", "date": None, "evidence": []})

    def test_duplicate_target_rejected(self):
        self.assert_invalid(lambda c: c["targets"].append(copy.deepcopy(c["targets"][0])))

    def test_conflicting_mc_source_rejected(self):
        self.assert_invalid(lambda c: c["targets"][1].update(source_branch="main"))

    def test_invalid_refs_rejected(self):
        for ref in ["refs/heads/26.2", "26.2~1", "mc/../26.2", "-main", "feature/a", "main\n", "26.2;pwd", 26.2]:
            with self.subTest(ref=ref):
                self.assert_invalid(lambda c: c["targets"][0].update(source_branch=ref))

    def test_numeric_branch_must_match_minecraft(self):
        self.assert_invalid(lambda c: c["targets"][0].update(source_branch="mc/26.1"))

    def test_unknown_fields_and_strict_types_rejected(self):
        edits = [lambda c: c.update(schema_version=True),
                 lambda c: c.update(unknown=True),
                 lambda c: c["targets"][0].update(java_version=True),
                 lambda c: c["targets"][0].update(java_version="25"),
                 lambda c: c["targets"][0].update(features="modmenu"),
                 lambda c: c["targets"][0].update(support="preview"),
                 lambda c: c["targets"][0].update(lifecycle="preview"),
                 lambda c: c["targets"][0].update(loader="forge"),
                 lambda c: c["targets"][0].update(minecraft=26.2),
                 lambda c: c["targets"][0].update(id="custom-name"),
                 lambda c: c["targets"][0].update(verification=[])]
        for edit in edits:
            with self.subTest(edit=edit):
                self.assert_invalid(edit)

    def test_verification_requires_actual_date_and_evidence(self):
        for record in [{"status": "passed", "date": None, "evidence": []},
                       {"status": "passed", "date": "2026-02-30", "evidence": ["run/1"]},
                       {"status": "passed", "date": "2026-10-24", "evidence": []},
                       {"status": "unverified", "date": "2026-10-24", "evidence": []}]:
            with self.subTest(record=record):
                self.assert_invalid(lambda c: c["targets"][0]["verification"].update(build=record))
        self.catalog["targets"][0]["verification"]["build"] = {
            "status": "passed", "date": "2026-10-24", "evidence": ["https://example.test/runs/1"]}
        vt.validate_catalog(self.catalog)

    def test_eol_must_be_unsupported(self):
        self.assert_invalid(lambda c: c["targets"][0].update(lifecycle="eol"))

    def test_unavailable_rows_excluded_and_explicit_request_rejected(self):
        for lifecycle, support in [("active", "testing"), ("active", "unsupported"), ("eol", "unsupported")]:
            with self.subTest(support=support, lifecycle=lifecycle):
                data = copy.deepcopy(self.catalog)
                data["targets"][1].update(lifecycle=lifecycle, support=support)
                self.assertEqual([r["id"] for r in vt.select_targets(data)], ["26.2-fabric"])
                with self.assertRaises(vt.ValidationError):
                    vt.select_targets(data, target_ids=["26.2-neoforge"])
                with self.assertRaises(vt.ValidationError):
                    vt.select_targets(data, loader="neoforge")

    def test_unknown_and_conflicting_selection_rejected(self):
        for kwargs in [{"target_ids": ["26.1-fabric"]}, {"minecraft": "26.1"}, {"loader": "forge"},
                       {"target_ids": ["26.2-fabric"], "loader": "neoforge"},
                       {"target_ids": ["26.2-fabric", "26.2-fabric"]}]:
            with self.subTest(kwargs=kwargs), self.assertRaises(vt.ValidationError):
                vt.select_targets(self.catalog, **kwargs)

    def test_selection_is_sorted_independent_and_repeatable(self):
        self.catalog["targets"].reverse()
        first = vt.select_targets(self.catalog)
        second = vt.select_targets(self.catalog)
        self.assertEqual(first, second)
        first[0]["features"].clear()
        self.assertNotEqual(first, vt.select_targets(self.catalog))

    def test_at_most_one_supported_active_and_maintenance_mc(self):
        for lifecycle in ["active", "maintenance"]:
            data = copy.deepcopy(self.catalog)
            data["targets"][0]["lifecycle"] = lifecycle
            data["targets"][1]["lifecycle"] = lifecycle
            row = copy.deepcopy(data["targets"][0])
            row.update(id="26.1-fabric", minecraft="26.1", source_branch="mc/26.1")
            data["targets"].append(row)
            with self.subTest(lifecycle=lifecycle), self.assertRaises(vt.ValidationError):
                vt.validate_catalog(data)

    def test_one_branch_cannot_publish_two_minecraft_versions(self):
        data = copy.deepcopy(self.catalog)
        for row in data["targets"]:
            row["source_branch"] = "main"
        old = copy.deepcopy(data["targets"][0])
        old.update(id="26.1-fabric", minecraft="26.1", lifecycle="maintenance")
        data["targets"].append(old)
        with self.assertRaises(vt.ValidationError):
            vt.validate_catalog(data)

    def test_main_and_separate_maintenance_source_are_valid(self):
        data = copy.deepcopy(self.catalog)
        data["authority_branch"] = "main"
        for row in data["targets"]:
            row["source_branch"] = "main"
        old = copy.deepcopy(data["targets"][0])
        old.update(id="26.1-fabric", minecraft="26.1", lifecycle="maintenance", source_branch="mc/26.1")
        data["targets"].append(old)
        self.assertEqual(len(vt.select_targets(data)), 3)

    def test_duplicate_json_keys_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "catalog.json"
            path.write_text('{"schema_version":1,"schema_version":1}', encoding="utf-8")
            with self.assertRaises(vt.ValidationError):
                vt.load_catalog(path)


class CheckoutTest(unittest.TestCase):
    def setUp(self):
        self.targets = vt.select_targets(vt.load_catalog(CATALOG))
        self.properties = vt.load_properties(ROOT / "gradle.properties")

    def test_current_checkout_and_single_target_match(self):
        for targets in [self.targets, self.targets[:1], self.targets[1:]]:
            vt.validate_properties(targets, self.properties, source_branch="26.2")

    def test_mc_java_source_or_loader_property_mismatch_rejected(self):
        for changes in [{"minecraft_version": "26.1"}, {"java_version": "21"},
                        {"fabric_loader_version": ""}, {"neoforge_version": ""}]:
            with self.subTest(changes=changes), self.assertRaises(vt.ValidationError):
                vt.validate_properties(self.targets, self.properties | changes, source_branch="26.2")
        with self.assertRaises(vt.ValidationError):
            vt.validate_properties(self.targets[:1], self.properties, source_branch="main")

    def test_properties_duplicate_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "gradle.properties"
            path.write_text("java_version=25\njava_version=21\n", encoding="utf-8")
            with self.assertRaises(vt.ValidationError):
                vt.load_properties(path)


class CliTest(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPTS / "validate_targets.py"),
                               "--catalog", str(CATALOG), *args],
                              capture_output=True, text=True, check=False)

    def test_single_loader_and_all_cli_json_is_deterministic(self):
        for args, expected in [(('--target', '26.2-neoforge'), ['26.2-neoforge']),
                               (('--loader', 'fabric'), ['26.2-fabric']),
                               (('--all-supported',), ['26.2-fabric', '26.2-neoforge'])]:
            with self.subTest(args=args):
                first, second = self.run_cli(*args), self.run_cli(*args)
                self.assertEqual(first.returncode, 0, first.stderr)
                self.assertEqual(first.stdout, second.stdout)
                self.assertEqual([r['id'] for r in json.loads(first.stdout)['targets']], expected)

    def test_cli_checkout_validation(self):
        result = self.run_cli('--target', '26.2-fabric', '--properties', str(ROOT / 'gradle.properties'),
                              '--source-branch', '26.2')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_cli_errors_have_no_partial_json(self):
        for args in [('--target', 'unknown'), ('--properties', str(ROOT / 'gradle.properties')),
                     ('--all-supported', '--loader', 'fabric'),
                     ('--properties', str(ROOT / 'gradle.properties'), '--source-branch', 'main')]:
            with self.subTest(args=args):
                result = self.run_cli(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, '')


if __name__ == "__main__":
    unittest.main()
