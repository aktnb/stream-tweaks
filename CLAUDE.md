# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## ブランチ戦略

### 重要: バージョンブランチへの直接 push 禁止
- `1.21.8` などのバージョンブランチには**直接 push しないこと**
- 必ず feature ブランチを切り、Pull Request 経由でマージすること
- feature ブランチの命名例: `feature/xxx`, `fix/xxx`

```bash
# 作業ブランチを作成してから作業する
git checkout -b feature/your-feature-name

# 作業完了後は PR を作成してマージ
gh pr create
```

## 開発コマンド

このプロジェクトは Fabric / NeoForge のマルチローダー構成（Gradleマルチプロジェクト）。ローダー非依存のコードはルートの `src/main/java` に置かれ、`fabric/` と `neoforge/` の各サブプロジェクトがそれを参照しつつ、自分に不要なローダー固有ファイルを `exclude` している（`common/` ディレクトリは骨組みのみで未使用）。テストはローダー非依存のためルートの `src/test/java` に置き、`test` タスクを持つのは `fabric` サブプロジェクトのみ（`:fabric:test` が実質「共通コードのテスト」）。

### ビルド
```bash
# 全サブプロジェクトをビルドする
gradle build

# 依存関係を更新
gradle --refresh-dependencies build
```

### 実行とテスト
```bash
# Fabricクライアントで実行
gradle :fabric:runClient

# NeoForgeクライアントで実行
gradle :neoforge:runClient

# Fabric用にソースを生成してリマップ
gradle :fabric:genSources
```

> クライアント専用モッドのため、サーバー実行タスク（`runServer`）は定義されていない。

## プロジェクト構造

### プロジェクト情報
- **グループID**: `org.etwas.streamtweaks`
- **モジュール名**: `stream-tweaks`（`fabric/`, `neoforge/` の2アーティファクトをビルド）
- **バージョン**: `gradle.properties` の `mod_version` を参照（頻繁に変わるためこのファイルには固定値を書かない）
- **環境**: `client` (クライアント専用モッド)

### モッド基本構造（マルチローダー）
- **共通コンポジションルート**: `org.etwas.streamtweaks.mod.StreamTweaksCommon`, `org.etwas.streamtweaks.mod.TwitchClientBootstrap`（ローダー非依存の初期化ロジック）
- **`ClientPlatform`**: 設定ディレクトリ取得などローダー差異を吸収するインターフェース（`org.etwas.streamtweaks.mod.platform.ClientPlatform`）
  - Fabric実装: `org.etwas.streamtweaks.mod.fabric.FabricClientPlatform`（`FabricLoader.getInstance().getConfigDir()`）
  - NeoForge実装: `org.etwas.streamtweaks.mod.neoforge.NeoForgeClientPlatform`（`FMLPaths.CONFIGDIR.get()`）
- **Fabric専用エントリポイント**: `StreamTweaks`（main）/ `StreamTweaksClient`（client）/ `ModMenuIntegration`（modmenu）、いずれも `org.etwas.streamtweaks.mod` 直下
  - コマンド登録: `org.etwas.streamtweaks.mod.fabric.FabricTwitchCommandRegistrar`
- **NeoForge専用エントリポイント**: `org.etwas.streamtweaks.mod.neoforge` パッケージの `NeoForgeStreamTweaksClient`, `NeoForgeStreamTweaksClientEvents`, `NeoForgeTwitchCommandRegistrar`
- **設定ファイル**: `fabric.mod.json`（Fabric）/ `META-INF/neoforge.mods.toml`（NeoForge）
- **Mixinファイル**: `stream-tweaks.mixins.json`（パッケージは `org.etwas.streamtweaks.presentation.mixin`、`compatibilityLevel: JAVA_25`）。`ChatComponentAccessor`, `FontManagerAccessor`, `FontStorageAccessor`, `FontStorageMixin`, `MutableComponentMixin` を実装済み（空ではない）

### 認証システム (twitch.auth パッケージ)
- **AuthenticationOrchestrator**: 認証プロセス全体を統括するオーケストレーター
  - `startAuthentication(Consumer<URI> onAuthUrlReady)` - OAuth2認証フローを開始
  - トークン検証、認証情報の永続化、コールバックサーバーの管理を統合
- **AuthenticationSession**: 認証セッション管理（state検証、認証URL生成）
- **AuthenticationState**: 認証状態の管理（IDLE、WAITING_FOR_CALLBACK、AUTHENTICATED、等）
- **AuthenticationResult**: 認証結果（SUCCESS、FAILURE、TIMEOUT、ALREADY_IN_PROGRESS）
- **TokenValidator**: アクセストークン検証インターフェース
  - `TokenValidateViaApi`（`twitch.auth.infra`）: Twitch API経由でのトークン検証実装
