package org.etwas.streamtweaks.twitch.badge.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelBadgeCatalogTest {

    @Test
    void lookupReturnsRegisteredBadge() {
        BadgeKey key = new BadgeKey("moderator", "1");
        ChatBadge badge = new ChatBadge(key, "https://static-cdn.jtvnw.net/badges/v1/moderator/4");
        ChannelBadgeCatalog catalog = new ChannelBadgeCatalog(Map.of(key, badge));

        assertEquals(badge, catalog.lookup(key).orElseThrow());
    }

    @Test
    void lookupReturnsEmptyForUnknownKey() {
        ChannelBadgeCatalog catalog = new ChannelBadgeCatalog(Map.of());

        assertTrue(catalog.lookup(new BadgeKey("unknown", "1")).isEmpty());
    }

    @Test
    void emptyReturnsCatalogWithNoBadges() {
        ChannelBadgeCatalog catalog = ChannelBadgeCatalog.empty();

        assertEquals(0, catalog.size());
        assertTrue(catalog.lookup(new BadgeKey("moderator", "1")).isEmpty());
    }
}
