package org.etwas.streamtweaks.twitch.badge.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.badge.domain.ChatBadge;
import org.etwas.streamtweaks.twitch.core.UserId;

/**
 * Twitch Helix APIのチャットバッジ取得エンドポイントを抽象化するインターフェース。
 * 実装はインフラ層に配置し、依存関係逆転の原則に従う。
 *
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#get-global-chat-badges">Get Global Chat Badges</a>
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#get-channel-chat-badges">Get Channel Chat Badges</a>
 */
public interface TwitchChatBadgeApi {

    /**
     * 全チャンネル共通のグローバルバッジセット（moderator, vip, turbo等）を取得する。
     */
    CompletableFuture<Map<BadgeKey, ChatBadge>> getGlobalBadges();

    /**
     * 指定チャンネル固有のバッジセット（subscriber, bits等。チャンネルによるカスタム画像を含む）を取得する。
     *
     * @param broadcasterId 配信者のユーザーID
     */
    CompletableFuture<Map<BadgeKey, ChatBadge>> getChannelBadges(UserId broadcasterId);
}
