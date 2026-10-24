# Contributing

このドキュメントは、開発時の最低限のローカルセットアップと品質チェック手順をまとめたものです。

## 対応環境と変更の送り先

現行は Minecraft 26.2 / Java 25、Fabric / NeoForge の2モジュールです。
対象 checkout の `gradle.properties` を確認して JDK を用意し、Gradle Wrapper を使ってください。
両モジュールはルート sourceSet を共有しますが、共有コードにも MC 依存 UI / Mixin が含まれます。
共通テストは `:fabric:test` が1回実行します。

[対応・保守方針](docs/support-policy.md) と [版番号・公開契約](docs/versioning.md) が開発ルールの ADR です。
Active は1 MC、Maintenance は最大1 MC。Loader 別の開発ブランチは作りません。
台帳はmainを唯一の公開元に予約する移行準備中です。main未作成時は新規公開を停止し、準備 PR は `26.2` 宛て、
移行検証後は main が Active の PR 宛先です。[移行手順と管理確認 gate](docs/main-migration.md)で実際の状態を確認し、
そこから `feature/<topic>` を作って対象 branch を base に PR を送ってください。バージョンブランチへ直接 push しません。

共通修正は Active で実装後、保守ブランチから `backport/<MC>/<issue>` を作り、
`git cherry-pick -x <元commitのfull-SHA>` で移植します。対象別 PR に元 Issue / commit と差分を残し、対象 CI と必要な client smoke を確認します。
Active に適用不能な修正は元 Issue / 保守 PR に理由と非該当根拠を記録してください。
Maintenance の期限は新 Active 昇格から6か月または次 Active 昇格の早い方です。
例外・Loader ごとの制限・EOL は台帳と notes に記録し、既存 branch / Tag / Release は保持します。

公開契約に対する互換修正は PATCH、機能追加は MINOR、1.0 以降の破壊変更は MAJOR（0 系では MINOR）です。
旧 MC だけの修正は旧 MINOR の PATCH、Loader 固有修正も PATCH を進めます。
同 MC リリースの Loader は同じ source SHA を使い、公開済み対象集合・JAR を差し替えません。
後日 Loader を追加する場合は新 PATCH として計画します。詳細と具体例は上記 ADR を参照してください。

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

## リリース準備と部分失敗の復旧

[公開・復旧ガイド](docs/release-workflow.md)を使い、純 SemVer・channel・MC・Loader 集合を明示して固定します。
alpha/beta は番号まで明示し、同 ID の再開で番号を変えません。stable の単 Loader も選べます。
`prepare-new`（公開なし build/check）→ `github-stage`（draft 永続保管）→ `publish` の順で実行し、
成功 prepare の full SHA / run ID / manifest SHA256 と plan を保存してください。

部分成功からは同じ入力・同じ bundle で `publish` を再実行します。相違・応答喪失の不明状態・artifact 消失時は
停止し、元 manifest と hash の一致を証明してから回復します。Tag / Release / version を削除・差し替えません。
公開先間の原子的成功は保証しません。後日の Loader 追加は新 PATCH とし、既存 target 集合を拡張しません。
必要な権限・secret 名と未設定時の挙動、状態確認 CLI、公開なし mock 確認は上記ガイドを参照してください。

## Local checks

手元でまとめて確認したい場合は、必要に応じて以下を実行してください。

```sh
./gradlew build
./gradlew :fabric:test
./gradlew check
./gradlew format
```

開発クライアントは `./gradlew :fabric:runClient` または `./gradlew :neoforge:runClient` で起動します。
通常 build は Mixin 適用・描画・認証・切断を実証しません。対応追加時は Loader ごとの client smoke 結果を記録してください。
