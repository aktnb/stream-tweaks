package org.etwas.streamtweaks.mod;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.etwas.streamtweaks.application.TwitchApplicationService;
import org.etwas.streamtweaks.config.StreamTweaksSettingsStore;
import org.etwas.streamtweaks.twitch.core.Login;

/**
 * Stream Tweaksの設定画面。
 *
 * <p>画面内のコンテンツ（"Twitch Connection Status:" から "Connected Channels:" のチャンネル一覧まで）は
 * すべて {@link ScrollableLayout}（{@code net.minecraft.client.gui.components}
 * パッケージ、Minecraft 1.20.5以降で導入されたバニラ標準API）でラップした1つのスクロール可能な
 * Paneにまとめている。"Back" ボタンだけは {@link HeaderAndFooterLayout} のフッター領域に置き、
 * スクロール対象外の画面下部に固定表示する。
 *
 * <p>以前は「Connected Channelsのチャンネル一覧部分だけ」を {@code ChannelListLayout}
 * （Y座標計算・スクロールオフセット・可視行数を手計算する純粋ロジッククラス）と、
 * {@code mouseScrolled}/{@code removeWidget}/{@code addRenderableWidget} を組み合わせた
 * 独自実装でスクロール対応させていた。しかしこの手法は画面全体には広げにくく、また
 * {@code enableScissor}/{@code disableScissor} を独自に呼ぶ実装だったため、表示領域の高さが
 * 0になった瞬間にクリップ矩形が空になりテキストが完全に消えるバグを一度踏んでいる
 * （{@code GuiGraphicsExtractor.ScissorStack.push()} の交差判定が `top < bottom`
 * という厳密不等号のため）。
 *
 * <p>{@link ScrollableLayout} はバニラの {@code ExperimentsScreen} / {@code RestrictionsScreen}
 * 等で実際に使われているMojang自身のスクロールコンポーネントであり、スクロール量に応じた
 * scissorクリッピング・スクロールバー描画・マウスホイール/ドラッグ操作・キーボードフォーカス時の
 * 自動スクロールを内部で完結して処理する。{@code ScrollableLayout.Container}
 * （非公開の内部クラス）が唯一 {@code Screen} に addRenderableWidget されるウィジェットとなり、
 * {@code EditBox}/{@code Button}/{@code CycleButton} 等は全てその子ウィジェットとして
 * {@code ContainerEventHandler} 経由でイベントを受け取るため、画面全体をスクロールしても
 * 個々のウィジェット（特にEditBoxのフォーカス）が壊れることはない。
 * これにより、以前 {@code ChannelListLayout} が担っていた「表示領域の高さ・可視行数・
 * スクロールオフセットの手計算」はすべて不要になった（{@code ChannelListLayout}
 * および対応するテストは本変更で削除した）。
 */
public class StreamTweaksConfigScreen extends Screen {

    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int LABEL_COLOR = 0xAAAAAA;
    private static final int AUTH_OK_COLOR = 0x55FF55;
    private static final int AUTH_FAIL_COLOR = 0xFF5555;
    private static final int NO_CHANNELS_COLOR = 0x888888;
    private static final int LINE_HEIGHT = 12;

    static final int BUTTON_HEIGHT = 20;

    /** ラベル＋ボタンの2カラム行で、ラベル側に確保する固定幅(px)。ボタンの開始X座標を行間で揃えるために使う。 */
    private static final int LABEL_COLUMN_WIDTH = 170;

    private static final int ROW_SPACING = 8;
    private static final int BODY_SPACING = 6;

    private static final int LOGIN_BUTTON_WIDTH = 60;
    private static final int EDIT_BOX_WIDTH = LABEL_COLUMN_WIDTH;
    private static final int CONNECT_BUTTON_WIDTH = 60;
    private static final int CONNECT_OWN_BUTTON_WIDTH = 230;
    private static final int SHOW_BADGES_BUTTON_WIDTH = 70;
    private static final int DISCONNECT_BUTTON_WIDTH = 80;
    private static final int BACK_BUTTON_WIDTH = 100;

    /** スクロール可能領域の最小幅(px)。内容がこれより狭い場合でも常にこの幅を確保する。 */
    private static final int CONTENT_MIN_WIDTH = 280;

