package org.etwas.streamtweaks.presentation.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BadgePuaMappingTest {

    private static final int BASE = 0xE000;
    private static final int MAX = 0xEFFF;
    private static final UserId CHANNEL_A = new UserId("channel-a");
    private static final UserId CHANNEL_B = new UserId("channel-b");

    private BadgePuaMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = new BadgePuaMapping();
    }

    @Test
    void assignsFirstAvailableCodePointAfterSkippingWellKnownOnes() {
        int first = mapping.getOrAssign(CHANNEL_A, new BadgeKey("moderator", "1")).orElseThrow();
        int second = mapping.getOrAssign(CHANNEL_A, new BadgeKey("vip", "1")).orElseThrow();

        // BASE(0xE000)以降、Tooltips StylizedがほぼU+E000〜U+E43Dを埋め尽くしているため、
        // 最初に空くのは欠番の0xE023・0xE024になる
        assertEquals(0xE023, first);
        assertEquals(0xE024, second);
    }

    @Test
    void skipsWellKnownCodePoints() {
        for (int i = 0; i < 100; i++) {
            int codePoint = mapping.getOrAssign(CHANNEL_A, new BadgeKey("set-" + i, "1")).orElseThrow();
            assertFalse(
                    WellKnownPuaCodePoints.CODE_POINTS.contains(codePoint),
                    "リソースパックが使用するコードポイントは割り当てられないこと: 0x" + Integer.toHexString(codePoint));
        }
    }

    @Test
    void returnsSameCodePointForSameChannelAndKey() {
        BadgeKey key = new BadgeKey("subscriber", "3");

        int first = mapping.getOrAssign(CHANNEL_A, key).orElseThrow();
        int second = mapping.getOrAssign(CHANNEL_A, key).orElseThrow();

        assertEquals(first, second);
    }

    @Test
    void differentVersionIdsOfSameSetGetDistinctCodePoints() {
        int tier1 = mapping.getOrAssign(CHANNEL_A, new BadgeKey("subscriber", "1"))
                .orElseThrow();
        int tier3 = mapping.getOrAssign(CHANNEL_A, new BadgeKey("subscriber", "3"))
                .orElseThrow();

        assertFalse(tier1 == tier3);
    }

    // --- マルチチャンネル衝突バグの回帰テスト ---
    // 同一のset_id/versionId（例: subscriber tier3）でも、チャンネルが異なれば
    // 実際の画像が異なりうる（チャンネル固有バッジ）。broadcasterIdをキーに含めないと
    // 別チャンネルの画像・グリフを誤って使い回してしまうバグが過去にあったため、
    // 「同じBadgeKeyでもチャンネルが違えば別のコードポイントになる」ことを固定する。

    @Test
    void sameBadgeKeyOnDifferentChannelsGetsDistinctCodePoints() {
        BadgeKey key = new BadgeKey("subscriber", "3");

        int channelACodePoint = mapping.getOrAssign(CHANNEL_A, key).orElseThrow();
        int channelBCodePoint = mapping.getOrAssign(CHANNEL_B, key).orElseThrow();

        assertNotEquals(
                channelACodePoint,
                channelBCodePoint,
                "同じset_id/versionIdでもチャンネルが異なれば別コードポイントを割り当てること"
                        + "（チャンネル固有バッジの画像衝突を防ぐため）");
    }

    @Test
    void sameBadgeKeyOnSameChannelReturnsSameCodePointAcrossMultipleCalls() {
        BadgeKey key = new BadgeKey("moderator", "1");

        int first = mapping.getOrAssign(CHANNEL_A, key).orElseThrow();
        int second = mapping.getOrAssign(CHANNEL_A, key).orElseThrow();
        int thirdOnOtherChannel = mapping.getOrAssign(CHANNEL_B, key).orElseThrow();

        assertEquals(first, second, "同一チャンネル・同一キーは常に同じコードポイントを返すこと");
        assertNotEquals(first, thirdOnOtherChannel);
    }

    @Test
    void isAssignedReflectsAssignmentState() {
        BadgeKey key = new BadgeKey("moderator", "1");

        assertFalse(mapping.isAssigned(CHANNEL_A, key));
        mapping.getOrAssign(CHANNEL_A, key);
        assertTrue(mapping.isAssigned(CHANNEL_A, key));
        assertFalse(mapping.isAssigned(CHANNEL_B, key), "別チャンネルには影響しないこと");
    }

    @Test
    void rejectsNullBroadcasterId() {
        assertThrows(NullPointerException.class, () -> mapping.getOrAssign(null, new BadgeKey("moderator", "1")));
    }

    @Test
    void rejectsNullKey() {
        assertThrows(NullPointerException.class, () -> mapping.getOrAssign(CHANNEL_A, null));
    }

    @Test
    void assignsUpToMaxCodePointThenReturnsEmptyWhenExhausted() {
        long skippedInRange =
                WellKnownPuaCodePoints.CODE_POINTS.stream().filter(cp -> cp >= BASE && cp <= MAX).count();
        int totalSlots = (int) (MAX - BASE + 1 - skippedInRange);
        Integer lastAssigned = null;
        for (int i = 0; i < totalSlots; i++) {
            Optional<Integer> assigned = mapping.getOrAssign(CHANNEL_A, new BadgeKey("set-" + i, "1"));
            assertTrue(assigned.isPresent(), "レンジ内では常に割り当てられること: " + i);
            lastAssigned = assigned.orElseThrow();
        }
        assertEquals(Integer.valueOf(MAX), lastAssigned, "最後に割り当てられるコードポイントはMAX_CODE_POINTであること");

        Optional<Integer> overflow = mapping.getOrAssign(CHANNEL_A, new BadgeKey("overflow-set", "1"));

        assertTrue(overflow.isEmpty(), "レンジ枯渇時は例外を投げず空を返すこと");
    }
}
