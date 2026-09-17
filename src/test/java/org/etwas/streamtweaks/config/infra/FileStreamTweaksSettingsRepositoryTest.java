package org.etwas.streamtweaks.config.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.etwas.streamtweaks.config.StreamTweaksSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStreamTweaksSettingsRepositoryTest {

    @TempDir
    Path configDir;

    @Test
    void loadReturnsDefaultWhenFileDoesNotExist() {
        Path settingsPath = configDir.resolve("stream-tweaks").resolve("stream-tweaks-config.toml");
        FileStreamTweaksSettingsRepository repository = new FileStreamTweaksSettingsRepository(settingsPath);

        assertEquals(StreamTweaksSettings.DEFAULT, repository.load());
        assertFalse(Files.exists(settingsPath), "load()はファイルを新規作成しないこと");
    }

    @Test
    void saveAndLoadRoundTripsTheSettings() {
        Path settingsPath = configDir.resolve("stream-tweaks").resolve("stream-tweaks-config.toml");
        FileStreamTweaksSettingsRepository repository = new FileStreamTweaksSettingsRepository(settingsPath);

        repository.save(new StreamTweaksSettings(false));

        assertTrue(Files.exists(settingsPath));
        assertEquals(new StreamTweaksSettings(false), repository.load());
    }

    @Test
    void loadReturnsDefaultWhenFileHasInvalidTomlSyntax() throws IOException {
        Path settingsPath = configDir.resolve("stream-tweaks").resolve("stream-tweaks-config.toml");
        Files.createDirectories(settingsPath.getParent());
        // キーの後にYAML風の ':' が来ており、TOML構文として不正（'=' が必要）。
        Files.writeString(settingsPath, "showBadges: true");
        FileStreamTweaksSettingsRepository repository = new FileStreamTweaksSettingsRepository(settingsPath);

        assertEquals(StreamTweaksSettings.DEFAULT, repository.load());
    }

    @Test
    void loadReturnsDefaultWhenShowBadgesHasWrongType() throws IOException {
        Path settingsPath = configDir.resolve("stream-tweaks").resolve("stream-tweaks-config.toml");
        Files.createDirectories(settingsPath.getParent());
        // 構文自体は正しいが、showBadgesがbooleanではなく文字列になっている型不一致。
        Files.writeString(settingsPath, "showBadges = \"yes please\"");
        FileStreamTweaksSettingsRepository repository = new FileStreamTweaksSettingsRepository(settingsPath);

        assertEquals(StreamTweaksSettings.DEFAULT, repository.load());
    }

    @Test
    void loadReturnsDefaultWhenFileDoesNotContainShowBadgesKey() throws IOException {
        Path settingsPath = configDir.resolve("stream-tweaks").resolve("stream-tweaks-config.toml");
        Files.createDirectories(settingsPath.getParent());
        // 構文としては正しい空のTOMLだが、showBadgesキー自体が存在しない。
        Files.writeString(settingsPath, "");
        FileStreamTweaksSettingsRepository repository = new FileStreamTweaksSettingsRepository(settingsPath);

        assertEquals(StreamTweaksSettings.DEFAULT, repository.load());
    }

    @Test
    void saveOverwritesPreviouslySavedSettings() {
        Path settingsPath = configDir.resolve("stream-tweaks").resolve("stream-tweaks-config.toml");
        FileStreamTweaksSettingsRepository repository = new FileStreamTweaksSettingsRepository(settingsPath);

        repository.save(new StreamTweaksSettings(false));
        repository.save(new StreamTweaksSettings(true));

        assertEquals(new StreamTweaksSettings(true), repository.load());
    }
}
