package org.etwas.streamtweaks.config;

/**
 * {@link StreamTweaksSettings} のインメモリキャッシュ兼書き込みスルー（write-through）ストア。
 *
 * <p>チャットメッセージ表示のような高頻度パスから設定値を参照する際に、毎回ディスクI/Oを
 * 発生させないための責務を持つ。値の変更は即座にメモリへ反映しつつ、
 * {@link StreamTweaksSettingsRepository} を通じて永続化する。
 *
 * <p>生成時に一度だけ {@link StreamTweaksSettingsRepository#load()} を呼び出し、以降は
 * このインスタンスが設定値の単一の真実（source of truth）となる。
 */
public final class StreamTweaksSettingsStore {

    private final StreamTweaksSettingsRepository repository;
    private volatile StreamTweaksSettings settings;

    public StreamTweaksSettingsStore(StreamTweaksSettingsRepository repository) {
        this.repository = repository;
        this.settings = repository.load();
    }

    public boolean showBadges() {
        return settings.showBadges();
    }

    public void setShowBadges(boolean showBadges) {
        settings = new StreamTweaksSettings(showBadges);
        repository.save(settings);
    }
}
