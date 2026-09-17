package org.etwas.streamtweaks.presentation.font;

/**
 * 毎クライアント tick ごとにアクティブなアニメーション emote グリフのフレームを進める。
 *
 * <p>Minecraft のクライアント tick は 20 TPS 固定（1 tick = 50ms）であることを前提にしている。
 */
public final class EmoteAnimationTicker {

    private static final long TICK_MS = 50;

    private EmoteAnimationTicker() {}

    public static void tick() {
        for (EmoteGlyph glyph : EmoteFontRegistry.animatedGlyphs()) {
            glyph.tick(TICK_MS);
        }
    }
}
