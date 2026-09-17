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

タグの発行からビルド・公開までを `.github/workflows/release.yml`（**Release** ワークフロー）が1本で行う。手動で `git tag` / `git push` することは基本的に不要。

> **背景**: 以前はタグ発行用ワークフローと `push: tags` トリガーの公開用ワークフローを分けていたが、GitHub Actions の仕様上「デフォルトの `GITHUB_TOKEN` で push したタグは他のワークフローをトリガーしない」という制限に引っかかり、タグは発行されても公開ワークフローが発火しない問題が起きた。そのため1本のワークフローに統合し、同一ジョブ内でタグ発行から公開まで完結させている。

- `mod_version` / `minecraft_version` は対象バージョンブランチの `gradle.properties` から自動取得される（形式を正規表現で検証してから使用）
- タグ名は自動採番される（手で決めない）。Fabric / NeoForge 共通の**1系列**で、ローダー別サフィックスは付けない
  - alpha / beta: 同じ `mod_version` 内で前回タグの連番 + 1（`vX.Y.Z-alpha.N+mcA.B.C` / `vX.Y.Z-beta.N+mcA.B.C`）
  - release: `vX.Y.Z+mcA.B.C`
- **実行順序は「ビルド → タグ push → publish」**。ビルド失敗ではタグが作られないため、失敗の大半はそのまま再実行できる
- タグは annotated tag で、メッセージは `Release <tag>` / `Loaders: <公開したローダー>` の2行のみ（changelog 本文は含めない）

### `loaders` 入力

`Release` ワークフローには `channel` に加えて `loaders` 入力がある。

| 値 | 公開対象 |
| --- | --- |
| `both`（既定） | Fabric + NeoForge |
| `fabric` | Fabric のみ |
| `neoforge` | NeoForge のみ |

**`channel=release` では `loaders=both` 必須**（安定版タグは `vX.Y.Z+mcA.B.C` 固定のため、片方だけ公開すると2回目の実行でタグが衝突する）。`compute` ステップで即座に失敗する。alpha / beta は連番採番なので片方だけの公開が可能で、次回はタグメッセージの `Loaders:` 行から「そのローダーを最後に公開したタグ」を探して差分ノートを作る。

### CHANGELOG のローダータグ記法

`CHANGELOG.md` の箇条書き先頭に `[fabric]` / `[neoforge]` / `[fabric,neoforge]` を付けると、リリースノート生成時に `scripts/changelog_tool.py --loaders` で絞り込まれる。

```markdown
### Fixed
- 共通の不具合を修正（タグ無し = 全ローダー向け）
- [fabric] Fabric でのみ発生する不具合を修正
- [neoforge] NeoForge でのみ発生する不具合を修正
```

- タグ無しの箇条書きは共通扱いで、どのローダーのノートにも必ず含まれる
- **本文が完全に同じ内容なら `[fabric,neoforge]` として1件にまとめる**（`[fabric]` と `[neoforge]` に分けて書くとまとめノートで2行になる）
- 未知のタグ（例: `[quilt]`）や Markdown リンク記法 `- [text](url)` はローダータグとみなされない
- 単一ローダー向けノートでは出力時にタグが自動除去される。`CHANGELOG.md` 自体には常にタグ付きのまま保存される

### alpha / beta リリース

`[Unreleased]` に変更を書き溜めた状態で、対象バージョンブランチ（例: `26.2`）を指定して **Release** ワークフローを実行し、`channel` に `alpha` または `beta` を選択する。CHANGELOG.md の事前更新は不要（ワークフローが `[Unreleased]` の差分をリリースノートとして使用する）。

```bash
# GitHub CLI から実行する場合
gh workflow run release.yml --ref 26.2 -f channel=alpha -f loaders=both

# Fabric だけ先に配りたい場合
gh workflow run release.yml --ref 26.2 -f channel=beta -f loaders=fabric
```

GitHub の Actions タブから手動実行（Run workflow）してもよい。

### 正式リリース（release）

**ワークフローを実行する前に必ず以下の手順を完了すること。** CHANGELOG.md に `## [{mod_version}]` セクションが存在しない状態で `release` を実行すると、ワークフローはタグを作成せず失敗する。

#### 1. リリース準備 PR を作成

```bash
git checkout -b release/0.1.2
```

#### 2. CHANGELOG.md を更新（ローカルで実行）

```bash
python3 scripts/changelog_tool.py release --version 0.1.2 --update-file
```

これにより：
- `[Unreleased]` の内容が `## [0.1.2] - YYYY-MM-DD` セクションに移動する
- `[Unreleased]` が空のテンプレートにリセットされる

#### 3. 確認・コミット・PR 作成

```bash
# ローカルで事前確認（ワークフローが読む内容を確認）
python3 scripts/changelog_tool.py read --version 0.1.2

git add CHANGELOG.md
git commit -m "docs: prepare release 0.1.2"
gh pr create --base 26.2
```

#### 4. PR マージ後に Release ワークフローを実行

```bash
gh workflow run release.yml --ref 26.2 -f channel=release -f loaders=both
```

`channel=release` を選択して実行すると、まず `## [0.1.2]` セクションからリリースノートを生成してビルドし、成功後に `v0.1.2+mc26.2` 形式のタグを push してから Modrinth / GitHub Release / Discord に自動投稿する。

### リカバリ手順

ビルドがタグ push より前に走るため、**ビルド失敗・入力ミス・CHANGELOG 不備ではタグが作られない**。原因を直してワークフローを再実行するだけでよい。

タグ削除が必要になるのは、**タグ push 後の publish ステップで失敗したとき**だけ（同名タグは既に存在するタグとして **Release** ワークフローに拒否されるため）。

```bash
# 1. タグを削除（ローカルとリモート両方）
git tag -d v0.1.2+mc26.2
git push origin :refs/tags/v0.1.2+mc26.2

# 2. GitHub Release が中途半端に作られていれば削除する
gh release delete v0.1.2+mc26.2 --yes

# 3. 必要なら CHANGELOG.md を修正して PR → マージ
python3 scripts/changelog_tool.py release --version 0.1.2 --update-file
git add CHANGELOG.md
git commit -m "docs: prepare release 0.1.2"
gh pr create --base 26.2
# PR マージ後

# 4. 再度 Release ワークフローを実行
gh workflow run release.yml --ref 26.2 -f channel=release -f loaders=both
```

Modrinth に version が作成済みの場合は、Modrinth 管理画面から該当 version を削除してから再実行する（同じ version 番号は再利用できない）。

詳細は `docs/release-workflow.md` / `docs/ci-build.md` を参照。
