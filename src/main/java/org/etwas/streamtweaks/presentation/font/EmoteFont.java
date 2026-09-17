package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Twitch emote 用のカスタムフォント実装。
 *
 * <p>コードポイント（PUA: U+F000〜）→ EmoteGlyph のマッピングを管理する。
 * 全操作は Minecraft メインスレッドから呼ぶこと（スレッドセーフではない）。
 */
public class EmoteFont implements GlyphProvider {

    static final int MAX_ACTIVE_ANIMATIONS = 64;

    private final Map<Integer, EmoteGlyph> glyphs = new HashMap<>();

    // tick 駆動対象のアニメーショングリフ。アクセス順 LRU で上限を超えた分は tick 対象から外れる
    // （glyphs 本体・PUA マッピングは変更しない。そのフレームで見た目上静止するだけで機能は壊れない）。
    private final LinkedHashMap<Integer, EmoteGlyph> animatedGlyphs =
            new LinkedHashMap<>(MAX_ACTIVE_ANIMATIONS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, EmoteGlyph> eldest) {
                    return size() > MAX_ACTIVE_ANIMATIONS;
                }
            };

    private boolean closed = false;

    @Override
    @Nullable
    public UnbakedGlyph getGlyph(int codePoint) {
        return glyphs.get(codePoint);
    }

    @Override
    public IntSet getSupportedGlyphs() {
        IntOpenHashSet set = new IntOpenHashSet(glyphs.size());
        for (int cp : glyphs.keySet()) {
            set.add(cp);
        }
        return set;
    }

    public void addGlyph(int codePoint, EmoteGlyph glyph) {
        if (closed) {
            throw new IllegalStateException("EmoteFont is already closed");
        }
        EmoteGlyph previous = glyphs.put(codePoint, glyph);
        if (previous != null && previous != glyph) {
            // 同一 codePoint への再登録で古いグリフが握り潰されてリークしないようにする。
            animatedGlyphs.remove(codePoint);
            previous.close();
        }
        if (glyph.isAnimated()) {
            animatedGlyphs.put(codePoint, glyph);
        }
    }

    public boolean hasGlyph(int codePoint) {
        return glyphs.containsKey(codePoint);
    }

    /**
     * 指定した codePoint のアニメーショングリフが「今表示された」ことを LRU に反映する。
     * 既にダウンロード済みのアニメーション絵文字がチャットに再度投稿された際に呼ぶことで、
     * tick 対象の LRU 順序が最近表示されたものを優先するよう更新される
     * （addGlyph 時のみ順序を更新すると実質 FIFO になり、先着 MAX_ACTIVE_ANIMATIONS 件が
     * 恒久的に tick 対象を占有してしまうため）。アニメーションでない・未登録の codePoint には無効。
     */
    public void touch(int codePoint) {
        animatedGlyphs.get(codePoint);
    }

    public void removeGlyph(int codePoint) {
        if (closed) {
            return;
        }
        // tick ループとの競合を避けるため、close() より先に tick 対象から外す。
        animatedGlyphs.remove(codePoint);
        EmoteGlyph removed = glyphs.remove(codePoint);
        if (removed != null) {
            removed.close();
        }
    }

    /**
     * tick 駆動用に、現在アクティブなアニメーショングリフのスナップショットを返す。
     * 走査中の同時変更（tick 中の evict 等）を避けるため防御的コピーを返す。
     */
    public Collection<EmoteGlyph> animatedGlyphsSnapshot() {
        return List.copyOf(animatedGlyphs.values());
    }

    @Override
    public void close() {
        closed = true;
        glyphs.values().forEach(EmoteGlyph::close);
        glyphs.clear();
        animatedGlyphs.clear();
    }
}
