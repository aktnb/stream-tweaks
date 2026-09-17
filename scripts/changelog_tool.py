#!/usr/bin/env python3
"""Changelog utility for release automation.

- alpha/beta: extract Unreleased content for release notes. With --since-ref,
  only the lines added to Unreleased since that git ref are emitted (diff against
  the previous alpha/beta/release tag).
- release: move Unreleased content into a versioned section and emit the notes.

Bullets may carry a loader tag (``- [fabric] ...`` / ``- [neoforge] ...`` /
``- [fabric,neoforge] ...``); untagged bullets are common to every loader.
``--loaders`` narrows the *emitted notes* to one loader's bullets plus the common
ones. CHANGELOG.md itself is always written back unfiltered, tags included.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import List, Tuple

SECTION_HEADER_RE = re.compile(
    r"^## \[(?P<name>[^\]]+)\](?: - [^\n]+)?\s*$",
    re.MULTILINE,
)

SUBSECTION_HEADER_RE = re.compile(
    r"^### (?P<name>.+?)\s*$",
    re.MULTILINE,
)

UNRELEASED_BODY_TEMPLATE = "\n\n### Added\n\n### Changed\n\n### Fixed\n"

#: CHANGELOG の箇条書き先頭に付けられるローダータグとして認識する名前。
KNOWN_LOADERS: frozenset[str] = frozenset({"fabric", "neoforge"})

#: '- [fabric] ...' のようなローダータグ付き箇条書きにマッチする。
#: '] ' の直後が '(' の場合は Markdown のリンク記法 '- [text](url)' なのでマッチさせない。
LOADER_TAG_RE = re.compile(r"^(?P<indent>\s*)-\s+\[(?P<tag>[A-Za-z0-9_,\- ]+)\](?!\()\s*(?P<rest>.*)$")

#: 出力するノートが空になったときの文言。
NO_CHANGES_NOTE = "変更点はありません。"


def parse_sections(text: str) -> Tuple[str, List[Tuple[str, str, str]]]:
    """Return the prefix (text before first section) and a list of sections."""
    matches = list(SECTION_HEADER_RE.finditer(text))
    if not matches:
        raise ValueError("CHANGELOG.md にセクション見出し (## [...]) が見つかりません")

    prefix = text[: matches[0].start()]
    sections = []
    for idx, match in enumerate(matches):
        header_line = match.group(0).rstrip("\n")
        name = match.group("name")
        start = match.end()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(text)
        body = text[start:end]
        sections.append((name, header_line, body))
    return prefix, sections


@dataclass(frozen=True)
class Bullet:
    """箇条書き1件。インデントされた継続行・入れ子bulletを含む論理ブロック。"""

    loaders: frozenset[str]  # 空集合 = 共通（全ローダー対象）
    lines: tuple[str, ...]  # 生の行（先頭行 + 継続行）。先頭行はタグ付きのまま


def _extract_loaders(line: str) -> frozenset[str] | None:
    """行頭のローダータグを解釈する。

    有効な既知ローダーの組み合わせなら frozenset を返し、タグが無い / 未知のトークンを
    含む場合は None を返す（= 通常の本文として扱うべき、というシグナル）。
    """
    match = LOADER_TAG_RE.match(line)
    if not match:
        return None
    tokens = {token.strip().lower() for token in match.group("tag").split(",")}
    if not tokens or "" in tokens:
        return None
    if not tokens <= KNOWN_LOADERS:
        return None
    return frozenset(tokens)


def parse_bullet_blocks(content: str) -> List[Bullet]:
    """サブセクション本文（例: '### Fixed' の直後から次の見出しまで）を箇条書きブロック単位に分割する。

    - インデント0の '- ' で始まる行を新ブロックの開始とみなす
    - それ以外の行（空行含む・インデントされた '- ' や継続行）は直前ブロックに属させる
      （ただし先頭に何もブロックがない場合は無視する）
    - 各ブロックの末尾の空行は取り除く
    - 先頭行が LOADER_TAG_RE にマッチし、かつタグの全トークンが KNOWN_LOADERS の部分集合
      である場合のみ loaders を設定する。それ以外は通常の本文として扱い loaders=frozenset()
      とする（後方互換 & フェイルセーフ: 「共通」扱いなので配信時に必ず含まれる）
    """
    blocks: List[List[str]] = []
    for line in content.splitlines():
        if line.startswith("- "):
            blocks.append([line])
        elif blocks:
            blocks[-1].append(line)
    bullets: List[Bullet] = []
    for lines in blocks:
        while lines and not lines[-1].strip():
            lines.pop()
        loaders = _extract_loaders(lines[0])
        bullets.append(Bullet(loaders=loaders or frozenset(), lines=tuple(lines)))
    return bullets


def strip_loader_tag(line: str) -> str:
    """'- [fabric] foo' を '- foo' にする。タグが無い / 未知タグの場合は原文を返す。"""
    match = LOADER_TAG_RE.match(line)
    if match is None or _extract_loaders(line) is None:
        return line
    # rest が空（タグのみの行）でも行末に空白を残さない。
    return f"{match.group('indent')}- {match.group('rest')}".rstrip()


def parse_subsections(body: str) -> List[Tuple[str, List[Bullet]]]:
    """Parse a section body (e.g. Unreleased) into (subsection name, bullets)."""
    matches = list(SUBSECTION_HEADER_RE.finditer(body))
    result = []
    for idx, match in enumerate(matches):
        name = match.group("name")
        start = match.end()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(body)
        result.append((name, parse_bullet_blocks(body[start:end])))
    return result


def _render_bullets(bullets: List[Bullet], *, strip_tags: bool) -> List[str]:
    """bullet 群を出力行へ展開する。strip_tags=True なら各ブロック先頭行のタグを除去する。"""
    lines: List[str] = []
    for bullet in bullets:
        block = list(bullet.lines)
        if strip_tags:
            block[0] = strip_loader_tag(block[0])
        lines.extend(block)
    return lines


def _render_subsections(subsections: List[Tuple[str, List[Bullet]]], *, strip_tags: bool) -> str:
    """(見出し, bullet群) の並びをリリースノート文字列へ組み立てる。bullet が0件の見出しは出力しない。"""
    parts: List[str] = []
    for name, bullets in subsections:
        if not bullets:
            continue
        if parts:
            parts.append("")
        parts.append(f"### {name}")
        parts.extend(_render_bullets(bullets, strip_tags=strip_tags))
    return "\n".join(parts)


def _split_lead_and_bullets(content: str) -> Tuple[str, List[Bullet]]:
    """見出しの無いチャンクを、先頭の非箇条書きテキストと箇条書き群に分ける。"""
    lines = content.splitlines()
    for idx, line in enumerate(lines):
        if line.startswith("- "):
            return "\n".join(lines[:idx]).strip("\n"), parse_bullet_blocks("\n".join(lines[idx:]))
    return "\n".join(lines).strip("\n"), []


def _select_bullets(bullets: List[Bullet], loaders: frozenset[str]) -> List[Bullet]:
    """共通（タグ無し）または loaders と交差する bullet だけを残す。"""
    return [bullet for bullet in bullets if not bullet.loaders or (bullet.loaders & loaders)]


def filter_section_body(body: str, loaders: frozenset[str] | None, *, strip_tags: bool) -> str:
    """セクション本文（'### Added' のような見出し行を含む文字列）をローダーで絞り込む。

    loaders is None の場合はフィルタせず原文をそのまま返す。それ以外の場合、共通（タグ無し）
    または loaders と交差する bullet だけを残す。strip_tags=True なら先頭行のタグを除去する。
    bullet が0件になったサブセクションの見出しは出力しない。

    '### 見出し' より前にあるリード文（見出しが一切ない本文を含む）は、そこに置かれた箇条書きを
    同じルールで絞り込んだうえで維持する（フィルタ時に静かに消えないようにするため）。
    """
    if loaders is None:
        return body

    matches = list(SUBSECTION_HEADER_RE.finditer(body))
    preamble_source = body[: matches[0].start()] if matches else body
    lead, preamble_bullets = _split_lead_and_bullets(preamble_source)

    parts: List[str] = []
    if lead.strip():
        parts.append(lead)
    kept_preamble = _select_bullets(preamble_bullets, loaders)
    if kept_preamble:
        if parts:
            parts.append("")
        parts.extend(_render_bullets(kept_preamble, strip_tags=strip_tags))

    filtered = [(name, _select_bullets(bullets, loaders)) for name, bullets in parse_subsections(body)]
    rendered = _render_subsections(filtered, strip_tags=strip_tags)
    if rendered:
        if parts:
            parts.append("")
        parts.append(rendered)
    return "\n".join(parts)


def parse_loaders_arg(value: str | None) -> frozenset[str] | None:
    """--loaders の値をパースする。None / '' / 'all' はフィルタなし (None) を意味する。"""
    if value is None:
        return None
    normalized = value.strip().lower()
    if not normalized or normalized == "all":
        return None
    tokens = [token.strip() for token in normalized.split(",")]
    unknown = [token for token in tokens if token not in KNOWN_LOADERS]
    if unknown:
        raise SystemExit(
            f"--loaders に不正な値が含まれています: {', '.join(repr(token) for token in unknown)} "
            f"(有効な値: {', '.join(sorted(KNOWN_LOADERS))}, all)"
        )
    return frozenset(tokens)


def _bullet_key(bullet: Bullet) -> Tuple[str, ...]:
    """差分比較用のキー。ローダータグと行末空白の揺れを無視し、ブロック全体を見る。

    ブロック全体をキーにすることで、既存 bullet に子項目を後から足した場合も「変更あり」として
    検出できる。タグを除去して比較することで、あとから `[fabric]` を付けただけの bullet が
    タグ無し版と別物として二重掲載されるのを防ぐ。
    """
    lines = (strip_loader_tag(bullet.lines[0]), *bullet.lines[1:])
    return tuple(line.rstrip() for line in lines if line.strip())


def diff_unreleased(current_body: str, previous_body: str) -> str:
    """Return only the bullets in current_body that are new since previous_body,
    grouped by subsection (Added/Changed/Fixed, ...) in current_body's order."""
    previous_keys_by_subsection = {
        name: {_bullet_key(bullet) for bullet in bullets} for name, bullets in parse_subsections(previous_body)
    }

    new_subsections = []
    for name, bullets in parse_subsections(current_body):
        seen_before = previous_keys_by_subsection.get(name, set())
        new_subsections.append((name, [b for b in bullets if _bullet_key(b) not in seen_before]))
    return _render_subsections(new_subsections, strip_tags=False)


