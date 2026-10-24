"""Fixed release identity and retry contracts; no external publication."""
import copy
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
CATALOG = pathlib.Path(__file__).parent / "fixtures/support-targets-26.2.json"
sys.path.insert(0, str(SCRIPTS))
import release_plan as rp
import validate_targets as vt


class ReleasePlanTest(unittest.TestCase):
    def setUp(self):
        self.catalog = vt.load_catalog(CATALOG)
        self.request = dict(version="0.3.3", minecraft="26.2", channel="release",
                            source_branch="26.2", source_sha="a" * 40, loaders=["fabric", "neoforge"])

    def build(self, **changes):
        return rp.build_plan(self.catalog, **(self.request | changes))

    def test_same_inputs_and_reordered_selection_have_identical_json(self):
        self.assertEqual(rp.canonical_json(self.build()), rp.canonical_json(self.build(loaders=["neoforge", "fabric"])))
        plan = self.build()
        self.assertEqual(plan["release_id"], "v0.3.3+mc26.2")
        self.assertEqual(plan["targets"][0]["filename"], "stream-tweaks-fabric-mc26.2-0.3.3.jar")

    def test_stable_single_loader(self):
        self.assertEqual([r["loader"] for r in self.build(loaders=["neoforge"])["targets"]], ["neoforge"])

    def test_invalid_versions_and_channel_rejected(self):
        for version in ["01.2.3", "1.02.3", "1.2.03", "1.2", "v1.2.3", "1.2.3+mc26.2", "1.2.3-beta.0",
                        "1.2.3-beta.01", "1.2.3-rc.1", "1.2.3-beta.1-fabric", "1.2.3\n", None]:
            with self.subTest(version=version), self.assertRaises(rp.PlanError):
                self.build(version=version)
        for channel in ["stable", "", "beta", "alpha"]:
            with self.subTest(channel=channel), self.assertRaises(rp.PlanError):
                self.build(channel=channel)

    def test_unknown_duplicate_unavailable_targets_rejected(self):
        for loaders in [[], ["forge"], ["fabric", "fabric"], "fabric"]:
            with self.subTest(loaders=loaders), self.assertRaises(rp.PlanError):
                self.build(loaders=loaders)
        self.catalog["targets"][0]["support"] = "testing"
        with self.assertRaises(vt.ValidationError):
            self.build()

    def test_unknown_mc_and_source_mismatch_rejected(self):
        with self.assertRaises(vt.ValidationError):
            self.build(minecraft="26.1", source_branch="26.1")
        for branch in ["main", "mc/26.2", "feature/a", "refs/heads/26.2", "26.2;id", "26.1"]:
            with self.subTest(branch=branch), self.assertRaises((rp.PlanError, vt.ValidationError)):
                self.build(source_branch=branch)

    def test_main_and_mc_branch_supported_only_when_catalog_matches(self):
        for branch in ["main", "mc/26.2"]:
            for row in self.catalog["targets"]:
                row["source_branch"] = branch
            self.assertEqual(self.build(source_branch=branch)["source_branch"], branch)

    def test_full_immutable_sha_required(self):
        for sha in ["main", "a" * 7, "A" * 40, "0" * 40, "a" * 40 + "\n", None]:
            with self.subTest(sha=sha), self.assertRaises(rp.PlanError):
                self.build(source_sha=sha)

    def test_prerelease_reuse_never_allocates_new_number(self):
        for channel in ["alpha", "beta"]:
            request = self.request | dict(version=f"0.3.3-{channel}.7", channel=channel)
            original = rp.build_plan(self.catalog, **request)
            reused = rp.reuse_plan(original, **request)
            self.assertEqual(rp.canonical_json(original), rp.canonical_json(reused))
            self.assertIn(f"-{channel}.7+mc", reused["tag"])

    def test_reuse_rejects_sha_target_identity_and_destination_change(self):
        original = self.build()
        for changes in [dict(source_sha="b" * 40), dict(loaders=["fabric"]), dict(version="0.3.4"),
                        dict(source_branch="main"), dict(curseforge_project="123")]:
            with self.subTest(changes=changes), self.assertRaises(rp.PlanError):
                rp.reuse_plan(original, **(self.request | changes))

    def test_saved_plan_reuse_does_not_read_latest_catalog(self):
        original = self.build(loaders=["fabric"])
        self.catalog["targets"][0].update(support="unsupported")
        self.assertEqual(original, rp.reuse_plan(original, **(self.request | dict(loaders=["fabric"]))))

    def test_stored_plan_is_strict_and_has_unique_sorted_targets(self):
        edits = [lambda p: p.update(schema_version=True), lambda p: p.update(extra=True),
                 lambda p: p.update(source_sha="main"), lambda p: p.update(tag="v0.3.4+mc26.2"),
                 lambda p: p["targets"].reverse(), lambda p: p["targets"].append(copy.deepcopy(p["targets"][0])),
                 lambda p: p["targets"][0].update(java_version=True),
                 lambda p: p["targets"][0].update(id="26.1-fabric"),
                 lambda p: p["targets"][0].update(filename="../evil.jar"),
                 lambda p: p["targets"][0].update(filename="stream-tweaks-fabric-mc26.2-0.3.3-sources.jar"),
                 lambda p: p["targets"][0]["destinations"][0].update(required=1),
                 lambda p: p["targets"][0]["destinations"][1].update(project="other"),
                 lambda p: p["targets"][0].update(loader="forge"),
                 lambda p: p["targets"][0].update(modrinth_version="wrong")]
        for edit in edits:
            plan = self.build()
            edit(plan)
            with self.subTest(edit=edit), self.assertRaises(rp.PlanError):
                rp.validate_plan(plan)

    def test_optional_and_required_curseforge_are_frozen(self):
        for required in [False, True]:
            request = self.request | dict(curseforge_project="123", curseforge_required=required)
            plan = rp.build_plan(self.catalog, **request)
            self.assertEqual(plan["targets"][0]["destinations"][-1],
                             dict(name="curseforge", project="123", required=required))
            self.assertEqual(plan, rp.reuse_plan(plan, **request))
        with self.assertRaises(rp.PlanError):
            self.build(curseforge_required=True)

    def test_no_mutation_of_catalog_or_saved_plan(self):
        before = copy.deepcopy(self.catalog)
        original = self.build()
        reused = rp.reuse_plan(original, **self.request)
        reused["targets"][0]["destinations"].clear()
        self.assertEqual(before, self.catalog)
        self.assertEqual(len(original["targets"][0]["destinations"]), 2)

    def cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPTS / "release_plan.py"),
                               "--catalog", str(CATALOG), *args],
                              capture_output=True, text=True)

    def cli_args(self):
        return ["--version", "0.3.3-beta.7", "--minecraft", "26.2", "--channel", "beta", "--source-branch",
                "26.2", "--source-sha", "a" * 40, "--loader", "fabric"]

    def test_cli_new_reuse_deterministic_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "plan.json"
            new = self.cli("new", *self.cli_args(), "--output", str(path))
            self.assertEqual(new.returncode, 0, new.stderr)
            original = path.read_bytes()
            again = self.cli("new", *self.cli_args(), "--output", str(path))
            self.assertNotEqual(again.returncode, 0)
            reused = self.cli("reuse", *self.cli_args(), "--existing", str(path), "--catalog", "/does/not/exist")
            self.assertEqual(reused.returncode, 0, reused.stderr)
            self.assertEqual(new.stdout, reused.stdout)
            self.assertEqual(original, path.read_bytes())

    def test_cli_missing_existing_and_duplicate_json_rejected(self):
        self.assertNotEqual(self.cli("reuse", *self.cli_args()).returncode, 0)
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "plan.json"
            path.write_text('{"schema_version":1,"schema_version":1}')
            self.assertNotEqual(self.cli("reuse", *self.cli_args(), "--existing", str(path)).returncode, 0)

    def test_properties_validation(self):
        args = self.cli_args()
        ok = self.cli("new", *args, "--properties", str(SCRIPTS.parent / "gradle.properties"))
        self.assertEqual(ok.returncode, 0, ok.stderr)
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "gradle.properties"
            path.write_text("minecraft_version=26.1\njava_version=25\nfabric_loader_version=0.19.3\n")
            self.assertNotEqual(self.cli("new", *args, "--properties", str(path)).returncode, 0)


if __name__ == "__main__":
    unittest.main()
