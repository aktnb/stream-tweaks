package org.etwas.streamtweaks.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StreamTweaksSettingsStoreTest {

    @Mock
    StreamTweaksSettingsRepository repository;

    @Test
    void constructorLoadsInitialValueFromRepositoryExactlyOnce() {
        when(repository.load()).thenReturn(new StreamTweaksSettings(false));

        StreamTweaksSettingsStore store = new StreamTweaksSettingsStore(repository);

        assertFalse(store.showBadges());
        verify(repository).load();
    }

    @Test
    void setShowBadgesUpdatesInMemoryValueAndPersists() {
        when(repository.load()).thenReturn(StreamTweaksSettings.DEFAULT);
        StreamTweaksSettingsStore store = new StreamTweaksSettingsStore(repository);
        assertTrue(store.showBadges());

        store.setShowBadges(false);

        assertFalse(store.showBadges(), "変更は即座にメモリ上へ反映されること");
        verify(repository).save(new StreamTweaksSettings(false));
    }

    @Test
    void showBadgesDoesNotHitRepositoryAfterConstruction() {
        when(repository.load()).thenReturn(StreamTweaksSettings.DEFAULT);
        StreamTweaksSettingsStore store = new StreamTweaksSettingsStore(repository);

        store.showBadges();
        store.showBadges();

        // コンストラクタで一度だけロードし、以降はメモリキャッシュのみを参照する設計の回帰を検知するため、
        // load()がコンストラクタ時の1回のみであることも明示的にアサートする。
        verify(repository, times(1)).load();
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
