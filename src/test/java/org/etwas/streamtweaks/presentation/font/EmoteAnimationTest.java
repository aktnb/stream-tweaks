package org.etwas.streamtweaks.presentation.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmoteAnimationTest {

    @Mock
    NativeImage frame0;

    @Mock
    NativeImage frame1;

    @Test
    void ofStaticWrapsSingleFrameAsNonAnimated() {
        EmoteAnimation animation = EmoteAnimation.ofStatic(frame0);

        assertFalse(animation.isAnimated());
        assertEquals(1, animation.frames().size());
    }

    @Test
    void multiFrameAnimationIsAnimated() {
        EmoteAnimation animation =
                new EmoteAnimation(List.of(new EmoteFrame(frame0, 100), new EmoteFrame(frame1, 200)));

        assertTrue(animation.isAnimated());
        assertEquals(2, animation.frames().size());
    }

    @Test
    void widthAndHeightDelegateToFirstFrame() {
        when(frame0.getWidth()).thenReturn(28);
        when(frame0.getHeight()).thenReturn(28);
        EmoteAnimation animation = EmoteAnimation.ofStatic(frame0);

        assertEquals(28, animation.width());
        assertEquals(28, animation.height());
    }

    @Test
    void closeClosesAllFrames() {
        EmoteAnimation animation =
                new EmoteAnimation(List.of(new EmoteFrame(frame0, 100), new EmoteFrame(frame1, 200)));

        animation.close();

        verify(frame0).close();
        verify(frame1).close();
    }

    @Test
    void emptyFramesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EmoteAnimation(List.of()));
    }

    @Test
    void multiFrameWithNonPositiveDelayRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmoteAnimation(List.of(new EmoteFrame(frame0, 100), new EmoteFrame(frame1, 0))));
    }

    @Test
    void singleFrameWithZeroDelayAllowed() {
        EmoteAnimation animation = new EmoteAnimation(List.of(new EmoteFrame(frame0, 0)));

        assertFalse(animation.isAnimated());
    }
}
