"""CI selection/aggregation fixtures, independent of GitHub runners and network."""
import copy
import json
import pathlib
import sys
import unittest
import tempfile
import os
import subprocess
import textwrap
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
CATALOG = pathlib.Path(__file__).parent / "fixtures/support-targets-26.2.json"
sys.path.insert(0, str(ROOT / "scripts"))
import ci_targets as ci
from validate_targets import ValidationError, load_catalog


class CiTargetsTest(unittest.TestCase):
    def setUp(self):
        self.catalog = load_catalog(CATALOG)
        self.sha = "a" * 40

    def plan(self, paths):
        return ci.plan(self.catalog, "26.2", self.sha, ci.changed_loaders(paths))

    def results(self, value):
        results = {name: {"result": "skipped" if name == "build" and not value["has_builds"] else "success"}
                for name in ["changes", "format", "test", "script-tests", "build"]}
        results["changes"]["outputs"] = {
            "matrix": json.dumps(value["matrix"], separators=(",", ":")),
            "has-builds": str(value["has_builds"]).lower(), "check-target": value["targets"][0]["id"]}
        return results

    def test_common_loader_docs_fixture_job_sets(self):
        cases = [(["src/main/Example.java"], {"fabric", "neoforge"}),
                 (["fabric/src/example"], {"fabric"}), (["neoforge/build.gradle"], {"neoforge"}),
                 (["docs/ci-build.md"], set()), (["scripts/ci_targets.py"], {"fabric", "neoforge"}),
                 (["support-targets.json"], {"fabric", "neoforge"})]
        for paths, loaders in cases:
            with self.subTest(paths=paths):
                value = self.plan(paths)
                ci.validate_plan(value)
                self.assertEqual({entry["loader"] for entry in value["matrix"]["include"]}, loaders)
                ci.aggregate(value, self.results(value))

    def test_every_common_pattern_is_in_workflow_filter(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        for name in ci.COMMON:
            self.assertIn("- '" + name + "'", workflow)
        for prefix in ci.COMMON_PREFIXES:
            self.assertIn("- '" + prefix + "**'", workflow)
        for loader in ["fabric", "neoforge"]:
            self.assertIn("- '" + loader + "/**'", workflow)

    def test_every_required_failure_cancel_or_skip_fails(self):
        for paths in [["src/main/X.java"], ["docs/test.md"]]:
            value = self.plan(paths)
            results = self.results(value)
            for name in results:
                for state in ["success", "skipped", "failure", "cancelled", "unknown"]:
                    if state == results[name]["result"]:
                        continue
                    changed = copy.deepcopy(results)
                    changed[name]["result"] = state
                    with self.subTest(name=name, state=state, paths=paths), self.assertRaises(ValidationError):
                        ci.aggregate(value, changed)

    def test_missing_extra_jobs_fail(self):
        value = self.plan(["fabric/build.gradle"])
        results = self.results(value)
        del results["format"]
        with self.assertRaises(ValidationError):
            ci.aggregate(value, results)
        results = self.results(value) | {"unexpected": {"result": "success"}}
        with self.assertRaises(ValidationError):
            ci.aggregate(value, results)

    def test_malformed_matrix_is_not_a_legal_skip(self):
        original = self.plan(["src/main/X.java"])
        mutations = [lambda p: p.update(matrix={}),
                     lambda p: p.update(matrix={"include": []}, has_builds=False),
                     lambda p: p["matrix"]["include"].pop(),
                     lambda p: p["matrix"]["include"].append(p["matrix"]["include"][0]),
                     lambda p: p["matrix"]["include"][0].update(target="26.2-unknown"),
                     lambda p: p["matrix"]["include"][0].update(loader="neoforge"),
                     lambda p: p.update(has_builds="true"),
                     lambda p: p.update(source_sha="main"),
                     lambda p: p.update(targets=[])]
        for mutation in mutations:
            value = copy.deepcopy(original)
            mutation(value)
            with self.subTest(mutation=mutation), self.assertRaises(ValidationError):
                ci.aggregate(value, self.results(original))

    def test_plan_outputs_cannot_disagree_with_matrix_or_condition(self):
        value = self.plan(["src/X.java"])
        for key, item in [("matrix", '{"include":[]}'), ("has-builds", "false"),
                          ("check-target", "unknown")]:
            results = self.results(value)
            results["changes"]["outputs"][key] = item
            with self.subTest(key=key), self.assertRaises(ValidationError):
                ci.aggregate(value, results)

    def test_dispatch_selects_exact_target_even_without_changed_paths(self):
        value = ci.plan(self.catalog, "26.2", self.sha, set(), "26.2-neoforge")
        ci.validate_plan(value)
        self.assertEqual(value["matrix"]["include"], [{"target": "26.2-neoforge", "loader": "neoforge"}])
        ci.aggregate(value, self.results(value))
        for target in ["unknown", "", "26.2-fabric,26.2-neoforge"]:
            with self.subTest(target=target), self.assertRaises(ValidationError):
                ci.plan(self.catalog, "26.2", self.sha, set(), target)

    def test_main_numeric_and_mc_branch_allowlist(self):
        for branch in ["main", "26.2", "mc/26.2"]:
            catalog = copy.deepcopy(self.catalog)
            catalog["authority_branch"] = branch
            for row in catalog["targets"]:
                row["source_branch"] = branch
            ci.validate_plan(ci.plan(catalog, branch, self.sha, {"fabric", "neoforge"}))
        for branch in ["mc/latest", "feature/test", "refs/heads/26.2", "26.2;echo bad"]:
            with self.subTest(branch=branch), self.assertRaises(ValidationError):
                ci.plan(self.catalog, branch, self.sha, set())

    def test_source_checkout_is_immutable_and_matches_catalog(self):
        for sha in ["main", "a" * 7, "A" * 40, "a" * 41]:
            with self.subTest(sha=sha), self.assertRaises(ValidationError):
                ci.plan(self.catalog, "26.2", sha, {"fabric"})
        with self.assertRaises(ValidationError):
            ci.plan(self.catalog, "mc/26.1", self.sha, {"fabric"})

    def test_catalog_base_has_priority_and_only_absence_bootstraps(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory) / "base.json"
            merge = pathlib.Path(directory) / "merge.json"
            merge.write_text(json.dumps(self.catalog))
            result, bootstrap = ci.load_ci_catalog(base, merge)
            self.assertTrue(bootstrap)
            self.assertEqual(result, self.catalog)
            base_catalog = copy.deepcopy(self.catalog)
            base_catalog["targets"][0]["limitations"].append("base catalog wins")
            base.write_text(json.dumps(base_catalog))
            result, bootstrap = ci.load_ci_catalog(base, merge)
            self.assertFalse(bootstrap)
            self.assertEqual(result, base_catalog)
            for invalid in ["{", '{}', json.dumps(self.catalog | {"schema_version": 99})]:
                base.write_text(invalid)
                with self.subTest(invalid=invalid), self.assertRaises((ValidationError, ValueError)):
                    ci.load_ci_catalog(base, merge)
            with patch.object(ci, "load_catalog", side_effect=PermissionError("denied")) as loader:
                with self.assertRaises(PermissionError):
                    ci.load_ci_catalog(base, merge)
                self.assertEqual(loader.call_count, 1)

    def test_setup_build_default_and_target_properties_regression(self):
        action = (ROOT / ".github/actions/setup-build/action.yml").read_text()
        self.assertIn("default: 'gradle.properties'", action)
        self.assertIn("cache: 'gradle'", action)
        script = textwrap.dedent(action.split("      run: |\n", 1)[1].split("\n    - name: Setup Java", 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for location in ["gradle.properties", "target checkout/gradle.properties"]:
                properties = root / location
                properties.parent.mkdir(exist_ok=True)
                for contents, succeeds in [("java_version=25\n", True), ("java_version=21\n", True),
                                            ("java_version=25\njava_version=21\n", False),
                                            ("java_version=latest\n", False), ("# java_version=25\n", False)]:
                    properties.write_text(contents)
                    output = root / "output"
                    output.write_text("")
                    env = os.environ | {"PROPERTIES_PATH": location, "GITHUB_OUTPUT": str(output)}
                    result = subprocess.run(["bash", "-c", script], cwd=root, env=env,
                                            capture_output=True, text=True, check=False)
                    with self.subTest(location=location, contents=contents):
                        self.assertEqual(result.returncode == 0, succeeds, result.stderr)
                        if succeeds:
                            self.assertEqual(output.read_text(), contents.replace("java_version", "java-version"))

    def test_workflow_permissions_checkout_and_verification_contract(self):
        build = (ROOT / ".github/workflows/build.yml").read_text()
        target = (ROOT / ".github/workflows/target-build.yml").read_text()
        self.assertIn("name: CI OK", build)
        self.assertIn("base: ${{ github.ref_name }}", build)
        self.assertIn("ref: ${{ github.event.pull_request.base.sha }}", build)
        self.assertIn("ref: ${{ fromJSON(inputs.source-plan).validation_source_sha }}", target)
        self.assertIn("build-artifacts-${{ inputs.target }}-${{ fromJSON(inputs.source-plan).validation_source_sha }}", target)
        self.assertIn("format) ./gradlew spotlessCheck --dependency-verification=strict", target)
        self.assertEqual(target.count("test) ./gradlew :fabric:test"), 1)
        for workflow in [build, target]:
            self.assertNotIn("secrets:", workflow)
            self.assertNotIn("secrets.", workflow)
            self.assertNotIn(": write", workflow)
            self.assertNotIn("pull_request_target:", workflow)
            self.assertNotIn("--write-verification-metadata", workflow)
            for block in workflow.split("run:")[1:]:
                # Expressions must be passed through env/with rather than interpreted by a shell.
                run = block.split("\n      -", 1)[0].split("\n\n  ", 1)[0]
                self.assertNotIn("${{", run)

    def test_checkout_properties_mismatch_fails_before_gradle(self):
        value = self.plan(["src/X.java"])
        properties = (ROOT / "gradle.properties").read_text()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "source").mkdir()
            output = root / "output"
            env = os.environ | {"PLAN": json.dumps(value), "TARGET": "26.2-fabric", "BUILD_MODE": "build",
                                "GITHUB_OUTPUT": str(output)}
            for contents, mode, valid in [(properties, "build", True), (properties, "format", True),
                                           (properties, "test", True), (properties, "build; echo injected", False),
                                           (properties.replace("minecraft_version=26.2", "minecraft_version=26.1"), "build", False),
                                           (properties.replace("java_version=25", "java_version=21"), "build", False)]:
                (root / "source/gradle.properties").write_text(contents)
                result = subprocess.run([sys.executable, str(ROOT / "scripts/ci_targets.py"), "checkout"],
                                        cwd=root, env=env | {"BUILD_MODE": mode}, capture_output=True, text=True)
                with self.subTest(mode=mode, valid=valid):
                    self.assertEqual(result.returncode == 0, valid, result.stderr)

    def test_cli_records_failure_metrics_without_jdk_setup(self):
        value = self.plan(["fabric/build.gradle"])
        with tempfile.TemporaryDirectory() as directory:
            summary = pathlib.Path(directory) / "summary.md"
            env = os.environ | {"PLAN": json.dumps(value), "TARGET": "26.2-fabric", "BUILD_MODE": "build",
                                "GITHUB_STEP_SUMMARY": str(summary), "SETUP_RESULT": "failure",
                                "GRADLE_RESULT": "skipped", "JOB_RESULT": "failure"}
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci_targets.py"), "report"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            contents = summary.read_text()
            for text in [self.sha, "26.2-fabric", "failure", "skipped", "not started", "unavailable"]:
                self.assertIn(text, contents)

    def test_missing_or_invalid_filter_output_cannot_become_legal_skip(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "output"
            env = os.environ | {"EVENT_NAME": "push", "SOURCE_BRANCH": "26.2", "SOURCE_SHA": self.sha,
                                "GITHUB_OUTPUT": str(output), "CATALOG": str(CATALOG),
                                "NEOFORGE": "false", "FALLBACK_CATALOG": ""}
            for flag, valid in [("", False), ("False", False), ("unknown", False),
                                ("false", True), ("true", True)]:
                result = subprocess.run([sys.executable, str(ROOT / "scripts/ci_targets.py"), "plan"],
                                        env=env | {"FABRIC": flag}, capture_output=True, text=True)
                with self.subTest(flag=flag):
                    self.assertEqual(result.returncode == 0, valid, result.stderr)

    def test_json_roundtrip_keeps_plan_valid(self):
        value = self.plan(["src/X.java"])
        ci.validate_plan(json.loads(json.dumps(value)))


if __name__ == "__main__":
    unittest.main()
