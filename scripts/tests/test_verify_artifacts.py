"""Distribution-content fixtures; no JVM or network access required."""
from __future__ import annotations

import hashlib
import io
import json
import pathlib
import string
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))
import verify_artifacts as va
from validate_targets import ValidationError, load_properties


def jar_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as jar:
        for name, value in entries.items():
            jar.writestr(name, value)
    return stream.getvalue()


def annotated_client_class(java, mod_id, dist="CLIENT"):
    constants = ["RuntimeVisibleAnnotations", "Lnet/neoforged/fml/common/Mod;", "value", mod_id,
                 "dist", "Lnet/neoforged/api/distmarker/Dist;", dist, "CLIENT"]
    pool = b"".join(b"\x01" + struct.pack(">H", len(value.encode())) + value.encode() for value in constants)
    annotation = struct.pack(">HHHHcHHcHcHH", 1, 2, 2, 3, b"s", 4, 5, b"[", 1, b"e", 6, 7)
    return (b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, java + 44, len(constants) + 1) + pool
            + struct.pack(">HHHHHHH", 0, 0, 0, 0, 0, 0, 1)
            + struct.pack(">HI", 1, len(annotation)) + annotation)


class ArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = pathlib.Path(self.temp.name)
        self.props = load_properties(ROOT / "gradle.properties")

    def entries(self, loader, props=None):
        props = props or self.props
        substitutions = props | {"version": props["mod_version"]}
        classes = b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, int(props["java_version"]) + 44)
        mixin = string.Template((ROOT / "src/main/resources" / va.MIXIN_FILE).read_text()).substitute(substitutions)
        entries = {va.MIXIN_FILE: mixin}
        for name in va.MIXINS:
            entries[f"{va.PACKAGE.replace('.', '/')}/{name}.class"] = classes
        if loader == "fabric":
            metadata = json.loads(string.Template((ROOT / "fabric/src/main/resources/fabric.mod.json").read_text())
                                  .substitute(substitutions))
            metadata["jars"] = []
            for module, class_name in [("core", "com/electronwill/nightconfig/core/Config.class"),
                                       ("toml", "com/electronwill/nightconfig/toml/TomlParser.class")]:
                nested = f"META-INF/jars/{module}-{props['nightconfig_version']}.jar"
                metadata["jars"].append({"file": nested})
                entries[nested] = jar_bytes({class_name: classes})
            entries["fabric.mod.json"] = json.dumps(metadata)
            for name in ["StreamTweaks", "StreamTweaksClient", "ModMenuIntegration"]:
                entries[f"{va.OWN_PREFIX}mod/{name}.class"] = classes
        else:
            entries["META-INF/neoforge.mods.toml"] = string.Template(
                (ROOT / "neoforge/src/main/resources/META-INF/neoforge.mods.toml").read_text()).substitute(substitutions)
            entries[f"{va.OWN_PREFIX}mod/neoforge/NeoForgeStreamTweaksClient.class"] = annotated_client_class(
                int(props["java_version"]), props["neoforge_mod_id"])
        return entries

    def write_jar(self, loader, entries=None, props=None, filename=None):
        props = props or self.props
        path = self.directory / (filename or va.expected_filename(loader, props))
        path.write_bytes(jar_bytes(entries if entries is not None else self.entries(loader, props)))
        return path

    def test_both_loaders_validate_metadata_and_hashes(self):
        for loader in ["fabric", "neoforge"]:
            with self.subTest(loader=loader):
                path = self.write_jar(loader)
                result = va.verify_artifact(path, loader, self.props)
                self.assertEqual(result["filename"], path.name)
                self.assertEqual(result["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
                self.assertEqual(result["sha512"], hashlib.sha512(path.read_bytes()).hexdigest())

    def test_changed_properties_generate_consistent_metadata(self):
        props = self.props | {"minecraft_version": "1.21.8", "java_version": "21", "mod_version": "0.4.0-beta.2",
                              "fabric_loader_version": "0.18.0", "neoforge_version": "21.8.1"}
        for loader in ["fabric", "neoforge"]:
            with self.subTest(loader=loader):
                path = self.write_jar(loader, props=props)
                self.assertEqual(va.verify_artifact(path, loader, props)["java_version"], 21)

    def test_sources_dev_and_wrong_filename_rejected(self):
        for suffix in ["-sources.jar", "-dev.jar", "-wrong.jar"]:
            path = self.write_jar("fabric", filename=va.expected_filename("fabric", self.props)[:-4] + suffix)
            with self.subTest(suffix=suffix), self.assertRaises(ValidationError):
                va.verify_artifact(path, "fabric", self.props)

    def test_metadata_version_mc_loader_java_mismatch_rejected(self):
        for loader in ["fabric", "neoforge"]:
            for property_name in ["mod_version", "minecraft_version", "java_version",
                                  "fabric_loader_version" if loader == "fabric" else "neoforge_version"]:
                with self.subTest(loader=loader, property_name=property_name):
                    wrong = self.props | {property_name: "99"}
                    path = self.write_jar(loader, entries=self.entries(loader, wrong))
                    with self.assertRaises(ValidationError):
                        va.verify_artifact(path, loader, self.props)

    def test_mixin_java_registration_and_classfile_mismatch_rejected(self):
        for change in ["java", "client", "required", "missing_class", "classfile", "preview"]:
            entries = self.entries("fabric")
            mixins = json.loads(entries[va.MIXIN_FILE])
            if change == "java":
                mixins["compatibilityLevel"] = "JAVA_21"
            elif change == "client":
                mixins["client"] = []
            elif change == "required":
                mixins["required"] = False
            elif change == "missing_class":
                del entries[f"{va.PACKAGE.replace('.', '/')}/ChatComponentAccessor.class"]
            else:
                entries[f"{va.OWN_PREFIX}mod/StreamTweaks.class"] = b"\xca\xfe\xba\xbe" + struct.pack(
                    ">HH", 65535 if change == "preview" else 0, 69 if change == "preview" else 65)
            entries[va.MIXIN_FILE] = json.dumps(mixins)
            with self.subTest(change=change), self.assertRaises(ValidationError):
                va.verify_artifact(self.write_jar("fabric", entries), "fabric", self.props)

    def test_fabric_client_only_and_nested_dependency_required(self):
        for change in ["environment", "jars", "missing_nested", "corrupt_nested"]:
            entries = self.entries("fabric")
            metadata = json.loads(entries["fabric.mod.json"])
            if change == "environment":
                metadata["environment"] = "*"
            elif change == "jars":
                metadata["jars"] = []
            elif change == "missing_nested":
                del entries[metadata["jars"][0]["file"]]
            else:
                entries[metadata["jars"][0]["file"]] = jar_bytes({"wrong.class": b""})
            entries["fabric.mod.json"] = json.dumps(metadata)
            with self.subTest(change=change), self.assertRaises(ValidationError):
                va.verify_artifact(self.write_jar("fabric", entries), "fabric", self.props)

    def test_neoforge_side_repository_annotation_mismatch_rejected(self):
        for change in ["side", "repo", "annotation"]:
            entries = self.entries("neoforge")
            if change == "annotation":
                key = f"{va.OWN_PREFIX}mod/neoforge/NeoForgeStreamTweaksClient.class"
                entries[key] = annotated_client_class(int(self.props["java_version"]),
                                                      self.props["neoforge_mod_id"], dist="DEDICATED_SERVER")
            else:
                key = "META-INF/neoforge.mods.toml"
                entries[key] = entries[key].replace('side = "CLIENT"', 'side = "BOTH"') if change == "side" else (
                    entries[key].replace("aktnb/stream-tweaks/issues", "aktnb/stream-tweaks-mod-0/issues"))
            with self.subTest(change=change), self.assertRaises(ValidationError):
                va.verify_artifact(self.write_jar("neoforge", entries), "neoforge", self.props)

    def test_source_jar_renamed_as_normal_and_missing_mixins_rejected(self):
        for entries in [{"Example.java": "class Example {}"}, {"fabric.mod.json": "{}"}]:
            with self.assertRaises(ValidationError):
                va.verify_artifact(self.write_jar("fabric", entries), "fabric", self.props)

    def test_select_never_falls_back_to_sources(self):
        self.write_jar("fabric", filename="source-sources.jar")
        with self.assertRaises(ValidationError):
            va.select_artifact(self.directory, "fabric", self.props)
        expected = self.write_jar("fabric")
        self.assertEqual(va.select_artifact(self.directory, "fabric", self.props), expected)

    def test_library_classes_may_target_older_java_but_not_newer(self):
        for major, valid in [(52, True), (70, False)]:
            entries = self.entries("fabric")
            entries["external/Library.class"] = b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, major)
            path = self.write_jar("fabric", entries)
            if valid:
                va.verify_artifact(path, "fabric", self.props)
            else:
                with self.assertRaises(ValidationError):
                    va.verify_artifact(path, "fabric", self.props)

    def test_unsafe_archive_names_rejected(self):
        for name in ["../outside", "/absolute"]:
            entries = self.entries("fabric")
            entries[name] = "untrusted"
            with self.subTest(name=name), self.assertRaises(ValidationError):
                va.verify_artifact(self.write_jar("fabric", entries), "fabric", self.props)

    def test_duplicate_zip_entry_rejected(self):
        import warnings
        path = self.write_jar("fabric")
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "a") as jar:
                jar.writestr("fabric.mod.json", "{}")
        with self.assertRaises(ValidationError):
            va.verify_artifact(path, "fabric", self.props)

    def test_cli_verifies_and_emits_json(self):
        self.write_jar("fabric")
        result = subprocess.run([sys.executable, str(SCRIPTS / "verify_artifacts.py"), "--loader", "fabric",
                                 "--directory", str(self.directory), "--properties", str(ROOT / "gradle.properties")],
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["loader"], "fabric")


if __name__ == "__main__":
    unittest.main()
