# 第2 Minecraft 候補調査（Issue #15）

調査日: 2026-10-24。基準ソースは 26.2 の tree
`35a5a6e4f418ec65ec058740e3a0c539a8c73f6e`（local `5de2947` と remote `06436c1` は同一 tree）。
この記録は互換性調査であり、移植成功・対応宣言・公開計画の承認ではない。

## 判断

**Maintenance の選定を保留する。Issue #16 の移植には着手しない。**
技術面の第一比較候補は **26.1.2** とするが、本 Mod をその版で利用したいという需要を確認できていない。
2026-10-24 に取得した development-infrastructure issue #2〜#17 には候補の例示はあるが、利用者の要求・
対象 modpack・継続利用数の根拠はない。公開依存物の download 数を本 Mod の需要へ読み替えない。
README／既存 docs にも第2版の利用要件は見つからなかった。需要がないと断定するものではない。

保留中は期限・優先 Loader を確約しない。需要確認後の最初の実験は Fabric を優先し、
同じ MC に対する NeoForge を別ゲートで評価する。Maintenance 昇格時は support-policy の
「新 Active 昇格から6か月、または次 Active 昇格の早い方」を適用し、起算日・期限を記録する。

## 配布物・toolchain の調査

以下は調査時に一次資料から確認した値で、組合せ build の成功を保証しない。
最新版追従を目的とせず、移植を開始する際に固定した版と checksum を改めて確定する。

| 項目 | 26.1.2（第一比較候補） | 1.21.11（境界比較用、選定対象外） |
|---|---|---|
| Minecraft 実在 | Mojang manifest に release、2026-04-09 公開 | 同 manifest に release、2025-12-09 公開 |
| Java | 25。Fabric 26.1 案内と NeoForge MDK が一致 | Java 25 化以前の系列。今回 patch/JDK 組合せは未検証 |
| Fabric Loader | 公式 meta の 26.1.2 用一覧に stable `0.20.1` | exact Loader は未調査 |
| Fabric API | 公式 Maven に `0.155.3+26.1.2`（ほか `0.155.0`／`0.155.2+26.1.2`） | 公式 docs の参照 build は `0.141.1+1.21.11` |
| Loom／Gradle | Fabric 26.1 公開時案内は Loom 1.15／Gradle 9.4.0。固定 patch と 26.1.2 実 build は未検証 | Fabric 案内は Loom 1.14。remap が必要で現行 no-remap 方式をそのまま使えない |
| NeoForge | 公式 26.1.2 MDK は `26.1.2.122`／MDG `2.0.148`／Gradle `9.2.1`／Java 25 | 今回 exact NeoForge／MDG／Gradle は未調査 |
| Mod Menu | 作者の Modrinth 公開 API: `18.0.2`、version ID `ZJ6YTrMY`、26.1／26.1.1／26.1.2、Fabric／Quilt、client-only | 作者公開ページに `17.0.1`、ただしこの調査ではバイナリ・Maven 解決未検証 |
| 配布確認の限界 | 上記 metadata／公式 MDK を取得。ゲーム JAR／依存 JAR 自体の download・checksum・build は未実施 | 同左。全依存の確定表がないため移植可能とは扱わない |

Fabric と NeoForge の公開時推奨 Gradle は異なる。片方の MDK の wrapper を無条件に全体へ採用せず、
#6 で固定する現行 toolchain を起点に両 Loader の解決・build を隔離環境で確認する。
26.1 以降は非難読化で Mojang 名を使う。26.1.2 は現行との難読化境界をまたがないが、
1.21.11 は remapping／依存宣言の差が増えるため、需要が同程度なら調査コストが高い。

## 現行ソースとの具体的な差分・調査箇所

下表の「確定」は公式 migration 資料と現行参照を照合した静的差分である。
26.1.2 の class signature を実 JAR で全比較したわけではなく、変更行数・コンパイル可否は未確定。
パスは特記しない限り `src/main/java/org/etwas/streamtweaks/` 以下。

