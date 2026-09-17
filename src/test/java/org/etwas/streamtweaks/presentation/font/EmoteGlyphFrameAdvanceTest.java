package org.etwas.streamtweaks.presentation.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmoteGlyphFrameAdvanceTest {

    private static final List<Integer> DELAYS = List.of(100, 200, 150);
    private static final long TOTAL_DELAY = 450;

    @Test
    void noAdvanceWhenElapsedBelowDelay() {
        EmoteGlyph.FrameAdvance result = EmoteGlyph.advanceFrame(0, 50, DELAYS, TOTAL_DELAY);

        assertEquals(0, result.frameIndex());
        assertEquals(50, result.remainingElapsedMs());
        assertFalse(result.changed());
    }

    @Test
    void advancesOneFrameWhenDelayExceeded() {
        EmoteGlyph.FrameAdvance result = EmoteGlyph.advanceFrame(0, 120, DELAYS, TOTAL_DELAY);

        assertEquals(1, result.frameIndex());
        assertEquals(20, result.remainingElapsedMs());
        assertTrue(result.changed());
    }

    @Test
    void advancesMultipleFramesInOneTick() {
        // frame0(100) + frame1(200) = 300 consumed, remainder 10, lands on frame2
        EmoteGlyph.FrameAdvance result = EmoteGlyph.advanceFrame(0, 310, DELAYS, TOTAL_DELAY);

        assertEquals(2, result.frameIndex());
        assertEquals(10, result.remainingElapsedMs());
        assertTrue(result.changed());
    }

    @Test
    void wrapsAroundToFirstFrame() {
        // frame0(100)+frame1(200)+frame2(150) = 450 でちょうど1周する。
        // 見た目上は元のフレームに戻るだけなので、正規化により changed=false（無駄な再アップロードを回避）。
        EmoteGlyph.FrameAdvance result = EmoteGlyph.advanceFrame(0, 450, DELAYS, TOTAL_DELAY);

        assertEquals(0, result.frameIndex());
        assertEquals(0, result.remainingElapsedMs());
        assertFalse(result.changed());
    }

    @Test
    void extremelyLargeElapsedIsNormalizedByTotalDelay() {
        // totalDelay=450. 1,000,000 % 450 = 100 → frame0(100)を消費してframe1へ、remainder=0
        EmoteGlyph.FrameAdvance result = EmoteGlyph.advanceFrame(0, 1_000_000, DELAYS, TOTAL_DELAY);

        assertEquals(1, result.frameIndex());
        assertEquals(0, result.remainingElapsedMs());
        assertTrue(result.changed());
    }

    @Test
    void startingFromNonZeroFrame() {
        EmoteGlyph.FrameAdvance result = EmoteGlyph.advanceFrame(2, 150, DELAYS, TOTAL_DELAY);

        assertEquals(0, result.frameIndex());
        assertEquals(0, result.remainingElapsedMs());
        assertTrue(result.changed());
    }
}
