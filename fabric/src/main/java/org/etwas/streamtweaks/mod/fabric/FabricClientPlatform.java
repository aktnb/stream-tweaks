package org.etwas.streamtweaks.mod.fabric;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.etwas.streamtweaks.mod.platform.ClientPlatform;

public final class FabricClientPlatform implements ClientPlatform {

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void executeOnClientThread(Runnable command) {
        Minecraft.getInstance().execute(command);
    }

    @Override
    public void registerEndClientTick(Runnable listener) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> listener.run());
    }

    @Override
    public void registerClientDisconnect(Runnable listener) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> listener.run());
    }

    @Override
    public void registerClientStopping(Runnable listener) {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> listener.run());
    }
}
