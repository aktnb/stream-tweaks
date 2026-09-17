package org.etwas.streamtweaks.mod.neoforge;

import java.util.concurrent.Executor;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.etwas.streamtweaks.application.TwitchApplicationService;
import org.etwas.streamtweaks.presentation.commands.TwitchCommand;

public final class NeoForgeTwitchCommandRegistrar {
    private NeoForgeTwitchCommandRegistrar() {}

    public static void register(TwitchApplicationService applicationService, Executor clientExecutor) {
        TwitchCommand<CommandSourceStack> command = new TwitchCommand<>(applicationService, clientExecutor);
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher()
                .register(command.build(source -> feedback -> source.sendSuccess(() -> feedback, false))));
    }
}
