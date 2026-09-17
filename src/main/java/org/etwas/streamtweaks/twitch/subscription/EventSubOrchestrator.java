package org.etwas.streamtweaks.twitch.subscription;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.etwas.streamtweaks.twitch.subscription.api.TwitchEventSubApi;
import org.etwas.streamtweaks.twitch.subscription.domain.ActualSubscriptionRegistry;
import org.etwas.streamtweaks.twitch.subscription.domain.ConnectionStateHolder;
import org.etwas.streamtweaks.twitch.subscription.domain.DesiredSubscriptionStore;
import org.etwas.streamtweaks.twitch.subscription.websocket.EventSubWebSocketClient;

public class EventSubOrchestrator {

    private final DesiredSubscriptionStore desiredStore;
    private final EventSubConnectionCoordinator coordinator;
    private final SubscriptionReconciler reconciler;

    public EventSubOrchestrator(TwitchEventSubApi eventSubApi, EventSubWebSocketClient webSocketClient) {
        this.desiredStore = new DesiredSubscriptionStore();
        var actualRegistry = new ActualSubscriptionRegistry();
        var stateHolder = new ConnectionStateHolder();
        this.reconciler = new SubscriptionReconciler(desiredStore, actualRegistry, eventSubApi);
        this.coordinator = new EventSubConnectionCoordinator(webSocketClient, stateHolder, actualRegistry, reconciler);
    }

    /** broadcasterId で識別されるチャンネルを購読したいという意図を表明する。 */
    public CompletableFuture<Void> subscribe(UserId broadcasterId) {
        if (!desiredStore.add(broadcasterId)) {
            return CompletableFuture.completedFuture(null);
        }
        return coordinator.ensureConnected().thenCompose(sessionId -> reconciler.reconcile(sessionId));
    }

    /** 購読したくないという意図を表明する。 */
    public CompletableFuture<Void> unsubscribe(UserId broadcasterId) {
        desiredStore.remove(broadcasterId);
        return coordinator.reconcileIfConnected();
    }

    /** 全チャンネルの購読をやめる。 */
    public CompletableFuture<Void> unsubscribeAll() {
        desiredStore.clear();
        return coordinator.reconcileIfConnected();
    }

    /** イベント到着時に呼ばれるリスナーを登録する。 */
    public void setNotificationListener(Consumer<String> listener) {
        coordinator.setNotificationHandler(listener);
    }

    /** 今イベントを受信できる状態かを問い合わせる。 */
    public boolean isConnected() {
        return coordinator.isConnected();
    }

    /** 明示的に接続を終了する。 */
    public void close() {
        coordinator.disconnect();
    }

    /** Minecraft終了時に、再利用しないバックグラウンドリソースまで破棄する。 */
    public void shutdown() {
        coordinator.shutdown();
    }
}
