package org.etwas.streamtweaks.twitch.auth;

public record AccessToken(String value) {
    @Override
    public String toString() {
        return "AccessToken[REDACTED]";
    }
}
