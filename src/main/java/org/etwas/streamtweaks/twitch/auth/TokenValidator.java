package org.etwas.streamtweaks.twitch.auth;

import java.util.List;

public interface TokenValidator {
    TokenValidationResult validateToken(AccessToken token, List<String> requiredScopes);
}
