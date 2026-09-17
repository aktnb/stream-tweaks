"""Tests for scripts/changelog_tool.py (standard library unittest only)."""
from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS_DIR.parent
TOOL_PATH = SCRIPTS_DIR / "changelog_tool.py"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import changelog_tool as ct  # noqa: E402


class ParseBulletBlocksTest(unittest.TestCase):
    def test_untagged_bullets_are_common(self):
        bullets = ct.parse_bullet_blocks("\n- foo\n- bar\n")
        self.assertEqual([b.loaders for b in bullets], [frozenset(), frozenset()])
        self.assertEqual([b.lines for b in bullets], [("- foo",), ("- bar",)])

    def test_single_loader_tag(self):
        (bullet,) = ct.parse_bullet_blocks("- [fabric] ModMenu 対応\n")
        self.assertEqual(bullet.loaders, frozenset({"fabric"}))
        self.assertEqual(bullet.lines, ("- [fabric] ModMenu 対応",))

    def test_neoforge_tag(self):
        (bullet,) = ct.parse_bullet_blocks("- [neoforge] foo\n")
        self.assertEqual(bullet.loaders, frozenset({"neoforge"}))

    def test_multi_loader_tag(self):
        (bullet,) = ct.parse_bullet_blocks("- [fabric,neoforge] foo\n")
        self.assertEqual(bullet.loaders, frozenset({"fabric", "neoforge"}))

    def test_multi_loader_tag_with_spaces(self):
        (bullet,) = ct.parse_bullet_blocks("- [fabric, neoforge] foo\n")
        self.assertEqual(bullet.loaders, frozenset({"fabric", "neoforge"}))

    def test_unknown_tag_is_treated_as_common(self):
        (bullet,) = ct.parse_bullet_blocks("- [foobar] foo\n")
        self.assertEqual(bullet.loaders, frozenset())
        self.assertEqual(bullet.lines, ("- [foobar] foo",))

    def test_partially_unknown_tag_is_treated_as_common(self):
        (bullet,) = ct.parse_bullet_blocks("- [fabric,foobar] foo\n")
        self.assertEqual(bullet.loaders, frozenset())

    def test_link_reference_like_bullet_is_common(self):
        """既存 CHANGELOG にある '- [Unreleased]: ...' のような記述をタグ扱いしない。"""
        (bullet,) = ct.parse_bullet_blocks("- [Unreleased]: https://example.com/compare\n")
        self.assertEqual(bullet.loaders, frozenset())

    def test_markdown_link_at_line_start_is_not_a_tag(self):
        (bullet,) = ct.parse_bullet_blocks("- [fabric](https://example.com) を参照\n")
        self.assertEqual(bullet.loaders, frozenset())
        self.assertEqual(bullet.lines, ("- [fabric](https://example.com) を参照",))

    def test_tag_is_case_insensitive(self):
        (bullet,) = ct.parse_bullet_blocks("- [Fabric] foo\n")
        self.assertEqual(bullet.loaders, frozenset({"fabric"}))
        # 元の行は加工しない
        self.assertEqual(bullet.lines, ("- [Fabric] foo",))

    def test_nested_bullets_and_continuation_lines_belong_to_previous_block(self):
        content = "\n- 親項目\n  - 子項目1\n  - 子項目2\n  継続行\n- 次の項目\n"
        bullets = ct.parse_bullet_blocks(content)
        self.assertEqual(len(bullets), 2)
        self.assertEqual(bullets[0].lines, ("- 親項目", "  - 子項目1", "  - 子項目2", "  継続行"))
        self.assertEqual(bullets[1].lines, ("- 次の項目",))

    def test_trailing_blank_lines_are_stripped_from_block(self):
        bullets = ct.parse_bullet_blocks("- foo\n\n\n")
        self.assertEqual(bullets[0].lines, ("- foo",))

    def test_blank_line_between_block_lines_is_kept(self):
        bullets = ct.parse_bullet_blocks("- foo\n\n  continued\n")
        self.assertEqual(bullets[0].lines, ("- foo", "", "  continued"))

    def test_empty_section(self):
        self.assertEqual(ct.parse_bullet_blocks(""), [])
        self.assertEqual(ct.parse_bullet_blocks("\n\n"), [])

    def test_leading_text_without_bullet_is_ignored(self):
        bullets = ct.parse_bullet_blocks("説明文\n- foo\n")
        self.assertEqual([b.lines for b in bullets], [("- foo",)])


