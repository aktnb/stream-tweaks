package org.etwas.streamtweaks.config;

/**
 * ユーザーが変更可能な Stream Tweaks の設定値。
 *
 * <p>{@link StreamTweaksSettingsRepository} により永続化される。フィールドが増えても
 * 単純な値の集合であり続けるよう、ロジックは持たせない。
 */
public record StreamTweaksSettings(boolean showBadges) {

    /**
     * 設定ファイルが存在しない・壊れている場合に使うデフォルト値。
     *
     * <p>要件により Twitch バッジ表示のデフォルトは ON。
     */
    public static final StreamTweaksSettings DEFAULT = new StreamTweaksSettings(true);
}
