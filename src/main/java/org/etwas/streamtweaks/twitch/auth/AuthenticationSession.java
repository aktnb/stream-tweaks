package org.etwas.streamtweaks.twitch.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.etwas.streamtweaks.twitch.core.TwitchConstants;

public final class AuthenticationSession {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String state;
    private final List<String> scopes;

    private AuthenticationSession(String state, List<String> scopes) {
        this.state = state;
        this.scopes = List.copyOf(scopes);
    }

    public void verifyState(String returnedState) throws IllegalStateException {
        if (!Objects.equals(this.state, returnedState)) {
            throw new IllegalStateException("Failed to verify state parameter");
        }
    }

    // https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#implicit-grant-flow
    public URI getAuthorizationUrl() {
        return URI.create("https://id.twitch.tv/oauth2/authorize" + "?client_id="
                + TwitchConstants.CLIENT_ID + "&redirect_uri="
                + URLEncoder.encode(TwitchConstants.REDIRECT_URI, StandardCharsets.UTF_8) + "&response_type=token"
                + "&scope="
                + String.join("%20", scopes) + "&state="
                + state);
    }

    public static AuthenticationSession create(List<String> scopes) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return new AuthenticationSession(HexFormat.of().formatHex(bytes), scopes);
    }
}
