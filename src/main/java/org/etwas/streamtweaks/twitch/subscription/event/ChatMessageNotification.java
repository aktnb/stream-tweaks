package org.etwas.streamtweaks.twitch.subscription.event;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * channel.chat.message イベントの通知データ構造
 *
 * @see <a href="https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#channelchatmessage">channel.chat.message Subscription Type</a>
 * @see <a href="https://dev.twitch.tv/docs/eventsub/handling-websocket-events/#notification-message">Notification Message Format</a>
 */
public record ChatMessageNotification(SubscriptionInfo subscription, ChatMessageEvent event) {

    public record SubscriptionInfo(
            String id,
            String type,
            String version,
            String status,
            Condition condition,
            @SerializedName("created_at") String createdAt) {}

    public record Condition(
            @SerializedName("broadcaster_user_id") String broadcasterUserId,
            @SerializedName("user_id") String userId) {}

    public record ChatMessageEvent(
            @SerializedName("broadcaster_user_id") String broadcasterUserId,
            @SerializedName("broadcaster_user_login") String broadcasterUserLogin,
            @SerializedName("broadcaster_user_name") String broadcasterUserName,
            @SerializedName("chatter_user_id") String chatterUserId,
            @SerializedName("chatter_user_login") String chatterUserLogin,
            @SerializedName("chatter_user_name") String chatterUserName,
            @SerializedName("message_id") String messageId,
            Message message,
            String color,
            @Nullable List<Badge> badges) {}

    /**
     * chatterが装着しているバッジ1件分の識別情報。画像URLは含まれないため、
     * 実際の画像は {@code twitch.badge} パッケージのカタログ経由で別途解決する。
     *
     * @param setId バッジセットのID（例: "moderator", "subscriber"）
     * @param id セット内のバージョンID（例: サブスクライバーバッジの継続月数tier）
     * @param info 補足情報（継続月数等）。将来のツールチップ表示用に保持する
     */
    public record Badge(@SerializedName("set_id") String setId, String id, @Nullable String info) {}

    public record Message(String text, @Nullable @SerializedName("fragments") List<Fragment> fragments) {}

    public record Fragment(String type, String text, @Nullable EmoteInfo emote) {
        public boolean isEmote() {
            return emote != null;
        }
    }

    public record EmoteInfo(
            @Nullable String id,
            @Nullable @SerializedName("emote_set_id") String emoteSetId,
            @Nullable @SerializedName("owner_id") String ownerId,
            @Nullable List<String> format) {}
}
