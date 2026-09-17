package org.etwas.streamtweaks.config;

/**
 * {@link StreamTweaksSettings} の永続化を抽象化するリポジトリ。
 *
 * <p>{@code twitch.auth.TwitchCredentialRepository} と同様のパターンで、実装は
 * {@code config.infra} パッケージに置く。
 */
public interface StreamTweaksSettingsRepository {
    StreamTweaksSettings load();

    void save(StreamTweaksSettings settings);
}
