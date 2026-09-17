package org.etwas.streamtweaks.twitch.auth;

import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.UserId;

public record TokenValidationResult(boolean isValid, UserId userId, Login login) {
    public static final TokenValidationResult INVALID = new TokenValidationResult(false, null, null);

    public static TokenValidationResult valid(UserId userId, Login login) {
        return new TokenValidationResult(true, userId, login);
    }
}
