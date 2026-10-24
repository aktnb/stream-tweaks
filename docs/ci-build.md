# CI ビルドワークフロー

`.github/workflows/build.yml`（**Build**）が対象の MC と変更のある Loader を選び、
`.github/workflows/target-build.yml` で同じ検証経路を実行します。
[対応台帳とtarget選択](support-targets.md)が対象選択の基準です。

## トリガーと固定するソース

- `main`、数値 MC ブランチ（`26.2` など）、`mc/<数値MC>` への PR / push。
- `workflow_dispatch` の `target` に単一の supported target（例: `26.2-fabric`）を指定。

GitHub の branch glob は広めですが、planner は `validate_source_branch` の allowlist で再検証します。
未登録ブランチ、EOL / unsupported、複数 target 指定は失敗します。
PR は宛先 `base_ref` の catalog を `base.sha` から読み、実際の検証には merge commit の full SHA を使います。
初回導入で宛先に `support-targets.json` が**存在しない場合だけ** merge checkout の catalog に
fallback し、job summary に bootstrap を明示します。不正 JSON・権限エラーなどは fallback しません。
この場合も宛先ブランチと catalog の source branch、検証 checkout の MC / Java の一致を要求します。
push はその checkout の catalog と SHA、dispatch は実行元 catalog で選んだ target の
`source_branch` を API で full SHA に解決します。対象の `gradle.properties` と台帳が異なれば失敗します。

`pull_request` を使用し、`pull_request_target` へ変更しません。権限は `contents: read` と
差分取得用の `pull-requests: read` のみです。公開 secret、write 権限、`secrets: inherit` は使用せず、
checkout の `persist-credentials` も無効です。

## ジョブ構成

| ジョブ | 実行条件 | 内容 |
| --- | --- | --- |
| `changes` | 常時 | path filter と catalog から immutable source / target / matrix を計画 |
| `format` | 有効な計画で常時 | `./gradlew spotlessCheck`。両 Loader と共通 source を検証 |
| `test` (Test (shared)) | 有効な計画で常時 | `./gradlew :fabric:test` を1回 |
| `script-tests` | 有効な計画で常時 | 対象 SHA の Python compile / unittest と Discord payload dry-run |
| `build` | 影響対象がある場合 | target matrix ごとに `./gradlew :<loader>:build -x test` と JAR 検証 |
| `ci-ok` (CI OK) | `always()` | plan / matrix / 全 required job の結果を厳密に集約 |

format、shared test、Loader build は再利用 workflow の `mode` だけを変えて呼び出します。
controller と対象 source を別 checkout とし、対象 source の full SHA を固定します。
shared test は `src/test/java` を Fabric の task で一度実行します。一部は Minecraft に依存するため、
純 Java テストとして扱いません。Loader build から `test` を除外し、重複実行を避けます。
Gradle は各経路で `--dependency-verification=strict` を明示し、verification metadata の生成は行いません。

### path filter と選択 fixture

共通変更は全 supported Loader、`fabric/**` / `neoforge/**` だけの変更は該当 Loader をビルドします。
共通変更は `src/**`、root Gradle 設定・Wrapper、`gradle/**`、`config/checkstyle/**`、
`.github/workflows/**`、`.github/actions/**`、`scripts/**`、`support-targets.json` です。
docs だけの変更では Loader build を省略し、format / shared tests / script tests は実行します。
dispatch は path filter に依存せず、指定した1 target をビルドします。

`scripts/tests/test_ci_targets.py` が共通 / Fabric / NeoForge / docs の job 集合、workflow の
filter 定義、単 target dispatch、branch allowlist、base catalog の優先と bootstrap 条件を検証します。

### CI OK

branch protection の必須 check 名は引き続き **CI OK** です。
`changes` / `format` / `test` / `script-tests` はすべて `success` を要求します。
`build` は plan の matrix が空のときだけ `skipped` を許可し、対象があるときは `success` を要求します。
失敗・取消・予期しない skip・不正 matrix・matrix と job 条件の不一致は失敗です。
単にすべての `skipped` を成功扱いにはしません。matrix 内の必要 target が失敗すると matrix job 自体が失敗します。

