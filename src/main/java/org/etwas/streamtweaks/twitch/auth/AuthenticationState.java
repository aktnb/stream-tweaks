package org.etwas.streamtweaks.twitch.auth;

public enum AuthenticationState {
    IDLE,
    WAITING_FOR_CALLBACK,
    AUTHENTICATED,
    FAILURE,
    TIMEOUT,
    CANCELLED,
}