class StripLoaderTagTest(unittest.TestCase):
    def test_strips_known_tag(self):
        self.assertEqual(ct.strip_loader_tag("- [fabric] foo"), "- foo")

    def test_strips_multi_loader_tag(self):
        self.assertEqual(ct.strip_loader_tag("- [fabric, neoforge] foo"), "- foo")

    def test_strips_case_insensitive_tag(self):
        self.assertEqual(ct.strip_loader_tag("- [Fabric] foo"), "- foo")

    def test_keeps_untagged_line(self):
        self.assertEqual(ct.strip_loader_tag("- foo"), "- foo")

    def test_keeps_unknown_tag(self):
        self.assertEqual(ct.strip_loader_tag("- [foobar] foo"), "- [foobar] foo")

    def test_keeps_indent(self):
        self.assertEqual(ct.strip_loader_tag("  - [fabric] foo"), "  - foo")

    def test_markdown_link_is_not_a_tag(self):
        line = "- [fabric](https://example.com) を参照"
        self.assertEqual(ct.strip_loader_tag(line), line)

    def test_parenthesis_after_tag_with_space_is_still_a_tag(self):
        self.assertEqual(ct.strip_loader_tag("- [fabric] (補足) foo"), "- (補足) foo")

    def test_tag_only_line_leaves_no_trailing_whitespace(self):
        self.assertEqual(ct.strip_loader_tag("- [fabric]"), "-")

    def test_trailing_whitespace_is_removed(self):
        self.assertEqual(ct.strip_loader_tag("- [fabric] foo   "), "- foo")


SAMPLE_BODY = """
### Added
- 共通の追加
- [fabric] Fabric だけの追加
- [neoforge] NeoForge だけの追加

### Changed
- [fabric,neoforge] 両方の変更

### Fixed
- [neoforge] NeoForge だけの修正
"""


