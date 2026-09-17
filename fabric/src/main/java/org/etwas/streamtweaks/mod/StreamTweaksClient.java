package org.etwas.streamtweaks.mod;

import net.fabricmc.api.ClientModInitializer;
import org.etwas.streamtweaks.mod.fabric.FabricClientPlatform;
import org.etwas.streamtweaks.mod.fabric.FabricTwitchCommandRegistrar;

public class StreamTweaksClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        TwitchApplicationServices.set(
                TwitchClientBootstrap.initialize(new FabricClientPlatform(), FabricTwitchCommandRegistrar::register));
    }
}
