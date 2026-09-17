package org.etwas.streamtweaks.twitch.auth.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.etwas.streamtweaks.twitch.auth.AccessToken;
import org.etwas.streamtweaks.twitch.auth.TwitchCredential;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTwitchCredentialRepositoryTest {

    @TempDir
    Path configDir;

    @Test
    void saveLoadAndDeleteCredential_usesInjectedPath() {
        Path credentialPath = configDir.resolve("stream-tweaks").resolve("twitch-credentials.json");
        FileTwitchCredentialRepository repository = new FileTwitchCredentialRepository(credentialPath);
        TwitchCredential credential = new TwitchCredential(
                new AccessToken("token"), List.of("user:read:chat"), new UserId("user-id"), new Login("streamer"));

        repository.saveCredential(credential);

        assertEquals(credential, repository.loadCredential());

        repository.deleteCredential();

        assertNull(repository.loadCredential().accessToken());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(credentialPath));
    }
}
