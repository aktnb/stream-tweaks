package org.etwas.streamtweaks.presentation.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import java.util.List;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FontSet.class)
public interface FontStorageAccessor {

    @Accessor("activeProviders")
    List<GlyphProvider> getActiveProviders();

    @Accessor("activeProviders")
    void setActiveProviders(List<GlyphProvider> providers);

    @Accessor("glyphCache")
    CodepointMap<?> getGlyphCache();
}
