# 固定リリース bundle の準備と検証

Issue #10 の `prepare-new` は **公開しない**準備経路です。通常公開には `release-new` を使い、
同じ workflow run の中で plan/build → GitHub draft 保管 → Environment 承認後の publish を行います。
`release-new` も Release workflow の `release_version` に
`0.3.4-beta.1` のような純 SemVer、`channel` と `loaders` を明示すると、開始時 SHA の固定計画を作り、
その SHA を checkout して全選択 Loader の build/check と共有テストを実行します。
stable の片 Loader も計画できます。旧 `operation=legacy` / mc-publish 経路は削除済みです。
`plan-new` / `plan-reuse` は引き続き計画だけを検証し、`prepare-new` は bundle 作成のみです。
これら2つの操作からは公開 job・Discord 通知を実行しません。

build job の権限は `contents: read`、公開 secret は参照しません。単一 Gradle task graph で共有テストを1回実行し、
全対象の build/check が成功してから `writeReleaseProvenance` が実際の解決結果を採取します。
独立 project の並列実行でも記録が先行しないよう build/test task 後の順序を明示しています。
検証済み通常 JAR、notes、properties、固定 plan、manifest を
`release-bundle-<run_id>-<source_sha>` artifact に30日保存します。`release-new` は artifact 名に run attempt も含めます。
artifact の期限は永続保管の代替ではありません。GitHub draft Release への永続保管は `github-stage` job、
不足先公開と finalize は Environment gate 後の `publish` job の責務です。
実行順序と復旧は[公開・復旧ガイド](release-workflow.md)を参照してください。

## 保存形式と契約

bundle はフラットなディレクトリです。`release-plan.json`、`release-manifest.json`、`gradle.properties`、
選択 Loader の通常 JAR、`notes-combined.md` と `notes-<loader>.md` だけを含めます。
publish-state は bundle の外へ保存し、manifest と JAR は公開の進行に合わせて更新しません。

manifest schema 1 は次の情報を固定します。

- 元の plan 全体：release ID、純 Mod version、MC、Loader 集合、公開元の full SHA、公開先契約。
- 各 JAR の target ID、filename、size、SHA256、SHA512、検証済み metadata・Java major。
- notes と properties の filename、size、SHA256、SHA512。
- compiler toolchain の実 major／完全 runtime／vendor と Gradle daemon JVM の実値、Gradle version。
- 固定 plugin versions、実際に解決した compile/runtime 依存の module・filename・SHA256。
  verification metadata 全件を「解決済み」とは扱いません。
- Wrapper distribution checksum、verification metadata 自体の SHA256、成功した build/check/test task、workflow run。

notes は `changelog_tool.py` の parser と Loader filter を再利用します。stable は一致する版セクション、
alpha/beta は同じ source SHA の Unreleased を使います。CHANGELOG の更新やプレ番号の採番は行いません。
この準備経路は Unreleased 全文を固定し、従来経路の前回 tag 以降の差分採取とは区別します。

`scripts/release_bundle.py` の `create_bundle()` / `verify_bundle()` は後続 adapter から利用できます。
`verify_bundle()` は信頼できる checkout／tag／保存 plan から得た `expected_source_sha` を必須にし、
既知の manifest hash と plan も照合できます。source/dev JAR、metadata・classfile・Mixin の不一致、
改変・不足・余分なファイル・symlink・不正 path を拒否します。失敗時に build・削除・上書きは行いません。
create CLI は HEAD の一致と clean checkout も確認し、既存出力を上書きしません。

## ローカルでの準備

信頼できる clean checkout の full SHA と対応する保存 plan を用意し、plan の version と Loader 集合を使います。
以下は `0.3.3`／両 Loader の例です。出力はソース checkout の外に置きます。

```sh
./gradlew clean :fabric:test :fabric:build :neoforge:build writeReleaseProvenance \
  -I scripts/release-provenance.gradle --dependency-verification=strict \
  -Pmod_version=0.3.3 -PreleaseLoaders=fabric,neoforge \
  -PreleaseProvenanceOutput=/tmp/resolved-provenance.json

python3 scripts/release_bundle.py create --plan /tmp/release-plan.json \
  --source-sha <planと同じ40文字SHA> --resolved-provenance /tmp/resolved-provenance.json \
  --output /tmp/release-bundle --run-id <workflow-run-id> --run-attempt 1

python3 scripts/release_bundle.py verify --bundle /tmp/release-bundle \
  --source-sha <初回と同じ40文字SHA> --plan /tmp/release-plan.json \
  --manifest-sha256 <信頼できる保存先から得たmanifestのSHA256>
```

再開は最後の verify と保存済みバイナリの再利用だけを行います。artifact が消失した場合は停止し、
無検証の再ビルドを公開入力にしません。期限切れ前の永続保管と、元 manifest と同 hash であることを証明した回復は
[公開・復旧手順](release-workflow.md)で扱います。hash 照合は信頼済み manifest との一致確認であり、出所の署名ではありません。
fork PR artifact や自由入力 JSON だけを公開済み承認の根拠にしないでください。
