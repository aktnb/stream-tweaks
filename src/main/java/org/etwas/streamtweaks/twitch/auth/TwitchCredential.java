package org.etwas.streamtweaks.twitch.auth;

import java.util.List;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.UserId;

public record TwitchCredential(AccessToken accessToken, List<String> scopes, UserId userId, Login login) {}