class FilterSectionBodyTest(unittest.TestCase):
    def test_none_returns_body_unchanged(self):
        self.assertEqual(
            ct.filter_section_body(SAMPLE_BODY, None, strip_tags=False),
            SAMPLE_BODY,
        )
        self.assertEqual(
            ct.filter_section_body(SAMPLE_BODY, None, strip_tags=True),
            SAMPLE_BODY,
        )

    def test_fabric_keeps_common_and_fabric(self):
        result = ct.filter_section_body(SAMPLE_BODY, frozenset({"fabric"}), strip_tags=False)
        self.assertEqual(
            result,
            "### Added\n"
            "- 共通の追加\n"
            "- [fabric] Fabric だけの追加\n"
            "\n"
            "### Changed\n"
            "- [fabric,neoforge] 両方の変更",
        )
        # Fixed は NeoForge 専用のみなので見出しごと消える
        self.assertNotIn("### Fixed", result)

    def test_neoforge_keeps_common_and_neoforge(self):
        result = ct.filter_section_body(SAMPLE_BODY, frozenset({"neoforge"}), strip_tags=False)
        self.assertEqual(
            result,
            "### Added\n"
            "- 共通の追加\n"
            "- [neoforge] NeoForge だけの追加\n"
            "\n"
            "### Changed\n"
            "- [fabric,neoforge] 両方の変更\n"
            "\n"
            "### Fixed\n"
            "- [neoforge] NeoForge だけの修正",
        )

    def test_strip_tags_removes_tags_from_output(self):
        result = ct.filter_section_body(SAMPLE_BODY, frozenset({"fabric"}), strip_tags=True)
        self.assertEqual(
            result,
            "### Added\n"
            "- 共通の追加\n"
            "- Fabric だけの追加\n"
            "\n"
            "### Changed\n"
            "- 両方の変更",
        )

    def test_both_loaders_keeps_everything(self):
        result = ct.filter_section_body(SAMPLE_BODY, frozenset({"fabric", "neoforge"}), strip_tags=False)
        self.assertIn("### Added", result)
        self.assertIn("### Changed", result)
        self.assertIn("### Fixed", result)
        self.assertIn("- [fabric] Fabric だけの追加", result)
        self.assertIn("- [neoforge] NeoForge だけの追加", result)

    def test_section_with_no_matching_bullets_is_dropped_entirely(self):
        body = "\n### Added\n- [neoforge] foo\n\n### Fixed\n- [neoforge] bar\n"
        self.assertEqual(ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True), "")

    def test_preamble_before_first_heading_is_kept(self):
        body = "\nこのリリースの概要です。\n\n### Added\n- [fabric] foo\n- [neoforge] bar\n"
        self.assertEqual(
            ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True),
            "このリリースの概要です。\n\n### Added\n- foo",
        )

    def test_preamble_is_kept_even_when_every_bullet_is_filtered_out(self):
        body = "\nこのリリースの概要です。\n\n### Added\n- [neoforge] bar\n"
        self.assertEqual(
            ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True),
            "このリリースの概要です。",
        )

    def test_body_without_any_heading_is_kept(self):
        body = "\n見出しのない本文です。\n"
        self.assertEqual(
            ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True),
            "見出しのない本文です。",
        )

    def test_bullets_without_any_heading_are_filtered(self):
        body = "\n- 共通\n- [fabric] fab\n- [neoforge] neo\n"
        self.assertEqual(
            ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True),
            "- 共通\n- fab",
        )

    def test_preamble_bullets_and_subsections_coexist(self):
        body = "\nリード文\n\n- [fabric] 前置きの項目\n\n### Added\n- 共通\n"
        self.assertEqual(
            ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True),
            "リード文\n\n- 前置きの項目\n\n### Added\n- 共通",
        )

    def test_neoforge_only_content_does_not_leak_into_fabric_notes(self):
        """フェイルセーフのフォールバックで他ローダー専用項目が漏れないこと。"""
        body = "\n### Added\n- [neoforge] foo\n\n### Fixed\n- [neoforge] bar\n"
        result = ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True)
        self.assertEqual(result, "")
        self.assertNotIn("foo", result)

    def test_nested_bullets_are_kept_with_their_parent(self):
        body = "\n### Added\n- [fabric] 親\n  - 子\n- [neoforge] 別\n"
        self.assertEqual(
            ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True),
            "### Added\n- 親\n  - 子",
        )


class ParseLoadersArgTest(unittest.TestCase):
    def test_none(self):
        self.assertIsNone(ct.parse_loaders_arg(None))

    def test_empty(self):
        self.assertIsNone(ct.parse_loaders_arg(""))
        self.assertIsNone(ct.parse_loaders_arg("   "))

    def test_all(self):
        self.assertIsNone(ct.parse_loaders_arg("all"))
        self.assertIsNone(ct.parse_loaders_arg("ALL"))

    def test_single(self):
        self.assertEqual(ct.parse_loaders_arg("fabric"), frozenset({"fabric"}))
        self.assertEqual(ct.parse_loaders_arg("neoforge"), frozenset({"neoforge"}))

    def test_multiple(self):
        self.assertEqual(ct.parse_loaders_arg("fabric,neoforge"), frozenset({"fabric", "neoforge"}))
        self.assertEqual(ct.parse_loaders_arg("fabric, neoforge"), frozenset({"fabric", "neoforge"}))

    def test_case_insensitive(self):
        self.assertEqual(ct.parse_loaders_arg("Fabric"), frozenset({"fabric"}))

    def test_unknown_loader_exits(self):
        with self.assertRaises(SystemExit) as cm:
            ct.parse_loaders_arg("forge")
        self.assertIn("forge", str(cm.exception))

    def test_partially_unknown_loader_exits(self):
        with self.assertRaises(SystemExit):
            ct.parse_loaders_arg("fabric,forge")

    def test_trailing_comma_exits(self):
        with self.assertRaises(SystemExit):
            ct.parse_loaders_arg("fabric,")


