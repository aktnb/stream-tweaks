# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Twitchチャットのユーザーが装着しているバッジ（モデレーター/VIP/ブロードキャスター/サブスクライバー等）を、グローバルバッジ・チャンネル固有バッジ（サブスクライバーバッジ等のカスタム画像を含む）ともにMinecraftのチャット欄へ画像アイコンとして表示するよう対応

### Changed

### Fixed
- 絵文字・バッジ表示用に内部で使用しているUnicode私用領域(PUA)のコードポイントが、リソースパック「Redstone Tweaks」「Tooltips Stylized」が使用するコードポイントと衝突し、意図しないグリフに上書きされる不具合を修正
- Minecraft終了時にEventSub WebSocketのkeepalive監視スレッドや認証用ローカルコールバックサーバーを停止し、Modが作成したバックグラウンドリソースが残る可能性を修正
- シングルプレイ終了時に、Twitch絵文字・バッジのフォントグリフがサーバースレッド上で遅延bakeされてクラッシュする可能性を修正
- EventSub WebSocketの接続確立自体（DNS解決失敗・TLSハンドシェイク失敗等）が失敗した場合に、接続状態が復帰不能なままハングし続ける不具合を修正
- Twitch Helix API（ユーザー取得・EventSub購読・チャットバッジ取得）、OAuthトークン検証、およびエモート画像ダウンロードのHTTPリクエストにタイムアウトが設定されておらず、Twitch側が応答不能になった場合にリクエストが無期限にハングしうる不具合を修正。特にエモートダウンロードは共有スレッドプール（ForkJoinPool.commonPool）上で同期的に待機するため、複数のダウンロードが同時にハングするとMod外にも影響しうる問題だった。トークン検証は `/twitch login` 完了直後の処理がこれに該当し、既存の100秒タイムアウト（コールバック待ち区間のみが対象）では防げなかった

## [0.3.2] - 2026-07-31

### Added
- 認証済みユーザー自身のチャンネルへワンクリックで接続できるボタンを設定画面に追加
- `/twitch connect` コマンドで、認証ユーザーが未接続の場合に自分のチャンネル名をサジェスト（補完候補）に表示するよう対応
- [neoforge] Mod 一覧画面の Config ボタンから設定 GUI を開けるよう対応（Fabric の ModMenu 連携と同じ画面）
- `/twitch config` コマンドで設定画面を開けるよう対応

### Changed

### Fixed

## [0.3.1] - 2026-07-27
### Added
- NeoForge に対応（Fabric / NeoForge のマルチローダー構成へ移行）

### Changed
- リリース生成物のファイル名に Minecraft バージョンを含めるよう変更

### Fixed
- リリース生成物のファイル名プレフィックスの typo を修正（`streamer-tweeks` → `stream-tweaks`）

## [0.3.0] - 2026-07-26
### Added
- Twitch でモデレーターがチャットメッセージを削除した際、Minecraft のチャット欄からも該当行を消すように対応（`channel.chat.message_delete` を `/twitch connect` 時に自動購読）
  - 対応するのはモデレーターによる個別メッセージ削除のみ。timeout / ban / チャット全消去による一括削除は対象外
  - 削除されたメッセージの本文は `logs/latest.log` には平文で残る（チャット欄の表示のみを消す）

### Changed
- Minecraft 26.2 へアップグレード
- EventSub の購読管理単位を「チャンネル」から「チャンネル × イベントタイプ」へ変更（片方のイベントタイプの購読に失敗しても、次回同期で欠けた分だけ再作成される）

### Fixed
- EventSub の reconnect 時に旧接続と新接続で受信バッファを共有しており、JSON が混線しうる問題を修正
- EventSub の reconnect 時に、閉じるべき旧接続ではなく確立したばかりの新接続を閉じていた問題を修正
- EventSub の reconnect 完了後に旧接続の切断イベントで新セッションが破棄されていた問題を修正

## [0.2.0] - 2026-07-25
### Added
- ModMenu 統合：コンフィグ画面から Twitch 認証状態・接続チャンネルの確認、login/logout、チャンネルの connect/disconnect が可能に
- Twitch アニメーション絵文字（GIF）対応
- チャット欄のユーザー名を Twitch の設定色で表示

### Changed
- Minecraft 26.1.2 + Java 25 へアップグレード
- ModMenu 18.0.0-alpha.8 へ更新

### Fixed
- コンフィグ画面の disconnect ボタンが重なる問題を修正
- 非同期処理後のフォーカス回復に `clearAndInit()` を使用するよう修正
- Minecraft 1.21.8 レンダリングのテキストカラーにアルファチャンネルを追加
- `/twitch` コマンドの非同期処理完了後のフィードバック表示がクライアントスレッド外から実行される問題を修正
- 絵文字が他の文字より下にずれ、チャット背景からはみ出して表示される問題を修正

## [0.1.2] - 2026-05-22
### Added
- Static Emoji の表示
- icon を追加
- `/twitch logout` コマンドを追加（logout 時に購読中の全チャンネルを自動 disconnect）
- `/twitch login` 認証成功時に Minecraft Chat へ成功メッセージを表示

### Changed
- `/twitch disconnect` 実行時に未認証の場合、`/twitch login` への誘導メッセージを表示するよう変更
- リリース生成物のファイル名プレフィックスを `streamer-tweeks` に変更

### Fixed
- `/twitch connect` 実行時に未認証の場合、例外ではなく `/twitch login` を促すメッセージを表示するよう修正
- Twitch API から 401 が返った際に再認証を促すエラーメッセージを表示するよう修正

## [0.1.1] - 2025-09-27
### Added
- Twitch OAuth 認証
- Twitch Chat　の Minecraft ゲーム内表示
- Changelog の自動化

### Changed

### Fixed
