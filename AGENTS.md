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
- CI は `.github/workflows/build.yml`（Build）の1ワークフローです。
  - すべての PR の作成・コミット追加・再オープン時に、PR の merge commit を検証します。
  - `matrix.loader: [fabric, neoforge]` で両 Loader を並列にビルドします（`fail-fast: false`）。
  - 各 Loader の `assemble` を実行し、Fabric のみ追加ステップで `:fabric:test` を実行します。
  - 表示名は `Fabric` / `NeoForge` とし、共有テストは Fabric 側で1回実行します。
  - `Summary` が両ジョブの成功を確認します。main の必須チェックは導入時に `CI OK` から切り替えます。
  - Format / Checkstyle、Python テスト、成果物アップロード、公開処理は CI に含めません。
  - Java は `.github/actions/setup-build` が `gradle.properties` の `java_version` を読み取ります。
    Gradle cache を再利用し、`--dependency-verification=strict` を明示します。
  - push 単独・定期・手動実行はありません。同じ PR の古い実行は取り消します。
- 旧 Build / Target build / Validate supported targets / Release は
  `.github/workflows/old_*.yml.disabled` に退避し、無効化しています。
  新しい Release の運用を用意するまで公開と全対象定期検証は停止します。
- ワークフロー内では `run:` ブロックに `${{ }}` を書かず、必ず `env:` 経由でクオートして参照します。
  action は commit SHA で pin します。CI の権限は `contents: read` のみです。
- 現在の手順は `docs/ci-build.md`、旧 CI の記録は `docs/old_ci-build.md`、
  停止した旧公開手順は `docs/release-workflow.md` を参照してください。

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