class DiffUnreleasedTest(unittest.TestCase):
    def test_only_new_bullets_are_emitted(self):
        previous = "\n### Added\n- 既存\n\n### Fixed\n- 既存の修正\n"
        current = "\n### Added\n- 既存\n- 新規\n\n### Fixed\n- 既存の修正\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "### Added\n- 新規")

    def test_empty_previous_emits_everything(self):
        current = "\n### Added\n- a\n\n### Fixed\n- b\n"
        self.assertEqual(ct.diff_unreleased(current, ""), "### Added\n- a\n\n### Fixed\n- b")

    def test_no_changes_returns_empty(self):
        body = "\n### Added\n- a\n"
        self.assertEqual(ct.diff_unreleased(body, body), "")

    def test_nested_bullets_are_picked_up(self):
        """既存バグの回帰テスト: 入れ子 bullet がインデントを失って平坦化されないこと。"""
        current = "\n### Added\n- 親項目\n  - 子項目1\n  - 子項目2\n"
        result = ct.diff_unreleased(current, "")
        self.assertEqual(result, "### Added\n- 親項目\n  - 子項目1\n  - 子項目2")

    def test_continuation_lines_are_picked_up(self):
        """既存バグの回帰テスト: '- ' で始まらない継続行が脱落しないこと。"""
        current = "\n### Added\n- 親項目\n  補足説明\n"
        self.assertEqual(ct.diff_unreleased(current, ""), "### Added\n- 親項目\n  補足説明")

    def test_nested_bullets_are_not_diffed_independently(self):
        """親項目が既出なら、その子項目だけが単独で出力されることはない。"""
        previous = "\n### Added\n- 親項目\n  - 子項目1\n"
        current = "\n### Added\n- 親項目\n  - 子項目1\n- 新規\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "### Added\n- 新規")

    def test_identical_text_under_different_tags_collides(self):
        """比較キーがタグ非依存であることの帰結（既知のトレードオフ）。

        本文が完全に同じで タグだけ違う bullet は同一視される。ローダー間で同じ文言を
        書きたい場合は `- [fabric,neoforge] a` のように1件にまとめること。
        タグの付け外しを「新規」と誤検知しないことを優先した仕様。
        """
        previous = "\n### Added\n- [fabric] a\n"
        current = "\n### Added\n- [fabric] a\n- [neoforge] a\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "")

    def test_different_text_under_different_tags_are_distinct(self):
        previous = "\n### Added\n- [fabric] a\n"
        current = "\n### Added\n- [fabric] a\n- [neoforge] b\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "### Added\n- [neoforge] b")

    def test_adding_a_tag_to_an_existing_bullet_is_not_a_new_entry(self):
        """既存 bullet に後からタグを付けただけでは二重掲載しない。"""
        previous = "\n### Added\n- a\n"
        current = "\n### Added\n- [fabric] a\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "")

    def test_removing_a_tag_from_an_existing_bullet_is_not_a_new_entry(self):
        previous = "\n### Added\n- [fabric] a\n"
        current = "\n### Added\n- a\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "")

    def test_adding_a_child_bullet_to_an_existing_bullet_is_detected(self):
        """既存 bullet に子項目を足した場合は差分として出力される。"""
        previous = "\n### Added\n- 親項目\n"
        current = "\n### Added\n- 親項目\n  - 子項目\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "### Added\n- 親項目\n  - 子項目")

    def test_adding_a_continuation_line_to_an_existing_bullet_is_detected(self):
        previous = "\n### Added\n- 親項目\n"
        current = "\n### Added\n- 親項目\n  補足説明\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "### Added\n- 親項目\n  補足説明")

    def test_trailing_whitespace_only_change_is_not_a_new_entry(self):
        previous = "\n### Added\n- a\n"
        current = "\n### Added\n- a   \n"
        self.assertEqual(ct.diff_unreleased(current, previous), "")

    def test_blank_line_inside_block_does_not_affect_comparison(self):
        previous = "\n### Added\n- a\n  補足\n"
        current = "\n### Added\n- a\n\n  補足\n"
        self.assertEqual(ct.diff_unreleased(current, previous), "")