## Java と cache

`.github/actions/setup-build` は `properties-path` で対象 checkout の `gradle.properties` を読み、
`java_version` が正の整数としてちょうど1回定義されていることを確認して Temurin JDK を選びます。
既存の `actions/setup-java` Gradle cache を継続し、二重 cache は導入しません。
cache key の入力には対象 checkout の Gradle 設定・properties・Wrapper・verification metadata を含めます。
引数を省略する既存の呼び出しは root checkout を使用します。
各再利用 job は成功・失敗とも target / MC / Loader / full SHA / 実 JDK / mode / 結果を summary に記録します。
cache の exact hit と Gradle 実行秒数も記録します。cache-hit 未報告や Gradle 未開始はそのまま表示し、
部分復元を exact hit と見なしません。時間は Gradle command 部分のみで、checkout / JDK setup を含みません。

外部 action は commit SHA で固定します。同一 repo の再利用 workflow は呼び出し元と同じ commit の
ローカルパスを使います（[GitHub の仕様](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)）。

## ビルド入力の固定と依存検証

26.2 のビルドは Gradle **9.5.1** / Fabric Loom **1.17.21** / ModDevGradle **2.0.142** を使用します。
[Fabric の26.2案内](https://fabricmc.net/2026/06/15/262.html)の Loom 1.17 / Gradle 9.5.1 と、
[NeoForge 26.2 MDK](https://github.com/NeoForgeMDKs/MDK-26.2-ModDevGradle/blob/main/build.gradle)を
確認した上で固定しています。Minecraft / Loader / API は既存の `gradle.properties` の値を維持します。

Wrapper の `distributionSha256Sum` は
[公式配布 checksum](https://services.gradle.org/distributions/gradle-9.5.1-bin.zip.sha256)と照合した
`bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f` です。
Wrapper は未取得の配布 ZIP をこの値で検証します。既存の展開済み Wrapper キャッシュを変更後に
自動再検証するわけではないため、導入時の検証には新規 `GRADLE_USER_HOME` を使います。

`settings.gradle` は可変 plugin version を拒否し、全 project の dependency resolution は
SNAPSHOT / changing module と動的 version を拒否します。JAR はエントリ順序を固定しファイル時刻を
保存しません。これらの設定は CI とローカルの通常 `build` に共通です。

依存更新時は `./gradlew clean build --write-verification-metadata sha256` で checksum 候補を生成し、
配布元・変更差分をレビューしてください。生成コマンドは新しく取得したバイナリを信頼する操作なので、
CI では実行せず、コミット済み `gradle/verification-metadata.xml` に対する通常の strict 検証を使用します。
checksum 不一致時は検証無効化やキャッシュ削除で通さず、配布元の変更を調査します。

検証の例外は `net.minecraft:minecraft-merged-043a8b3edf:26.2` のみです。これは
`LoomLocalMinecraft`（project の `.gradle/loom-cache/minecraftMaven/`）へ Loom がローカル生成する
変換済み Minecraft で、外部 Maven 配布物ではありません。既存キャッシュと新規生成では ZIP 時刻と
class payload が変わることを隔離ビルドで確認したため、生成物の checksum を配布物として固定しません。
group 全体や他バージョンは除外していません。処理名・Minecraft 更新時は例外も再審査してください。

元入力は Loom の `MinecraftProvider` が Mojang version manifest の SHA1 を使って検証します
（[実装](https://github.com/FabricMC/fabric-loom/blob/1.17/src/main/java/net/fabricmc/loom/configuration/providers/minecraft/MinecraftProvider.java)）。
固定した 1.17.21 JAR の bytecode でも client/server 両方の `DownloadBuilder.sha1` 呼出しを確認しました。
今回、既存/新規 cache の元 client と server はそれぞれ manifest の
`2dc72797acbc1b63fc16a11c4ac393605f453754` / `823e2250d24b3ddac457a60c92a6a941943fcd6a`
と実測一致しました。Gradle plugin・外部 Maven 依存は通常の SHA256 検証対象のままです。

Java の major は `java_version` で管理します。セキュリティ更新を妨げないため開発・CIでの patch 自動更新は
許容しますが、同一バイナリの再構築を比較する際は vendor / 完全な `java -version` と OS も記録し、同じ JDK を
使用します。公開時は生成済み JAR を保存して再利用し、JDK patch が違う再ビルドを同一バイナリとみなしません。

### 26.2 再構築の検証記録（2026-10-24）

環境: macOS 26.5.1 arm64 / Oracle JDK `25.0.3+9-LTS-195` / Gradle 9.5.1。
変更前の `./gradlew build` は成功。変更後は両 Loader の `clean build` が成功し、共有 JUnit は
286 件成功（failure/error/skipped は各0）、Checkstyle / Spotless も成功しました。

さらに同じ178入力ファイルを別ディレクトリ A/B にコピーし、専用の新規 Gradle home で A を、
その cache を使って B を、それぞれ `clean build --no-daemon --no-configuration-cache` で実行しました。
いずれも checksum 生成・検証無効化を指定せず strict 検証を通過しました（A: 5分5秒、B: 11秒）。
初回のローカル生成 Minecraft hash 不一致は上記の exact-coordinate 例外で解消しました。
元入力を変えず、両通常 JAR の SHA256 とサイズが一致しています。

| 通常 JAR | bytes | A/B 共通 SHA256 |
| --- | ---: | --- |
| `stream-tweaks-fabric-mc26.2-0.3.3.jar` | 2015241 | `2d124803e7d5ce4580c809853d1c91a75028d47edd74ccdc3d840a067c8bad1a` |
| `stream-tweaks-neoforge-mc26.2-0.3.3.jar` | 1631394 | `a17c46463bd6bd2b2919711362655a28cdf9d3ffb3d8e765e51def8d83092d79` |

検証対象は HEAD `5de2947dc1108730a655db5010d7253041cd4528` に #3–#6 の作業差分と
#15 の調査文書を加えた固定 snapshot です。
入力の相対パス順に `UTF-8(path) + NUL + bytes` を連結した SHA256 は
`5c14e202a8cdf298a5cebe9e6a74ef346fa5f7e8c792be342ef61918daaf3751` でした
（生成された `.gradle` / `build` / `logs` を除く）。この比較は同じ OS/JDK の実測で、異なる JDK/OS までの
byte-for-byte 再現性を保証しません。クライアント起動 / Mixin 適用の smoke 確認も別途必要です。

負の検証として plugin `1-SNAPSHOT`、依存 `gson:2.+`、`changing=true` を各 fixture で拒否し、
Loom JAR の checksum を故意に変えた fixture は warm cache でも strict 検証で失敗することを確認しました。

## 成果物

`build-artifacts-<MC>-<loader>-<full source SHA>` に通常 JAR と `artifact.json` を7日間保存します。
`scripts/verify_artifacts.py` が期待するファイル名を厳密に選び、metadata / classfile / Mixin / 配布依存を
検証してから upload します。sources / dev JAR は公開用として選択しません。
`artifact.json` には対象、ファイル名、サイズ、SHA256 / SHA512 を記録します。

## ローカルでの確認方法

```bash
# CI と同じ検証を一通り実行する
./gradlew spotlessCheck
./gradlew :fabric:test
python3 -m compileall -q scripts
python3 -m unittest discover scripts/tests
./gradlew :fabric:build -x test
./gradlew :neoforge:build -x test

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

1. **固定した Fabric Loom / 依存が見つからない**
   - 公式配布元の状態と checksum を確認してください。SNAPSHOT や検証無効化への切替は行わないでください。
2. **`java_version` の検証で失敗する**
   - `gradle.properties` の `java_version` が数字のみ（例: `25`）になっているか確認してください。
3. **`ci-ok` だけが失敗する**
   - 失敗・取消・予期しない skip、または plan / matrix の不整合です。集約ログに対象 job と期待値が表示されます。
4. **意図したビルドジョブが走らない**
   - `changes` ジョブの plan / matrix と宛先 catalog を確認してください。docs だけなら build の skip は正常です。

## 全サポート対象の定期・手動検証

`.github/workflows/validate-all.yml`（**Validate supported targets**）は毎週月曜 03:23 UTC と手動で実行します。
main 導入前の台帳権威元は `26.2` です。workflow がその default branch に取り込まれた後、次の操作を使います。

```bash
# 台帳の全 supported 行
gh workflow run validate-all.yml --ref 26.2 -f targets=all

# 明示した subset。単一 target も同じ resolver を使う
gh workflow run validate-all.yml --ref 26.2 -f targets=26.2-fabric,26.2-neoforge
gh workflow run validate-all.yml --ref 26.2 -f targets=26.2-fabric
```

main 移行時は台帳の `authority_branch` と実際の default / 実行 ref を main へ揃えます。
権威元以外からの実行、未知 / 重複 / 検証中 / 未対応 / EOL target の明示指定は拒否します。
`all` では supported かつ Active / Maintenance の行だけを選びます。

schedule と dispatch はいずれも `scripts/resolve_targets.py` を実行します。完全な台帳を検証してから
選択を行い、allowlist 済み source branch を**一度ずつ** full SHA へ解決します。
すべての解決が成功した後だけ matrix を出力し、`resolved-targets.json` を
`validation-plan-<run ID>-<attempt>` artifact として30日間保存します。
実行途中で branch が進んでも、checkout は保存した SHA のままです。

format / shared tests / Python tests は source ごとに1回、Loader build は選択した MC × Loader ごとに
実行します。format / shared tests / build は PR・単 target CI と同じ `target-build.yml` を使用し、
対象 checkout の properties / Wrapper / verification metadata と JDK を使います。
全 job が成功しなければ **All supported targets OK** は失敗します。取消・skip も成功にはしません。

summary の先頭に MC / Loader / source branch / 固定 SHA / 予定 JDK を、各 Target build の summary に
実 JDK、job / Gradle 結果、setup-java の cache-hit、Gradle 秒数を記録します。
cache-hit が未報告の場合は `not reported` とし、hit と推測しません。Gradle 秒数には JDK setup や
runner 待ち時間を含めないため、全体時間は Actions の job 時間と区別してください。
cache は既存 setup-java の Gradle cache を共用し、別の cache action を追加していません。

**これは各 branch HEAD の健全性検証です。PR 修正を Maintenance へ backport した証明にはなりません。**
backport は対象別 PR / `cherry-pick -x` と、その変更 SHA に対する CI / 必要な smoke を別途確認します。

導入時のローカル fixture は2 MC × 2 Loader で source 解決2回・shared test group 2つ・build 4行を確認し、
途中の HEAD 更新で SHA が変わらないこと、明示 subset と不正 target の拒否を確認しています。
GitHub runner 上では、2026-10-24 UTC に main 移行前の先行確認として `26.2` から次の dispatch を実行しました。

| 選択 / Run | 対象 / JDK | 固定 source SHA | 結果 | Cache exact hit / Gradle 秒数 |
| --- | --- | --- | --- | --- |
| `all` / [40096170430](https://github.com/aktnb/stream-tweaks/actions/runs/40096170430) | 26.2 Fabric + NeoForge / 25 | `965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d` | workflow 全 job 成功 | 各 Gradle job `true`; format 14 / shared tests 63 / Fabric 74 / NeoForge 61 |
| `26.2-fabric` / [40096370197](https://github.com/aktnb/stream-tweaks/actions/runs/40096370197) | 26.2 Fabric / 25 | `965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d` | workflow 全 job 成功 | 各 Gradle job `true`; format 15 / shared tests 79 / Fabric 51 |

Artifact 名は次のとおりです。

- `all`: `validation-plan-40096170430-1`、`build-artifacts-26.2-fabric-965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d`、`build-artifacts-26.2-neoforge-965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d`
- `26.2-fabric`: `validation-plan-40096370197-1`、`build-artifacts-26.2-fabric-965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d`

上記は Actions job log の実測値です。対象 checkout と plan artifact の source SHA は全 job で一致しました。
schedule の cron 設定と dispatch は同じ `resolve` job を使いますが、scheduled event 自体の実行はまだ確認していません。
上の26.2再構築記録の cold / warm 時間はローカル隔離ビルドの値であり、この workflow の runner 計測とは別です。
