package org.etwas.streamtweaks.presentation.chat.twitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.etwas.streamtweaks.platform.PlatformId;
import org.etwas.streamtweaks.presentation.chat.ChatRowRemover;
import org.etwas.streamtweaks.presentation.chat.DisplayedChatMessage;
import org.etwas.streamtweaks.presentation.chat.DisplayedChatMessageIndex;
import org.etwas.streamtweaks.presentation.chat.DisplayedMessageKey;
import org.etwas.streamtweaks.presentation.chat.DrainingExecutor;
import org.etwas.streamtweaks.presentation.chat.IntegratedChatMessagePresenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TwitchChatMessageDeletePresenterTest {

    private static final String BROADCASTER = "12345";

    private final Gson gson = new Gson();
    private final DisplayedChatMessageIndex index = new DisplayedChatMessageIndex();
    private final List<Set<DisplayedMessageKey>> removedBatches = new ArrayList<>();

    /** メインスレッドのタスク処理を模す。実運用と同じくバッチ集約の挙動を通す。 */
    private final DrainingExecutor clientExecutor = new DrainingExecutor();

    private TwitchChatMessageDeletePresenter presenter;

    private static DisplayedMessageKey key(String messageId) {
        return new DisplayedMessageKey(PlatformId.TWITCH, messageId);
    }

    @BeforeEach
    void setUp() {
        ChatRowRemover rowRemover = new ChatRowRemover(clientExecutor, ids -> {
            removedBatches.add(ids);
            return ids;
        });
        IntegratedChatMessagePresenter integratedPresenter = new IntegratedChatMessagePresenter(index, rowRemover);
        presenter = new TwitchChatMessageDeletePresenter(gson, integratedPresenter, clientExecutor);
    }

    private JsonObject payload(String broadcasterUserId, String messageId) {
        String json =
                """
                {
                  "subscription": {"type": "channel.chat.message_delete"},
                  "event": {
                    "broadcaster_user_id": "%s",
                    "broadcaster_user_login": "streamer",
                    "target_user_id": "67890",
                    "message_id": "%s"
                  }
                }
                """
                        .formatted(broadcasterUserId, messageId);
        return gson.fromJson(json, JsonObject.class);
    }

    private void remember(String messageId) {
        index.remember(key(messageId), new DisplayedChatMessage(BROADCASTER, "67890"));
    }

    /** 削除通知を処理し、メインスレッドのタスクを1tick分流す。 */
    private void handleAndTick(JsonObject payload) {
        presenter.handleMessageDelete(payload);
        clientExecutor.drain();
    }

    @Test
    void removesRowWhenTheMessageIsTracked() {
        remember("abc-123");

        handleAndTick(payload(BROADCASTER, "abc-123"));

        assertEquals(1, removedBatches.size());
        assertEquals(Set.of(key("abc-123")), removedBatches.get(0));
        assertTrue(index.forget(key("abc-123")).isEmpty(), "索引からも取り除かれること");
    }

    @Test
    void collapsesDeletionsArrivingInTheSameTickIntoOneRemovalPass() {
        remember("abc-1");
        remember("abc-2");
        remember("abc-3");

        // 同じtick内に3件の削除通知が届いた状況（drainはまとめて1回だけ）
        presenter.handleMessageDelete(payload(BROADCASTER, "abc-1"));
        presenter.handleMessageDelete(payload(BROADCASTER, "abc-2"));
        presenter.handleMessageDelete(payload(BROADCASTER, "abc-3"));
        clientExecutor.drain();

        assertEquals(1, removedBatches.size(), "チャット欄の走査は1回にまとめられること");
        assertEquals(Set.of(key("abc-1"), key("abc-2"), key("abc-3")), removedBatches.get(0));
    }

    @Test
    void ignoresDeletionForUntrackedMessage() {
        handleAndTick(payload(BROADCASTER, "never-displayed"));

        assertTrue(removedBatches.isEmpty());
    }

    @Test
    void ignoresDeletionFromAMismatchedBroadcaster() {
        remember("abc-123");

        handleAndTick(payload("99999", "abc-123"));

        assertTrue(removedBatches.isEmpty(), "別チャンネルからの削除通知は適用しないこと");
    }

    @Test
    void mismatchedBroadcasterDoesNotConsumeTheTrackedEntry() {
        remember("abc-123");

        // 不正な（別チャンネルを名乗る）通知が先に届いても索引を消費しない
        handleAndTick(payload("99999", "abc-123"));
        // その後に届く正当な削除通知はきちんと効くこと
        handleAndTick(payload(BROADCASTER, "abc-123"));

        assertEquals(1, removedBatches.size());
        assertEquals(Set.of(key("abc-123")), removedBatches.get(0));
    }

    @Test
    void ignoresDeletionWithoutBroadcasterUserId() {
        remember("abc-123");

        JsonObject payload = gson.fromJson(
                "{\"event\": {\"message_id\": \"abc-123\", \"target_user_id\": \"1\"}}", JsonObject.class);
        handleAndTick(payload);

        assertTrue(removedBatches.isEmpty());
        assertTrue(index.forget(key("abc-123")).isPresent(), "検証前に索引を消してしまわないこと");
    }

    @Test
    void ignoresDeletionWithAnUnacceptableMessageId() {
        handleAndTick(payload(BROADCASTER, "a".repeat(TwitchMessageIds.MAX_LENGTH + 1)));
        handleAndTick(payload(BROADCASTER, ""));

        assertTrue(removedBatches.isEmpty());
    }

    @Test
    void ignoresPayloadWithoutEvent() {
        handleAndTick(gson.fromJson("{\"subscription\": {}}", JsonObject.class));

        assertTrue(removedBatches.isEmpty());
    }

    @Test
    void requestsRemovalOnceForADuplicatedMessageId() {
        // at-least-once配信で同じmessage_idが二重表示された場合、削除要求は1つのIDにまとまる
        // （実際に複数行が消えることは MinecraftChatRowsTest で検証している）
        remember("abc-123");
        remember("abc-123");

        handleAndTick(payload(BROADCASTER, "abc-123"));

        assertEquals(1, removedBatches.size());
        assertEquals(Set.of(key("abc-123")), removedBatches.get(0));
    }
}
