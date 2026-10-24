#!/usr/bin/env python3
"""Validate/select support targets without network access or build side effects.

Public functions return fresh dictionaries; callers may add resolved source SHAs
to their own plans without modifying the catalog. CLI JSON is sorted by target ID.
"""
from __future__ import annotations

import argparse
import copy
import datetime
import json
import pathlib
import re
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADERS = frozenset({"fabric", "neoforge"})
MC_PATTERN = r"[1-9][0-9]*\.(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*))?"
ROW_KEYS = frozenset({"id", "minecraft", "loader", "lifecycle", "support", "source_branch",
                      "java_version", "features", "limitations", "verification"})


class ValidationError(ValueError):
    """Invalid catalog, selection, or checkout configuration."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def _keys(value: Any, expected: set | frozenset, context: str) -> None:
    _require(isinstance(value, dict), f"{context}: expected object")
    _require(set(value) == expected, f"{context}: unexpected or missing fields")


def _strings(value: Any, context: str) -> None:
    _require(isinstance(value, list), f"{context}: expected list")
    _require(all(isinstance(item, str) and item.strip() for item in value),
             f"{context}: expected nonempty strings")
    _require(len(value) == len(set(value)), f"{context}: duplicate entry")


def validate_source_branch(branch: Any) -> str:
    """Only main, a numeric MC branch, or mc/<numeric MC> is allowed."""
    _require(isinstance(branch, str), "source_branch: expected string")
    _require(branch == "main" or re.fullmatch(rf"(?:mc/)?{MC_PATTERN}", branch) is not None,
             f"source_branch: disallowed ref {branch!r}")
    return branch


def _verification(value: Any, context: str) -> None:
    _keys(value, {"status", "date", "evidence"}, context)
    _require(isinstance(value["status"], str) and value["status"] in {"unverified", "passed", "failed"},
             f"{context}: unknown status")
    _strings(value["evidence"], f"{context}.evidence")
    if value["status"] == "unverified":
        _require(value["date"] is None and not value["evidence"],
                 f"{context}: unverified must not claim a date/evidence")
        return
    date = value["date"]
    _require(isinstance(date, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", date) is not None,
             f"{context}: verified result requires ISO date")
    try:
        datetime.date.fromisoformat(date)
    except ValueError as exc:
        raise ValidationError(f"{context}: invalid calendar date") from exc
    _require(bool(value["evidence"]), f"{context}: verified result requires evidence")


def validate_catalog(data: Any) -> dict:
    """Validate complete schema and cross-row invariants; return an independent copy."""
    _keys(data, {"schema_version", "authority_branch", "targets"}, "catalog")
    _require(type(data["schema_version"]) is int and data["schema_version"] == 1,
             "catalog: unsupported schema_version")
    validate_source_branch(data["authority_branch"])
    _require(isinstance(data["targets"], list) and bool(data["targets"]), "catalog: targets must be nonempty list")
    ids, mc_sources, mc_java, mc_lifecycle = set(), {}, {}, {}
    active, maintenance, branch_mcs = set(), set(), {}
    for row in data["targets"]:
        _keys(row, ROW_KEYS, "target")
        mc, loader, target_id = row["minecraft"], row["loader"], row["id"]
        _require(isinstance(mc, str) and re.fullmatch(MC_PATTERN, mc) is not None, "target: invalid minecraft")
        _require(isinstance(loader, str) and loader in LOADERS, "target: unknown loader")
        _require(isinstance(target_id, str) and target_id == f"{mc}-{loader}", "target: id must be <MC>-<loader>")
        _require(target_id not in ids, f"target: duplicate {target_id}")
        ids.add(target_id)
        lifecycle, support = row["lifecycle"], row["support"]
        _require(isinstance(lifecycle, str) and lifecycle in {"active", "maintenance", "eol"},
                 f"{target_id}: unknown lifecycle")
        _require(isinstance(support, str) and support in {"supported", "testing", "unsupported"},
                 f"{target_id}: unknown support")
        _require(lifecycle != "eol" or support == "unsupported", f"{target_id}: EOL must be unsupported")
        branch = validate_source_branch(row["source_branch"])
        _require(branch == "main" or branch.removeprefix("mc/") == mc,
                 f"{target_id}: numeric source branch must match MC")
        _require(mc not in mc_sources or mc_sources[mc] == branch, f"{mc}: conflicting source branches")
        mc_sources[mc] = branch
        java = row["java_version"]
        _require(type(java) is int and java > 0, f"{target_id}: java_version must be positive integer")
        _require(mc not in mc_java or mc_java[mc] == java, f"{mc}: conflicting Java versions")
        mc_java[mc] = java
        _strings(row["features"], f"{target_id}.features")
        _strings(row["limitations"], f"{target_id}.limitations")
        _require(loader == "fabric" or "modmenu" not in row["features"], f"{target_id}: ModMenu is Fabric only")
        _keys(row["verification"], {"build", "runtime_smoke"}, f"{target_id}.verification")
        for kind, value in row["verification"].items():
            _verification(value, f"{target_id}.{kind}")
        if support == "supported":
            _require(branch not in branch_mcs or branch_mcs[branch] == mc,
                     f"{branch}: one source branch cannot publish multiple MC versions")
            branch_mcs[branch] = mc
            _require(mc not in mc_lifecycle or mc_lifecycle[mc] == lifecycle,
                     f"{mc}: conflicting supported lifecycles")
            mc_lifecycle[mc] = lifecycle
            (active if lifecycle == "active" else maintenance).add(mc)
    _require(len(active) <= 1 and len(maintenance) <= 1, "catalog: maximum one Active and one Maintenance MC")
    return copy.deepcopy(data)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict:
    result = {}
    for key, value in pairs:
        _require(key not in result, f"JSON: duplicate key {key}")
        result[key] = value
    return result


def load_catalog(path: str | pathlib.Path = ROOT / "support-targets.json") -> dict:
    return validate_catalog(json.loads(pathlib.Path(path).read_text(encoding="utf-8"),
                                       object_pairs_hook=_unique_object))


def select_targets(catalog: dict, *, target_ids: list[str] | None = None,
                   loader: str | None = None, minecraft: str | None = None) -> list[dict]:
    """Select supported, non-EOL rows. Explicit unknown/unavailable targets fail."""
    catalog = validate_catalog(catalog)
    _require(loader is None or loader in LOADERS, "selection: unknown loader")
    if minecraft is not None:
        _require(any(row["minecraft"] == minecraft for row in catalog["targets"]), "selection: unknown MC")
    rows = {row["id"]: row for row in catalog["targets"]}
    if target_ids is not None:
        _require(bool(target_ids) and len(target_ids) == len(set(target_ids)), "selection: empty or duplicate IDs")
        for target_id in target_ids:
            _require(target_id in rows, f"selection: unknown target {target_id}")
            row = rows[target_id]
            _require(row["support"] == "supported" and row["lifecycle"] != "eol",
                     f"selection: unavailable target {target_id}")
            _require((loader is None or row["loader"] == loader)
                     and (minecraft is None or row["minecraft"] == minecraft),
                     f"selection: filters exclude requested target {target_id}")
    selected = [row for row in rows.values()
                if row["support"] == "supported" and row["lifecycle"] != "eol"
                and (target_ids is None or row["id"] in target_ids)
                and (loader is None or row["loader"] == loader)
                and (minecraft is None or row["minecraft"] == minecraft)]
    _require(bool(selected), "selection: no supported targets")
    return sorted(selected, key=lambda row: row["id"])


def load_properties(path: str | pathlib.Path) -> dict[str, str]:
    """Read the repository's simple key=value Gradle properties; reject duplicates."""
    result = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        _require("=" in line, "properties: expected key=value")
        key, value = (part.strip() for part in line.split("=", 1))
        _require(bool(key) and key not in result, f"properties: empty or duplicate key {key}")
        result[key] = value
    return result


