#!/usr/bin/env python3
"""Create or reuse a fixed release plan; no builds, tags, or publication occur."""
from __future__ import annotations

import argparse
import copy
import json
import pathlib
import re
import sys

try:
    from . import validate_targets as vt
except ImportError:
    import validate_targets as vt

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLAN_KEYS = {"schema_version", "release_id", "tag", "mod_version", "minecraft", "channel",
             "source_branch", "source_sha", "targets"}
TARGET_KEYS = {"id", "loader", "java_version", "filename", "modrinth_version", "destinations"}
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(alpha|beta)\.([1-9][0-9]*))?"


class PlanError(ValueError):
    """A plan or requested release contradicts the fixed publication contract."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PlanError(message)


def validate_identity(*, version: str, minecraft: str, channel: str,
                      source_branch: str, source_sha: str) -> str:
    match = re.fullmatch(VERSION_PATTERN, version) if isinstance(version, str) else None
    require(match is not None, "mod_version: expected pure SemVer, with optional alpha.N/beta.N")
    require(channel in ("alpha", "beta", "release"), "channel: unknown value")
    require((match.group(1) or "release") == channel, "channel does not match explicit mod_version")
    require(isinstance(minecraft, str) and re.fullmatch(vt.MC_PATTERN, minecraft) is not None,
            "minecraft: invalid version")
    vt.validate_source_branch(source_branch)
    require(source_branch == "main" or source_branch.removeprefix("mc/") == minecraft,
            "source branch must match minecraft")
    require(isinstance(source_sha, str) and re.fullmatch(r"[0-9a-f]{40}", source_sha) is not None
            and source_sha != "0" * 40, "source_sha: expected full immutable Git SHA")
    return f"v{version}+mc{minecraft}"


def normalize_loaders(loaders: list[str]) -> list[str]:
    require(isinstance(loaders, list) and bool(loaders), "loaders: explicit nonempty selection required")
    require(all(isinstance(loader, str) and loader in vt.LOADERS for loader in loaders), "loaders: unknown target")
    require(len(set(loaders)) == len(loaders), "loaders: duplicate target")
    return sorted(loaders)


def destinations(curseforge_project: str | None, curseforge_required: bool) -> list[dict]:
    require(type(curseforge_required) is bool, "curseforge_required: expected boolean")
    require(curseforge_project is None or (isinstance(curseforge_project, str)
            and re.fullmatch(r"[1-9][0-9]*", curseforge_project) is not None), "CurseForge project must be numeric")
    require(not curseforge_required or curseforge_project is not None, "required CurseForge needs a project")
    result = [{"name": "github", "project": "aktnb/stream-tweaks", "required": True},
              {"name": "modrinth", "project": "jWHdryID", "required": True}]
    if curseforge_project is not None:
        result.append({"name": "curseforge", "project": curseforge_project, "required": curseforge_required})
    return result


def _target(row: dict, version: str, minecraft: str, tag: str, selected_destinations: list[dict]) -> dict:
    loader = row["loader"]
    return {"id": f"{minecraft}-{loader}", "loader": loader, "java_version": row["java_version"],
            "filename": f"stream-tweaks-{loader}-mc{minecraft}-{version}.jar",
            "modrinth_version": f"{tag}-{loader}", "destinations": copy.deepcopy(selected_destinations)}


def build_plan(catalog: dict, *, version: str, minecraft: str, channel: str,
               source_branch: str, source_sha: str, loaders: list[str],
               curseforge_project: str | None = None, curseforge_required: bool = False) -> dict:
    """Freeze selected supported rows; catalog changes cannot later expand this plan."""
    tag = validate_identity(version=version, minecraft=minecraft, channel=channel,
                            source_branch=source_branch, source_sha=source_sha)
    loaders = normalize_loaders(loaders)
    rows = vt.select_targets(catalog, target_ids=[f"{minecraft}-{loader}" for loader in loaders])
    require(all(row["source_branch"] == source_branch for row in rows), "catalog publication source mismatch")
    selected_destinations = destinations(curseforge_project, curseforge_required)
    return {"schema_version": 1, "release_id": tag, "tag": tag, "mod_version": version,
            "minecraft": minecraft, "channel": channel, "source_branch": source_branch,
            "source_sha": source_sha,
            "targets": [_target(row, version, minecraft, tag, selected_destinations) for row in rows]}


def validate_plan(plan: dict) -> dict:
    """Validate a stored plan without consulting mutable catalog or branch HEADs."""
    require(isinstance(plan, dict) and set(plan) == PLAN_KEYS, "plan: unexpected or missing fields")
    require(type(plan["schema_version"]) is int and plan["schema_version"] == 1, "plan: unsupported schema")
    tag = validate_identity(version=plan["mod_version"], minecraft=plan["minecraft"], channel=plan["channel"],
                            source_branch=plan["source_branch"], source_sha=plan["source_sha"])
    require(plan["release_id"] == tag and plan["tag"] == tag, "plan: release ID mismatch")
    rows = plan["targets"]
    require(isinstance(rows, list) and bool(rows), "plan: missing targets")
    for row in rows:
        require(isinstance(row, dict) and set(row) == TARGET_KEYS, "plan target: unexpected or missing fields")
        require(type(row["java_version"]) is int and row["java_version"] > 0, "plan target: invalid Java")
        ds = row["destinations"]
        require(isinstance(ds, list) and len(ds) in (2, 3), "plan target: invalid destinations")
        for d in ds:
            require(isinstance(d, dict) and set(d) == {"name", "project", "required"}, "invalid destination")
            require(isinstance(d["name"], str) and isinstance(d["project"], str)
                    and type(d["required"]) is bool, "invalid destination field types")
        cf = ds[2] if len(ds) == 3 else None
        expected_ds = destinations(cf["project"] if cf else None, cf["required"] if cf else False)
        require(ds == expected_ds, "plan target: destination contract mismatch")
        require(row == _target(row, plan["mod_version"], plan["minecraft"], tag, expected_ds),
                "plan target: ID/filename/version mismatch")
    loaders = normalize_loaders([row["loader"] for row in rows])
    require([row["loader"] for row in rows] == loaders, "plan: targets must be sorted")
    require(len({row["java_version"] for row in rows}) == 1, "plan: conflicting Java versions")
    require(all(row["destinations"] == rows[0]["destinations"] for row in rows),
            "plan: conflicting target destination contracts")
    return copy.deepcopy(plan)


def reuse_plan(existing: dict, *, version: str, minecraft: str, channel: str,
               source_branch: str, source_sha: str, loaders: list[str],
               curseforge_project: str | None = None, curseforge_required: bool = False) -> dict:
    """Require the same explicit identity, targets and destinations; never re-resolve catalog."""
    plan = validate_plan(existing)
    tag = validate_identity(version=version, minecraft=minecraft, channel=channel,
                            source_branch=source_branch, source_sha=source_sha)
    require(plan["release_id"] == tag and plan["channel"] == channel, "reuse: release identity mismatch")
    require(plan["source_sha"] == source_sha and plan["source_branch"] == source_branch, "reuse: source mismatch")
    require([row["loader"] for row in plan["targets"]] == normalize_loaders(loaders), "reuse: target set mismatch")
    require(plan["targets"][0]["destinations"] == destinations(curseforge_project, curseforge_required),
            "reuse: destination contract mismatch")
    return plan


def canonical_json(plan: dict) -> str:
    return json.dumps(validate_plan(plan), ensure_ascii=False, sort_keys=True, indent=2) + "\n"


def load_plan(path: str | pathlib.Path) -> dict:
    return validate_plan(json.loads(pathlib.Path(path).read_text(encoding="utf-8"),
                                    object_pairs_hook=vt._unique_object))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["new", "reuse"])
    parser.add_argument("--catalog", type=pathlib.Path, default=ROOT / "support-targets.json")
    parser.add_argument("--version", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--channel", required=True, choices=["alpha", "beta", "release"])
    parser.add_argument("--source-branch", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--loader", action="append", required=True, choices=sorted(vt.LOADERS))
    parser.add_argument("--existing", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, help="Create a new local file exclusively; never overwrite")
    parser.add_argument("--properties", type=pathlib.Path, help="Validate selected MC/Java against source checkout")
    parser.add_argument("--curseforge-project")
    parser.add_argument("--curseforge-required", action="store_true")
    args = parser.parse_args(argv)
    try:
        kwargs = dict(version=args.version, minecraft=args.minecraft, channel=args.channel,
                      source_branch=args.source_branch, source_sha=args.source_sha, loaders=args.loader,
                      curseforge_project=args.curseforge_project, curseforge_required=args.curseforge_required)
        require((args.mode == "reuse") == (args.existing is not None), "--existing is required only for reuse")
        plan = (reuse_plan(load_plan(args.existing), **kwargs) if args.mode == "reuse"
                else build_plan(vt.load_catalog(args.catalog), **kwargs))
        if args.properties:
            rows = [dict(row, minecraft=plan["minecraft"], source_branch=plan["source_branch"])
                    for row in plan["targets"]]
            vt.validate_properties(rows, vt.load_properties(args.properties), source_branch=plan["source_branch"])
        output = canonical_json(plan)
        if args.output:
            with args.output.open("x", encoding="utf-8") as stream:
                stream.write(output)
        sys.stdout.write(output)
    except (PlanError, vt.ValidationError, OSError, json.JSONDecodeError) as exc:
        print(f"release plan failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
