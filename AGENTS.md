# Repository Guidelines

## 言語
- 回答は必ず日本語で行ってください。

## プロジェクト概要
- Stream Tweaks は Minecraft 26.2 / Java 25 向けクライアント専用 Mod です（実際の値は `gradle.properties`）。
- `settings.gradle` は `fabric` / `neoforge` の2サブプロジェクトを含み、Fabric Loom / ModDevGradle を使います。
- Twitch の OAuth・Helix API・EventSub WebSocket を組み合わせて、Minecraft 内チャットへ Twitch コメントを転送します。
- 現在の実装は `application` / `presentation` / `twitch/*` に責務を分離した構成です。
- 主な依存関係は Fabric API / NeoForge、Gson、NightConfig、Java 標準ライブラリ (`java.net.http` など) です。
- 対応・保守の ADR は `docs/support-policy.md`、公開契約・版番号の ADR は `docs/versioning.md` を参照してください。
- Active 1 MC + Maintenance 最大1 MC、Loader 別ブランチなし。台帳は main を唯一の Active 公開元に予約する移行準備中です。
- main 未作成・default 未切替の段階では新規公開を停止し、準備 PR は `26.2` 宛てにします。実際の状態と merge gate は `docs/main-migration.md` を参照してください。
- feature / backport ブランチから対象別 PR / CI を使い、共通修正の移植は `cherry-pick -x` で追跡します。
- ルート共通 sourceSet にも MC 依存コードがあります。MC 非依存業務ロジックと区別してください。

## ソース構成
- `src/main/java/org/etwas/streamtweaks/mod/StreamTweaksCommon.java`
  - 共通の `MOD_ID` と `LOGGER` を提供します。
- `src/main/java/org/etwas/streamtweaks/mod/TwitchClientBootstrap.java`
  - 認証、API クライアント、EventSub、チャット表示、終了処理、コマンド登録を配線する共通コンポジションルートです。
- `src/main/java/org/etwas/streamtweaks/mod/platform/ClientPlatform.java`, `TwitchCommandRegistrar.java`
  - 設定ディレクトリ、クライアントスレッド・ライフサイクル、コマンド登録の Loader 境界です。
- `fabric/src/main/java/org/etwas/streamtweaks/mod/StreamTweaks.java`, `StreamTweaksClient.java`
  - Fabric エントリポイントです。Fabric 実装は同ディレクトリの `fabric/` にあります。
- `neoforge/src/main/java/org/etwas/streamtweaks/mod/neoforge/NeoForgeStreamTweaksClient.java`
  - NeoForge のクライアント専用エントリポイントです。platform / command 実装も同パッケージにあります。
- `fabric/src/main/resources/fabric.mod.json`, `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
  - 各 Loader の Mod metadata です。
- `src/main/java/org/etwas/streamtweaks/application/TwitchApplicationService.java`
  - アプリケーションサービス層です。
  - `login` / `connect` / `disconnect` / `logout` を公開し、購読中ログイン名の集合を管理します。
- `src/main/java/org/etwas/streamtweaks/presentation/commands/TwitchCommand.java`
  - `/twitch` クライアントコマンドを実装します。
  - `login` / `connect <login>` / `disconnect <login>` / `logout` / `config` を登録します。
- `src/main/java/org/etwas/streamtweaks/twitch/auth`
  - OAuth 認証周辺です。
  - `AuthenticationOrchestrator` が認証開始、state 検証、トークン検証、資格情報保存、ログアウトを管理します。
  - `AuthenticationSession` が OAuth state と認可 URL を生成します。
  - `TwitchCredentialRepository` が資格情報の永続化抽象です。
- `src/main/java/org/etwas/streamtweaks/twitch/auth/infra`
  - `LocalCallbackServer` が `http://localhost:7654/callback` と `/callback/consume` を受けます。
  - `FileTwitchCredentialRepository` が `config/stream-tweaks/twitch-credentials.json` に資格情報を保存します。
  - `TokenValidateViaApi` が Twitch の validate API でトークン検証を行います。
- `src/main/java/org/etwas/streamtweaks/twitch/core`
  - Twitch API 共通モデルと定数です。
  - `Login` / `UserId` などの値オブジェクトを持ちます。`AccessToken` は `twitch/auth` にあります。
- `src/main/java/org/etwas/streamtweaks/twitch/core/infra/TwitchApiClientImpl.java`
  - Helix `Get Users` を呼び出し、ログイン名から `UserId` を解決します。
  - 認証切れ時は再ログインを促す例外を返します。
- `src/main/java/org/etwas/streamtweaks/twitch/subscription`
  - EventSub 購読調停の中心です。
  - `EventSubOrchestrator` が desired/actual 購読状態と再接続時の再同期を管理します。
  - `EventSubConnectionCoordinator` が WebSocket 接続状態と `SessionId` を管理します。
  - `SubscriptionReconciler` が desired と actual の差分から作成・削除 API を実行します。
- `src/main/java/org/etwas/streamtweaks/twitch/subscription/domain`
  - `DesiredSubscriptionStore`、`ActualSubscriptionRegistry`、`ConnectionStateHolder` などの状態保持クラス群です。
