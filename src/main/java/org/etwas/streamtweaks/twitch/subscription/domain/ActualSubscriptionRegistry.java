package org.etwas.streamtweaks.twitch.subscription.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 現在のWebSocketセッションで実際に作成済みの購読を保持する（actual state）。
 *
 * <p>キーは「チャンネル × イベントタイプ」（{@link SubscriptionKey}）。
 * 1チャンネルにつき複数のイベントタイプを購読するため、チャンネル単位では一意にならない。
 */
public class ActualSubscriptionRegistry {

    private final Map<SubscriptionKey, SubscriptionId> registry = new ConcurrentHashMap<>();

    public void register(SubscriptionKey key, SubscriptionId subscriptionId) {
        registry.put(key, subscriptionId);
    }

    public void deregister(SubscriptionKey key) {
        registry.remove(key);
    }

    public boolean deregisterBySubscriptionId(SubscriptionId subscriptionId) {
        for (Map.Entry<SubscriptionKey, SubscriptionId> entry : registry.entrySet()) {
            if (entry.getValue().equals(subscriptionId)) {
                return registry.remove(entry.getKey(), entry.getValue());
            }
        }
        return false;
    }

    public Optional<SubscriptionId> getSubscriptionId(SubscriptionKey key) {
        return Optional.ofNullable(registry.get(key));
    }

    public Set<SubscriptionKey> activeKeys() {
        return Set.copyOf(registry.keySet());
    }

    public Map<SubscriptionKey, SubscriptionId> clearAndSnapshot() {
        Map<SubscriptionKey, SubscriptionId> snapshot = Map.copyOf(registry);
        registry.clear();
        return snapshot;
    }

    public boolean isRegistered(SubscriptionKey key) {
        return registry.containsKey(key);
    }
}
