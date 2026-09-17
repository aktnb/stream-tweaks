package org.etwas.streamtweaks.mod.platform;

import java.nio.file.Path;

/**
 * Loader-specific client integration points needed by the shared composition root.
 */
public interface ClientPlatform {
    Path configDir();

    void executeOnClientThread(Runnable command);

    void registerEndClientTick(Runnable listener);

    void registerClientDisconnect(Runnable listener);

    void registerClientStopping(Runnable listener);
}
