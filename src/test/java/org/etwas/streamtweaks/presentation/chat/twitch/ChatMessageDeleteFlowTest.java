package org.etwas.streamtweaks.presentation.chat.twitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import org.etwas.streamtweaks.platform.PlatformId;
import org.etwas.streamtweaks.presentation.chat.ChatRowRemover;
import org.etwas.streamtweaks.presentation.chat.DisplayedChatMessageIndex;
import org.etwas.streamtweaks.presentation.chat.DisplayedMessageKey;
import org.etwas.streamtweaks.presentation.chat.DrainingExecutor;
import org.etwas.streamtweaks.presentation.chat.IntegratedChatMessagePresenter;
import org.etwas.streamtweaks.presentation.chat.MinecraftChatRows;
import org.etwas.streamtweaks.presentation.chat.NormalizedChatMessage;
import org.etwas.streamtweaks.presentation.chat.StreamTweaksTaggedComponent;
import org.etwas.streamtweaks.twitch.subscription.event.ChatMessageNotification;
import org.etwas.streamtweaks.twitch.subscription.event.EventSubEventType;
import org.junit.jupiter.api.Test;

/**
 * 「表示側が登録したものを削除側が正しく引ける」ことを、実際のEventSubペイロードから通しで確認する。
 *
 * <p>ルーティング → 表示側の追跡登録・タグ付け（{@link IntegratedChatMessagePresenter#trackForDeletion}）
 * → 削除側の検証（{@link TwitchChatMessageDeletePresenter}）→ バッチ集約 → 行の除去 まで、
 * すべて本番クラスを通す。ヘッドレスでは再現できない
 * {@code Minecraft#execute} と {@code player.sendSystemMessage()} の部分だけを、
 * メインスレッドを模した {@link DrainingExecutor} と表示行リストで置き換えている。
 */
class ChatMessageDeleteFlowTest {

    private static final String BROADCASTER = "12345";

    private final Gson gson = new Gson();
    private final DisplayedChatMessageIndex index = new DisplayedChatMessageIndex();
    private final DrainingExecutor clientExecutor = new DrainingExecutor();

    /** Minecraftのチャット欄（{@code ChatComponent.allMessages}）を模した表示行リスト。 */
    private final List<GuiMessage> displayedRows = new ArrayList<>();

    private final ChatRowRemover rowRemover =
            new ChatRowRemover(clientExecutor, ids -> MinecraftChatRows.removeTaggedRows(displayedRows, ids));
    private final IntegratedChatMessagePresenter integratedPresenter =
            new IntegratedChatMessagePresenter(index, rowRemover);
    private final TwitchChatMessageDeletePresenter deletePresenter =
            new TwitchChatMessageDeletePresenter(gson, integratedPresenter, clientExecutor);

    private final EventSubNotificationRouter router = new EventSubNotificationRouter(
            gson,
            Map.of(
                    EventSubEventType.CHAT_MESSAGE.value(), this::display,
                    EventSubEventType.CHAT_MESSAGE_DELETE.value(), deletePresenter::handleMessageDelete));

    /**
     * Mixinで {@code MutableComponent} に生えるタグ用フィールドを模したComponent。
     * setterで保存した値をgetterが返すため、タグ付けと参照の受け渡しは本物と同じ経路を通る。
     */
    private static Component taggableComponent() {
        Component component = mock(Component.class, withSettings().extraInterfaces(StreamTweaksTaggedComponent.class));
        StreamTweaksTaggedComponent tagged = (StreamTweaksTaggedComponent) component;
        DisplayedMessageKey[] holder = new DisplayedMessageKey[1];
        doAnswer(invocation -> {
                    holder[0] = invocation.getArgument(0);
                    return null;
                })
                .when(tagged)
                .streamTweaks$setMessageKey(any(DisplayedMessageKey.class));
        when(tagged.streamTweaks$getMessageKey()).thenAnswer(invocation -> holder[0]);
        return component;
    }

    /**
     * {@code TwitchChatMessagePresenter} の表示処理のうち、ヘッドレスで実行できる部分
     * （NormalizedChatMessageへの変換・追跡登録・タグ付け）を通す。
     */
    private void display(JsonObject payload) {
        ChatMessageNotification notification = gson.fromJson(payload, ChatMessageNotification.class);
        ChatMessageNotification.ChatMessageEvent event = notification.event();
        Component content = taggableComponent();
        NormalizedChatMessage message = new NormalizedChatMessage(
                PlatformId.TWITCH,
                event.messageId(),
                event.broadcasterUserId(),
                event.chatterUserId(),
                Component.literal(event.chatterUserName()),
                Component.literal(event.message().text()));
        integratedPresenter.trackForDeletion(content, message);
        displayedRows.add(new GuiMessage(0, content, null, GuiMessageSource.SYSTEM_SERVER, null));
    }