- **TwitchCredentialRepository**: 認証情報リポジトリインターフェース
  - `FileTwitchCredentialRepository`（`twitch.auth.infra`）: ファイルベースの認証情報永続化実装
- **TwitchCredential**: 認証情報エンティティ（token、scopes、userId、login）
- **CallbackServer**: OAuth2コールバックサーバーインターフェース
  - `LocalCallbackServer`（`twitch.auth.infra`）: ローカルHTTPサーバー実装（ポート **7654**、リダイレクトURIは `twitch.core.TwitchConstants`）
- **OAuthCallback**: OAuth2コールバック情報（code、state、token）

### サブスクリプションシステム (twitch.subscription パッケージ)
- **EventSubOrchestrator**: EventSub購読の薄いFacade（ルートパッケージ `twitch.subscription`）
  - `subscribe(broadcasterId)` - チャンネルのchat messageイベント購読
  - `unsubscribe(broadcasterId)` - チャンネル購読解除
  - `unsubscribeAll()` - 全チャンネル購読解除
  - WebSocketイベント受け口（handleWebSocketDisconnected/Reconnected/Revocation）
- **EventSubConnectionCoordinator**: WebSocket接続ライフサイクル管理
  - `ensureConnected()` - 接続確立（DISCONNECTED/CONNECTING/CONNECTED状態管理）
  - `onDisconnected()` - 切断時にactualを全クリアしてDISCONNECTEDへ遷移
  - `onReconnected(sessionId)` - 再接続時にactualを全クリアしてreconcileを起動
  - `reconcileIfConnected()` - 接続済みなら差分同期、未接続なら即完了
- **SubscriptionReconciler**: desired/actual差分同期エンジン
  - `reconcile(sessionId)` - desired-actualの差分を計算してAPI呼び出しを実行

#### domain/ サブパッケージ (`twitch.subscription.domain`)
- **DesiredSubscriptionStore**: ユーザーが購読したい配信者ID×イベントタイプのセット（desired state）
- **ActualSubscriptionRegistry**: 現在のセッションで実際に作成済みの購読マップ（actual state）
  - `clearAndSnapshot()` - セッション切替時に全エントリをクリア
  - `deregisterBySubscriptionId(subscriptionId)` - revocation時の逆引き削除
- **ConnectionStateHolder**: WebSocketセッション状態（DISCONNECTED/CONNECTING/CONNECTED）とSessionIdを原子的に保持
- **SubscriptionId**: サブスクリプションIDの値オブジェクト
- **SubscriptionKey**: 配信者ID×イベントタイプの複合キー（desired/actualの差分計算に使用）
- **EventSubSessionState**: WebSocketセッション状態enum（DISCONNECTED、CONNECTING、CONNECTED）

#### websocket/ サブパッケージ (`twitch.subscription.websocket`)
- **EventSubWebSocketClient**: WebSocket接続クライアントインターフェース
- **SessionId**: EventSubセッションID

#### api/ サブパッケージ (`twitch.subscription.api`)
- **TwitchEventSubApi**: EventSub REST APIクライアントインターフェース
- **EventSubSubscription**: サブスクリプションエンティティ（id、status、type、version、condition、transport）

#### event/ サブパッケージ (`twitch.subscription.event`)
- **ChatMessageNotification**: channel.chat.messageイベントの通知データ構造
- **ChatMessageDeleteNotification**: channel.chat.message_deleteイベントの通知データ構造（モデレーターによる個別メッセージ削除をMinecraftチャットに反映するために使用）
- **EventSubEventType**: 購読対象イベントタイプのenum

#### infra/ サブパッケージ (`twitch.subscription.infra`)
- **EventSubWebSocketClientImpl**: WebSocket実装クラス
  - `wss://eventsub.wss.twitch.tv/ws` への接続
  - Welcome、Keepalive、Notification、Reconnect、Revocationメッセージ処理
  - 自動再接続機能（reconnect_url対応）
- **TwitchEventSubApiImpl**: REST API実装クラス
  - サブスクリプション作成・削除・一覧取得
  - ユーザー名からユーザーID取得

### コマンドシステム (`org.etwas.streamtweaks.presentation.commands` パッケージ)
- **TwitchCommand**: Twitchコマンドの登録と処理（Fabric/NeoForge共通、各ローダーの `*TwitchCommandRegistrar` から呼び出される）
  - `/twitch login` - Twitch認証を開始（ブラウザで認証URLを開く）
  - `/twitch connect <login>` - 指定チャンネルのchat message / message_deleteイベントを購読
  - `/twitch disconnect <login>` - 指定チャンネルの購読を解除
  - `/twitch logout` - ログアウトし、購読中の全チャンネルを自動disconnect

