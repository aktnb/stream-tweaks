package org.etwas.streamtweaks.twitch.subscription.event;

import com.google.gson.annotations.SerializedName;

/**
 * channel.chat.message_delete イベントの通知データ構造。
 *
 * <p>モデレーター／配信者による「個別メッセージの削除」のみを表す。
 * timeout / ban による一括削除（{@code channel.chat.clear_user_messages}）や
 * チャット全消去（{@code channel.chat.clear}）は別イベントであり、このMODでは扱わない。
 *
 * <p>{@code subscription} はルーティング時に {@code EventSubNotificationRouter} が解決済みのため保持しない。
 *
 * @see <a href="https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#channelchatmessage_delete">channel.chat.message_delete Subscription Type</a>
 */
public record ChatMessageDeleteNotification(MessageDeleteEvent event) {

    /**
     * 削除イベント本体。
     *
     * <p>{@code target_user_id} は削除されたメッセージの投稿者。今回の削除ロジックでは使用しないが、
     * 将来の一括削除対応時に必要となるため定義しておく。
     */
    public record MessageDeleteEvent(
            @SerializedName("broadcaster_user_id") String broadcasterUserId,
            @SerializedName("target_user_id") String targetUserId,
            @SerializedName("message_id") String messageId) {}
}
