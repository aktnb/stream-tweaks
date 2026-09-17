package org.etwas.streamtweaks.mod.platform;

import java.util.concurrent.Executor;
import org.etwas.streamtweaks.application.TwitchApplicationService;

@FunctionalInterface
public interface TwitchCommandRegistrar {
    void register(TwitchApplicationService applicationService, Executor clientExecutor);
}
