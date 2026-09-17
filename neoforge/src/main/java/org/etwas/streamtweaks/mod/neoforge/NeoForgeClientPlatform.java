package org.etwas.streamtweaks.mod.neoforge;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import org.etwas.streamtweaks.mod.platform.ClientPlatform;

public final class NeoForgeClientPlatform implements ClientPlatform {

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void executeOnClientThread(Runnable command) {
        Minecraft.getInstance().execute(command);
    }

    @Override
    public void registerEndClientTick(Runnable listener) {
        NeoForgeStreamTweaksClientEvents.registerEndClientTick(listener);
    }

    @Override
    public void registerClientDisconnect(Runnable listener) {
        NeoForgeStreamTweaksClientEvents.registerClientDisconnect(listener);
    }

    @Override
    public void registerClientStopping(Runnable listener) {
        NeoForgeStreamTweaksClientEvents.registerClientStopping(listener);
    }
}