    private String chatMessageNotification(String broadcasterUserId, String messageId) {
        return """
                {
                  "metadata": {"message_type": "notification"},
                  "payload": {
                    "subscription": {"id": "sub-1", "type": "channel.chat.message", "version": "1"},
                    "event": {
                      "broadcaster_user_id": "%s",
                      "broadcaster_user_login": "streamer",
                      "broadcaster_user_name": "Streamer",
                      "chatter_user_id": "67890",
                      "chatter_user_login": "chatter",
                      "chatter_user_name": "Chatter",
                      "message_id": "%s",
                      "message": {"text": "hello"},
                      "color": "#1E90FF"
                    }
                  }
                }
                """
                .formatted(broadcasterUserId, messageId);
    }

    private String messageDeleteNotification(String broadcasterUserId, String messageId) {
        return """
                {
                  "metadata": {"message_type": "notification"},
                  "payload": {
                    "subscription": {"id": "sub-2", "type": "channel.chat.message_delete", "version": "1"},
                    "event": {
                      "broadcaster_user_id": "%s",
                      "broadcaster_user_login": "streamer",
                      "target_user_id": "67890",
                      "target_user_login": "chatter",
                      "message_id": "%s"
                    }
                  }
                }
                """
                .formatted(broadcasterUserId, messageId);
    }

    @Test
    void displayedMessageIsRemovedFromTheChatWhenTwitchDeletesIt() {
        router.accept(chatMessageNotification(BROADCASTER, "abc-123"));
        assertEquals(1, displayedRows.size(), "表示されていること");

        router.accept(messageDeleteNotification(BROADCASTER, "abc-123"));
        clientExecutor.drain();

        assertTrue(displayedRows.isEmpty(), "削除通知で表示行が消えること");
        assertTrue(
                index.find(new DisplayedMessageKey(PlatformId.TWITCH, "abc-123"))
                        .isEmpty(),
                "索引からも取り除かれること");
    }

    @Test
    void onlyTheDeletedMessageIsRemoved() {
        router.accept(chatMessageNotification(BROADCASTER, "keep-1"));
        router.accept(chatMessageNotification(BROADCASTER, "delete-me"));
        router.accept(chatMessageNotification(BROADCASTER, "keep-2"));

        router.accept(messageDeleteNotification(BROADCASTER, "delete-me"));
        clientExecutor.drain();

        assertEquals(2, displayedRows.size());
        assertTrue(
                index.find(new DisplayedMessageKey(PlatformId.TWITCH, "keep-1")).isPresent());
        assertTrue(
                index.find(new DisplayedMessageKey(PlatformId.TWITCH, "keep-2")).isPresent());
    }

    @Test
    void duplicatedDeliveryOfTheSameMessageRemovesEveryDisplayedRow() {
        // EventSubのat-least-once配信で同一メッセージが2回届き、2行表示された状況
        router.accept(chatMessageNotification(BROADCASTER, "dup-1"));
        router.accept(chatMessageNotification(BROADCASTER, "dup-1"));
        assertEquals(2, displayedRows.size());

        router.accept(messageDeleteNotification(BROADCASTER, "dup-1"));
        clientExecutor.drain();

        assertTrue(displayedRows.isEmpty(), "重複表示された行が両方とも消えること");
    }

    @Test
    void deleteNotificationFromAnotherBroadcasterDoesNotRemoveTheRow() {
        router.accept(chatMessageNotification(BROADCASTER, "abc-123"));

        router.accept(messageDeleteNotification("99999", "abc-123"));
        clientExecutor.drain();

        assertEquals(1, displayedRows.size(), "別チャンネルからの削除通知では消えないこと");
        assertTrue(
                index.find(new DisplayedMessageKey(PlatformId.TWITCH, "abc-123"))
                        .isPresent(),
                "索引も消費されないこと");
    }

    @Test
    void deleteNotificationForAMessageThatWasNeverDisplayedIsIgnored() {
        router.accept(chatMessageNotification(BROADCASTER, "abc-123"));

        router.accept(messageDeleteNotification(BROADCASTER, "never-displayed"));
        clientExecutor.drain();

        assertEquals(1, displayedRows.size());
    }

    @Test
    void deletionsArrivingInTheSameTickAreAppliedTogether() {
        router.accept(chatMessageNotification(BROADCASTER, "abc-1"));
        router.accept(chatMessageNotification(BROADCASTER, "abc-2"));
        router.accept(chatMessageNotification(BROADCASTER, "abc-3"));

        router.accept(messageDeleteNotification(BROADCASTER, "abc-1"));
        router.accept(messageDeleteNotification(BROADCASTER, "abc-3"));
        clientExecutor.drain();

        assertEquals(1, displayedRows.size());
        assertTrue(
                index.find(new DisplayedMessageKey(PlatformId.TWITCH, "abc-2")).isPresent());
    }

    @Test
    void clearingTheIndexOnDisconnectStopsLaterDeletionsFromMatching() {
        router.accept(chatMessageNotification(BROADCASTER, "abc-123"));

        // ワールド退出相当（Minecraft側のチャット欄もクリアされる）
        index.clear();
        rowRemover.clear();
        displayedRows.clear();

        router.accept(messageDeleteNotification(BROADCASTER, "abc-123"));
        clientExecutor.drain();

        assertTrue(displayedRows.isEmpty());
    }
}