    /**
     * スクロール可能領域の最大高さの下限(px)。{@link ScrollableLayout#setMaxHeight}は内部で
     * {@code Math.clamp(height, minHeight, maxHeight)} を呼ぶため、maxHeightが0や負の値になると
     * （minHeightのデフォルト0を下回り）{@code IllegalArgumentException}で画面ごとクラッシュする。
     * 極端に低い画面高さ（ヘッダー+フッターだけで画面が埋まってしまうケース）でもクラッシュしない
     * よう、常にこの値以上を下限として確保する。
     */
    private static final int MIN_SCROLL_AREA_HEIGHT = BUTTON_HEIGHT * 2;

    private final Screen parent;

    private String channelInputText = "";

    /** True while an async operation is in progress; disables interactive widgets. */
    private boolean busy = false;

    private EditBox channelField;
    private Button connectBtn;
    private List<Login> subscribedLogins = List.of();

    /** 画面全体のヘッダー(タイトル)／コンテンツ／フッター(Backボタン)を管理するレイアウト。 */
    private HeaderAndFooterLayout layout;

    /**
     * "Twitch Connection Status:" から "Connected Channels:" のチャンネル一覧までをまとめてラップする
     * スクロール可能領域。Backボタンはこの外（{@link #layout}のフッター）に置かれるため、
     * スクロールの影響を受けない。
     */
    private ScrollableLayout scrollArea;