### 技術スタック
- **Minecraft**: 26.2（Mojang公式マッピング / Mojmap。Yarnではない）
- **対応ローダー**: Fabric / NeoForge
- **Fabric Loader**: 0.19.3+
- **Fabric API**: 0.155.2+26.2
- **NeoForge**: 26.2.0.25-beta+
- **Java 25**（`gradle.properties` の `java_version` が単一の真実。`build.gradle` の toolchain も CI の `.github/actions/setup-build` もこの値を参照するので、Java を上げるときはこの1行だけを変える）
- **Gson**: 2.13.2 (JSON処理)
- **CompletableFuture**: 非同期処理
- **HttpClient**: HTTP通信 (Java標準)
- **WebSocket**: WebSocket通信 (Java標準)

### 設定とビルド
- **Gradle**: マルチプロジェクト構成。`net.fabricmc.fabric-loom` 1.16-SNAPSHOT（fabricサブプロジェクト）、`net.neoforged.moddev` 2.0.142（neoforgeサブプロジェクト）
- **設定ディレクトリ**: `ClientPlatform` 経由で取得（Fabric: `FabricLoader.getInstance().getConfigDir()` / NeoForge: `FMLPaths.CONFIGDIR.get()`）、`MOD_ID` サブディレクトリを解決
- **認証ファイル**: `twitch-credentials.json` (設定ディレクトリに保存)
- **Mixinパッケージ**: `org.etwas.streamtweaks.presentation.mixin` (`compatibilityLevel: JAVA_25`)

## 重要な設計パターン
- **レイヤードアーキテクチャ**: ドメインロジック、インフラストラクチャ、コマンドを分離
- **オーケストレーションパターン**: AuthenticationOrchestratorとEventSubOrchestratorで複雑なフローを統括
- **リポジトリパターン**: TwitchCredentialRepositoryで永続化を抽象化
- **ステートパターン**: AuthenticationStateとEventSubSessionStateで状態管理
- **非同期処理**: CompletableFutureで非同期I/O処理
- **差分同期（Reconciliation）**: SubscriptionReconcilerがdesired/actualの差分を計算してAPI呼び出しを冪等に実行
- **依存性注入**: コンストラクタインジェクションで疎結合を実現
- **自動再接続**: WebSocket切断時の自動再接続とreconcileによる購読の再確立

## 使用例
```java
// 認証とEventSub購読の例
// 依存関係の構築
CallbackServer callbackServer = new LocalCallbackServer();
TwitchCredentialRepository credentialRepository = new FileTwitchCredentialRepository(configDir);
TokenValidator tokenValidator = new TokenValidateViaApi();
AuthenticationOrchestrator authOrchestrator = new AuthenticationOrchestrator(
    callbackServer, credentialRepository, tokenValidator);

EventSubWebSocketClient webSocketClient = new EventSubWebSocketClientImpl();
TwitchEventSubApi eventSubApi = new TwitchEventSubApiImpl(credentialRepository);
EventSubOrchestrator eventSubOrchestrator = new EventSubOrchestrator(eventSubApi, webSocketClient);

// 認証
authOrchestrator.startAuthentication(uri -> {
    // ブラウザで認証URLを開く処理
    StreamTweaks.LOGGER.info("Please visit: {}", uri);
}).thenAccept(result -> {
    if (result == AuthenticationResult.SUCCESS) {
        // チャンネルを購読
        eventSubOrchestrator.subscribe(new UserId("broadcaster_id"));
    }
});
```

## リリース手順

現行の運用手順は [docs/release-workflow.md](docs/release-workflow.md)、版番号契約は
[docs/versioning.md](docs/versioning.md) を参照する。手順切替版は2026-10-24で、remote への導入後に使用する。

- 純 SemVer と channel、MC、Loader 集合を明示する。alpha/beta は番号まで固定し、自動採番しない。
- `release-new` が唯一の workflow dispatch 経路。plan / build / bundle 検証 / GitHub draft 保管後、承認を経て公開する。
- `github-stage` と `publish` は workflow 内部 job。独立した stage / resume / publish dispatch はない。
- `Publish <tag>` の一時失敗は、同じ run の失敗した job だけを再実行する。source / version / target / 公開先は変えない。
- stable の単 Loader を許可する。公開後に別 Loader を追加する場合は新 PATCH とし、既存集合を拡張しない。
- 応答喪失は再照会し、成功を証明できなければ停止する。Tag / Release / Modrinth version の削除・overwrite による復旧は禁止。
- draft 保管後の publish job は draft から bundle を復元・再検証する。無検証で再ビルド・差し替えしない。
- changelog の `[fabric]` / `[neoforge]` / `[fabric,neoforge]` filter を再利用し、タグなし項目は共通とする。
- secret は環境からのみ読み、値をログや引数へ出さない。GitHub / Modrinth の原子的公開は保証しない。

権限・未設定時の挙動、部分失敗時の job 再実行と状態確認は上記ガイドを使う。
