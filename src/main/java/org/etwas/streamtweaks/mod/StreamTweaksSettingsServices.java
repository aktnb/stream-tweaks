package org.etwas.streamtweaks.mod;

import org.etwas.streamtweaks.config.StreamTweaksSettingsStore;

/**
 * {@link TwitchApplicationServices} と同様、コンポジションルート（{@link TwitchClientBootstrap}）
 * で組み立てた {@link StreamTweaksSettingsStore} を、設定画面などクライアント側の
 * どこからでも参照できるようにするための静的ホルダー。
 */
public final class StreamTweaksSettingsServices {
    private static StreamTweaksSettingsStore settingsStore;

    private StreamTweaksSettingsServices() {}

    public static StreamTweaksSettingsStore get() {
        return settingsStore;
    }

    public static void set(StreamTweaksSettingsStore settingsStore) {
        StreamTweaksSettingsServices.settingsStore = settingsStore;
    }
}
