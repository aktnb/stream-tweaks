package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.font.GlyphProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.gui.font.FontSet;
import org.etwas.streamtweaks.presentation.mixin.FontStorageAccessor;

/**
 * EmoteFont のシングルトンを保持し、FontSet への注入窓口となるクラス。
 *
 * <p>リロード（createFontSet 再呼び出し）でも同一の EmoteFont インスタンスを注入することで、
 * addGlyph で追加済みのグリフが消えないようにする。
 *
 * <p>全操作は Minecraft メインスレッドから呼ぶこと（スレッドセーフではない）。
 */
public final class EmoteFontRegistry {

    private static final EmoteFont INSTANCE = new EmoteFont();
    private static FontSet chatFontSet = null;

    private EmoteFontRegistry() {}

    public static EmoteFont getCurrent() {
        return INSTANCE;
    }

    /**
     * 指定された FontSet の activeProviders リストに EmoteFont を追加する。
     * 既に注入済みの場合は重複追加しない。
     */
    public static void injectInto(FontSet fontSet) {
        FontStorageAccessor accessor = (FontStorageAccessor) fontSet;
        List<GlyphProvider> current = accessor.getActiveProviders();
        if (current != null && current.contains(INSTANCE)) {
            return;
        }
        List<GlyphProvider> updated = new ArrayList<>(current != null ? current : List.of());
        updated.add(INSTANCE);
        accessor.setActiveProviders(updated);
        chatFontSet = fontSet;
    }

    public static void addGlyph(int codePoint, EmoteGlyph glyph) {
        INSTANCE.addGlyph(codePoint, glyph);
    }

    /** tick 駆動用。現在アクティブなアニメーショングリフのスナップショットを返す。 */
    public static Collection<EmoteGlyph> animatedGlyphs() {
        return INSTANCE.animatedGlyphsSnapshot();
    }

    /** 指定した codePoint のアニメーショングリフが今表示されたことを LRU に反映する。 */
    public static void touch(int codePoint) {
        INSTANCE.touch(codePoint);
    }

    /**
     * グリフキャッシュをクリアする。
     * addGlyph / removeGlyph 後に呼び出すことで、次フレームの ChatHud 再描画時に変更が反映される。
     */
    public static void invalidateGlyphCache() {
        if (chatFontSet == null) {
            return;
        }
        FontStorageAccessor accessor = (FontStorageAccessor) chatFontSet;
        accessor.getGlyphCache().clear();
    }
}
