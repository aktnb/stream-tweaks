package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.List;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;

/**
 * Twitch emote 画像をフォントグリフとして扱うクラス。複数フレームを保持しアニメーション表示できる。
 *
 * <p>NativeImage のライフタイム:
 * bake() を呼ぶと stitcher.stitch(this, this) → upload() → 現在フレームの内容が GPU テクスチャに転送される。
 * グリフキャッシュがクリアされると bake() が再呼び出しされるため、フレームの NativeImage は bake() では解放しない。
 * evict 時に close() を呼ぶことで全フレームのネイティブヒープを解放する。
 *
 * <p>Minecraft のフォントアトラス機構は一度 bake したグリフを永続キャッシュし、二度と bake() を
 * 呼び出さない。そのためアニメーションのフレーム切替は bake() を再トリガーせず、初回 upload() で
 * 受け取ったアトラス座標に対して {@link #tick(long)} から直接 GPU テクスチャへ書き込むことで実現する。
 * 静止画（フレーム数1）の場合は tick() が即座に return し、upload() 時の座標記憶も行わないため、
 * 従来と同一のオーバーヘッドゼロの命令列になる。
 *
 * <p>全操作は Minecraft メインスレッドから呼ぶこと（スレッドセーフではない）。
 */
public class EmoteGlyph implements UnbakedGlyph, GlyphBitmap, GlyphInfo, AutoCloseable {

    // Minecraft標準フォント（BitmapProvider）の論理グリフ高さと同じ8px。
    // 9px（行送り込み）を使うと通常文字より1px大きく描画され、行の下にはみ出す。
    private static final float EMOTE_LOGICAL_SIZE = 8.0f;

    private final List<EmoteFrame> frames;
    private final List<Integer> delaysMs;
    private final long totalDelayMs;
    private final int width;
    private final int height;

    private int currentFrame = 0;
    private long elapsedInFrameMs = 0;

    private int uploadX;
    private int uploadY;
    private GpuTexture uploadTexture;
    private boolean uploaded = false;
    private boolean closed = false;

    public EmoteGlyph(EmoteAnimation animation) {
        this.frames = animation.frames();
        this.width = animation.width();
        this.height = animation.height();
        this.delaysMs = frames.stream().map(EmoteFrame::delayMs).toList();
        long total = 0;
        for (int delay : delaysMs) {
            total += delay;
        }
        this.totalDelayMs = total;
    }

    public boolean isAnimated() {
        return frames.size() > 1;
    }

    // --- GlyphInfo ---

    @Override
    public float getAdvance() {
        return EMOTE_LOGICAL_SIZE;
    }

    // --- UnbakedGlyph ---

    @Override
    public GlyphInfo info() {
        return this;
    }

    @Override
    public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
        // NativeImage はここでクローズしない。グリフキャッシュが再クリアされると
        // bake() が再呼び出しされるため、image を残しておく必要がある。
        // 解放は close()（evict 時）でのみ行う。
        return stitcher.stitch(this, this);
    }

    // --- GlyphBitmap ---

    @Override
    public int getPixelWidth() {
        return width;
    }

    @Override
    public int getPixelHeight() {
        return height;
    }

    @Override
    public float getOversample() {
        // 高さ基準で計算する（バニラの BitmapProvider と同じ方式）。幅基準にすると
        // 画像が正方形でない場合に論理高さがずれ、他の文字より下にはみ出して描画される。
        return height / EMOTE_LOGICAL_SIZE;
    }

    @Override
    public boolean isColored() {
        return true;
    }

    @Override
    public void upload(int x, int y, GpuTexture texture) {
        if (closed) {
            // removeGlyph() を経ずに close() が呼ばれた場合の安全弁。
            return;
        }
        NativeImage image = frames.get(currentFrame).image();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, x, y);
        if (frames.size() > 1) {
            this.uploadX = x;
            this.uploadY = y;
            this.uploadTexture = texture;
            this.uploaded = true;
        }
    }

    /**
     * アニメーションフレームを進める。静止画、または upload() が未実行の場合は何もしない
     * （bake 前にチャットに一切表示されていないグリフを進める意味がないため）。
     *
     * @param deltaMs 前回呼び出しからの経過時間（ミリ秒）
     */
    public void tick(long deltaMs) {
        if (closed || frames.size() <= 1 || !uploaded) {
            return;
        }
        FrameAdvance result = advanceFrame(currentFrame, elapsedInFrameMs + deltaMs, delaysMs, totalDelayMs);
        currentFrame = result.frameIndex();
        elapsedInFrameMs = result.remainingElapsedMs();
        if (result.changed()) {
            NativeImage image = frames.get(currentFrame).image();
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToTexture(uploadTexture, image, 0, 0, uploadX, uploadY);
        }
    }

    /** フレーム前進の結果。GPU 非依存の純粋ロジックとして切り出しテスト可能にしている。 */
    record FrameAdvance(int frameIndex, long remainingElapsedMs, boolean changed) {}

    static FrameAdvance advanceFrame(int currentFrame, long elapsedMs, List<Integer> delaysMs, long totalDelayMs) {
        int size = delaysMs.size();
        // バックグラウンド長時間停止等で極端に大きい elapsedMs が来ても、周回分を切り捨てて
        // 1周未満に正規化することで while ループが必ず size 回以内に収束することを保証する。
        long remaining = totalDelayMs > 0 && elapsedMs >= totalDelayMs ? elapsedMs % totalDelayMs : elapsedMs;
        int frame = currentFrame;
        boolean changed = false;
        while (remaining >= delaysMs.get(frame)) {
            remaining -= delaysMs.get(frame);
            frame = (frame + 1) % size;
            changed = true;
        }
        return new FrameAdvance(frame, remaining, changed);
    }

    // --- AutoCloseable ---

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        frames.forEach(frame -> frame.image().close());
        uploadTexture = null;
    }
}
