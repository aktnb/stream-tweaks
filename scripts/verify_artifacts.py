#!/usr/bin/env python3
"""Validate normal distribution JARs against one checkout's Gradle properties.

Python 3.11+ (tomllib). No extraction, class loading, network access, or mutation.
The returned size/hashes are calculated only after metadata/content validation.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import struct
import sys
import tomllib
import zipfile

try:
    from .validate_targets import ValidationError, load_properties
except ImportError:
    from validate_targets import ValidationError, load_properties

MIXIN_FILE = "stream-tweaks.mixins.json"
PACKAGE = "org.etwas.streamtweaks.presentation.mixin"
MIXINS = {"ChatComponentAccessor", "FontManagerAccessor", "FontStorageAccessor",
          "FontStorageMixin", "MutableComponentMixin"}
OWN_PREFIX = "org/etwas/streamtweaks/"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def expected_filename(loader: str, properties: dict[str, str]) -> str:
    require(loader in {"fabric", "neoforge"}, "unknown loader")
    return (f"{properties['archives_base_name']}-{loader}-mc{properties['minecraft_version']}-"
            f"{properties['mod_version']}.jar")


def select_artifact(directory: str | pathlib.Path, loader: str, properties: dict[str, str]) -> pathlib.Path:
    """Select exactly the expected normal JAR; sources/dev JARs are never fallback inputs."""
    path = pathlib.Path(directory) / expected_filename(loader, properties)
    require(path.is_file(), f"missing normal JAR: {path.name}")
    return path


def _json(archive: zipfile.ZipFile, name: str) -> dict:
    value = json.loads(archive.read(name))
    require(isinstance(value, dict), f"{name}: expected object")
    return value


def _check_classes(archive: zipfile.ZipFile, java: int) -> None:
    classes = [name for name in archive.namelist() if name.startswith(OWN_PREFIX) and name.endswith(".class")]
    require(bool(classes), "no compiled mod classes (source JAR is not distributable)")
    for name in [name for name in archive.namelist() if name.endswith(".class")]:
        header = archive.read(name)[:8]
        require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe", f"invalid classfile: {name}")
        minor, major = struct.unpack(">HH", header[4:])
        require(minor == 0 and major <= java + 44, f"classfile Java upper bound/preview mismatch: {name}")
        if name.startswith(OWN_PREFIX):
            require(major == java + 44, f"mod classfile Java mismatch: {name}")


def _check_mixins(archive: zipfile.ZipFile, java: int) -> None:
    mixins = _json(archive, MIXIN_FILE)
    require(mixins.get("compatibilityLevel") == f"JAVA_{java}", "Mixin Java compatibility mismatch")
    require(mixins.get("required") is True and mixins.get("package") == PACKAGE, "Mixin declaration changed")
    clients = mixins.get("client")
    require(isinstance(clients, list) and len(clients) == len(MIXINS)
            and all(isinstance(item, str) for item in clients) and set(clients) == MIXINS,
            "client Mixin registration mismatch")
    require(mixins.get("mixins") == [], "unexpected common/server Mixins")
    for name in MIXINS:
        require(f"{PACKAGE.replace('.', '/')}/{name}.class" in archive.namelist(), f"missing Mixin class: {name}")


def _fabric(archive: zipfile.ZipFile, props: dict[str, str]) -> None:
    metadata = _json(archive, "fabric.mod.json")
    require(metadata.get("schemaVersion") == 1 and metadata.get("id") == props["mod_id"], "Fabric Mod ID mismatch")
    require(metadata.get("version") == props["mod_version"], "Fabric version mismatch")
    require(metadata.get("environment") == "client", "Fabric must remain client-only")
    depends = metadata.get("depends", {})
    require(depends.get("minecraft") == props["minecraft_version"], "Fabric Minecraft mismatch")
    require(depends.get("fabricloader") == f">={props['fabric_loader_version']}", "Fabric loader mismatch")
    require(depends.get("java") == f">={props['java_version']}", "Fabric Java requirement mismatch")
    require(depends.get("fabric-api") == "*", "Fabric API requirement missing")
    require(metadata.get("mixins") == [MIXIN_FILE], "Fabric Mixin registration mismatch")
    entries = metadata.get("entrypoints", {})
    for key, name in {"main": "StreamTweaks", "client": "StreamTweaksClient", "modmenu": "ModMenuIntegration"}.items():
        full_name = f"org.etwas.streamtweaks.mod.{name}"
        require(entries.get(key) == [full_name], f"Fabric {key} entrypoint mismatch")
        require(f"{full_name.replace('.', '/')}.class" in archive.namelist(), f"missing Fabric {key} class")
    nested = metadata.get("jars", [])
    require(isinstance(nested, list) and all(isinstance(item, dict) and isinstance(item.get("file"), str)
                                          for item in nested), "invalid nested JAR registration")
    for module, class_name in [("core", "com/electronwill/nightconfig/core/Config.class"),
                               ("toml", "com/electronwill/nightconfig/toml/TomlParser.class")]:
        suffix = f"/{module}-{props['nightconfig_version']}.jar"
        matches = [item["file"] for item in nested if item["file"].endswith(suffix)]
        require(len(matches) == 1, f"Fabric distribution must nest NightConfig {module}")
        with zipfile.ZipFile(io.BytesIO(archive.read(matches[0]))) as library:
            require(class_name in library.namelist(), f"NightConfig {module} class missing")
            for name in library.namelist():
                if name.endswith(".class"):
                    header = library.read(name)[:8]
                    require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe", "invalid nested classfile")
                    minor, major = struct.unpack(">HH", header[4:])
                    require(minor == 0 and major <= int(props["java_version"]) + 44,
                            f"nested dependency Java mismatch: {name}")


def _class_annotations(raw: bytes) -> dict:
    """Read class-level annotation values, without interpreting/loading JVM code."""
    stream = io.BytesIO(raw)

    def number(size=2):
        value = stream.read(size)
        require(len(value) == size, "truncated annotation classfile")
        return int.from_bytes(value, "big")

    require(number(4) == 0xCAFEBABE, "invalid annotation classfile")
    stream.read(4)  # minor / major
    pool, count, index = {}, number(), 1
    while index < count:
        tag = number(1)
        if tag == 1:
            pool[index] = stream.read(number()).decode("utf-8", errors="replace")
        else:
            sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
                     12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
            require(tag in sizes, "unknown class constant tag")
            stream.read(sizes[tag])
            if tag in {5, 6}:
                index += 1
        index += 1
    stream.read(6)  # access, this, super
    stream.read(2 * number())  # interfaces
    for _ in range(2):  # fields, methods and their attributes
        for _ in range(number()):
            stream.read(6)
            for _ in range(number()):
                number()  # attribute name
                stream.read(number(4))

    def annotation():
        kind = pool[number()]
        return kind, {pool[number()]: element() for _ in range(number())}

    def element():
        tag = chr(number(1))
        if tag == "e":
            return (pool[number()], pool[number()])
        if tag == "@":
            return annotation()
        if tag == "[":
            return [element() for _ in range(number())]
        require(tag in "BCDFIJSZsc", "invalid annotation element tag")
        return pool.get(number())

    annotations = {}
    for _ in range(number()):
        name, size = pool[number()], number(4)
        end = stream.tell() + size
        require(end <= len(raw), "truncated class annotation")
        if name in {"RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"}:
            for _ in range(number()):
                kind, values = annotation()
                require(kind not in annotations, "duplicate class annotation")
                annotations[kind] = values
            require(stream.tell() == end, "class annotation size mismatch")
        stream.seek(end)
    return annotations


def _neoforge(archive: zipfile.ZipFile, props: dict[str, str]) -> None:
    metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
    require(metadata.get("modLoader") == "javafml", "NeoForge modLoader mismatch")
    require(metadata.get("issueTrackerURL") == "https://github.com/aktnb/stream-tweaks/issues", "incorrect repository URL")
    mods = metadata.get("mods", [])
    require(len(mods) == 1 and mods[0].get("modId") == props["neoforge_mod_id"], "NeoForge Mod ID mismatch")
    require(mods[0].get("version") == props["mod_version"], "NeoForge version mismatch")
    require(mods[0].get("displayTest") == "IGNORE_SERVER_VERSION", "NeoForge client-only display test changed")
    require(metadata.get("mixins") == [{"config": MIXIN_FILE}], "NeoForge Mixin registration mismatch")
    features = metadata.get("features", {}).get(props["neoforge_mod_id"], {})
    require(features.get("javaVersion") == f"[{props['java_version']},)", "NeoForge Java requirement mismatch")
    dependencies = metadata.get("dependencies", {}).get(props["neoforge_mod_id"], [])
    require(len(dependencies) == 2, "NeoForge dependency declarations mismatch")
    for mod_id, version in [("minecraft", f"[{props['minecraft_version']}]"),
                            ("neoforge", f"[{props['neoforge_version']},)")]:
        rows = [row for row in dependencies if row.get("modId") == mod_id]
        require(len(rows) == 1 and rows[0].get("versionRange") == version, f"NeoForge {mod_id} mismatch")
        require(rows[0].get("side") == "CLIENT" and rows[0].get("type") == "required",
                f"NeoForge {mod_id} must remain client-only required dependency")
    entry = f"{OWN_PREFIX}mod/neoforge/NeoForgeStreamTweaksClient.class"
    require(entry in archive.namelist(), "missing NeoForge client entrypoint")
    mod = _class_annotations(archive.read(entry)).get("Lnet/neoforged/fml/common/Mod;", {})
    require(mod.get("value") == props["neoforge_mod_id"]
            and mod.get("dist") == [("Lnet/neoforged/api/distmarker/Dist;", "CLIENT")],
            "NeoForge @Mod must declare only Dist.CLIENT")


def verify_artifact(path: str | pathlib.Path, loader: str, properties: dict[str, str]) -> dict:
    """Validate contents, then return filename/size/SHA256/SHA512 and target metadata.

    Release callers pass a properties copy whose mod_version is the fixed plan's
    pure SemVer. This function never determines or increments release versions.
    """
    path = pathlib.Path(path)
    require(path.name == expected_filename(loader, properties), "not the expected normal JAR filename")
    java = int(properties["java_version"])
    raw = path.read_bytes()
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "duplicate ZIP entries")
            require(not any(name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts for name in names),
                    "unsafe ZIP entry")
            require(not any(name.endswith(".java") for name in names), "source files in distribution JAR")
            _check_classes(archive, java)
            _check_mixins(archive, java)
            (_fabric if loader == "fabric" else _neoforge)(archive, properties)
    except (zipfile.BadZipFile, KeyError, json.JSONDecodeError, tomllib.TOMLDecodeError, UnicodeDecodeError,
            TypeError, AttributeError) as exc:
        raise ValidationError(f"invalid JAR contents: {exc}") from exc
    return {"filename": path.name, "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
            "sha512": hashlib.sha512(raw).hexdigest(), "loader": loader,
            "minecraft": properties["minecraft_version"], "mod_version": properties["mod_version"],
            "java_version": java}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=pathlib.Path, default=pathlib.Path("gradle.properties"))
    parser.add_argument("--loader", required=True, choices=["fabric", "neoforge"])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--jar", type=pathlib.Path)
    group.add_argument("--directory", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        props = load_properties(args.properties)
        path = args.jar if args.jar else select_artifact(args.directory, args.loader, props)
        print(json.dumps(verify_artifact(path, args.loader, props), sort_keys=True, indent=2))
    except (ValidationError, OSError, KeyError, ValueError) as exc:
        print(f"artifact verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