    public StreamTweaksConfigScreen(Screen parent) {
        super(Component.literal("Stream Tweaks"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TwitchApplicationService service = TwitchApplicationServices.get();
        // 認証情報ファイルの読み込みは init() あたり1回に抑える。読み取った値を使い回し、
        // 自チャンネル接続済みかどうかはメモリ上の subscribedLogins（ファイルI/Oなし）で判定する。
        String authenticatedLogin = service != null ? service.getAuthenticatedLogin() : null;
        boolean authenticated = authenticatedLogin != null;

        subscribedLogins = service != null ? List.copyOf(service.getSubscribedLogins()) : List.of();

        this.layout = new HeaderAndFooterLayout(this);
        this.layout.addTitleHeader(this.title, this.font);

        LinearLayout body = LinearLayout.vertical().spacing(BODY_SPACING);
        body.defaultCellSetting().alignHorizontallyLeft();

        // --- Twitch Connection Status ---
        body.addChild(sectionLabel("Twitch Connection Status:"));

        Component authText = authenticated
                ? Component.literal("  Authenticated: " + authenticatedLogin).withColor(AUTH_OK_COLOR)
                : Component.literal("  Not authenticated").withColor(AUTH_FAIL_COLOR);
        StringWidget authStatusWidget = new StringWidget(LABEL_COLUMN_WIDTH, LINE_HEIGHT, authText, this.font);

        Button authButton;
        if (authenticated) {
            authButton = Button.builder(Component.literal("Logout"), button -> doLogout(service))
                    .width(LOGIN_BUTTON_WIDTH)
                    .build();
            authButton.active = !busy;
        } else {
            authButton = Button.builder(Component.literal("Login"), button -> doLogin(service))
                    .width(LOGIN_BUTTON_WIDTH)
                    .build();
            authButton.active = !busy && service != null;
        }
        body.addChild(row(authStatusWidget, authButton));

        // --- Connect to Channel ---
        body.addChild(sectionLabel("Connect to Channel:"));

        channelField =
                new EditBox(this.font, 0, 0, EDIT_BOX_WIDTH, BUTTON_HEIGHT, Component.literal("Channel name"));
        channelField.setValue(channelInputText);
        channelField.setEditable(authenticated && !busy);
        channelField.setResponder(text -> {
            channelInputText = text;
            updateConnectButton();
        });

        connectBtn = Button.builder(Component.literal("Connect"), button -> doConnect(service))
                .width(CONNECT_BUTTON_WIDTH)
                .build();
        connectBtn.active = authenticated && !busy && isValidChannelName(channelInputText);
        body.addChild(row(channelField, connectBtn));

        boolean connectedToOwnChannel = authenticatedLogin != null
                && subscribedLogins.stream().anyMatch(login -> login.value().equalsIgnoreCase(authenticatedLogin));
        Button connectOwnBtn = Button.builder(
                        Component.literal("Connect to My Channel"), button -> doConnectOwnChannel(service))
                .width(CONNECT_OWN_BUTTON_WIDTH)
                .build();
        connectOwnBtn.active = authenticated && !busy && !connectedToOwnChannel;
        body.addChild(connectOwnBtn);

        // --- Display Settings ---
        StreamTweaksSettingsStore settingsStore = StreamTweaksSettingsServices.get();
        if (settingsStore != null) {
            body.addChild(sectionLabel("Display Settings:"));

            StringWidget showBadgesLabel = new StringWidget(
                    LABEL_COLUMN_WIDTH,
                    LINE_HEIGHT,
                    Component.literal("Show Twitch Badges").withColor(TEXT_COLOR),
                    this.font);
            // "Show Twitch Badges" というラベルはStringWidgetとして描画し、ボタンには
            // displayOnlyValue() で値（Enabled/Disabled）のみを表示させる。name には
            // narration（読み上げ）用にラベルを渡す。
            CycleButton<Boolean> showBadgesBtn = CycleButton.booleanBuilder(
                            Component.literal("Enabled"), Component.literal("Disabled"), settingsStore.showBadges())
                    .displayOnlyValue()
                    .create(
                            0,
                            0,
                            SHOW_BADGES_BUTTON_WIDTH,
                            BUTTON_HEIGHT,
                            Component.literal("Show Twitch Badges"),
                            (button, value) -> settingsStore.setShowBadges(value));
            // 他のウィジェットと異なり意図的に .active = !busy を設定していない。
            // このトグルはネットワークI/Oを伴わないローカル設定の変更のみのため、
            // ログイン/ログアウト/接続処理中（busy中）でも操作可能にしてよいという設計判断による。
            body.addChild(row(showBadgesLabel, showBadgesBtn));
        }

        // --- Connected Channels ---
        body.addChild(sectionLabel("Connected Channels:"));
        if (subscribedLogins.isEmpty()) {
            body.addChild(new StringWidget(
                    Component.literal("  No channels connected").withColor(NO_CHANNELS_COLOR), this.font));
        } else {
            for (Login login : subscribedLogins) {
                StringWidget channelLabel = new StringWidget(
                        LABEL_COLUMN_WIDTH,
                        LINE_HEIGHT,
                        Component.literal("  - " + login.value()).withColor(TEXT_COLOR),
                        this.font);
                Button disconnectBtn = Button.builder(
                                Component.literal("Disconnect"), button -> doDisconnect(service, login))
                        .width(DISCONNECT_BUTTON_WIDTH)
                        .build();
                disconnectBtn.active = !busy;
                body.addChild(row(channelLabel, disconnectBtn));
            }
        }

        this.scrollArea = new ScrollableLayout(this.minecraft, body, computeScrollAreaMaxHeight());
        this.scrollArea.setMinWidth(CONTENT_MIN_WIDTH);
        this.layout.addToContents(this.scrollArea);

        this.layout.addToFooter(Button.builder(Component.translatable("gui.back"), button -> this.onClose())
                .width(BACK_BUTTON_WIDTH)
                .build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    /**
     * Backボタン(フッター)を除いたコンテンツ領域に確保できる最大高さ。
     *
     * <p>{@link ScrollableLayout#setMaxHeight}は内部で{@code Math.clamp(height, 0, maxHeight)}を
     * 呼ぶため、画面が極端に低くヘッダー+フッターだけで埋まってしまう場合でも{@link
     * #MIN_SCROLL_AREA_HEIGHT}を下限として確保し、負の値や0を渡してクラッシュすることを防ぐ。
     */
    private int computeScrollAreaMaxHeight() {
        return Math.max(MIN_SCROLL_AREA_HEIGHT, this.layout.getContentHeight());
    }

    /**
     * ラベル用ウィジェットとして、色付きの{@link StringWidget}を返す。
     * "Twitch Connection Status:" 等、単独行で使うセクション見出しに使用する。
     */
    private StringWidget sectionLabel(String text) {
        return new StringWidget(Component.literal(text).withColor(LABEL_COLOR), this.font);
    }

    /**
     * ラベル(または入力欄)とボタンを横に並べた1行を作る。左側の幅は
     * {@link #LABEL_COLUMN_WIDTH} に揃え、行ごとのボタン開始X座標を合わせる。
     */
    private LinearLayout row(LayoutElement left, LayoutElement right) {
        LinearLayout row = LinearLayout.horizontal().spacing(ROW_SPACING);
        row.defaultCellSetting().alignVerticallyMiddle();
        row.addChild(left);
        row.addChild(right);
        return row;
    }

    @Override
    protected void repositionElements() {
        // Screen#repositionElements()のデフォルト実装はrebuildWidgets()（clearWidgets()+init()）を
        // 呼ぶため、ここをオーバーライドせずinit()末尾からthis.repositionElements()を呼ぶと
        // 無限再帰になる。バニラのExperimentsScreen/RestrictionsScreenと同様、ウィジェットを
        // 作り直さずレイアウトの再配置だけを行う版にオーバーライドする。
        // これにより、ウィンドウリサイズ時（Screen#resize()がrepositionElements()を直接呼ぶ経路）も
        // channelFieldなどのウィジェットが再生成されず、フォーカスが保たれる副次的な利点もある。
        if (this.layout == null) {
            return;
        }
        if (this.scrollArea != null) {
            this.scrollArea.arrangeElements();
            this.scrollArea.setMaxHeight(computeScrollAreaMaxHeight());
        }
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    // -------------------------------------------------------------------------
    // Async action handlers
    // -------------------------------------------------------------------------

    private void doLogin(TwitchApplicationService service) {
        if (service == null || busy) {
            return;
        }
        busy = true;
        reinitialize();
        service.login(uri -> Util.getPlatform().openUri(uri))
                .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                        return;
                    }
                    busy = false;
                    reinitialize();
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                            return;
                        }
                        StreamTweaksCommon.LOGGER.error("Failed to login", ex);
                        busy = false;
                        reinitialize();
                    });
                    return null;
                });
    }

    private void doLogout(TwitchApplicationService service) {
        if (service == null || busy) {
            return;
        }
        busy = true;
        reinitialize();
        service.logout()
                .thenRun(() -> Minecraft.getInstance().execute(() -> {
                    if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                        return;
                    }
                    busy = false;
                    reinitialize();
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                            return;
                        }
                        StreamTweaksCommon.LOGGER.error("Failed to logout", ex);
                        busy = false;
                        reinitialize();
                    });
                    return null;
                });
    }

    private void doConnect(TwitchApplicationService service) {
        if (service == null || busy || !isValidChannelName(channelInputText)) {
            return;
        }
        busy = true;
        Login target = new Login(channelInputText.strip());
        reinitialize();
        service.connect(target)
                .thenRun(() -> Minecraft.getInstance().execute(() -> {
                    if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                        return;
                    }
                    busy = false;
                    channelInputText = "";
                    reinitialize();
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                            return;
                        }
                        StreamTweaksCommon.LOGGER.error("Failed to connect to channel", ex);
                        busy = false;
                        reinitialize();
                    });
                    return null;
                });
    }

    private void doConnectOwnChannel(TwitchApplicationService service) {
        if (service == null || busy) {
            return;
        }
        String ownLogin = service.getAuthenticatedLogin();
        if (ownLogin == null) {
            return;
        }
        busy = true;
        Login target = new Login(ownLogin);
        reinitialize();
        service.connect(target)
                .thenRun(() -> Minecraft.getInstance().execute(() -> {
                    if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                        return;
                    }
                    busy = false;
                    reinitialize();
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                            return;
                        }
                        StreamTweaksCommon.LOGGER.error("Failed to connect to own channel", ex);
                        busy = false;
                        reinitialize();
                    });
                    return null;
                });
    }

    private void doDisconnect(TwitchApplicationService service, Login login) {
        if (service == null || busy) {
            return;
        }
        busy = true;
        reinitialize();
        service.disconnect(login)
                .thenRun(() -> Minecraft.getInstance().execute(() -> {
                    if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                        return;
                    }
                    busy = false;
                    reinitialize();
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        if (this.minecraft == null || this.minecraft.gui.screen() != this) {
                            return;
                        }
                        StreamTweaksCommon.LOGGER.error("Failed to disconnect from channel", ex);
                        busy = false;
                        reinitialize();
                    });
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void reinitialize() {
        this.rebuildWidgets();
    }

    private void updateConnectButton() {
        if (connectBtn == null) {
            return;
        }
        TwitchApplicationService service = TwitchApplicationServices.get();
        boolean authenticated = service != null && service.getAuthenticatedLogin() != null;
        connectBtn.active = authenticated && !busy && isValidChannelName(channelInputText);
    }

    private static boolean isValidChannelName(String text) {
        return text != null && text.strip().matches("^[a-zA-Z0-9_]{1,25}$");
    }
}
