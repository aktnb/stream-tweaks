#!/usr/bin/env python3
"""Freeze all-supported/subset validation sources before any matrix jobs start."""
from __future__ import annotations

import argparse
import copy
import json
import os
import pathlib
import sys
from collections.abc import Callable

import ci_targets
from validate_targets import (ValidationError, load_catalog, select_targets,
                              validate_source_branch, validate_catalog)


def resolve_targets(catalog: dict, catalog_sha: str, controller_branch: str,
                    target_ids: list[str] | None, resolve: Callable[[str], str]) -> dict:
    """Resolve each allowed branch exactly once; no later build consults branch HEAD."""
    validate_source_branch(controller_branch)
    # select_targets validates the whole catalog, including rows outside the subset.
    rows = select_targets(catalog, target_ids=target_ids)
    if controller_branch != catalog["authority_branch"]:
        raise ValidationError("validation must run from the catalog authority branch")
    if not isinstance(catalog_sha, str) or not ci_targets.SHA.fullmatch(catalog_sha):
        raise ValidationError("catalog source must be a full SHA")
    sources = []
    for branch in sorted({row["source_branch"] for row in rows}):
        selected = [row for row in rows if row["source_branch"] == branch]
        subset = {"schema_version": 1, "authority_branch": catalog["authority_branch"], "targets": selected}
        value = ci_targets.plan(subset, branch, resolve(branch), {"fabric", "neoforge"})
        ci_targets.validate_plan(value)
        sources.append(value)
    result = {"schema_version": 1, "authority_branch": controller_branch,
              "catalog_sha": catalog_sha, "sources": sources}
    validate_resolution(result)
    return copy.deepcopy(result)


def validate_resolution(value: dict) -> None:
    if (not isinstance(value, dict)
            or set(value) != {"schema_version", "authority_branch", "catalog_sha", "sources"}
            or type(value["schema_version"]) is not int or value["schema_version"] != 1):
        raise ValidationError("invalid validation resolution schema")
    validate_source_branch(value["authority_branch"])
    if not isinstance(value["catalog_sha"], str) or not ci_targets.SHA.fullmatch(value["catalog_sha"]):
        raise ValidationError("invalid catalog SHA")
    if not isinstance(value["sources"], list) or not value["sources"]:
        raise ValidationError("validation requires at least one source")
    branches, targets, rows = set(), set(), []
    for source in value["sources"]:
        ci_targets.validate_plan(source)
        branch = source["source_branch"]
        if (source["authority_branch"] != value["authority_branch"]
                or source["validation_source_branch"] != source["publication_source_branch"]):
            raise ValidationError("all-supported validation must resolve publication sources")
        if branch in branches or source["requested_target"] is not None:
            raise ValidationError("duplicate source or unexpected single-target plan")
        branches.add(branch)
        if source["changed_loaders"] != ["fabric", "neoforge"] or not source["has_builds"]:
            raise ValidationError("all selected targets must be built")
        for row in source["targets"]:
            if row["id"] in targets:
                raise ValidationError("duplicate resolved target")
            targets.add(row["id"])
            rows.append(row)
    validate_catalog({"schema_version": 1, "authority_branch": value["authority_branch"], "targets": rows})


def matrices(value: dict) -> tuple[dict, dict]:
    """One shared-test/format group per source; one build per MC×Loader row."""
    validate_resolution(value)
    groups, builds = [], []
    for source in value["sources"]:
        encoded = json.dumps(source, sort_keys=True, separators=(",", ":"))
        groups.append({"target": source["targets"][0]["id"], "source_plan": encoded})
        builds.extend({"target": row["id"], "source_plan": encoded} for row in source["targets"])
    return {"include": groups}, {"include": builds}


def summary(value: dict) -> str:
    validate_resolution(value)
    lines = ["## 全サポート対象の固定 SHA 検証", "",
             "各 branch HEAD の検証です。PR 修正の backport 完了を証明するものではありません。", "",
             f"Catalog source SHA: `{value['catalog_sha']}`", "",
             "| MC | Loader | source branch | fixed source SHA | planned JDK |",
             "| --- | --- | --- | --- | --- |"]
    for source in value["sources"]:
        for row in source["targets"]:
            lines.append(f"| {row['minecraft']} | {row['loader']} | {source['publication_source_branch']} | "
                         f"`{source['validation_source_sha']}` | {row['java_version']} |")
    lines.extend(["", "行別の実 JDK・結果・cache hit・Gradle 秒数は Target build の summary を参照してください。",
                  "同 source の shared tests / format は Loader ごとに重複実行しません。", ""])
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=pathlib.Path, default=pathlib.Path("support-targets.json"))
    parser.add_argument("--target", action="append", dest="targets")
    parser.add_argument("--all-supported", action="store_true")
    parser.add_argument("--catalog-sha", required=True)
    parser.add_argument("--controller-branch", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--github-output", type=pathlib.Path)
    parser.add_argument("--summary", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        if args.all_supported and args.targets:
            raise ValidationError("all-supported cannot be combined with a subset")
        value = resolve_targets(load_catalog(args.catalog), args.catalog_sha, args.controller_branch,
                                args.targets, lambda branch: ci_targets.resolve_branch(args.repository, branch))
        groups, builds = matrices(value)
        args.output.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write("groups=" + json.dumps(groups, separators=(",", ":")) + "\n")
                output.write("builds=" + json.dumps(builds, separators=(",", ":")) + "\n")
        if args.summary:
            with args.summary.open("a", encoding="utf-8") as output:
                output.write(summary(value))
        return 0
    except (ValidationError, KeyError, ValueError, TypeError, OSError) as exc:
        print(f"target resolution failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
