package org.etwas.streamtweaks.presentation.chat.twitch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.util.concurrent.Executor;
import org.etwas.streamtweaks.platform.PlatformId;
import org.etwas.streamtweaks.presentation.chat.IntegratedChatMessagePresenter;
import org.etwas.streamtweaks.twitch.subscription.event.ChatMessageDeleteNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * channel.chat.message_delete 通知を解析し、{@link IntegratedChatMessagePresenter} へ削除を委譲する。
 *
 * <p>Twitch固有の削除ペイロード解析（{@code message_id}・{@code broadcaster_user_id} の検証）は
 * ここで行うが、実際の索引・チャット欄の操作は {@link IntegratedChatMessagePresenter} の責務であり、
 * このクラスは関与しない。
 *
 * <p>{@link IntegratedChatMessagePresenter#presentDelete(PlatformId, String, String)} へ渡す
 * {@code platform} 引数には、通知ペイロードの中身から読み取った値ではなく、
 * このクラス自身の識別子である {@link PlatformId#TWITCH} を定数として渡す
 * （なりすまし防止のため、呼び出し元が固定的に渡すことが前提の設計になっている）。
 *
 * <p>ログにメッセージ本文・{@code target_user_login} 等は出さない。
 */
public final class TwitchChatMessageDeletePresenter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TwitchChatMessageDeletePresenter.class);

    private final Gson gson;
    private final IntegratedChatMessagePresenter integratedPresenter;
    private final Executor clientExecutor;

    public TwitchChatMessageDeletePresenter(
            Gson gson, IntegratedChatMessagePresenter integratedPresenter, Executor clientExecutor) {
        this.gson = gson;
        this.integratedPresenter = integratedPresenter;
        this.clientExecutor = clientExecutor;
    }

    /**
     * 削除通知を処理する。WebSocketスレッドから呼ばれる。
     */
    public void handleMessageDelete(JsonObject payload) {
        ChatMessageDeleteNotification notification;
        try {
            notification = gson.fromJson(payload, ChatMessageDeleteNotification.class);
        } catch (JsonSyntaxException e) {
            LOGGER.error("Failed to parse chat message delete notification");
            return;
        }
        if (notification == null || notification.event() == null) {
            return;
        }

        String messageId = notification.event().messageId();
        String broadcasterUserId = notification.event().broadcasterUserId();
        if (!TwitchMessageIds.isValid(messageId)) {
            LOGGER.debug("Ignored chat message delete notification with an unacceptable message_id");
            return;
        }
        if (broadcasterUserId == null || broadcasterUserId.isBlank()) {
            LOGGER.debug("Ignored chat message delete notification without broadcaster_user_id");
            return;
        }

        // 索引とチャット欄の操作はメインスレッドに閉じ込める。
        clientExecutor.execute(
                () -> integratedPresenter.presentDelete(PlatformId.TWITCH, messageId, broadcasterUserId));
    }
}