| ファイル／境界 | 差分または次に照合する契約 | 状態 |
|---|---|---|
| `mod/StreamTweaksConfigScreen.java`、`presentation/commands/TwitchCommand.java`、`fabric/src/main/java/org/etwas/streamtweaks/mod/ModMenuIntegration.java` | 26.2 の `minecraft.gui.setScreen()`／`gui.screen()` を26.1系の Minecraft 側 API へ戻す。非同期画面同一性チェックも全件対象 | GUI の所有先移動は確定 |
| `presentation/chat/MinecraftChatRows.java`、`IntegratedChatMessagePresenter.java` | 26.2 の HUD／チャット取得経路を26.1系へ戻す。スクロール維持と行削除を確認 | GUI／HUD 再編は確定 |
| `presentation/mixin/ChatComponentAccessor.java` | `ChatComponent.allMessages` の `List<GuiMessage>` と `refreshTrimmedMessages` invoker の実 signature | 未照合、削除機能の runtime ゲート |
| `presentation/mixin/FontManagerAccessor.java` | `FontManager.fontSets` の map key/value | 未照合 |
| `presentation/mixin/FontStorageAccessor.java` | `FontSet.activeProviders` と `glyphCache` の型・変更可能性 | 未照合 |
| `presentation/mixin/FontStorageMixin.java` | `createFontSet(Identifier,List<GlyphProvider.Conditional>,Set<FontOption>)` の RETURN 注入点 | 未照合、起動だけでなく reload 後も確認 |
| `presentation/mixin/MutableComponentMixin.java` | `MutableComponent` へのタグ interface／参照 field 追加 | 同等性未検証、メッセージ削除 smoke が必要 |
| `presentation/font/EmoteFont.java`、`EmoteGlyph.java`、`EmoteFontRegistry.java` | `GlyphProvider`／`UnbakedGlyph`／`GlyphBitmap`／`GpuTexture` と texture upload・再描画・close | font internals の互換性は公式 primer だけでは証明不能 |
| `presentation/font/{BadgeDownloader,EmoteDownloader,GifFrameDecoder,EmoteFrame,EmoteAnimation}.java` | `NativeImage` の生成・decode・所有権。GIF animation の破棄と再接続 | class signature と lifecycle は未検証 |
| `presentation/chat/{NormalizedChatMessage,PlatformChatStyles}.java`、`chat/twitch/TwitchChatMessagePresenter.java` | Component／Style／クリックイベント。26.2 で ChatFormatting から Style への整理あり | 個別利用との照合が必要 |
| `fabric/src/main/java/.../mod/fabric/*` | Fabric lifecycle／client commands と Mod Menu 18 の設定画面 factory | Fabric API／Mod Menu のコンパイル・実行確認が必要 |
| `neoforge/src/main/java/.../mod/neoforge/*` | NeoForge client events／command registration／`IConfigScreenFactory` | NeoForge 26.1.2 用 API で確認、Fabric 成功から推論しない |
| `gradle.properties`、Loader build／metadata、`src/main/resources/stream-tweaks.mixins.json` | MC／Loader／API／Mod Menu 差替えと metadata 生成。26.1.2 は Java 25 を維持 | 将来の移植対象、今回は未変更 |

Twitch の認証／API／購読業務ロジックは複製しない。MC 依存差は上記 presentation／platform 境界で処理する。
共通 `src` に置かれていることは MC 非依存を意味しない。

## 保守費用と方式の再評価

26.1.2 でも GUI 所有先の逆移植は実差分として存在し、5 Mixin と font の内部 API は未検証である。
従って「properties だけの変更」や S サイズの移植とは見積もらない。初期予算は **Fabric の移植・smoke を M、
NeoForge は別 M 以下を確認して追加判断**とし、font 契約の差が大きければ先に再分割する。
これは実測工数ではなく、未確定領域を含む調査予算である。

現時点は既存 branch＋2 Loader module＋`ClientPlatform` を継続し、Stonecutter を導入しない。
既に確定した GUI 呼出し先の差は局所的で、生成 build の追加だけでは Mixin／font の正しさを解決できない。
また、候補のコンパイル差分量が未測定のため Stonecutter の削減効果を実証できていない。
需要確認後に隔離 port で衝突ファイル数・継続 backport 費用を測り、反復する adapter 移植／3 MC 以上の要求が
生じた場合に公式 multi-loader template との小規模比較を行う。

## 再開条件と未実施のゲート

1. 利用者／modpack の具体的な需要と優先 Loader を記録し、26.1.2 を選定するか再判断する。
2. 公式 metadata から依存版を固定し、隔離 branch で class signature 差と両 Loader の clean build/check を確認する。
3. 認証（テストアカウント、秘密情報非記録）・起動・5 Mixin・チャット表示／削除・静止／動画 emote・
   リソース reload・切断／終了を target ごとに smoke し結果だけ記録する。
4. main 移行基盤の完了後、固定 SHA の全対象 CI／release plan dry-run と `cherry-pick -x` の backport を実証する。
5. 成功した Loader 行だけを Maintenance とし、非対応 Loader を明記する。

今回は恒久 branch／build／台帳の変更、移植 build、client smoke、公開を行っていない。
未検証 target を supported に昇格させていない。

## 一次資料と確認範囲

- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json): 26.1.2 と1.21.11の release 行を取得。
- [Fabric 26.1](https://fabricmc.net/2026/03/14/261.html): Java 25／非難読化／公開時 toolchain。
- [Fabric Loader 26.1.2 metadata](https://meta.fabricmc.net/v2/versions/loader/26.1.2): stable 0.20.1 行を取得。
- [Fabric API Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml): 26.1.2 artifact version を取得。
- [NeoForge 26.1.2 MDK properties](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle/blob/main/gradle.properties)、
  [build](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle/blob/main/build.gradle)、
  [wrapper](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle/blob/main/gradle/wrapper/gradle-wrapper.properties): 上表の値を取得。
- [Mod Menu 作者公開 API](https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%2226.1.2%22%5D): 18.0.2 の supported game versions／Loader／配布 URL を取得。
- [Fabric 26.2](https://fabricmc.net/2026/06/15/262.html) と
  [NeoForge 26.1.x→26.2 primer](https://github.com/neoforged/.github/blob/main/primers/26.2/index.md): GUI／HUD の移動と描画 API 差を現行ソースと照合。
- [Fabric 1.21.11](https://fabricmc.net/2025/12/05/12111.html)、
  [公式参照 build](https://github.com/FabricMC/fabric-docs/blob/main/reference/1.21.11/build.gradle)、
  [Loom の難読化境界](https://docs.fabricmc.net/develop/loom/): 古い系列の比較根拠。
- [Stonecutter 公式 multi-loader template](https://github.com/stonecutter-versioning/stonecutter-template-multiloader): 将来の費用比較先。今回本 Mod への適用は未実験。

Web tool が JSON／XML を取得できなかった箇所は、許可されたネットワーク実行の curl（TLS検証有効）で取得した。
Python 標準 trust store では証明書エラーになったが、検証無効化は行っていない。
