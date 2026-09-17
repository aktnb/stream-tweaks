package org.etwas.streamtweaks.mod;

import com.google.gson.Gson;
import java.util.Map;
import java.util.concurrent.Executor;
import org.etwas.streamtweaks.application.TwitchApplicationService;
import org.etwas.streamtweaks.config.StreamTweaksSettingsStore;
import org.etwas.streamtweaks.config.infra.FileStreamTweaksSettingsRepository;
import org.etwas.streamtweaks.mod.platform.ClientPlatform;
import org.etwas.streamtweaks.mod.platform.TwitchCommandRegistrar;
import org.etwas.streamtweaks.presentation.chat.ChatRowRemover;
import org.etwas.streamtweaks.presentation.chat.DisplayedChatMessageIndex;
import org.etwas.streamtweaks.presentation.chat.IntegratedChatMessagePresenter;
import org.etwas.streamtweaks.presentation.chat.MinecraftChatRows;
import org.etwas.streamtweaks.presentation.chat.twitch.EventSubNotificationRouter;
import org.etwas.streamtweaks.presentation.chat.twitch.TwitchChatMessageDeletePresenter;
import org.etwas.streamtweaks.presentation.chat.twitch.TwitchChatMessagePresenter;
import org.etwas.streamtweaks.presentation.font.BadgeDownloader;
import org.etwas.streamtweaks.presentation.font.BadgePuaMapping;
import org.etwas.streamtweaks.presentation.font.EmoteAnimationTicker;
import org.etwas.streamtweaks.presentation.font.EmoteDownloader;
import org.etwas.streamtweaks.presentation.font.EmoteFontRegistry;
import org.etwas.streamtweaks.presentation.font.PuaMapping;
import org.etwas.streamtweaks.twitch.auth.AuthenticationOrchestrator;
import org.etwas.streamtweaks.twitch.auth.infra.FileTwitchCredentialRepository;
import org.etwas.streamtweaks.twitch.auth.infra.LocalCallbackServer;
import org.etwas.streamtweaks.twitch.auth.infra.TokenValidateViaApi;
import org.etwas.streamtweaks.twitch.badge.BadgeCatalogRepository;
import org.etwas.streamtweaks.twitch.badge.infra.TwitchChatBadgeApiImpl;
import org.etwas.streamtweaks.twitch.core.TwitchApiClient;
import org.etwas.streamtweaks.twitch.core.infra.TwitchApiClientImpl;
import org.etwas.streamtweaks.twitch.subscription.EventSubOrchestrator;
import org.etwas.streamtweaks.twitch.subscription.event.EventSubEventType;
import org.etwas.streamtweaks.twitch.subscription.infra.EventSubWebSocketClientImpl;
import org.etwas.streamtweaks.twitch.subscription.infra.TwitchEventSubApiImpl;

/**
 * Twitch関連コンポーネントの組み立て（コンポジションルート）を担う。
 *
 * <p>{@code StreamTweaksClient} はFabricのエントリポイントとしての責務に専念し、
 * DI配線はここに集約する。将来YouTube等を追加する際は、同様の
 * {@code YoutubeClientBootstrap} を並べて用意する想定。
 */
public final class TwitchClientBootstrap {

    private TwitchClientBootstrap() {
        throw new AssertionError("Cannot instantiate TwitchClientBootstrap");
    }