- `src/main/java/org/etwas/streamtweaks/twitch/subscription/api`
  - EventSub API 抽象です。`TwitchEventSubApi` と `EventSubSubscription` を定義します。
- `src/main/java/org/etwas/streamtweaks/twitch/subscription/infra`
  - `TwitchEventSubApiImpl` が EventSub 購読作成・削除を実装します。
  - `EventSubWebSocketClientImpl` が WebSocket 接続、keepalive 監視、`session_reconnect`、`revocation` を処理します。
- `src/main/java/org/etwas/streamtweaks/twitch/subscription/event/ChatMessageNotification.java`
  - `channel.chat.message` 通知 JSON のデシリアライズ用 DTO です。
- `src/main/resources/assets/stream-tweaks/lang/*.json`
  - ユーザー向けメッセージ定義です。日本語文言の更新時は `ja_jp.json` と `en_us.json` を揃えてください。
- `src/main/resources/html/callback.html`, `callback-consume.html`
  - OAuth コールバック完了ページです。
- `scripts/changelog_tool.py`
  - タグリリース時の changelog 整形と安定版セクション更新に使います。

## Twitch コマンドの挙動
- `/twitch login`
  - OAuth 認証を開始します。クリック可能な URL をチャットへ表示します。
- `/twitch connect <login>`
  - 指定ログイン名のチャンネルへ接続します。
  - 未認証時は `/twitch login` を促すエラーメッセージを返します。
- `/twitch disconnect <login>`
  - 指定ログイン名のチャンネル購読を解除します。
  - 候補補完は `TwitchApplicationService#getSubscribedLogins()` を使います。
- `/twitch logout`
  - 全購読を解除してから資格情報を削除します。
- `/twitch config`
  - クライアント設定画面を開きます。
- 非同期処理の完了・失敗は Minecraft チャットへ翻訳済みメッセージで通知します。

## 認証と資格情報
- OAuth リダイレクト URI は `http://localhost:7654/callback` 固定です。
- `AuthenticationOrchestrator` は `user:read:chat` スコープで認証を開始し、state 検証に失敗したコールバックを拒否します。
- トークンは `TokenValidateViaApi` で検証した後に保存します。
- 資格情報は `config/stream-tweaks/twitch-credentials.json` に保存されます。リポジトリへ含めてはいけません。
- `FileTwitchCredentialRepository` は一時ファイル経由で保存し、POSIX 環境では 600 相当の権限設定を試みます。
- ログアウト時はまず全購読を解除し、その後に資格情報を削除します。

## EventSub / チャット処理
- 購読対象は `DesiredSubscriptionStore` に保持し、実際に Twitch 側へ存在する購読は `ActualSubscriptionRegistry` で管理します。
- `EventSubOrchestrator#subscribe` は必要時のみ WebSocket 接続を確立し、接続後に購読差分を reconcile します。
- WebSocket 切断・再接続時は actual 状態を破棄し、新しい `SessionId` で再同期します。
- `revocation` 受信時は該当購読を registry から除去し、接続中であれば再購読を試みます。
- 受信通知は `presentation/chat/twitch/EventSubNotificationRouter` から各 presenter に渡し、チャット表示・削除を処理します。
- Minecraft へのメッセージ表示は `ClientPlatform#executeOnClientThread` / `Minecraft#execute` でクライアントスレッドへ戻してください。

## ビルド / 実行 / 品質チェック
- Gradle の主要タスク:
  - `./gradlew build` : コンパイル、テスト、Spotless/Checkstyle を含む検証、JAR 出力
  - `./gradlew :fabric:test` : ルート `src/test/java` の JUnit 5 テストを1回実行（一部は MC 依存）
  - `./gradlew check` : 品質チェック実行
  - `./gradlew spotlessApply` または `./gradlew format` : ソース整形
  - `./gradlew :fabric:runClient` / `./gradlew :neoforge:runClient` : Loader 別開発用クライアント起動
  - `./gradlew :fabric:genSources` : Fabric の IDE 向けソース生成
- Java toolchain は `gradle.properties` の `java_version`（現行25）を参照し、JUnit 5 / Mockito / Checkstyle / Spotless を使います。
- クライアント専用です。開発手順としてサーバー起動は案内しません。
- `config/checkstyle/checkstyle.xml` が Checkstyle 設定です。
- ビルド成果物は `fabric/build/libs/` / `neoforge/build/libs/`、開発ランタイムは `run/fabric/` / `run/neoforge/` です。

## テスト
- 既存のユニットテストは `src/test/java` にあります。
  - `application/TwitchApplicationServiceTest`
  - `twitch/auth/AuthenticationOrchestratorTest`
  - `twitch/auth/infra/LocalCallbackServerTest`
  - `twitch/subscription/EventSubOrchestratorTest`
- 新しいテストは JUnit 5 を使い、対象クラスに対応する名前で追加してください。
- 変更前後で少なくとも `./gradlew build` を実行して確認してください。

