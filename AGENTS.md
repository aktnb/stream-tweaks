# Repository Guidelines

## 言語
- 回答は必ず日本語で行ってください。

## プロジェクト概要
- Stream Tweaks は Fabric Loom を用いた Minecraft 1.21.8 向けクライアント専用 Mod です。
- Twitch の OAuth・Helix API・EventSub WebSocket を組み合わせて、Minecraft 内チャットへ Twitch コメントを転送します。
- 現在の実装は `application` / `presentation` / `twitch/*` に責務を分離した構成です。
- 主な依存関係は Fabric API、Gson、Java 21 標準ライブラリ (`java.net.http` など) です。

## ソース構成
- `src/main/java/org/etwas/streamtweaks/mod/StreamTweaks.java`
  - 共通エントリポイントです。`MOD_ID` と共通ロガー、開発時のみ出力する `devLogger` を提供します。
- `src/main/java/org/etwas/streamtweaks/mod/StreamTweaksClient.java`
  - クライアント初期化処理です。認証、API クライアント、EventSub、コマンド登録を配線します。
  - EventSub 通知を受け取り、Minecraft のチャットスレッドへメッセージ表示を戻します。
- `src/main/java/org/etwas/streamtweaks/application/TwitchApplicationService.java`
  - アプリケーションサービス層です。
  - `login` / `connect` / `disconnect` / `logout` を公開し、購読中ログイン名の集合を管理します。
- `src/main/java/org/etwas/streamtweaks/presentation/commands/TwitchCommand.java`
  - `/twitch` クライアントコマンドを実装します。
  - `login` / `connect <login>` / `disconnect <login>` / `logout` を登録します。
- `src/main/java/org/etwas/streamtweaks/twitch/auth`
  - OAuth 認証周辺です。
  - `AuthenticationOrchestrator` が認証開始、state 検証、トークン検証、資格情報保存、ログアウトを管理します。
  - `AuthenticationSession` が OAuth state と認可 URL を生成します。
  - `TwitchCredentialRepository` が資格情報の永続化抽象です。
- `src/main/java/org/etwas/streamtweaks/twitch/auth/infra`
  - `LocalCallbackServer` が `http://localhost:7654/callback` と `/callback/consume` を受けます。
  - `FileTwitchCredentialRepository` が `config/stream-tweaks/twitch-credentials.json` に資格情報を保存します。
  - `TokenValidateViaApi` が Twitch の validate API でトークン検証を行います。
- `src/main/java/org/etwas/streamtweaks/twitch/common`
  - Twitch API 共通モデルと定数です。
  - `Login` / `UserId` / `AccessToken` などの値オブジェクトを持ちます。
- `src/main/java/org/etwas/streamtweaks/twitch/common/infra/TwitchApiClientImpl.java`
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
- 受信した `channel.chat.message` は `StreamTweaksClient` で `[Twitch] <user>: <message>` として表示します。
- Minecraft へのメッセージ表示は `MinecraftClient#execute` でクライアントスレッドへ戻してから行ってください。

## ビルド / 実行 / 品質チェック
- Gradle の主要タスク:
  - `./gradlew build` : コンパイル、テスト、Spotless/Checkstyle を含む検証、JAR 出力
  - `./gradlew test` : JUnit 5 テスト実行
  - `./gradlew check` : 品質チェック実行
  - `./gradlew spotlessApply` または `./gradlew format` : ソース整形
  - `./gradlew runClient` : 開発用クライアント起動
  - `./gradlew runServer` : 開発用サーバー起動
  - `./gradlew genSources` : IDE 向け再マッピング
- `build.gradle` では Java 21 をターゲットにし、JUnit 5 / Mockito / Checkstyle / Spotless を設定しています。
- `config/checkstyle/checkstyle.xml` が Checkstyle 設定です。
- ビルド成果物は `build/libs/`、開発ランタイムは `run/` に出力されます。

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
  - `changes` ジョブが `dorny/paths-filter` で fabric / neoforge に影響する変更を検知します。
  - `format`（`spotlessCheck`）、`test`（`:fabric:test` = 共通コードのテスト）、`script-tests`
    （リリース支援 Python スクリプトの compile / unittest）は常時実行します。
  - `build-fabric`（`:fabric:build -x test`）と `build-neoforge`（`:neoforge:build`）は、該当ローダーに
    影響する変更があるときだけ実行します。
  - `ci-ok` ジョブが全ジョブの結果を集約します。branch protection の必須ステータスチェックはこれ1つです。
  - Java バージョンは `gradle.properties` の `java_version` が単一の真実で、`.github/actions/setup-build`
    （composite action）が読み取って `actions/setup-java` を実行します。matrix ビルドはありません。
- リリースは `.github/workflows/release.yml` の手動実行（`workflow_dispatch`）で行います。
  - 入力は `channel`（`alpha` / `beta` / `release`）と `loaders`（`both` / `fabric` / `neoforge`）です。
    `channel=release` では `loaders=both` のみ指定できます。
  - `mod_version` と対象 Minecraft バージョンブランチを検証し、**ビルド成功後に** annotated tag を push して
    から Modrinth と GitHub Releases へ公開し、最後に Discord へ通知します。
  - changelog は `scripts/changelog_tool.py` を使い、`--loaders` でローダー別ノートを生成します。
  - Discord 通知は `scripts/notify_discord.py` が組み立てます。
- ワークフロー内では `run:` ブロックに `${{ }}` を書かず、必ず `env:` 経由でクオートして参照します。
  action は commit SHA で pin します。
- 補足手順は `docs/ci-build.md` と `docs/release-workflow.md` を参照してください。

## コーディングスタイル
- Java コードは 4 スペースインデント、UTF-8、原則 120 文字以内です。
- パッケージは小文字ドメイン、クラスは PascalCase、メソッド・フィールドは camelCase、定数は UPPER_SNAKE です。
- 値オブジェクトやドメイン状態は既存の `record` / 小粒なクラス構成に合わせてください。
- 依存関係の配線は `StreamTweaksClient` に集約し、ビジネスロジックは `application` / `twitch/*` に寄せてください。
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
