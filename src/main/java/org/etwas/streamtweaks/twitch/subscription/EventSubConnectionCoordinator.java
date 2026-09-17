package org.etwas.streamtweaks.twitch.subscription;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.etwas.streamtweaks.twitch.subscription.domain.ActualSubscriptionRegistry;
import org.etwas.streamtweaks.twitch.subscription.domain.ConnectionStateHolder;
import org.etwas.streamtweaks.twitch.subscription.domain.SubscriptionId;
import org.etwas.streamtweaks.twitch.subscription.websocket.EventSubWebSocketClient;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EventSubConnectionCoordinator implements EventSubWebSocketClient.EventSubWebSocketListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventSubConnectionCoordinator.class);
    private static final Gson GSON = new GsonBuilder().create();

    private final EventSubWebSocketClient webSocketClient;
    private final ConnectionStateHolder stateHolder;
    private final ActualSubscriptionRegistry actualRegistry;
    private final SubscriptionReconciler reconciler;

    // WebSocket受信スレッドからの読み取りとsetNotificationHandlerからの書き込みが競合しても
    // stale readやNPEが起きないよう、volatileかつデフォルトでno-opにしておく。
    private volatile Consumer<String> notificationHandler = payload -> {};
    private CompletableFuture<SessionId> pendingConnect;

    public EventSubConnectionCoordinator(
            EventSubWebSocketClient webSocketClient,
            ConnectionStateHolder stateHolder,
            ActualSubscriptionRegistry actualRegistry,
            SubscriptionReconciler reconciler) {
        this.webSocketClient = webSocketClient;
        this.stateHolder = stateHolder;
        this.actualRegistry = actualRegistry;
        this.reconciler = reconciler;
        webSocketClient.setListener(this);
    }

    public void setNotificationHandler(Consumer<String> notificationHandler) {
        this.notificationHandler = notificationHandler;
    }

    public synchronized CompletableFuture<SessionId> ensureConnected() {
        if (stateHolder.isConnected()) {
            return CompletableFuture.completedFuture(stateHolder
                    .getSessionId()
                    .orElseThrow(() -> new IllegalStateException("Connected but no session ID")));
        }

        if (pendingConnect != null && !pendingConnect.isDone()) {
            return pendingConnect;
        }

        stateHolder.transitionToConnecting();
        pendingConnect = webSocketClient
                .connect()
                .thenApply(sessionId -> {
                    stateHolder.transitionToConnected(sessionId);
                    return sessionId;
                })
                .exceptionally(ex -> {
                    stateHolder.transitionToDisconnected();
                    throw new java.util.concurrent.CompletionException(ex);
                });
        return pendingConnect;
    }

    @Override
    public synchronized void onDisconnected() {
        // 旧セッションのactualを破棄（接続が切れているためdelete APIは呼ばない）
        actualRegistry.clearAndSnapshot();
        stateHolder.transitionToDisconnected();
        LOGGER.warn("EventSub WebSocket disconnected");
    }

    @Override
    public synchronized void onReconnected(SessionId newSessionId) {
        // 旧セッションのactualを破棄してから新セッションに遷移する
        actualRegistry.clearAndSnapshot();
        stateHolder.transitionToConnected(newSessionId);
        LOGGER.info("EventSub WebSocket reconnected with session: {}", newSessionId.value());

        reconciler.reconcile(newSessionId).exceptionally(ex -> {
            LOGGER.error("Failed to reconcile subscriptions after reconnect", ex);
            return null;
        });
    }

    @Override
    public void onNotification(String payload) {
        notificationHandler.accept(payload);
    }

    @Override
    public synchronized void onRevocation(String payload) {
        parseRevocationSubscriptionId(payload).ifPresent(subId -> {
            boolean removed = actualRegistry.deregisterBySubscriptionId(subId);
            if (removed) {
                LOGGER.warn("Subscription revoked, will re-subscribe: {}", subId.value());
                reconcileIfConnected().exceptionally(ex -> {
                    LOGGER.error("Failed to reconcile after revocation", ex);
                    return null;
                });
            }
        });
    }

    public CompletableFuture<Void> reconcileIfConnected() {
        return stateHolder
                .getSessionId()
                .map(sessionId -> reconciler.reconcile(sessionId))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    public void disconnect() {
        webSocketClient.disconnect();
    }

    public void shutdown() {
        webSocketClient.shutdown();
    }

    public boolean isConnected() {
        return stateHolder.isConnected();
    }

    private Optional<SubscriptionId> parseRevocationSubscriptionId(String payload) {
        try {
            JsonObject json = GSON.fromJson(payload, JsonObject.class);
            JsonObject payloadObj = json.getAsJsonObject("payload");
            if (payloadObj == null) {
                return Optional.empty();
            }
            JsonObject subscription = payloadObj.getAsJsonObject("subscription");
            if (subscription == null) {
                return Optional.empty();
            }
            var idElement = subscription.get("id");
            if (idElement == null || idElement.isJsonNull()) {
                return Optional.empty();
            }
            return Optional.of(new SubscriptionId(idElement.getAsString()));
        } catch (Exception e) {
            LOGGER.error("Failed to parse revocation payload", e);
            return Optional.empty();
        }
    }
}
