package org.etwas.streamtweaks.config.infra;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.etwas.streamtweaks.config.StreamTweaksSettings;
import org.etwas.streamtweaks.config.StreamTweaksSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StreamTweaksSettings} をTOMLファイルとして永続化する実装。
 *
 * <p>認証情報用の {@code FileTwitchCredentialRepository} と同様、一時ファイルへ書き込んでから
 * アトミックにリネームすることで書き込み中のクラッシュに対する耐性を持たせる。ただし
 * 認証情報と異なりトークン等の機微情報を含まないため、パーミッション制御は行わない。
 *
 * <p>night-config の {@code TomlParser}/{@code TomlWriter} は文字列との相互変換のみを担い、
 * ファイルI/O自体は行わない（{@code FileConfig} を使うとnight-config側が独自にファイルI/Oを
 * 行ってしまい、このリポジトリが持つ一時ファイル＋アトミックリネームの方針と衝突するため、
 * あえて使わない）。イミュータブルな {@link StreamTweaksSettings} レコードとの相互変換は
 * {@code CommentedConfig} を経由して手動で行う。
 *
 * <p>ファイルが存在しない、または壊れている（TOML構文エラー・型不一致等）場合は
 * {@link StreamTweaksSettings#DEFAULT} にフォールバックする。
 */
public final class FileStreamTweaksSettingsRepository implements StreamTweaksSettingsRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileStreamTweaksSettingsRepository.class);
    private static final String SHOW_BADGES_KEY = "showBadges";

    private final TomlParser tomlParser = new TomlParser();
    private final TomlWriter tomlWriter = new TomlWriter();
    private final Path filePath;

    public FileStreamTweaksSettingsRepository(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public StreamTweaksSettings load() {
        try {
            if (!Files.exists(filePath)) {
                return StreamTweaksSettings.DEFAULT;
            }
            CommentedConfig config = tomlParser.parse(Files.readString(filePath));
            return toSettings(config);
        } catch (IOException | ParsingException | ClassCastException e) {
            LOGGER.error("Failed to load Stream Tweaks settings from {}; falling back to defaults", filePath, e);
            return StreamTweaksSettings.DEFAULT;
        }
    }

    @Override
    public void save(StreamTweaksSettings settings) {
        try {
            Files.createDirectories(filePath.getParent());
            var tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(
                    tmp,
                    tomlWriter.writeToString(toConfig(settings)),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save Stream Tweaks settings to {}", filePath, e);
        }
    }

    private static StreamTweaksSettings toSettings(CommentedConfig config) {
        Object showBadges = config.get(SHOW_BADGES_KEY);
        if (!(showBadges instanceof Boolean showBadgesValue)) {
            return StreamTweaksSettings.DEFAULT;
        }
        return new StreamTweaksSettings(showBadgesValue);
    }

    private static CommentedConfig toConfig(StreamTweaksSettings settings) {
        CommentedConfig config = TomlFormat.newConfig();
        config.set(SHOW_BADGES_KEY, settings.showBadges());
        return config;
    }
}
