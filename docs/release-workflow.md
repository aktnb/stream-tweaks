# GitHub Actions を用いたリリース運用ガイド

このドキュメントは、GitHub Actions の `Release` ワークフロー（`.github/workflows/release.yml`）を
手動実行し、Fabric / NeoForge の JAR を Modrinth と GitHub Releases に公開する手順をまとめたものです。

## 概要

- リリースはタグ発行からビルド・公開・通知までを `Release` ワークフロー1本で行います。手動で
  `git tag` / `git push` する必要はありません。
- Actions タブ（または `gh workflow run`）から、対象の Minecraft バージョンブランチを選び、
  `channel`（`alpha` / `beta` / `release`）と `loaders`（`both` / `fabric` / `neoforge`）を指定して実行します。
- タグは **Fabric / NeoForge 共通の1系列** です。ローダーごとにタグを分けることはしません。
  - alpha / beta: `v<mod_version>-<channel>.<N>+mc<minecraft_version>`（例: `v0.3.2-alpha.3+mc26.2`）
  - release: `v<mod_version>+mc<minecraft_version>`（例: `v0.3.2+mc26.2`）
- `mod_version` / `minecraft_version` は対象ブランチの `gradle.properties` から自動取得され、形式を
  検証したうえで使われます。実行ブランチ名は `minecraft_version` と一致している必要があります。

## loaders の選択

| 選択 | 公開対象 | 用途 |
| --- | --- | --- |
| `both`（既定） | Fabric + NeoForge | 通常のリリース |
| `fabric` | Fabric のみ | 片方のローダーだけ修正を届けたいとき |
| `neoforge` | NeoForge のみ | 同上 |

**`channel=release` では `loaders=both` のみ指定できます。** 安定版のタグは
`v<mod_version>+mc<mc>` 固定で、片方だけ公開すると残りを公開する2回目の実行で必ずタグが衝突するため、
`compute` ステップで即座に失敗させています。連番を採番する alpha / beta では片方だけの公開が可能です。

## ステップの実行順序

現在のワークフローは **ビルドを先に行い、成功してからタグを push** します。

1. `checkout`（`fetch-depth: 0` / `fetch-tags: true` / `persist-credentials: false`）
2. `.github/actions/setup-build`（`gradle.properties` の `java_version` から JDK を用意）
3. `compute`: 入力検証・バージョン検証・タグ採番・ローダー別の前回タグ解決（**まだ push しない**）
4. `changelog`: ローダー別リリースノートとまとめノートを生成し、artifact として保存
5. `build`: `loaders` に応じて、タグ push 前に対象ローダーをビルド
   - `both`: `./gradlew clean build --stacktrace "-Pmod_version=<version>"`
   - `fabric`: `./gradlew clean :fabric:test :fabric:build --stacktrace "-Pmod_version=<version>"`
   - `neoforge`: `./gradlew clean :fabric:test :neoforge:build --stacktrace "-Pmod_version=<version>"`
6. `tag`: annotated tag を作成して push
7. `Publish Fabric to Modrinth`（`loaders` が `both` / `fabric` のとき）
8. `Publish NeoForge to Modrinth`（`loaders` が `both` / `neoforge` のとき）
9. `Publish to GitHub Release`: 選択したローダーの JAR をまとめて1つの Release に添付
10. `notify` ジョブ: 成功・失敗のどちらでも Discord に通知（`scripts/notify_discord.py`）

ビルドが先なので、コンパイルエラーでタグだけが残るという状態は発生しません。タグが残るのは
「タグ push 後の publish ステップで失敗したとき」だけです。

単一ローダーを選んだ場合は、そのローダーのビルド失敗だけで止まります。ただし共通コードのテストは
現状 `:fabric:test` にだけ存在するため、`neoforge` 単独公開でも `:fabric:test` は実行します。

## タグに埋め込むローダー情報

タグは annotated tag として作成され、メッセージは次の2行だけです。

```
Release v0.3.2-alpha.3+mc26.2
Loaders: fabric,neoforge
```

changelog 本文はタグメッセージに含めません（自由入力が `Loaders:` 行のパースを汚染しうるため）。

この `Loaders:` 行は、次回の alpha / beta で「そのローダーを公開した直近のタグ」を探すために使います。
`^Loaders: ((fabric|neoforge)(,(fabric|neoforge))*)$` に一致しないタグ（`Loaders:` 行を持たない
既存の lightweight タグなど）は「不明」＝両ローダー公開済みとみなします。

結果として、`fabric` だけを公開した直後に `neoforge` を公開すると、NeoForge 向けのリリースノートは
「NeoForge を最後に公開したタグ」からの差分になり、取りこぼしが起きません。

## リリースノートの生成

`scripts/changelog_tool.py` を `--loaders` 付きで複数回呼び出し、3種類のノートを作ります。

| ノート | `--loaders` | `--since-ref` | 用途 |
| --- | --- | --- | --- |
| Fabric 向け | `fabric` | Fabric の前回タグ | Modrinth の Fabric version |
| NeoForge 向け | `neoforge` | NeoForge の前回タグ | Modrinth の NeoForge version |
| まとめ | 選択したローダー（`both` なら `fabric,neoforge`） | 選択ローダーのうち**古い方**の前回タグ | GitHub Release / Discord |

まとめノートで古い方の前回タグを使うのは、片方だけ先に公開していた場合でも変更点を取りこぼさない
ようにするためです。

## CHANGELOG のローダータグ記法

`CHANGELOG.md` の箇条書き先頭に `[fabric]` / `[neoforge]` / `[fabric,neoforge]` を付けると、
リリースノート生成時にローダーで絞り込まれます。

