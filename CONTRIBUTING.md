# Contributing

このドキュメントは、開発時の最低限のローカルセットアップと品質チェック手順をまとめたものです。

## Git hooks

このリポジトリでは、共有用の Git hook を `.githooks/` 配下で管理します。

### セットアップ

```sh
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
```

### pre-commit

`pre-commit` hook は commit 前に以下を実行します。

- `./gradlew spotlessApply`
- `./gradlew checkstyleMain checkstyleTest`

`spotlessApply` がファイルを更新した場合は commit を中断します。差分を確認して `git add` したあと、再度 commit してください。

## コミット前に

### CHANGELOG.md の更新

ユーザーから見える変更を入れたときは `CHANGELOG.md` の `[Unreleased]` に追記してください。
リリースノートはここから自動生成されます。

Fabric / NeoForge の片方にしか関係しない項目には、箇条書きの先頭にローダータグを付けます。

```markdown
### Fixed
- 共通の不具合を修正（タグ無し = 全ローダー向け）
- [fabric] Fabric でのみ発生する不具合を修正
- [neoforge] NeoForge でのみ発生する不具合を修正
- [fabric,neoforge] 両方に関係するが、あえて明示したい場合
```

- **タグ無しの箇条書きは共通扱い**で、どのローダーのリリースノートにも必ず含まれます。迷ったらタグを付けないでください。
- **本文が完全に同じ内容なら `[fabric,neoforge]` として1件にまとめてください。** 同じ文章を `[fabric]` と
  `[neoforge]` に分けて書くと、両ローダーをまとめたリリースノートに2行として出てしまいます。
- 認識されるタグは `fabric` / `neoforge` だけです。未知のタグ（例: `[quilt]`）は通常の本文として扱われます。
- Markdown のリンク記法 `- [text](url)` はローダータグとみなされません。
- `CHANGELOG.md` にはタグ付きのまま保存され、リリースノート出力時にのみ絞り込みが行われます。

手元で出力を確認できます。

```sh
python3 scripts/changelog_tool.py alpha --loaders fabric
python3 scripts/changelog_tool.py alpha --loaders neoforge
```

## Local checks

手元でまとめて確認したい場合は、必要に応じて以下を実行してください。

```sh
./gradlew build
./gradlew test
./gradlew check
./gradlew format
```
