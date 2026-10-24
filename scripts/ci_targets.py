#!/usr/bin/env python3
"""Plan one source checkout's CI and fail closed when aggregating required jobs."""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys
import subprocess
import urllib.parse
import urllib.request

from validate_targets import (ValidationError, load_catalog, select_targets,
                              validate_source_branch, load_properties, validate_properties, validate_catalog)

SHA = re.compile(r"[0-9a-f]{40}")
COMMON = {"build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
          "support-targets.json"}
COMMON_PREFIXES = ("src/", "gradle/", "config/checkstyle/", ".github/workflows/", ".github/actions/", "scripts/")


def changed_loaders(paths: list[str]) -> set[str]:
    """Paths shared with the workflow's paths-filter (fixtures check both definitions)."""
    result = set()
    for path in paths:
        if path in COMMON or path.startswith(COMMON_PREFIXES):
            result.update({"fabric", "neoforge"})
        for loader in ("fabric", "neoforge"):
            if path.startswith(loader + "/"):
                result.add(loader)
    return result


def permits_validation_source(authority: str, branch: str, row: dict) -> bool:
    """A numeric old Active branch may remain testable without becoming a publication source."""
    return row["source_branch"] == branch or (
        authority == "main" and row["source_branch"] == "main" and row["lifecycle"] == "active"
        and row["minecraft"] == branch and re.fullmatch(r"[0-9]+\.[0-9]+(?:\.[0-9]+)?", branch) is not None)


def plan(catalog: dict, branch: str, source_sha: str, loaders: set[str], target: str | None = None) -> dict:
    validate_source_branch(branch)
    if target is not None and (not isinstance(target, str) or not target):
        raise ValidationError("dispatch requires exactly one target")
    if not SHA.fullmatch(source_sha):
        raise ValidationError("source must be a full immutable SHA")
    if not loaders <= {"fabric", "neoforge"}:
        raise ValidationError("unknown changed loader")
    rows = select_targets(catalog, target_ids=[target] if target else None)
    rows = [row for row in rows if permits_validation_source(catalog["authority_branch"], branch, row)]
    if not rows:
        raise ValidationError("branch has no supported catalog targets")
    selected = rows if target else [row for row in rows if row["loader"] in loaders]
    # source_* remain explicit compatibility aliases for the validation checkout, never publication provenance.
    return {"authority_branch": catalog["authority_branch"],
            "source_branch": branch, "source_sha": source_sha,
            "validation_source_branch": branch, "validation_source_sha": source_sha,
            "publication_source_branch": rows[0]["source_branch"], "targets": rows,
            "matrix": {"include": [{"target": row["id"], "loader": row["loader"]} for row in selected]},
            "has_builds": bool(selected), "changed_loaders": sorted(loaders), "requested_target": target}


def validate_plan(value: dict) -> None:
    if not isinstance(value, dict) or set(value) != {"authority_branch", "source_branch", "source_sha",
                                                   "validation_source_branch", "validation_source_sha",
                                                   "publication_source_branch", "targets", "matrix", "has_builds",
                                                   "changed_loaders", "requested_target"}:
        raise ValidationError("invalid CI plan fields")
    validate_source_branch(value["source_branch"])
    validate_source_branch(value["authority_branch"])
    validate_source_branch(value["publication_source_branch"])
    if (value["source_branch"] != value["validation_source_branch"]
            or value["source_sha"] != value["validation_source_sha"]):
        raise ValidationError("validation source aliases disagree")
    if not isinstance(value["source_sha"], str) or not SHA.fullmatch(value["source_sha"]):
        raise ValidationError("invalid CI source SHA")
    rows = value["targets"]
    if not isinstance(rows, list) or not rows:
        raise ValidationError("missing planned targets")
    validate_catalog({"schema_version": 1, "authority_branch": value["authority_branch"], "targets": rows})
    changed = value["changed_loaders"]
    if (not isinstance(changed, list) or any(not isinstance(item, str) for item in changed)
            or len(set(changed)) != len(changed) or not set(changed) <= {"fabric", "neoforge"}):
        raise ValidationError("invalid changed loader selection")
    requested = value["requested_target"]
    if requested is not None and (not isinstance(requested, str) or len(rows) != 1 or rows[0]["id"] != requested):
        raise ValidationError("invalid dispatch target")
    ids = set()
    for row in rows:
        if (not isinstance(row, dict) or row.get("source_branch") != value["publication_source_branch"]
                or not permits_validation_source(value["authority_branch"], value["validation_source_branch"], row)
                or row.get("loader") not in {"fabric", "neoforge"}
                or row.get("id") != f"{row.get('minecraft')}-{row.get('loader')}"
                or row.get("support") != "supported" or row.get("lifecycle") not in {"active", "maintenance"}
                or row["id"] in ids):
            raise ValidationError("invalid planned target")
        ids.add(row["id"])
    matrix = value["matrix"]
    if not isinstance(matrix, dict) or set(matrix) != {"include"} or not isinstance(matrix["include"], list):
        raise ValidationError("invalid CI matrix")
    seen = set()
    for entry in matrix["include"]:
        if (not isinstance(entry, dict) or set(entry) != {"target", "loader"}
                or entry["target"] not in ids or entry["target"] in seen
                or not any(row["id"] == entry["target"] and row["loader"] == entry["loader"] for row in rows)):
            raise ValidationError("unexpected/duplicate matrix target")
        seen.add(entry["target"])
    expected = {row["id"] for row in rows if requested or row["loader"] in changed}
    if seen != expected:
        raise ValidationError("matrix omits/includes unexpected required target")
    if type(value["has_builds"]) is not bool or value["has_builds"] != bool(seen):
        raise ValidationError("matrix/build condition mismatch")


