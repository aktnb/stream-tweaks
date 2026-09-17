package org.etwas.streamtweaks.twitch.auth;

public interface TwitchCredentialRepository {
    void saveCredential(TwitchCredential credential);

    TwitchCredential loadCredential();

    void deleteCredential();
}