```markdown
### Fixed
- 共通の不具合を修正（タグ無し = 全ローダー向け）
- [fabric] Fabric でのみ発生する不具合を修正
- [neoforge] NeoForge でのみ発生する不具合を修正
- [fabric,neoforge] 両方に関係するが、記述を分けたい場合
```

- **タグ無しの箇条書きは共通** として、どのローダーのノートにも必ず含まれます。
- 未知のタグ（例: `[quilt]`）はタグとして解釈されず、通常の本文として扱われます（フェイルセーフ）。
- Markdown のリンク記法 `- [text](url)` はローダータグとみなされません。
- 単一ローダー向けノートではタグが自明なので、出力時に自動で除去されます。
- **本文が完全に同じ内容なら、`[fabric,neoforge]` として1件にまとめてください。** 同じ内容を
  `[fabric]` と `[neoforge]` に分けて書くと、まとめノートに2行として出てしまいます。
- `CHANGELOG.md` 自体には常にタグ付きのまま保存されます（`--loaders` はノート出力にのみ効きます）。

## 事前準備

1. リリースしたいコミットが対象の Minecraft バージョンブランチ（例: `origin/26.2`）に含まれていることを確認します。
2. `gradle.properties` の `mod_version` を公開対象バージョンに更新します。
3. `CHANGELOG.md` の `[Unreleased]` に変更点を書きます（必要ならローダータグを付けます）。
4. 必要なら Discord 通知用に `DISCORD_WEBHOOK_URL`、Modrinth 公開用に `MODRINTH_TOKEN` を
   `Settings > Secrets and variables > Actions` に設定します。
5. ローカルでビルド確認を行います。

```bash
./gradlew build --stacktrace
```

## alpha / beta リリース

`[Unreleased]` に変更を書き溜めた状態でワークフローを実行するだけです。`CHANGELOG.md` の
事前更新は不要です（ワークフローが `[Unreleased]` の差分をリリースノートとして使います）。

```bash
gh workflow run release.yml --ref 26.2 -f channel=alpha -f loaders=both

# Fabric だけ先に配りたいとき
gh workflow run release.yml --ref 26.2 -f channel=beta -f loaders=fabric
```

## 正式リリース（release）

`CHANGELOG.md` に `## [<mod_version>]` セクションが存在しないと `compute` ステップで失敗します。
先に以下を済ませてください。

```bash
git checkout -b release/0.3.2
python3 scripts/changelog_tool.py release --version 0.3.2 --update-file
python3 scripts/changelog_tool.py read --version 0.3.2   # 内容確認
git add CHANGELOG.md
git commit -m "docs: prepare release 0.3.2"
gh pr create --base 26.2
```

PR をマージしたあとにワークフローを実行します。

```bash
gh workflow run release.yml --ref 26.2 -f channel=release -f loaders=both
```

## リカバリ手順

ビルドがタグ push より前に走るため、**失敗の大半はタグを作らずに終わります**。その場合は
原因を直して再実行するだけで済みます。

タグの削除が必要になるのは、**タグ push 後の publish ステップで失敗したとき**だけです。

```bash
# 1. タグを削除（ローカルとリモート両方）
git tag -d v0.3.2+mc26.2
git push origin :refs/tags/v0.3.2+mc26.2

# 2. GitHub Release が中途半端に作られていれば削除する
gh release delete v0.3.2+mc26.2 --yes

# 3. 原因を修正（必要なら CHANGELOG.md を PR 経由で更新）してから再実行
gh workflow run release.yml --ref 26.2 -f channel=release -f loaders=both
```

Modrinth 側に version が作成済みの場合は、Modrinth の管理画面から該当 version を削除してから
再実行してください（同じ version 番号は再利用できません）。

## セキュリティ上の取り決め

- ワークフロー最上位の `permissions` は `contents: read` で、タグ push と Release 作成を行う
  `release` ジョブだけが `contents: write` を持ちます。
- すべての action は commit SHA で pin し、コメントに元のバージョンタグを併記します。
- `run:` ブロックの中で `${{ }}` を直接展開しません。値は必ず `env:` 経由で渡し、
  シェル側ではクオートした変数参照を使います（コマンドインジェクション対策）。
- `gradle.properties` から読んだ `mod_version` / `minecraft_version`、および解決したタグ名は
  正規表現で形式を検証してから使用します。
- Discord 通知は `scripts/notify_discord.py` が `json.dumps()` でペイロードを組み立てます。
  webhook URL は環境変数からのみ読み、失敗通知には実行ログやエラー詳細を含めません。

## リリース後の確認

- GitHub Releases に対象タグ名のリリースが作成され、選択したローダーの JAR が添付されていること。
- Modrinth に選択したローダーの version が公開され、ローダー別のリリースノートが載っていること。
- 添付 JAR が sources / dev ではない通常 JAR であること。
- Discord に通知が届いていること（`DISCORD_WEBHOOK_URL` 設定時）。

## トラブルシューティング

- **ブランチ名と Minecraft バージョンが一致しない**: 実行ブランチ名が `gradle.properties` の
  `minecraft_version` と同じか確認します。
- **`channel=release` で即失敗する**: `loaders=both` になっているか、`CHANGELOG.md` に
  `## [<mod_version>]` セクションがあるかを確認します。
- **リリースノートが「変更点はありません。」になる**: 選択したローダー向けの箇条書きが
  `[Unreleased]` に無い（前回タグ以降に差分が無い）状態です。
- **成果物が見つからない**: JAR は root の `build/libs` ではなく `fabric/build/libs` と
  `neoforge/build/libs` に出力されます。
- **Discord 通知が飛ばない**: `DISCORD_WEBHOOK_URL` シークレットが設定されているか確認します
  （未設定の場合は通知ステップがスキップされます）。
