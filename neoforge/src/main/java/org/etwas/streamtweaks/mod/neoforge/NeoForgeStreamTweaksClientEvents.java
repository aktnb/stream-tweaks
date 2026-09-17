package org.etwas.streamtweaks.mod.neoforge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;

public final class NeoForgeStreamTweaksClientEvents {
    private static final List<Runnable> END_CLIENT_TICK_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> CLIENT_DISCONNECT_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> CLIENT_STOPPING_LISTENERS = new CopyOnWriteArrayList<>();

    private NeoForgeStreamTweaksClientEvents() {}

    public static void registerEndClientTick(Runnable listener) {
        END_CLIENT_TICK_LISTENERS.add(listener);
    }

    public static void registerClientDisconnect(Runnable listener) {
        CLIENT_DISCONNECT_LISTENERS.add(listener);
    }

    public static void registerClientStopping(Runnable listener) {
        CLIENT_STOPPING_LISTENERS.add(listener);
    }

    @SubscribeEvent
    public static void onEndClientTick(ClientTickEvent.Post event) {
        END_CLIENT_TICK_LISTENERS.forEach(Runnable::run);
    }

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        CLIENT_DISCONNECT_LISTENERS.forEach(Runnable::run);
    }

    @SubscribeEvent
    public static void onClientStopping(ClientStoppingEvent event) {
        CLIENT_STOPPING_LISTENERS.forEach(Runnable::run);
    }
}
