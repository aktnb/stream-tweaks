package org.etwas.streamtweaks.presentation.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PuaMappingTest {

    private static final int BASE = 0xF000;

    private List<Integer> evicted;
    private PuaMapping mapping;

    @BeforeEach
    void setUp() {
        evicted = new ArrayList<>();
        mapping = new PuaMapping(evicted::add);
    }

    @Test
    void assignsCodePointsStartingFromBase() {
        int first = mapping.getOrAssign("emote-a");
        int second = mapping.getOrAssign("emote-b");
        int third = mapping.getOrAssign("emote-c");

        assertEquals(BASE, first);
        assertEquals(BASE + 1, second);
        assertEquals(BASE + 2, third);
    }

    @Test
    void returnsSameCodePointForSameEmoteId() {
        int first = mapping.getOrAssign("emote-a");
        int second = mapping.getOrAssign("emote-a");

        assertEquals(first, second);
    }

    @Test
    void evictsAndCallsCallbackWhenOver200Entries() {
        for (int i = 0; i < 200; i++) {
            mapping.getOrAssign("emote-" + i);
        }

        assertEquals(0, evicted.size(), "200件以内ではevictが発生しないこと");

        mapping.getOrAssign("emote-overflow");

        assertEquals(1, evicted.size(), "201件目でevictが1件発生すること");
        assertEquals(BASE, evicted.get(0), "最初に追加したエントリがevictされること");
    }

    @Test
    void reassignsEvictedCodePointToNewEntry() {
        // 200件埋める（emote-0 が最古 = 次のevict対象）
        for (int i = 0; i < 200; i++) {
            mapping.getOrAssign("emote-" + i);
        }

        // 201件目 → emote-0(BASE) がevict → フリーリストへ
        mapping.getOrAssign("emote-overflow");
        assertEquals(1, evicted.size());
        assertEquals(BASE, evicted.get(0));

        // emote-0 は evict 済みなので再要求すると新しいコードポイント（= フリーリストから BASE を再利用）
        int reassigned = mapping.getOrAssign("emote-0");
        assertNotEquals(BASE + 200, reassigned, "新規割り当てではなくフリーリストのコードポイントを再利用する");
        assertEquals(BASE, reassigned, "evictされたコードポイント(BASE)をフリーリストから再利用する");
    }

    @Test
    void isCurrentMappingReturnsTrueForRegisteredMapping() {
        int cp = mapping.getOrAssign("emote-a");

        assertTrue(mapping.isCurrentMapping("emote-a", cp));
    }

    @Test
    void isCurrentMappingReturnsFalseForWrongEmoteId() {
        int cp = mapping.getOrAssign("emote-a");

        assertFalse(mapping.isCurrentMapping("emote-b", cp));
    }

    @Test
    void isCurrentMappingReturnsFalseAfterEvict() {
        // 200件埋めて emote-0 を最古にする
        for (int i = 0; i < 200; i++) {
            mapping.getOrAssign("emote-" + i);
        }
        int originalCp = BASE; // emote-0 のコードポイント

        // evict 発生 → emote-0(BASE) がフリーリストへ
        mapping.getOrAssign("emote-overflow");

        // evict 後は isCurrentMapping が false を返す
        assertFalse(mapping.isCurrentMapping("emote-0", originalCp), "evict 後は false を返すこと");
    }

    @Test
    void isCurrentMappingReturnsFalseAfterCodePointReassigned() {
        // emote-0 に BASE を割り当てた後 evict し、別エモートに BASE を再割り当て
        for (int i = 0; i < 200; i++) {
            mapping.getOrAssign("emote-" + i);
        }
        mapping.getOrAssign("emote-overflow"); // emote-0(BASE) を evict

        // フリーリストから BASE が再利用される
        int reassigned = mapping.getOrAssign("emote-new");
        assertEquals(BASE, reassigned, "evictされたコードポイントが再利用される");

        // 古い emote-0 では false、新しい emote-new では true
        assertFalse(mapping.isCurrentMapping("emote-0", BASE));
        assertTrue(mapping.isCurrentMapping("emote-new", BASE));
    }

    @Test
    void isCurrentMappingReturnsFalseForUnallocatedCodePoint() {
        assertFalse(mapping.isCurrentMapping("emote-a", 0x1234));
    }

    @Test
    void rejectsNullEmoteId() {
        assertThrows(NullPointerException.class, () -> mapping.getOrAssign(null));
    }

    @Test
    void rejectsBlankEmoteId() {
        assertThrows(IllegalArgumentException.class, () -> mapping.getOrAssign(""));
        assertThrows(IllegalArgumentException.class, () -> mapping.getOrAssign("   "));
    }
}
