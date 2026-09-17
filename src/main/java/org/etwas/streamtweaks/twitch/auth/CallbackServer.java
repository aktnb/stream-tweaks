package org.etwas.streamtweaks.twitch.auth;

import java.util.concurrent.CompletableFuture;

public interface CallbackServer {
    void start();

    void stop();

    CompletableFuture<OAuthCallback> onCallback();
}