## CI / リリース
- CI は `.github/workflows/build.yml` で実行されます。
  - `main` / 数値 MC ブランチ / `mc/<数値MC>` の PR・push と、単一 supported target を指定する
    `workflow_dispatch` に対応します。`changes` が path filter と catalog から full source SHA・対象 matrix を固定します。
  - PR は宛先 `base.sha` の catalog と merge SHA を使います。宛先 catalog が不存在の場合だけ導入用に merge 版へ
    fallback し、summary に明示します。不正 catalog や権限エラーでの fallback は禁止します。
  - 有効な計画では `format`（全体 `spotlessCheck`）、`test`（`:fabric:test` を1回）、`script-tests`
    （Python compile / unittest と通知 dry-run）を変更パスによらず実行します。共有テストには MC 依存テストも含みます。
  - `build` は共通変更で全 supported Loader、Loader 固有変更で該当 Loader の matrix を実行します。
    docs のみなら build は省略し、dispatch は指定 target のみをビルドします。
  - format / test / build は `.github/workflows/target-build.yml` を再利用します。Loader build は
    `:<loader>:build -x test` と通常 JAR 検証を行い、target と full source SHA を含む名で artifact を保存します。
  - `ci-ok`（表示名 `CI OK`）が plan / matrix と必要 job を厳密に集約します。build 対象が空の場合だけその skip を許可し、
    不正 matrix・失敗・取消・予期しない skip は失敗します。唯一の必須 check 方針は維持します。
  - Java は対象 checkout の `gradle.properties` の `java_version` を `.github/actions/setup-build` が読み取ります。
    既存の setup-java Gradle cache を使い、各 Gradle 経路で strict dependency verification を明示します。
    reusable job の summary には target / SHA / 実 JDK / 結果 / cache exact hit / Gradle 実行秒数を記録します。
- リリースは `.github/workflows/release.yml` の手動実行（`workflow_dispatch`）で行います。
  - `release_version` に alpha/beta の番号を含む純 SemVer を明示し、`channel` と `loaders` を固定します。
    supported であれば stable 単 Loader も許可します。公開後の Loader 追加は新 PATCH とします。
  - `plan-new` / `plan-reuse` は公開なしの計画検証、`prepare-new` は read-only build/check と不変 bundle 生成です。
  - 成功 prepare の run ID / full source SHA / manifest SHA256 で `github-stage` を実行し、
    annotated tag と draft に同じ bundle を保管・再照合します。既存一致は再利用し、相違は停止します。
  - `github-resume` は保存済み bundle の復元・照合、`publish` は不足先を照合・補完して全必須先成功後に finalize します。
    再開は保存 plan を使い、移行後も `release_source_branch` と SHA / target 集合を変えません。
  - attempt / verified receipt と外部 ID は別 publish-state に記録します。応答喪失時に成功を証明できなければ停止し、
    tag / Release / version の削除・上書きや無検証の再ビルドで復旧しません。旧 mc-publish 経路は使用しません。
  - changelog は `scripts/changelog_tool.py --loaders` を再利用します。Discord は finalize 成功後だけ通知し、
    `DISCORD_WEBHOOK_URL` 未設定なら通知を省略します。具体的手順・必要権限は `docs/release-workflow.md` を参照してください。
- ワークフロー内では `run:` ブロックに `${{ }}` を書かず、必ず `env:` 経由でクオートして参照します。
  action は commit SHA で pin します。
- 補足手順は `docs/ci-build.md` と `docs/release-workflow.md` を参照してください。

## コーディングスタイル
- Java コードは 4 スペースインデント、UTF-8、原則 120 文字以内です。
- パッケージは小文字ドメイン、クラスは PascalCase、メソッド・フィールドは camelCase、定数は UPPER_SNAKE です。
- 値オブジェクトやドメイン状態は既存の `record` / 小粒なクラス構成に合わせてください。
- 依存関係の配線は `TwitchClientBootstrap` に集約し、ビジネスロジックは `application` / `twitch/*` に寄せてください。
- 使わない mixin は `stream-tweaks.mixins.json` に追加しないこと。
- ユーザー向け文言は既存の翻訳キーを再利用し、必要なら `ja_jp.json` と `en_us.json` を同時更新してください。

## セキュリティ / ログ方針
- アクセストークン、ユーザー ID、Webhook URL などの機密情報をログへ直接出力しないでください。
- OAuth callback や EventSub の外部入力は、既存実装と同様にフォーマットと必須項目を検証してください。
- `session_reconnect` の URL は `wss://eventsub.wss.twitch.tv` のみ許可する既存方針を維持してください。
- 認証・購読まわりの例外メッセージ変更時は、内部情報をユーザー向けに漏らしすぎないようにしてください。

## 開発メモ
- `TwitchApplicationService` はログイン状態と購読中ログイン名をまたぐため、状態変更の順序に注意してください。
- `EventSubConnectionCoordinator` は接続状態遷移を握っているため、接続・切断まわりの仕様変更は `pendingConnect` と state 遷移の整合を確認してください。
- `EventSubWebSocketClientImpl` は keepalive タイマー用に `ScheduledExecutorService` を持っています。長寿命オブジェクトとしての後始末や再接続時の副作用に注意してください。
- Minecraft 側 UI へ触る処理は常にクライアントスレッドへ戻してください。
