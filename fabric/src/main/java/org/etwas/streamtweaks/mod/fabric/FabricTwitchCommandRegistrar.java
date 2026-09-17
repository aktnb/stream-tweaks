package org.etwas.streamtweaks.mod.fabric;

import java.util.concurrent.Executor;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.etwas.streamtweaks.application.TwitchApplicationService;
import org.etwas.streamtweaks.presentation.commands.TwitchCommand;

public final class FabricTwitchCommandRegistrar {
    private FabricTwitchCommandRegistrar() {}

    public static void register(TwitchApplicationService applicationService, Executor clientExecutor) {
        TwitchCommand<FabricClientCommandSource> command = new TwitchCommand<>(applicationService, clientExecutor);
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> dispatcher.register(command.build(source -> source::sendFeedback)));
    }
}
