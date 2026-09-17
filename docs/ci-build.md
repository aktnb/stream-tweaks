# CI ビルドワークフロー

このプロジェクトの CI は `.github/workflows/build.yml`（**Build** ワークフロー）で実行されます。
Fabric / NeoForge のマルチローダー構成に合わせてジョブを分割し、変更のあったローダーだけをビルドします。

## トリガー条件

- 全ブランチへのプルリクエスト
- バージョンブランチ（例: `26.2`）へのプッシュ

`pull_request` トリガーのまま運用します（fork PR で secrets が露出するため `pull_request_target` には
変更しないでください）。ワークフロー最上位の `permissions` は `contents: read` です。

## ジョブ構成

| ジョブ | 実行条件 | 内容 |
| --- | --- | --- |
| `changes` | 常時 | `dorny/paths-filter` で fabric / neoforge に影響する変更を検知する |
| `format` | 常時 | `./gradlew spotlessCheck --stacktrace` |
| `test` (Test (shared)) | 常時 | `./gradlew :fabric:test --stacktrace` |
| `script-tests` (Release Script Tests) | 常時 | `python3 -m py_compile scripts/changelog_tool.py scripts/notify_discord.py` / `python3 -m unittest discover scripts/tests` |
| `build-fabric` | `changes.outputs.fabric == 'true'` | `./gradlew :fabric:build -x test --stacktrace` |
| `build-neoforge` | `changes.outputs.neoforge == 'true'` | `./gradlew :neoforge:build --stacktrace` |
| `ci-ok` | `always()` | 上記すべての結果を集約する。**唯一の必須ステータスチェック** |

### path filter

`changes` ジョブのフィルタ定義は次のとおりです。共通部分は YAML アンカー `&common` で共有しています。

```yaml
common: &common
  - 'src/**'
  - 'build.gradle'
  - 'settings.gradle'
  - 'gradle.properties'
  - 'gradle/**'
  - 'gradlew'
  - 'gradlew.bat'
  - 'config/checkstyle/**'
  - '.github/workflows/*.yml'
  - '.github/actions/**'
fabric:
  - *common
  - 'fabric/**'
neoforge:
  - *common
  - 'neoforge/**'
```

つまり共通コード、ビルド設定、workflow を触ると両方のビルドが走り、`fabric/` だけを触ったときは
`build-neoforge` がスキップされます。`changes` ジョブには `pull-requests: read` を付与しています
（`pull_request` イベントで差分を取得するために API アクセスが必要なため）。

### なぜ `test` は path filter の対象外なのか

テストの実体はローダー非依存の共通コード（ルートの `src/test/java`）にあり、`test` タスクを宣言して
いるのは `fabric/build.gradle` だけです。したがって `:fabric:test` は実質「共通コードのテスト」で
あり、Fabric 固有の変更が無くても常に実行する価値があります。ジョブ名を `Test (shared)` にしているのは
この意図を明示するためです。

`build-fabric` が `-x test` を付けているのは、この `test` ジョブとの二重実行を避けるためです。

### `script-tests` ジョブ

`release.yml` は `scripts/changelog_tool.py --loaders` と `scripts/notify_discord.py` に依存しています。
この依存が壊れた状態でリリースワークフローだけが失敗することを避けるため、CI で Python の構文チェックと
既存の changelog tool unittest を常時実行します。

### `ci-ok` ジョブ

`ci-ok` は `if: always()` で必ず実行され、`needs` に列挙した全ジョブの `result` を走査します。

- `success` / `skipped` → 許容（path filter でスキップされたジョブがあっても成功扱い）
- `failure` / `cancelled` → `exit 1`

branch protection の必須ステータスチェックには、この `CI OK` だけを指定してください。
条件付きで実行されるビルドジョブを直接必須チェックにすると、スキップ時に PR がマージできなくなります。

## Java バージョンの一元管理

Java のバージョンは `gradle.properties` の `java_version` が単一の真実です。

- `build.gradle` は `JavaLanguageVersion.of(rootProject.java_version as int)` で参照します。
- CI は `.github/actions/setup-build`（composite action）経由で `actions/setup-java` を呼びます。
  この action は `gradle.properties` から `java_version` を読み取り、`^[0-9]+$` に一致することを
  検証してから Temurin JDK をセットアップします（不正な値なら即座に失敗します）。

Java を上げるときは `gradle.properties` の 1 行を変えるだけで、ワークフロー側の修正は不要です。

## Action のバージョン固定

サプライチェーン対策として、すべての action は commit SHA で pin し、コメントに元のバージョンタグを
併記しています。

```yaml
- uses: actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803 # v6
```

## 成果物

- `build-artifacts-fabric-<commit sha>`: `fabric/build/libs/*.jar`（sources / dev jar は除外）
- `build-artifacts-neoforge-<commit sha>`: `neoforge/build/libs/*.jar`（sources / dev jar は除外）
- 保持期間: 7 日間

ローダー名を含めることで、両方のビルドが走ったときにアーティファクト名が衝突しないようにしています。

## ローカルでの確認方法

```bash
# CI と同じ検証を一通り実行する
./gradlew spotlessCheck
./gradlew :fabric:test
python3 -m py_compile scripts/changelog_tool.py scripts/notify_discord.py
python3 -m unittest discover scripts/tests
./gradlew :fabric:build -x test
./gradlew :neoforge:build

# 整形だけを適用する
./gradlew format

# lint / formatter を含む品質チェックだけを実行する
./gradlew check
```

現在の Checkstyle 基準は、最低限の整形検証に加えて以下を含みます。

- タブ文字の禁止
- 120 文字の行長制限（`package` / `import` / URL を除外）
- wildcard import の禁止
- `if` などの波括弧必須
- 未使用 / 冗長 import の禁止

## トラブルシューティング

1. **Fabric Loom SNAPSHOT 版が見つからない**
   - 通常は一時的な問題です。時間をおいて再実行してください。
2. **`java_version` の検証で失敗する**
   - `gradle.properties` の `java_version` が数字のみ（例: `25`）になっているか確認してください。
3. **`ci-ok` だけが失敗する**
   - 依存ジョブのどれかが `failure` / `cancelled` です。ログの「依存ジョブの結果」行で内訳を確認できます。
4. **意図したビルドジョブが走らない**
   - `changes` ジョブの出力（fabric / neoforge）を確認してください。path filter の対象外ファイルしか
     変更していない場合はスキップされます。
