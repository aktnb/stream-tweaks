# CI ビルドワークフロー

`.github/workflows/build.yml`（**Build**）は PR の作成・コミット追加・再オープン時に実行します。
宛先ブランチや変更パスで絞り込まず、PR の merge commit で Fabric / NeoForge 両方を検証します。

`build` ジョブの `matrix.loader: [fabric, neoforge]` で、別のランナーに展開して並列実行します。
`fail-fast: false` により、一方が失敗しても他方の検証は続行します。
checkout・Java 準備・ビルド手順を共通化し、共有テストのステップは Fabric のみで実行します。

Fabric（ビルドと共有テスト）:

```sh
./gradlew :fabric:assemble --dependency-verification=strict --stacktrace
./gradlew :fabric:test --dependency-verification=strict --stacktrace
```

NeoForge（ビルド）:

```sh
./gradlew :neoforge:assemble --dependency-verification=strict --stacktrace
```

- `assemble` は両 Loader のコンパイルと JAR 作成を行います。
- 共有テストはルート `src/test/java` を使う `:fabric:test` で1回実行します。一部は Minecraft に依存します。
- Format / Checkstyle、Python スクリプトテスト、成果物アップロード、公開処理は CI に含めません。

Java は `.github/actions/setup-build` が `gradle.properties` の `java_version` から選びます。
既存の Gradle キャッシュを再利用し、依存関係は strict モードで検証します。
権限は `contents: read` のみで、公開用 secret は使用しません。

必須チェック名は引き続き **CI OK** です。両ジョブの終了後、成功したことを確認します。
いずれかが失敗・キャンセル・スキップした場合も集約ジョブを実行し、失敗として扱います。
同じ PR に新しいコミットが追加された場合、実行中の古い Build は取り消します。
push 単独・定期実行・手動実行のトリガーはありません。

## 旧ワークフロー

既存の4ワークフローは、内容を保持したまま次のファイルへ退避しています。

- `.github/workflows/old_build.yml.disabled`
- `.github/workflows/old_target-build.yml.disabled`
- `.github/workflows/old_validate-all.yml.disabled`
- `.github/workflows/old_release.yml.disabled`

`.disabled` 拡張子のため GitHub Actions のワークフローとして読み込まれません。
旧 Release と全サポート対象の定期検証も停止しています。
新 Build でも使う `.github/actions/setup-build` は継続利用します。

旧 CI の構成・検証記録は [旧 CI ガイド](old_ci-build.md)、旧公開手順は
[停止中の Release ガイド](release-workflow.md) に残しています。
