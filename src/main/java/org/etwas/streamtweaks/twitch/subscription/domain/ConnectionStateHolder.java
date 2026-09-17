package org.etwas.streamtweaks.twitch.subscription.domain;

import java.util.Optional;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;

public class ConnectionStateHolder {

    private volatile EventSubSessionState state = EventSubSessionState.DISCONNECTED;
    private volatile SessionId sessionId = null;

    public synchronized void transitionToConnecting() {
        this.state = EventSubSessionState.CONNECTING;
        this.sessionId = null;
    }

    public synchronized void transitionToConnected(SessionId sessionId) {
        this.state = EventSubSessionState.CONNECTED;
        this.sessionId = sessionId;
    }

    public synchronized void transitionToDisconnected() {
        this.state = EventSubSessionState.DISCONNECTED;
        this.sessionId = null;
    }

    public EventSubSessionState getState() {
        return state;
    }

    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public boolean isConnected() {
        return state == EventSubSessionState.CONNECTED && sessionId != null;
    }
}
