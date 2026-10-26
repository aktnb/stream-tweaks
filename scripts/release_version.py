#!/usr/bin/env python3
"""Resolve a release version from the base version, channel, MC, and existing Git tags."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

try:
    from . import validate_targets as vt
except ImportError:
    import validate_targets as vt

VERSION_BASE_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
CHANNELS = ("alpha", "beta", "release")


class VersionError(ValueError):
    """The requested release identity is invalid or already reserved."""


def resolve_release_version(*, base_version: str, minecraft: str, channel: str, tags: list[str]) -> str:
    """Return the stable base version or the next prerelease number for this MC and channel."""
    if not isinstance(base_version, str) or re.fullmatch(VERSION_BASE_PATTERN, base_version) is None:
        raise VersionError("mod_version must be a pure SemVer base version")
    if not isinstance(minecraft, str) or re.fullmatch(vt.MC_PATTERN, minecraft) is None:
        raise VersionError("minecraft_version is invalid")
    if channel not in CHANNELS:
        raise VersionError("unknown release channel")
    if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
        raise VersionError("tags must be a list of tag names")

    if channel == "release":
        stable_tag = f"v{base_version}+mc{minecraft}"
        if stable_tag in tags:
            raise VersionError(f"release tag already exists: {stable_tag}")
        return base_version

    pattern = re.compile(rf"^v{re.escape(base_version)}-{channel}\.([1-9][0-9]*)\+mc{re.escape(minecraft)}$")
    highest = max(
        (int(match.group(1)) for tag in tags if (match := pattern.fullmatch(tag))),
        default=0,
    )
    return f"{base_version}-{channel}.{highest + 1}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--channel", required=True, choices=CHANNELS)
    args = parser.parse_args(argv)

    try:
        tags = subprocess.run(["git", "tag", "--list"], check=True, text=True, capture_output=True).stdout.splitlines()
        version = resolve_release_version(
            base_version=args.base,
            minecraft=args.minecraft,
            channel=args.channel,
            tags=tags,
        )
    except (VersionError, OSError, subprocess.SubprocessError) as exc:
        print(f"release version failed: {exc}", file=sys.stderr)
        return 1

    print(version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
