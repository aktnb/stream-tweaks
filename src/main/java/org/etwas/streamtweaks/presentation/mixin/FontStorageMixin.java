package org.etwas.streamtweaks.presentation.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.etwas.streamtweaks.presentation.font.EmoteFontRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontManager.class)
public abstract class FontStorageMixin {

    /**
     * createFontSet() の戻り時に EmoteFont を注入する。
     * minecraft:default (チャットフォント) のみが対象。
     */
    @Inject(method = "createFontSet", at = @At("RETURN"))
    private void onCreateFontSet(
            Identifier id,
            List<GlyphProvider.Conditional> providers,
            Set<FontOption> options,
            CallbackInfoReturnable<FontSet> cir) {
        if (!Minecraft.DEFAULT_FONT.equals(id)) {
            return;
        }
        EmoteFontRegistry.injectInto(cir.getReturnValue());
    }
}