    /**
     * Twitch関連コンポーネントを組み立て、コマンドを登録した上で {@link TwitchApplicationService} を返す。
     */
    public static TwitchApplicationService initialize(
            ClientPlatform platform, TwitchCommandRegistrar commandRegistrar) {
        var emotesCacheDir = platform.configDir().resolve("stream-tweaks/emotes");
        var emoteDownloader = new EmoteDownloader(emotesCacheDir);
        platform.registerEndClientTick(EmoteAnimationTicker::tick);
        var puaMapping = new PuaMapping(codePoint -> {
            // getOrAssign() はメインスレッド専用。onEvict も同じスレッドから同期的に
            // 呼ばれるため、mc.execute() を使わず直接実行する。
            // mc.execute() でキューに積むと scheduleEmoteDownloads の hasGlyph チェックより
            // 後に removeGlyph が実行され、再割り当てされたコードポイントへのダウンロードが
            // 誤ってスキップされる。
            EmoteFontRegistry.getCurrent().removeGlyph(codePoint);
            EmoteFontRegistry.invalidateGlyphCache();
        });

        var badgesCacheDir = platform.configDir().resolve("stream-tweaks/badges");
        var badgeDownloader = new BadgeDownloader(badgesCacheDir);
        var badgePuaMapping = new BadgePuaMapping();

        var settingsRepository = new FileStreamTweaksSettingsRepository(
                platform.configDir().resolve("stream-tweaks").resolve("stream-tweaks-config.toml"));
        var settingsStore = new StreamTweaksSettingsStore(settingsRepository);
        // 設定画面（StreamTweaksConfigScreen）はUIレイヤーであり、コンポジションルートである
        // ここで組み立てたインスタンスを直接受け取れないため、TwitchApplicationServicesと
        // 同様の静的ホルダー経由で公開する。
        StreamTweaksSettingsServices.set(settingsStore);

        var callbackServer = new LocalCallbackServer();
        var credentialRepository = new FileTwitchCredentialRepository(
                platform.configDir().resolve("stream-tweaks").resolve("twitch-credentials.json"));
        var authenticationOrchestrator =
                new AuthenticationOrchestrator(callbackServer, credentialRepository, new TokenValidateViaApi());

        var eventSubWebSocketClient = new EventSubWebSocketClientImpl();
        var eventSubApi = new TwitchEventSubApiImpl(credentialRepository);
        TwitchApiClient apiClient = new TwitchApiClientImpl(credentialRepository);
        var chatBadgeApi = new TwitchChatBadgeApiImpl(credentialRepository);
        var badgeCatalogRepository = new BadgeCatalogRepository(chatBadgeApi);

        // Gsonはスレッドセーフかつ不変なので、チャット周りの各コンポーネントで共有する。
        var gson = new Gson();
        Executor clientExecutor = platform::executeOnClientThread;
        var displayedIndex = new DisplayedChatMessageIndex();
        var chatRowRemover = new ChatRowRemover(clientExecutor, MinecraftChatRows::remove);
        var integratedChatMessagePresenter = new IntegratedChatMessagePresenter(displayedIndex, chatRowRemover);
        var chatMessagePresenter = new TwitchChatMessagePresenter(
                puaMapping,
                emoteDownloader,
                badgePuaMapping,
                badgeDownloader,
                badgeCatalogRepository,
                settingsStore,
                gson,
                integratedChatMessagePresenter);
        var chatMessageDeletePresenter =
                new TwitchChatMessageDeletePresenter(gson, integratedChatMessagePresenter, clientExecutor);
        var notificationRouter = new EventSubNotificationRouter(
                gson,
                Map.of(
                        EventSubEventType.CHAT_MESSAGE.value(), chatMessagePresenter::handleChatMessage,
                        EventSubEventType.CHAT_MESSAGE_DELETE.value(),
                                chatMessageDeletePresenter::handleMessageDelete));
        var eventSubOrchestrator = new EventSubOrchestrator(eventSubApi, eventSubWebSocketClient);
        // subscribe()が呼ばれる前に通知の配線を完了させておく必要がある。
        eventSubOrchestrator.setNotificationListener(notificationRouter);

        // ワールド退出・サーバー切断時はMinecraft側のチャット欄もクリアされるため、
        // 表示済みメッセージの追跡情報も一緒に破棄する（残すと以降の削除通知が誤検知になる）。
        platform.registerClientDisconnect(() -> {
            displayedIndex.clear();
            chatRowRemover.clear();
        });

        var applicationService = new TwitchApplicationService(
                authenticationOrchestrator, eventSubOrchestrator, apiClient, badgeCatalogRepository);
        platform.registerClientStopping(applicationService::shutdown);
        commandRegistrar.register(applicationService, clientExecutor);

        return applicationService;
    }
}
