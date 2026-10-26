"""Automatic release version assignment contracts."""
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import release_version as rv


class ReleaseVersionTest(unittest.TestCase):
    def resolve(self, **changes):
        request = dict(base_version="0.3.3", minecraft="26.2", channel="beta", tags=[])
        return rv.resolve_release_version(**(request | changes))

    def test_first_prerelease_number_is_one(self):
        self.assertEqual(self.resolve(channel="alpha"), "0.3.3-alpha.1")
        self.assertEqual(self.resolve(), "0.3.3-beta.1")

    def test_next_number_follows_maximum_matching_tag(self):
        tags = [
            "v0.3.3-beta.2+mc26.2",
            "v0.3.3-beta.9+mc26.1",
            "v0.3.3-beta.11+mc26.2-fabric",
            "v0.3.3-beta.01+mc26.2",
            "v0.3.3-alpha.20+mc26.2",
            "v0.3.4-beta.30+mc26.2",
        ]
        self.assertEqual(self.resolve(tags=tags), "0.3.3-beta.3")
        self.assertEqual(self.resolve(tags=["v0.3.3-beta.2+mc26.2", "v0.3.3-beta.7+mc26.2"]),
                         "0.3.3-beta.8")

    def test_stable_uses_base_version_and_rejects_existing_tag(self):
        self.assertEqual(self.resolve(channel="release"), "0.3.3")
        with self.assertRaises(rv.VersionError):
            self.resolve(channel="release", tags=["v0.3.3+mc26.2"])

    def test_invalid_release_identity_is_rejected(self):
        requests = [
            dict(base_version="01.3.3"),
            dict(base_version="0.3.3-beta.1"),
            dict(minecraft="026.2"),
            dict(channel="stable"),
        ]
        for changes in requests:
            with self.subTest(changes=changes), self.assertRaises(rv.VersionError):
                self.resolve(**changes)


if __name__ == "__main__":
    unittest.main()
