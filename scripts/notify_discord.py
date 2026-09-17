#!/usr/bin/env python3
"""Release ワークフローから Discord へ通知を送るスクリプト。

Webhook URL は引数ではなく環境変数 ``DISCORD_WEBHOOK_URL`` から読む（プロセス一覧や
ログに残さないため）。失敗通知には実行ログやエラー詳細を含めず、Actions の実行 URL への
リンクだけを載せる。
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

MODRINTH_PROJECT_ID = "jWHdryID"
MODRINTH_VERSIONS_URL = f"https://modrinth.com/mod/{MODRINTH_PROJECT_ID}/versions"

#: Discord の embed description 上限は 4096 文字。余白を見て少し手前で切る。
DESCRIPTION_LIMIT = 3900
#: Discord の message content 上限（本スクリプトでは未使用だが切り詰めの基準として持つ）。
CONTENT_LIMIT = 2000
#: HTTP エラー時に出力するレスポンスボディの最大長。
ERROR_BODY_LIMIT = 500

REQUEST_TIMEOUT_SECONDS = 30
USER_AGENT = "StreamTweaksReleaseBot/1.0 (+https://github.com/aktnb/stream-tweaks-mod)"

#: channel -> (タイトル接頭辞, 色, 安定性ラベル)
CHANNEL_STYLES = {
    "alpha": ("🔬 Alpha", 0x9B59B6, "Pre-release"),
    "beta": ("🧪 Beta", 0x3498DB, "Pre-release"),
    "release": ("🎉 Release", 0x2ECC71, "Stable"),
}
FALLBACK_STYLE = ("📦 Build", 0xE74C3C, "Build")

FAILURE_COLOR = 0xE74C3C

LOADER_LABELS = {
    "both": "Fabric & NeoForge",
    "fabric": "Fabric",
    "neoforge": "NeoForge",
    "fabric,neoforge": "Fabric & NeoForge",
}

UNKNOWN = "unknown"


def truncate(text: str, limit: int) -> str:
    """limit を超える場合に末尾を '...' に置き換えて切り詰める。"""
    if len(text) <= limit:
        return text
    return text[: max(limit - 3, 0)] + "..."


def loader_label(loaders: str) -> str:
    key = (loaders or "").strip().lower()
    return LOADER_LABELS.get(key, key or UNKNOWN)


def read_notes(path: str | None) -> str:
    """リリースノートを読む。存在しない / 読めない場合は空文字列を返す。"""
    if not path:
        return ""
    try:
        return pathlib.Path(path).read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def avatar_url() -> str | None:
    """リポジトリの icon.png を Bot アバターに使う。環境変数が無ければ None。"""
    repository = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("GITHUB_SHA")
    if not repository or not sha:
        return None
    return f"https://raw.githubusercontent.com/{repository}/{sha}/src/main/resources/assets/stream-tweaks/icon.png"


def build_success_payload(args: argparse.Namespace) -> dict:
    title_prefix, color, stability = CHANNEL_STYLES.get(args.channel, FALLBACK_STYLE)
    loaders = loader_label(args.loaders)
    tag = args.tag or UNKNOWN
    mc = args.mc or UNKNOWN

    lead = f"Released for Minecraft **{mc}** ({loaders})."
    notes = read_notes(args.notes_file)
    description = f"{lead}\n\n{notes}" if notes else lead

    return {
        "embeds": [
            {
                "title": f"{title_prefix}: {tag}",
                "url": MODRINTH_VERSIONS_URL,
                "description": truncate(description, DESCRIPTION_LIMIT),
                "color": color,
                "fields": [
                    {"name": "Channel", "value": args.channel or UNKNOWN, "inline": True},
                    {"name": "Stability", "value": stability, "inline": True},
                    {"name": "Minecraft", "value": mc, "inline": True},
                    {"name": "Loader", "value": loaders, "inline": True},
                    {"name": "Modrinth", "value": MODRINTH_VERSIONS_URL},
                ],
                "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            }
        ],
        "components": [
            {
                "type": 1,
                "components": [
                    {"type": 2, "style": 5, "label": "View on Modrinth", "url": MODRINTH_VERSIONS_URL},
                ],
            }
        ],
    }


def build_failure_payload(args: argparse.Namespace) -> dict:
    """失敗通知。ログやエラー詳細は載せず、実行 URL へのリンクだけを付ける。"""
    tag = args.tag or UNKNOWN
    components = []
    if args.run_url:
        components = [
            {
                "type": 1,
                "components": [
                    {"type": 2, "style": 5, "label": "View workflow run", "url": args.run_url},
                ],
            }
        ]

    return {
        "embeds": [
            {
                "title": f"❌ Release failed: {tag}",
                "description": "リリースワークフローが失敗しました。詳細は Actions の実行ログを確認してください。",
                "color": FAILURE_COLOR,
                "fields": [
                    {"name": "Channel", "value": args.channel or UNKNOWN, "inline": True},
                    {"name": "Minecraft", "value": args.mc or UNKNOWN, "inline": True},
                    {"name": "Loader", "value": loader_label(args.loaders), "inline": True},
                ],
                "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            }
        ],
        "components": components,
    }


def build_payload(args: argparse.Namespace) -> dict:
    payload = build_success_payload(args) if args.status == "success" else build_failure_payload(args)
    payload["username"] = "StreamTweaks Release Bot"
    avatar = avatar_url()
    if avatar:
        payload["avatar_url"] = avatar
    return payload


def post(payload: dict) -> None:
    try:
        webhook_url = os.environ["DISCORD_WEBHOOK_URL"]
    except KeyError:
        print("DISCORD_WEBHOOK_URL が設定されていません", file=sys.stderr)
        raise SystemExit(1)

    request = urllib.request.Request(
        webhook_url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": USER_AGENT,
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            print(f"Discord への通知に成功しました (HTTP {response.status})")
    except urllib.error.HTTPError as error:
        # error 自体（str(error) や error.url）は webhook URL を含みうるので出力しない
        body = ""
        try:
            body = error.read().decode("utf-8", errors="replace")
        except OSError:
            body = ""
        print(f"Discord への通知に失敗しました (HTTP {error.code}): {truncate(body, ERROR_BODY_LIMIT)}", file=sys.stderr)
        raise SystemExit(1)
    except urllib.error.URLError as error:
        print(f"Discord への接続に失敗しました: {error.reason}", file=sys.stderr)
        raise SystemExit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--status", choices=["success", "failure"], required=True, help="リリースの結果")
    parser.add_argument("--channel", default="", help="リリースチャンネル (alpha / beta / release)")
    parser.add_argument("--tag", default="", help="リリースタグ")
    parser.add_argument("--mc", default="", help="対象 Minecraft バージョン")
    parser.add_argument("--loaders", default="", help="公開したローダー (both / fabric / neoforge)")
    parser.add_argument("--run-url", default="", help="GitHub Actions の実行 URL")
    parser.add_argument("--notes-file", default="", help="リリースノートのファイルパス（成功時のみ使用）")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="送信せず payload を標準出力に表示する（動作確認用）",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    payload = build_payload(args)

    if args.dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    post(payload)


if __name__ == "__main__":
    main()
