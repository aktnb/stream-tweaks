package org.etwas.streamtweaks.mod;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import org.etwas.streamtweaks.application.TwitchApplicationService;
import org.etwas.streamtweaks.mod.platform.ClientPlatform;
import org.etwas.streamtweaks.mod.platform.TwitchCommandRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TwitchClientBootstrapTest {

    @TempDir
    Path configDir;

    @Test
    void initialize_wiresThroughPlatformAdapters() {
        FakeClientPlatform platform = new FakeClientPlatform(configDir);
        RecordingCommandRegistrar commandRegistrar = new RecordingCommandRegistrar();

        TwitchApplicationService applicationService = TwitchClientBootstrap.initialize(platform, commandRegistrar);

        assertNotNull(platform.endClientTickListener);
        assertNotNull(platform.clientDisconnectListener);
        assertNotNull(platform.clientStoppingListener);
        assertSame(applicationService, commandRegistrar.applicationService);
        assertNotNull(commandRegistrar.clientExecutor);
    }

    private static final class FakeClientPlatform implements ClientPlatform {
        private final Path configDir;
        private Runnable endClientTickListener;
        private Runnable clientDisconnectListener;
        private Runnable clientStoppingListener;

        private FakeClientPlatform(Path configDir) {
            this.configDir = configDir;
        }

        @Override
        public Path configDir() {
            return configDir;
        }

        @Override
        public void executeOnClientThread(Runnable command) {
            command.run();
        }

        @Override
        public void registerEndClientTick(Runnable listener) {
            endClientTickListener = listener;
        }

        @Override
        public void registerClientDisconnect(Runnable listener) {
            clientDisconnectListener = listener;
        }

        @Override
        public void registerClientStopping(Runnable listener) {
            clientStoppingListener = listener;
        }
    }

    private static final class RecordingCommandRegistrar implements TwitchCommandRegistrar {
        private TwitchApplicationService applicationService;
        private Executor clientExecutor;

        @Override
        public void register(TwitchApplicationService applicationService, Executor clientExecutor) {
            this.applicationService = applicationService;
            this.clientExecutor = clientExecutor;
        }
    }
}