def aggregate(value: dict, results: dict) -> None:
    validate_plan(value)
    expected = {"changes", "format", "test", "script-tests", "build"}
    if not isinstance(results, dict) or set(results) != expected:
        raise ValidationError("missing/unexpected required jobs")
    outputs = results["changes"].get("outputs", {})
    if (outputs.get("matrix") != json.dumps(value["matrix"], separators=(",", ":"))
            or outputs.get("has-builds") != str(value["has_builds"]).lower()
            or outputs.get("check-target") != value["targets"][0]["id"]):
        raise ValidationError("workflow outputs do not match validated plan")
    for name in expected:
        required = "skipped" if name == "build" and not value["has_builds"] else "success"
        if results[name].get("result") != required:
            raise ValidationError(f"{name}: expected {required}, got {results[name].get('result')}")


def resolve_branch(repository: str, branch: str) -> str:
    validate_source_branch(branch)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValidationError("invalid repository")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/git/ref/heads/{urllib.parse.quote(branch, safe='')}",
        headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["object"]["sha"]


def load_ci_catalog(primary: str | pathlib.Path, fallback: str | pathlib.Path | None = None) -> tuple[dict, bool]:
    """Only a missing base catalog permits first-introduction bootstrap."""
    try:
        return load_catalog(primary), False
    except FileNotFoundError:
        if fallback is None:
            raise
        return load_catalog(fallback), True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["plan", "aggregate", "checkout", "report"])
    args = parser.parse_args()
    try:
        if args.mode == "report":
            value = json.loads(os.environ["PLAN"])
            validate_plan(value)
            row = next(row for row in value["targets"] if row["id"] == os.environ["TARGET"])
            jdk = "unavailable (JDK setup failed/skipped)"
            if os.environ.get("SETUP_RESULT") == "success":
                result = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False)
                jdk = (result.stderr or result.stdout).splitlines()[0] if result.returncode == 0 else "unavailable"
            fields = {"Target": row["id"], "Minecraft": row["minecraft"], "Loader": row["loader"],
                      "Validation source branch": value["validation_source_branch"],
                      "Validation source SHA": value["validation_source_sha"],
                      "Publication source branch": value["publication_source_branch"],
                      "Publication eligibility": "CI only; new release plans require the publication branch",
                      "Java major": row["java_version"], "JDK": jdk,
                      "Mode": os.environ["BUILD_MODE"], "Job result": os.environ.get("JOB_RESULT", "unknown"),
                      "Gradle result": os.environ.get("GRADLE_RESULT", "unknown"),
                      "Gradle cache exact hit": os.environ.get("CACHE_HIT") or "not reported",
                      "Gradle elapsed seconds": os.environ.get("ELAPSED_SECONDS") or "not started"}
            with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
                summary.write("### Target validation\n\n| Field | Value |\n| --- | --- |\n")
                for key, item in fields.items():
                    summary.write(f"| {key} | {str(item).replace('|', '/').replace(chr(10), ' ')} |\n")
        elif args.mode == "checkout":
            value = json.loads(os.environ["PLAN"])
            validate_plan(value)
            target = os.environ["TARGET"]
            rows = [row for row in value["targets"] if row["id"] == target]
            if len(rows) != 1:
                raise ValidationError("target is not in validated plan")
            validate_properties(rows, load_properties("source/gradle.properties"),
                                source_branch=value["publication_source_branch"])
            if os.environ["BUILD_MODE"] not in {"format", "test", "build"}:
                raise ValidationError("unknown build mode")
            with pathlib.Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
                output.write(f"loader={rows[0]['loader']}\n")
        elif args.mode == "aggregate":
            aggregate(json.loads(os.environ["PLAN"]), json.loads(os.environ["NEEDS"]))
        else:
            catalog, bootstrap = load_ci_catalog(os.environ.get("CATALOG", "support-targets.json"),
                                                 os.environ.get("FALLBACK_CATALOG") or None)
            if bootstrap:
                message = ("CI catalog bootstrap: PR destination has no support-targets.json; "
                           "validate merge catalog against destination branch and checkout properties.")
                print(message)
                with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
                    summary.write(message + "\n")
            event = os.environ["EVENT_NAME"]
            if event not in {"pull_request", "push", "workflow_dispatch"}:
                raise ValidationError("unsupported event")
            target = os.environ.get("TARGET") if event == "workflow_dispatch" else None
            branch = os.environ["SOURCE_BRANCH"]
            sha = os.environ["SOURCE_SHA"]
            if event == "workflow_dispatch":
                if not target:
                    raise ValidationError("dispatch requires one target")
                row = select_targets(catalog, target_ids=[target])[0]
                branch = row["source_branch"]
                sha = resolve_branch(os.environ["GITHUB_REPOSITORY"], branch)
            loaders = set()
            for loader in ("fabric", "neoforge"):
                flag = os.environ.get(loader.upper(), "false")
                if flag not in {"true", "false"}:
                    raise ValidationError("invalid filter boolean")
                if flag == "true":
                    loaders.add(loader)
            value = plan(catalog, branch, sha, loaders, target)
            validate_plan(value)
            outputs = {"plan": json.dumps(value, separators=(",", ":")),
                       "matrix": json.dumps(value["matrix"], separators=(",", ":")),
                       "has-builds": str(value["has_builds"]).lower(), "source-sha": sha,
                       "source-branch": branch, "check-target": value["targets"][0]["id"]}
            with pathlib.Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
                for key, item in outputs.items():
                    output.write(f"{key}={item}\n")
        return 0
    except (ValidationError, KeyError, ValueError, TypeError, OSError) as exc:
        print(f"CI validation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
