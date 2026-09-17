package org.etwas.streamtweaks.twitch.auth;

public record OAuthCallback(AccessToken token, String state) {}
