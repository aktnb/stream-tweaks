package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import java.util.Objects;

/**
 * Twitch emote のフレーム列。静止画は1フレームとして扱うことで、
 * 静止画とアニメーションを同一の型で統一的に扱う。
 *
 * <p>close() されるまで、または {@link EmoteGlyph} に渡されて所有権が移るまで、
 * 各フレームの {@link NativeImage} の解放責任はこのインスタンスが持つ。
 */
public final class EmoteAnimation implements AutoCloseable {

    private final List<EmoteFrame> frames;

    public EmoteAnimation(List<EmoteFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must not be null or empty");
        }
        // delayMs <= 0 のフレームが複数フレーム中に混ざると、EmoteGlyph.advanceFrame() の
        // while ループがそのフレームに到達した時点で無限ループしうる（1フレームの静止画は
        // tick() 自体が呼ばれないため delayMs=0 でも無害）。
        if (frames.size() > 1) {
            for (EmoteFrame frame : frames) {
                if (frame.delayMs() <= 0) {
                    throw new IllegalArgumentException("animated frames must have a positive delayMs");
                }
            }
        }
        this.frames = List.copyOf(frames);
    }

    public static EmoteAnimation ofStatic(NativeImage image) {
        Objects.requireNonNull(image, "image must not be null");
        return new EmoteAnimation(List.of(new EmoteFrame(image, 0)));
    }

    public List<EmoteFrame> frames() {
        return frames;
    }

    public boolean isAnimated() {
        return frames.size() > 1;
    }

    public int width() {
        return frames.get(0).image().getWidth();
    }

    public int height() {
        return frames.get(0).image().getHeight();
    }

    @Override
    public void close() {
        frames.forEach(frame -> frame.image().close());
    }
}
