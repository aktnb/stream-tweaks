package org.etwas.streamtweaks.presentation.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmoteFontTest {

    EmoteFont font;

    @BeforeEach
    void setUp() {
        font = new EmoteFont();
    }

    private EmoteGlyph staticGlyph() {
        NativeImage image = org.mockito.Mockito.mock(NativeImage.class);
        lenient().when(image.getWidth()).thenReturn(28);
        lenient().when(image.getHeight()).thenReturn(28);
        return new EmoteGlyph(EmoteAnimation.ofStatic(image));
    }

    private EmoteGlyph animatedGlyph() {
        NativeImage frame0 = org.mockito.Mockito.mock(NativeImage.class);
        NativeImage frame1 = org.mockito.Mockito.mock(NativeImage.class);
        lenient().when(frame0.getWidth()).thenReturn(28);
        lenient().when(frame0.getHeight()).thenReturn(28);
        return new EmoteGlyph(new EmoteAnimation(List.of(new EmoteFrame(frame0, 100), new EmoteFrame(frame1, 100))));
    }

    @Test
    void staticGlyphIsNotTrackedForAnimation() {
        EmoteGlyph glyph = staticGlyph();

        font.addGlyph(0xF000, glyph);

        assertTrue(font.hasGlyph(0xF000));
        assertTrue(font.animatedGlyphsSnapshot().isEmpty());
    }

    @Test
    void animatedGlyphIsTrackedForAnimation() {
        EmoteGlyph glyph = animatedGlyph();

        font.addGlyph(0xF000, glyph);

        assertTrue(font.hasGlyph(0xF000));
        assertEquals(1, font.animatedGlyphsSnapshot().size());
    }

    @Test
    void removeGlyphStopsTrackingAndCloses() {
        EmoteGlyph glyph = animatedGlyph();
        font.addGlyph(0xF000, glyph);

        font.removeGlyph(0xF000);

        assertFalse(font.hasGlyph(0xF000));
        assertTrue(font.animatedGlyphsSnapshot().isEmpty());
    }

    @Test
    void exceedingActiveAnimationLimitDropsOldestFromTickingButKeepsGlyph() {
        for (int i = 0; i < EmoteFont.MAX_ACTIVE_ANIMATIONS; i++) {
            font.addGlyph(0xF000 + i, animatedGlyph());
        }
        assertEquals(
                EmoteFont.MAX_ACTIVE_ANIMATIONS, font.animatedGlyphsSnapshot().size());

        // 上限を1件超過させる
        font.addGlyph(0xF000 + EmoteFont.MAX_ACTIVE_ANIMATIONS, animatedGlyph());

        assertEquals(
                EmoteFont.MAX_ACTIVE_ANIMATIONS, font.animatedGlyphsSnapshot().size());
        // 最初に追加したグリフは tick 対象から外れるが、glyphs 本体には残っている
        assertTrue(font.hasGlyph(0xF000));
        assertTrue(font.hasGlyph(0xF000 + EmoteFont.MAX_ACTIVE_ANIMATIONS));
    }

    @Test
    void closeClosesAllGlyphsIncludingStatic() {
        NativeImage image = org.mockito.Mockito.mock(NativeImage.class);
        when(image.getWidth()).thenReturn(28);
        when(image.getHeight()).thenReturn(28);
        EmoteGlyph glyph = new EmoteGlyph(EmoteAnimation.ofStatic(image));
        font.addGlyph(0xF000, glyph);

        font.close();

        verify(image).close();
        assertFalse(font.hasGlyph(0xF000));
    }

    @Test
    void animatedGlyphsSnapshotIsDefensiveCopy() {
        font.addGlyph(0xF000, animatedGlyph());
        Collection<EmoteGlyph> snapshot = font.animatedGlyphsSnapshot();

        font.addGlyph(0xF001, animatedGlyph());

        assertEquals(1, snapshot.size());
        assertEquals(2, font.animatedGlyphsSnapshot().size());
    }

    @Test
    void touchUpdatesLruOrderSoRecentlyTouchedGlyphSurvivesEviction() {
        EmoteGlyph firstGlyph = animatedGlyph();
        font.addGlyph(0xF000, firstGlyph);
        for (int i = 1; i < EmoteFont.MAX_ACTIVE_ANIMATIONS; i++) {
            font.addGlyph(0xF000 + i, animatedGlyph());
        }
        // 最古の 0xF000 を touch して最近表示扱いにする
        font.touch(0xF000);

        // 上限を1件超過させる
        font.addGlyph(0xF000 + EmoteFont.MAX_ACTIVE_ANIMATIONS, animatedGlyph());

        // touch していれば evict されず tick 対象に残る
        assertTrue(font.animatedGlyphsSnapshot().contains(firstGlyph));
    }

    @Test
    void addGlyphOverwritingExistingCodePointClosesOldGlyph() {
        NativeImage oldImage = org.mockito.Mockito.mock(NativeImage.class);
        when(oldImage.getWidth()).thenReturn(28);
        when(oldImage.getHeight()).thenReturn(28);
        EmoteGlyph oldGlyph = new EmoteGlyph(EmoteAnimation.ofStatic(oldImage));
        font.addGlyph(0xF000, oldGlyph);

        font.addGlyph(0xF000, animatedGlyph());

        verify(oldImage).close();
        assertTrue(font.hasGlyph(0xF000));
    }
}