def validate_properties(targets: list[dict], properties: dict[str, str], *, source_branch: str) -> None:
    """Check selected rows against one checkout; never infer its source from MC alone."""
    validate_source_branch(source_branch)
    _require(bool(targets), "properties: no selected targets")
    for row in targets:
        _require(row["source_branch"] == source_branch, f"{row['id']}: checkout source branch mismatch")
        _require(properties.get("minecraft_version") == row["minecraft"], f"{row['id']}: minecraft_version mismatch")
        _require(properties.get("java_version") == str(row["java_version"]), f"{row['id']}: java_version mismatch")
        key = "fabric_loader_version" if row["loader"] == "fabric" else "neoforge_version"
        _require(bool(properties.get(key)), f"{row['id']}: missing {key}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=pathlib.Path, default=ROOT / "support-targets.json")
    parser.add_argument("--target", action="append", dest="target_ids")
    parser.add_argument("--loader", choices=sorted(LOADERS))
    parser.add_argument("--minecraft")
    parser.add_argument("--all-supported", action="store_true", help="Explicitly select all supported rows (default)")
    parser.add_argument("--properties", type=pathlib.Path, help="Properties from the selected source checkout")
    parser.add_argument("--source-branch", help="Expected source branch of that checkout; requires --properties")
    args = parser.parse_args(argv)
    try:
        _require(not args.all_supported or not (args.target_ids or args.loader or args.minecraft),
                 "selection: --all-supported cannot be combined with filters")
        _require((args.properties is None) == (args.source_branch is None),
                 "properties: --properties and --source-branch must be supplied together")
        catalog = load_catalog(args.catalog)
        selected = select_targets(catalog, target_ids=args.target_ids, loader=args.loader, minecraft=args.minecraft)
        if args.properties is not None:
            validate_properties(selected, load_properties(args.properties), source_branch=args.source_branch)
        print(json.dumps({"schema_version": 1, "authority_branch": catalog["authority_branch"],
                          "targets": selected}, ensure_ascii=False, sort_keys=True, indent=2))
    except (ValidationError, OSError, json.JSONDecodeError) as exc:
        print(f"target validation failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
