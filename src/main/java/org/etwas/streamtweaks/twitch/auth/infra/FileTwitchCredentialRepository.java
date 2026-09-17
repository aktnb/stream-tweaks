package org.etwas.streamtweaks.twitch.auth.infra;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.etwas.streamtweaks.twitch.auth.AccessToken;
import org.etwas.streamtweaks.twitch.auth.TwitchCredential;
import org.etwas.streamtweaks.twitch.auth.TwitchCredentialRepository;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileTwitchCredentialRepository implements TwitchCredentialRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileTwitchCredentialRepository.class);

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(
                    AccessToken.class, (JsonSerializer<AccessToken>) (src, type, ctx) -> new JsonPrimitive(src.value()))
            .registerTypeAdapter(AccessToken.class, (JsonDeserializer<AccessToken>)
                    (json, type, ctx) -> json.isJsonNull() ? null : new AccessToken(json.getAsString()))
            .registerTypeAdapter(
                    UserId.class, (JsonSerializer<UserId>) (src, type, ctx) -> new JsonPrimitive(src.value()))
            .registerTypeAdapter(UserId.class, (JsonDeserializer<UserId>)
                    (json, type, ctx) -> json.isJsonNull() ? null : new UserId(json.getAsString()))
            .registerTypeAdapter(
                    Login.class, (JsonSerializer<Login>) (src, type, ctx) -> new JsonPrimitive(src.value()))
            .registerTypeAdapter(Login.class, (JsonDeserializer<Login>)
                    (json, type, ctx) -> json.isJsonNull() ? null : new Login(json.getAsString()))
            .create();
    private final Path filePath;

    public FileTwitchCredentialRepository(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public void saveCredential(TwitchCredential credential) {
        try {
            Files.createDirectories(filePath.getParent());
            var tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(
                    tmp, gson.toJson(credential), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                    Set<PosixFilePermission> perms =
                            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                    Files.setPosixFilePermissions(tmp, perms);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to set file permissions on {}", tmp, e);
            }
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save Twitch credentials to {}", filePath, e);
        }
    }

    @Override
    public void deleteCredential() {
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            LOGGER.error("Failed to delete Twitch credentials at {}", filePath, e);
        }
    }

    @Override
    public TwitchCredential loadCredential() {
        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                return new TwitchCredential(null, null, null, null);
            }
            return gson.fromJson(Files.readString(filePath), TwitchCredential.class);
        } catch (IOException e) {
            LOGGER.error("Failed to load Twitch credentials from {}", filePath, e);
            return new TwitchCredential(null, null, null, null);
        }
    }
}
