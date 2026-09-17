"""Tests for scripts/notify_discord.py (standard library unittest only)."""
from __future__ import annotations

import os
import pathlib
import sys
import unittest
from unittest import mock

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import notify_discord as nd  # noqa: E402


class _FakeResponse:
    status = 204

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False


class PostTest(unittest.TestCase):
    def test_post_sets_discord_safe_headers(self):
        with mock.patch.dict(os.environ, {"DISCORD_WEBHOOK_URL": "https://discord.example/webhook"}, clear=True):
            with mock.patch.object(nd.urllib.request, "urlopen", return_value=_FakeResponse()) as urlopen:
                with mock.patch("sys.stdout"):
                    nd.post({"embeds": [{"title": "ok"}]})

        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "https://discord.example/webhook")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("Accept"), "application/json")
        self.assertEqual(request.get_header("Content-type"), "application/json")
        self.assertEqual(request.get_header("User-agent"), nd.USER_AGENT)


if __name__ == "__main__":
    unittest.main()
