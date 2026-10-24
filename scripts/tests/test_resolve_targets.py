"""All-supported validation freezes branch heads once before matrix expansion."""
import copy
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
CATALOG = pathlib.Path(__file__).parent / "fixtures/support-targets-26.2.json"
sys.path.insert(0, str(ROOT / "scripts"))
import resolve_targets as resolver
from validate_targets import ValidationError, load_catalog


class ResolveTargetsTest(unittest.TestCase):
    def setUp(self):
        self.catalog = load_catalog(CATALOG)
        self.catalog_sha = "c" * 40
        self.sha = "a" * 40

    def resolve(self, catalog=None, targets=None, callback=None):
        return resolver.resolve_targets(catalog or self.catalog, self.catalog_sha, "26.2", targets,
                                        callback or (lambda branch: self.sha))

    def maintenance(self):
        catalog = copy.deepcopy(self.catalog)
        for original in list(catalog["targets"]):
            row = copy.deepcopy(original)
            row.update(id="26.1-" + row["loader"], minecraft="26.1", lifecycle="maintenance",
                       source_branch="mc/26.1")
            catalog["targets"].append(row)
        return catalog

    def test_all_freezes_each_branch_once_and_deduplicates_shared_tests(self):
        remote = Mock(side_effect=["a" * 40, "b" * 40])
        result = self.resolve(self.maintenance(), callback=remote)
        self.assertEqual(remote.call_count, 2)
        self.assertEqual([call.args[0] for call in remote.call_args_list], ["26.2", "mc/26.1"])
        groups, builds = resolver.matrices(result)
        self.assertEqual(len(groups["include"]), 2)
        self.assertEqual(len(builds["include"]), 4)
        for entry in builds["include"]:
            plan = json.loads(entry["source_plan"])
            expected = "a" * 40 if plan["source_branch"] == "26.2" else "b" * 40
            self.assertEqual(plan["source_sha"], expected)
        # Simulate branch advances after resolution. Matrix generation never resolves again.
        remote.side_effect = AssertionError("must not re-read branch HEAD")
        self.assertEqual(resolver.matrices(result), (groups, builds))

    def test_subset_does_not_build_other_loader_or_resolve_other_branch(self):
        remote = Mock(return_value=self.sha)
        result = self.resolve(self.maintenance(), ["26.1-neoforge"], remote)
        remote.assert_called_once_with("mc/26.1")
        groups, builds = resolver.matrices(result)
        self.assertEqual([entry["target"] for entry in groups["include"]], ["26.1-neoforge"])
        self.assertEqual([entry["target"] for entry in builds["include"]], ["26.1-neoforge"])

    def test_all_filters_unavailable_targets_explicit_subset_refuses_them(self):
        for support, lifecycle in [("testing", "maintenance"), ("unsupported", "maintenance"),
                                   ("unsupported", "eol")]:
            catalog = self.maintenance()
            for row in catalog["targets"][2:]:
                row.update(support=support, lifecycle=lifecycle)
            with self.subTest(support=support, lifecycle=lifecycle):
                self.assertEqual(len(self.resolve(catalog)["sources"]), 1)
                with self.assertRaises(ValidationError):
                    self.resolve(catalog, ["26.1-fabric"])
        for ids in [["unknown"], ["26.2-fabric", "26.2-fabric"], [], [""]]:
            with self.subTest(ids=ids), self.assertRaises(ValidationError):
                self.resolve(targets=ids)

    def test_invalid_ref_or_catalog_is_rejected_before_network(self):
        for branch in ["feature/x", "refs/heads/main", "mc/latest", "26.2;echo bad"]:
            catalog = copy.deepcopy(self.catalog)
            catalog["targets"][0]["source_branch"] = branch
            remote = Mock()
            with self.subTest(branch=branch), self.assertRaises(ValidationError):
                self.resolve(catalog, callback=remote)
            remote.assert_not_called()

    def test_controller_must_be_catalog_authority_and_immutable(self):
        for branch, sha in [("main", self.catalog_sha), ("feature/x", self.catalog_sha),
                            ("26.2", "main"), ("26.2", "a" * 7)]:
            remote = Mock()
            with self.subTest(branch=branch, sha=sha), self.assertRaises(ValidationError):
                resolver.resolve_targets(self.catalog, sha, branch, None, remote)
            remote.assert_not_called()

    def test_invalid_remote_sha_and_lookup_failure_do_not_emit_partial_plan(self):
        for sha in ["main", "a" * 7, "A" * 40]:
            with self.subTest(sha=sha), self.assertRaises(ValidationError):
                self.resolve(callback=lambda branch: sha)
        with self.assertRaises(OSError):
            self.resolve(self.maintenance(), callback=Mock(side_effect=[self.sha, OSError("network down")]))

    def test_saved_resolution_rejects_missing_duplicate_or_unbuilt_rows(self):
        original = self.resolve()
        mutations = [lambda p: p.update(schema_version=True), lambda p: p.update(sources=[]),
                     lambda p: p["sources"].append(copy.deepcopy(p["sources"][0])),
                     lambda p: p["sources"][0].update(source_sha="26.2"),
                     lambda p: p["sources"][0]["matrix"]["include"].pop(),
                     lambda p: p["sources"][0].update(changed_loaders=["fabric"]),
                     lambda p: p["sources"][0].update(has_builds=False)]
        for mutate in mutations:
            changed = copy.deepcopy(original)
            mutate(changed)
            with self.subTest(mutate=mutate), self.assertRaises(ValidationError):
                resolver.matrices(changed)

    def test_summary_identifies_sources_and_distinguishes_backports(self):
        text = resolver.summary(self.resolve())
        for value in ["26.2", "fabric", "neoforge", self.sha, self.catalog_sha, "25", "backport",
                      "cache hit", "Gradle"]:
            self.assertIn(value, text)

    def test_schedule_and_dispatch_cli_share_resolver_and_write_fixed_matrices(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = ["--catalog", str(CATALOG), "--catalog-sha", self.catalog_sha,
                    "--controller-branch", "26.2", "--repository", "aktnb/stream-tweaks"]
            for selection, expected in [(["--all-supported"], 2), (["--target", "26.2-fabric"], 1)]:
                output, github, summary = (root / name for name in ["plan.json", "outputs", "summary"])
                github.write_text("")
                with patch.object(resolver.ci_targets, "resolve_branch", return_value=self.sha) as remote:
                    code = resolver.main(base + selection + ["--output", str(output), "--github-output",
                                                             str(github), "--summary", str(summary)])
                self.assertEqual(code, 0)
                remote.assert_called_once_with("aktnb/stream-tweaks", "26.2")
                saved = json.loads(output.read_text())
                resolver.validate_resolution(saved)
                values = dict(line.split("=", 1) for line in github.read_text().splitlines())
                self.assertEqual(len(json.loads(values["groups"])["include"]), 1)
                self.assertEqual(len(json.loads(values["builds"])["include"]), expected)
            with patch.object(resolver.ci_targets, "resolve_branch") as remote:
                self.assertEqual(resolver.main(base + ["--all-supported", "--target", "26.2-fabric",
                                                       "--output", str(root / "invalid.json")]), 1)
                remote.assert_not_called()
                self.assertFalse((root / "invalid.json").exists())


if __name__ == "__main__":
    unittest.main()
