package org.etwas.streamtweaks.twitch.subscription.api;

import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.etwas.streamtweaks.twitch.subscription.domain.SubscriptionId;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;

/**
 * Twitch EventSub APIとのやり取りを抽象化するインターフェース
 * 実装はインフラ層に配置し、依存関係逆転の原則に従う
 *
 * @see <a href="https://dev.twitch.tv/docs/eventsub/">EventSub Overview</a>
 */
public interface TwitchEventSubApi {
    /**
     * EventSubサブスクリプションを作成する
     *
     * @param broadcasterId 配信者のユーザーID
     * @param eventType イベントタイプ（例: "channel.chat.message"）
     * @param sessionId WebSocketセッションID
     * @return 作成されたサブスクリプション情報
     * @see <a href="https://dev.twitch.tv/docs/api/reference/#create-eventsub-subscription">Create EventSub Subscription</a>
     */
    CompletableFuture<EventSubSubscription> createSubscription(
            UserId broadcasterId, String eventType, SessionId sessionId);

    /**
     * EventSubサブスクリプションを削除する
     *
     * @param subscriptionId 削除するサブスクリプションのID
     * @return 削除完了を示すFuture
     * @see <a href="https://dev.twitch.tv/docs/api/reference/#delete-eventsub-subscription">Delete EventSub Subscription</a>
     */
    CompletableFuture<Void> deleteSubscription(SubscriptionId subscriptionId);
}
