package org.etwas.streamtweaks.twitch.subscription.websocket;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

// https://dev.twitch.tv/docs/eventsub/handling-websocket-events/
public interface EventSubWebSocketClient {
    CompletableFuture<SessionId> connect();

    void disconnect();

    void shutdown();

    Optional<SessionId> getCurrentSessionId();

    void setListener(EventSubWebSocketListener listener);

    interface EventSubWebSocketListener {
        void onDisconnected();

        // https://dev.twitch.tv/docs/eventsub/websocket-reference/#reconnect-message
        void onReconnected(SessionId sessionId);

        // https://dev.twitch.tv/docs/eventsub/websocket-reference/#notification-message
        void onNotification(String payload);

        // https://dev.twitch.tv/docs/eventsub/websocket-reference/#revocation-message
        void onRevocation(String payload);
    }
}
