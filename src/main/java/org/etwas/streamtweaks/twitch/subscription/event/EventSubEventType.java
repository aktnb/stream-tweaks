package org.etwas.streamtweaks.twitch.subscription.event;

/**
 * このMODが購読するEventSubイベントタイプ。
 *
 * <p>いずれも version {@code 1}、condition は {@code broadcaster_user_id} / {@code user_id} で共通のため、
 * {@code /twitch connect} 実行時にまとめて購読される。
 *
 * @see <a href="https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#channelchatmessage">channel.chat.message</a>
 * @see <a href="https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#channelchatmessage_delete">channel.chat.message_delete</a>
 */
public enum EventSubEventType {
    CHAT_MESSAGE("channel.chat.message"),
    CHAT_MESSAGE_DELETE("channel.chat.message_delete");

    private final String value;

    EventSubEventType(String value) {
        this.value = value;
    }

    /**
     * Twitch APIのワイヤ表現（{@code type} フィールドの文字列）を返す。
     */
    public String value() {
        return value;
    }
}
