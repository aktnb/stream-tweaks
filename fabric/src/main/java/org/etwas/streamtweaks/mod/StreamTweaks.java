package org.etwas.streamtweaks.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class StreamTweaks implements ModInitializer {

    @Override
    public void onInitialize() {
        StreamTweaksCommon.LOGGER.info("Stream Tweaks initialized");
    }

    public static void devLogger(String loggerInput) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }
        StreamTweaksCommon.LOGGER.info("DEV - [ %s ]".formatted(loggerInput));
    }
}