def get_previous_unreleased_body(file_path: str, since_ref: str) -> str:
    """Fetch the Unreleased section body of `file_path` as it existed at `since_ref`.

    Returns an empty string if the ref/file/section can't be resolved (e.g. the
    very first tag in a branch's history), which makes diff_unreleased() naturally
    fall back to emitting everything currently in Unreleased.
    """
    try:
        result = subprocess.run(
            ["git", "show", f"{since_ref}:{file_path}"],
            capture_output=True,
            text=True,
            check=True,
        )
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""

    try:
        _, sections = parse_sections(result.stdout)
    except ValueError:
        return ""

    for name, _, body in sections:
        if name.lower() == "unreleased":
            return body
    return ""


def build_changelog(prefix: str, sections: List[Tuple[str, str, str]]) -> str:
    """Reconstruct changelog text from prefix and sections."""
    parts: List[str] = []
    if prefix:
        parts.append(prefix.rstrip("\n"))
    for _, header, body in sections:
        if parts:
            parts.append("")  # blank line between sections
        section_text = header + body.rstrip("\n")
        parts.append(section_text)
    return "\n".join(parts).rstrip("\n") + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["alpha", "beta", "release", "read"], help="リリース種別")
    parser.add_argument("--file", default="CHANGELOG.md", help="Changelog ファイルのパス")
    parser.add_argument("--version", help="正式リリース時のバージョン (例: 1.2.3)")
    parser.add_argument(
        "--notes-output",
        help="リリースノートを書き出すファイルパス。未指定なら標準出力に出力",
    )
    parser.add_argument(
        "--update-file",
        action="store_true",
        help="リリース時に CHANGELOG.md を更新して保存する",
    )
    parser.add_argument(
        "--date",
        help="リリース日 (YYYY-MM-DD)。未指定なら今日の日付",
    )
    parser.add_argument(
        "--since-ref",
        help=(
            "alpha/beta モードで指定すると、この git ref 時点の Unreleased セクションとの差分"
            "（新規追加された箇条書きのみ）をノートとして出力する。未指定なら Unreleased 全文を出力"
        ),
    )
    parser.add_argument(
        "--loaders",
        help=(
            "対象ローダーを絞り込む ('fabric' / 'neoforge' / 'fabric,neoforge' / 'all'、省略時 = all)。"
            "出力するリリースノートにのみ適用され、CHANGELOG.md への書き戻し内容は常に無加工"
        ),
    )
    args = parser.parse_args()

    loaders = parse_loaders_arg(args.loaders)
    # 単一ローダー向けのノートではタグが自明なので除去する。
    strip_tags = loaders is not None and len(loaders) == 1

    def apply_loader_filter(notes: str) -> str:
        """出力用ノートにローダーフィルタを適用する（CHANGELOG.md 書き戻しには使わない）。

        フィルタで空になった場合はここで NO_CHANGES_NOTE に落とす。--loaders 未指定
        （loaders is None）の場合は素通しなので、呼び出し側の従来のフォールバックが効く。
        """
        if loaders is None:
            return notes
        return filter_section_body(notes, loaders, strip_tags=strip_tags).strip() or NO_CHANGES_NOTE

    changelog_path = pathlib.Path(args.file)
    if not changelog_path.exists():
        raise SystemExit(f"Changelog ファイルが見つかりません: {changelog_path}")

    text = changelog_path.read_text(encoding="utf-8")
    prefix, sections = parse_sections(text)

    if args.mode == "read":
        if not args.version:
            raise SystemExit("--version は read モードで必須です")
        try:
            _, _, body = next(s for s in sections if s[0] == args.version)
        except StopIteration:
            raise SystemExit(
                f"## [{args.version}] セクションが CHANGELOG.md に見つかりません。\n"
                "リリース前に changelog を更新してください"
            )
        notes = apply_loader_filter(body.strip())
        if args.notes_output:
            pathlib.Path(args.notes_output).write_text(notes + "\n", encoding="utf-8")
        else:
            sys.stdout.write(notes)
            if not notes.endswith("\n"):
                sys.stdout.write("\n")
        return

    try:
        unreleased_index = next(i for i, section in enumerate(sections) if section[0].lower() == "unreleased")
    except StopIteration as exc:
        raise SystemExit("CHANGELOG.md に [Unreleased] セクションが見つかりません") from exc

    _, _, unreleased_body = sections[unreleased_index]
    notes = unreleased_body.strip()

    if args.mode == "release":
        if not args.version:
            raise SystemExit("--version は release モードで必須です")
        release_content = unreleased_body.strip("\n")
        if not release_content.strip():
            release_content = NO_CHANGES_NOTE
        date_str = args.date or _dt.date.today().isoformat()
        new_section_body = "\n\n" + release_content.strip("\n") + "\n"
        new_section = (
            args.version,
            f"## [{args.version}] - {date_str}",
            new_section_body,
        )
        sections[unreleased_index] = (
            "Unreleased",
            "## [Unreleased]",
            UNRELEASED_BODY_TEMPLATE,
        )
        sections.insert(unreleased_index + 1, new_section)
        updated_text = build_changelog(prefix, sections)
        if args.update_file:
            # CHANGELOG.md には常に全項目をタグ付きのまま保存する（--loaders はノートにのみ適用）
            changelog_path.write_text(updated_text, encoding="utf-8")
        notes = apply_loader_filter(release_content.strip())
    else:
        if args.since_ref:
            previous_body = get_previous_unreleased_body(args.file, args.since_ref)
            notes = diff_unreleased(unreleased_body, previous_body).strip()
        notes = apply_loader_filter(notes)
        if not notes:
            notes = NO_CHANGES_NOTE

    if args.notes_output:
        pathlib.Path(args.notes_output).write_text(notes + "\n", encoding="utf-8")
    else:
        sys.stdout.write(notes)
        if not notes.endswith("\n"):
            sys.stdout.write("\n")


if __name__ == "__main__":
    main()