class RealChangelogTest(unittest.TestCase):
    """実際の CHANGELOG.md を使った統合的な確認。"""

    @classmethod
    def setUpClass(cls):
        text = (REPO_ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        _, sections = ct.parse_sections(text)
        cls.bodies = {name: body for name, _, body in sections}

    def test_030_nested_bullets_are_parsed(self):
        subsections = dict(ct.parse_subsections(self.bodies["0.3.0"]))
        added = subsections["Added"]
        self.assertEqual(len(added), 1)
        self.assertEqual(len(added[0].lines), 3, added[0].lines)
        self.assertTrue(added[0].lines[1].startswith("  - 対応するのは"))
        self.assertEqual(added[0].loaders, frozenset())

    def test_030_diff_against_empty_keeps_nested_bullets(self):
        notes = ct.diff_unreleased(self.bodies["0.3.0"], "")
        self.assertIn("  - 対応するのはモデレーターによる個別メッセージ削除のみ", notes)
        self.assertIn("  - 削除されたメッセージの本文は", notes)

    def test_031_is_all_common_so_filtering_keeps_everything(self):
        body = self.bodies["0.3.1"].strip()
        filtered = ct.filter_section_body(body, frozenset({"fabric"}), strip_tags=True)
        for line in body.splitlines():
            if line.strip():
                self.assertIn(line, filtered)

    def test_all_sections_survive_filtering_unchanged_content(self):
        """タグの無い箇条書きは、どのローダーで絞り込んでも全項目が残る。"""
        for name, body in self.bodies.items():
            for loader in ("fabric", "neoforge"):
                with self.subTest(section=name, loader=loader):
                    filtered = ct.filter_section_body(body, frozenset({loader}), strip_tags=True)
                    for line in body.splitlines():
                        if line.strip().startswith("- ") and ct._extract_loaders(line) is None:
                            self.assertIn(line.rstrip(), filtered)


CLI_CHANGELOG = """# Changelog

## [Unreleased]

### Added
- 共通の追加
- [fabric] Fabric だけの追加
  - Fabric の補足
- [neoforge] NeoForge だけの追加

### Changed
- [fabric,neoforge] 両方の変更

### Fixed
- [neoforge] NeoForge だけの修正

## [0.1.0] - 2026-01-01
### Added
- 初回
"""


class CliIntegrationTest(unittest.TestCase):
    """main() を通した CLI の統合テスト（subprocess で実際に起動する）。"""

    def run_tool(self, *args: str) -> str:
        result = subprocess.run(
            [sys.executable, str(TOOL_PATH), *args],
            capture_output=True,
            text=True,
            cwd=str(REPO_ROOT),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_release_with_update_file_and_loaders_keeps_changelog_unfiltered(self):
        """このPRの核心的な安全要件の回帰テスト。

        release + --update-file + --loaders を同時指定しても、書き戻される CHANGELOG.md は
        常に無加工（全ローダーの項目がタグ付きのまま）でなければならない。フィルタとタグ除去は
        出力される notes にのみ適用される。
        """
        with tempfile.TemporaryDirectory() as tmp:
            changelog = pathlib.Path(tmp) / "CHANGELOG.md"
            changelog.write_text(CLI_CHANGELOG, encoding="utf-8")

            notes = self.run_tool(
                "release",
                "--version",
                "0.9.9",
                "--date",
                "2026-07-28",
                "--file",
                str(changelog),
                "--update-file",
                "--loaders",
                "fabric",
            )
            written = changelog.read_text(encoding="utf-8")

        # 1) CHANGELOG.md 本体はフィルタ非適用: 全ローダーの項目がタグ付きのまま残る
        self.assertIn("## [0.9.9] - 2026-07-28", written)
        for line in (
            "- 共通の追加",
            "- [fabric] Fabric だけの追加",
            "  - Fabric の補足",
            "- [neoforge] NeoForge だけの追加",
            "- [fabric,neoforge] 両方の変更",
            "- [neoforge] NeoForge だけの修正",
        ):
            self.assertIn(line, written, "CHANGELOG.md 本体からローダー項目が失われている")
        # 見出しも全て残る（フィルタで 0 件になった見出しが消えたりしない）
        for heading in ("### Added", "### Changed", "### Fixed"):
            self.assertIn(heading, written)
        # 過去のセクションと [Unreleased] のリセットは従来どおり
        self.assertIn("## [0.1.0] - 2026-01-01", written)
        self.assertIn("## [Unreleased]\n\n### Added\n\n### Changed\n\n### Fixed\n", written)

        # 2) notes 側は fabric + 共通のみ、かつタグ除去済み
        self.assertEqual(
            notes,
            "### Added\n"
            "- 共通の追加\n"
            "- Fabric だけの追加\n"
            "  - Fabric の補足\n"
            "\n"
            "### Changed\n"
            "- 両方の変更\n",
        )
        self.assertNotIn("NeoForge だけの", notes)
        self.assertNotIn("[fabric]", notes)

    def test_release_notes_output_file_is_filtered_while_changelog_is_not(self):
        """--notes-output 経由でも同じ不変条件が成り立つこと。"""
        with tempfile.TemporaryDirectory() as tmp:
            changelog = pathlib.Path(tmp) / "CHANGELOG.md"
            changelog.write_text(CLI_CHANGELOG, encoding="utf-8")
            notes_path = pathlib.Path(tmp) / "notes.md"

            self.run_tool(
                "release",
                "--version",
                "0.9.9",
                "--date",
                "2026-07-28",
                "--file",
                str(changelog),
                "--update-file",
                "--notes-output",
                str(notes_path),
                "--loaders",
                "neoforge",
            )
            written = changelog.read_text(encoding="utf-8")
            notes = notes_path.read_text(encoding="utf-8")

        self.assertIn("- [fabric] Fabric だけの追加", written)
        self.assertIn("- [neoforge] NeoForge だけの追加", written)
        self.assertEqual(
            notes,
            "### Added\n"
            "- 共通の追加\n"
            "- NeoForge だけの追加\n"
            "\n"
            "### Changed\n"
            "- 両方の変更\n"
            "\n"
            "### Fixed\n"
            "- NeoForge だけの修正\n",
        )
        self.assertNotIn("Fabric だけの追加", notes)

    def test_release_without_loaders_is_unchanged(self):
        """--loaders 未指定なら notes も CHANGELOG.md も従来どおり全項目・タグ付き。"""
        with tempfile.TemporaryDirectory() as tmp:
            changelog = pathlib.Path(tmp) / "CHANGELOG.md"
            changelog.write_text(CLI_CHANGELOG, encoding="utf-8")
            notes = self.run_tool(
                "release", "--version", "0.9.9", "--date", "2026-07-28",
                "--file", str(changelog), "--update-file",
            )
            written = changelog.read_text(encoding="utf-8")

        self.assertIn("- [neoforge] NeoForge だけの修正", notes)
        self.assertIn("- [fabric] Fabric だけの追加", notes)
        self.assertIn("- [neoforge] NeoForge だけの修正", written)

    def test_release_without_update_file_does_not_touch_changelog(self):
        with tempfile.TemporaryDirectory() as tmp:
            changelog = pathlib.Path(tmp) / "CHANGELOG.md"
            changelog.write_text(CLI_CHANGELOG, encoding="utf-8")
            self.run_tool(
                "release", "--version", "0.9.9", "--file", str(changelog), "--loaders", "fabric",
            )
            self.assertEqual(changelog.read_text(encoding="utf-8"), CLI_CHANGELOG)

    def test_read_mode_filters_notes(self):
        with tempfile.TemporaryDirectory() as tmp:
            changelog = pathlib.Path(tmp) / "CHANGELOG.md"
            changelog.write_text(CLI_CHANGELOG, encoding="utf-8")
            notes = self.run_tool("read", "--version", "0.1.0", "--file", str(changelog), "--loaders", "fabric")
        self.assertEqual(notes, "### Added\n- 初回\n")

    def test_unknown_loader_exits_nonzero_without_touching_changelog(self):
        with tempfile.TemporaryDirectory() as tmp:
            changelog = pathlib.Path(tmp) / "CHANGELOG.md"
            changelog.write_text(CLI_CHANGELOG, encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable, str(TOOL_PATH), "release", "--version", "0.9.9",
                    "--file", str(changelog), "--update-file", "--loaders", "forge",
                ],
                capture_output=True,
                text=True,
                cwd=str(REPO_ROOT),
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("forge", result.stderr)
            self.assertEqual(changelog.read_text(encoding="utf-8"), CLI_CHANGELOG)


if __name__ == "__main__":
    unittest.main()
