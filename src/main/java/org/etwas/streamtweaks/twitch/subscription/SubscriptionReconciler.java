package org.etwas.streamtweaks.twitch.subscription;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.etwas.streamtweaks.twitch.subscription.api.TwitchEventSubApi;
import org.etwas.streamtweaks.twitch.subscription.domain.ActualSubscriptionRegistry;
import org.etwas.streamtweaks.twitch.subscription.domain.DesiredSubscriptionStore;
import org.etwas.streamtweaks.twitch.subscription.domain.SubscriptionKey;
import org.etwas.streamtweaks.twitch.subscription.event.EventSubEventType;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * desired（購読したいチャンネル）とactual（実際に作成済みの購読）の差分を同期する。
 *
 * <p>desiredはチャンネル単位だが、1チャンネルにつき {@link EventSubEventType} の全タイプを購読するため、
 * 差分計算は「チャンネル × イベントタイプ」の直積（{@link SubscriptionKey}）を単位に行う。
 * これにより、片方のイベントタイプだけAPI呼び出しに失敗した場合でも、
 * 次回のreconcileで欠けている組み合わせだけが再作成される（冪等）。
 */
public class SubscriptionReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionReconciler.class);

    private final DesiredSubscriptionStore desiredStore;
    private final ActualSubscriptionRegistry actualRegistry;
    private final TwitchEventSubApi eventSubApi;

    public SubscriptionReconciler(
            DesiredSubscriptionStore desiredStore,
            ActualSubscriptionRegistry actualRegistry,
            TwitchEventSubApi eventSubApi) {
        this.desiredStore = desiredStore;
        this.actualRegistry = actualRegistry;
        this.eventSubApi = eventSubApi;
    }

    public CompletableFuture<Void> reconcile(SessionId sessionId) {
        Set<SubscriptionKey> desired = desiredKeys();
        Set<SubscriptionKey> actual = actualRegistry.activeKeys();

        Set<SubscriptionKey> toSubscribe =
                desired.stream().filter(key -> !actual.contains(key)).collect(Collectors.toSet());
        Set<SubscriptionKey> toUnsubscribe =
                actual.stream().filter(key -> !desired.contains(key)).collect(Collectors.toSet());

        if (toSubscribe.isEmpty() && toUnsubscribe.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        var subscribeFutures = toSubscribe.stream()
                .map(key -> eventSubApi
                        .createSubscription(key.broadcasterId(), key.eventType().value(), sessionId)
                        .thenAccept(sub -> {
                            actualRegistry.register(key, sub.id());
                            LOGGER.info(
                                    "Subscription created: {} ({}) for broadcaster {}",
                                    sub.id().value(),
                                    key.eventType().value(),
                                    key.broadcasterId().value());
                        })
                        .exceptionally(ex -> {
                            LOGGER.error(
                                    "Failed to subscribe {} for broadcaster {}",
                                    key.eventType().value(),
                                    key.broadcasterId().value(),
                                    ex);
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);

        var unsubscribeFutures = toUnsubscribe.stream()
                .map(key -> actualRegistry
                        .getSubscriptionId(key)
                        .map(subId -> eventSubApi
                                .deleteSubscription(subId)
                                .thenAccept(v -> {
                                    actualRegistry.deregister(key);
                                    LOGGER.info(
                                            "Subscription deleted: {} ({}) for broadcaster {}",
                                            subId.value(),
                                            key.eventType().value(),
                                            key.broadcasterId().value());
                                })
                                .exceptionally(ex -> {
                                    LOGGER.error(
                                            "Failed to unsubscribe {} for broadcaster {}",
                                            key.eventType().value(),
                                            key.broadcasterId().value(),
                                            ex);
                                    return null;
                                }))
                        .orElseGet(() -> CompletableFuture.completedFuture(null)))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(
                CompletableFuture.allOf(subscribeFutures), CompletableFuture.allOf(unsubscribeFutures));
    }

    private Set<SubscriptionKey> desiredKeys() {
        return desiredStore.snapshot().stream()
                .flatMap(broadcasterId -> Stream.of(EventSubEventType.values())
                        .map(eventType -> new SubscriptionKey(broadcasterId, eventType)))
                .collect(Collectors.toSet());
    }
}
