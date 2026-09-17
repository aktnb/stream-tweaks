package org.etwas.streamtweaks.twitch.badge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.badge.api.TwitchChatBadgeApi;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.badge.domain.ChatBadge;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BadgeCatalogRepositoryTest {

    private static final UserId BROADCASTER = new UserId("broadcaster-1");

    @Mock
    TwitchChatBadgeApi api;

    BadgeCatalogRepository repository;

    @BeforeEach
    void setUp() {
        repository = new BadgeCatalogRepository(api);
    }

    @Test
    void lookupReturnsEmptyBeforeEnsureLoaded() {
        assertTrue(repository.lookup(BROADCASTER, new BadgeKey("moderator", "1")).isEmpty());
    }

    @Test
    void ensureLoadedMergesGlobalAndChannelBadgesPreferringChannelSpecific() {
        BadgeKey moderatorKey = new BadgeKey("moderator", "1");
        BadgeKey subscriberKey = new BadgeKey("subscriber", "3");
        ChatBadge globalModerator = new ChatBadge(moderatorKey, "https://static-cdn.jtvnw.net/global-moderator.png");
        ChatBadge channelModerator =
                new ChatBadge(moderatorKey, "https://static-cdn.jtvnw.net/channel-moderator.png");
        ChatBadge channelSubscriber = new ChatBadge(subscriberKey, "https://static-cdn.jtvnw.net/subscriber.png");

        when(api.getGlobalBadges())
                .thenReturn(CompletableFuture.completedFuture(Map.of(moderatorKey, globalModerator)));
        when(api.getChannelBadges(BROADCASTER))
                .thenReturn(CompletableFuture.completedFuture(
                        Map.of(moderatorKey, channelModerator, subscriberKey, channelSubscriber)));

        repository.ensureLoaded(BROADCASTER);

        assertEquals(
                channelModerator,
                repository.lookup(BROADCASTER, moderatorKey).orElseThrow(),
                "同一キーはチャンネル固有側を優先すること");
        assertEquals(channelSubscriber, repository.lookup(BROADCASTER, subscriberKey).orElseThrow());
    }

    @Test
    void ensureLoadedCalledTwiceDoesNotRefetch() {
        when(api.getGlobalBadges()).thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(api.getChannelBadges(BROADCASTER)).thenReturn(CompletableFuture.completedFuture(Map.of()));

        repository.ensureLoaded(BROADCASTER);
        repository.ensureLoaded(BROADCASTER);

        verify(api, times(1)).getGlobalBadges();
        verify(api, times(1)).getChannelBadges(BROADCASTER);
    }

    @Test
    void lookupReturnsEmptyWhileLoadIsInProgress() {
        CompletableFuture<Map<BadgeKey, ChatBadge>> pendingGlobal = new CompletableFuture<>();
        when(api.getGlobalBadges()).thenReturn(pendingGlobal);
        when(api.getChannelBadges(BROADCASTER)).thenReturn(new CompletableFuture<>());

        repository.ensureLoaded(BROADCASTER);

        assertTrue(
                repository.lookup(BROADCASTER, new BadgeKey("moderator", "1")).isEmpty(),
                "ロード完了前は未取得と同じくバッジなし扱いになること");
    }

    @Test
    void loadFailureIsNotCachedAndCanBeRetried() {
        when(api.getGlobalBadges())
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("network error")))
                .thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(api.getChannelBadges(BROADCASTER)).thenReturn(CompletableFuture.completedFuture(Map.of()));

        repository.ensureLoaded(BROADCASTER);
        assertTrue(repository.lookup(BROADCASTER, new BadgeKey("moderator", "1")).isEmpty());

        // 失敗はキャッシュに残らないため、再度ensureLoadedを呼ぶと再取得が走ること
        repository.ensureLoaded(BROADCASTER);

        verify(api, times(2)).getGlobalBadges();
    }

    @Test
    void isLoadedReflectsLoadState() {
        assertFalse(repository.isLoaded(BROADCASTER), "ensureLoadedを呼ぶ前は未ロードであること");

        when(api.getGlobalBadges()).thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(api.getChannelBadges(BROADCASTER)).thenReturn(CompletableFuture.completedFuture(Map.of()));
        repository.ensureLoaded(BROADCASTER);

        assertTrue(repository.isLoaded(BROADCASTER), "ロード完了後はtrueを返すこと");
    }

    @Test
    void isLoadedReturnsFalseWhileLoadIsInProgress() {
        when(api.getGlobalBadges()).thenReturn(new CompletableFuture<>());
        when(api.getChannelBadges(BROADCASTER)).thenReturn(new CompletableFuture<>());

        repository.ensureLoaded(BROADCASTER);

        assertFalse(repository.isLoaded(BROADCASTER), "ロード中はfalseを返すこと");
    }

    @Test
    void clearAllRemovesCachedCatalogsForAllChannels() {
        when(api.getGlobalBadges()).thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(api.getChannelBadges(BROADCASTER)).thenReturn(CompletableFuture.completedFuture(Map.of()));
        repository.ensureLoaded(BROADCASTER);

        repository.clearAll();
        repository.ensureLoaded(BROADCASTER);

        verify(api, times(2)).getGlobalBadges();
    }
}
