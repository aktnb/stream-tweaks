package org.etwas.streamtweaks.twitch.subscription.domain;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.etwas.streamtweaks.twitch.core.UserId;

public class DesiredSubscriptionStore {

    private final Set<UserId> desired = ConcurrentHashMap.newKeySet();

    public boolean add(UserId broadcasterId) {
        return desired.add(broadcasterId);
    }

    public boolean remove(UserId broadcasterId) {
        return desired.remove(broadcasterId);
    }

    public void clear() {
        desired.clear();
    }

    public Set<UserId> snapshot() {
        return Set.copyOf(desired);
    }

    public boolean contains(UserId broadcasterId) {
        return desired.contains(broadcasterId);
    }
}
