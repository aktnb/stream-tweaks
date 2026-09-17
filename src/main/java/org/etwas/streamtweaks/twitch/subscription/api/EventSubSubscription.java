package org.etwas.streamtweaks.twitch.subscription.api;

import org.etwas.streamtweaks.twitch.core.UserId;
import org.etwas.streamtweaks.twitch.subscription.domain.SubscriptionId;

/**
 * Twitch EventSubのサブスクリプション情報を表すドメインモデル
 *
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#create-eventsub-subscription">Create EventSub Subscription</a>
 * @see <a href="https://dev.twitch.tv/docs/eventsub/manage-subscriptions/#subscription-statuses">Subscription Statuses</a>
 */
public record EventSubSubscription(SubscriptionId id, String type, String status, UserId broadcasterId) {}
