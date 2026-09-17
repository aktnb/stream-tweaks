package org.etwas.streamtweaks.presentation.commands;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.network.chat.Component;
import org.etwas.streamtweaks.application.TwitchApplicationService;
import org.etwas.streamtweaks.twitch.core.Login;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TwitchCommandTest {

    @Mock
    TwitchApplicationService applicationService;

    @Test
    void handleConnect_sendsAsyncFeedbackViaClientExecutor() throws CommandSyntaxException {
        RecordingExecutor executor = new RecordingExecutor();
        List<Component> feedbacks = new ArrayList<>();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        TwitchCommand<Object> command = new TwitchCommand<>(applicationService, executor);
        CompletableFuture<Void> connectFuture = new CompletableFuture<>();

        dispatcher.register(command.build(source -> feedbacks::add));
        when(applicationService.isAuthenticated()).thenReturn(true);
        when(applicationService.connect(new Login("streamer"))).thenReturn(connectFuture);

        dispatcher.execute("twitch connect streamer", new Object());

        verify(applicationService, times(1)).connect(new Login("streamer"));
        verify(applicationService, never()).disconnect(any());
        org.junit.jupiter.api.Assertions.assertEquals(0, feedbacks.size());
        executor.runNext();
        org.junit.jupiter.api.Assertions.assertEquals(1, feedbacks.size());

        connectFuture.complete(null);

        org.junit.jupiter.api.Assertions.assertEquals(1, feedbacks.size());
        executor.runNext();
        org.junit.jupiter.api.Assertions.assertEquals(2, feedbacks.size());
    }

    @Test
    void handleConnect_whenAlreadyConnectedToOwnChannelButLoginTypedManually_stillConnectsSuccessfully()
            throws CommandSyntaxException {
        // 「自分のチャンネルに既に接続済み」の状態を suggestOwnChannelLogin() が空を返すことで再現する。
        // handleConnect はサジェストの状態に関わらず、手打ちで渡された login をそのまま connect() に渡すべきで、
        // 実行時にブロックされてはならない。
        RecordingExecutor executor = new RecordingExecutor();
        List<Component> feedbacks = new ArrayList<>();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        TwitchCommand<Object> command = new TwitchCommand<>(applicationService, executor);
        CompletableFuture<Void> connectFuture = new CompletableFuture<>();

        dispatcher.register(command.build(source -> feedbacks::add));
        when(applicationService.isAuthenticated()).thenReturn(true);
        when(applicationService.connect(new Login("testuser"))).thenReturn(connectFuture);

        dispatcher.execute("twitch connect testuser", new Object());

        verify(applicationService, times(1)).connect(new Login("testuser"));
        org.junit.jupiter.api.Assertions.assertEquals(0, feedbacks.size());
        executor.runNext();
        org.junit.jupiter.api.Assertions.assertEquals(1, feedbacks.size());

        connectFuture.complete(null);

        org.junit.jupiter.api.Assertions.assertEquals(1, feedbacks.size());
        executor.runNext();
        org.junit.jupiter.api.Assertions.assertEquals(2, feedbacks.size());
    }

    @Test
    void handleConfig_schedulesScreenOpenViaClientExecutor() throws CommandSyntaxException {
        RecordingExecutor executor = new RecordingExecutor();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        TwitchCommand<Object> command = new TwitchCommand<>(applicationService, executor);
        dispatcher.register(command.build(source -> feedback -> {}));

        int result = dispatcher.execute("twitch config", new Object());

        assertEquals(1, result);
        assertEquals(1, executor.pendingCount());
    }

    // --- connect suggestions ---

    @Test
    void connectSuggestions_whenNotAuthenticated_returnsNoSuggestions() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        TwitchCommand<Object> command = new TwitchCommand<>(applicationService, executor);
        dispatcher.register(command.build(source -> feedback -> {}));
        when(applicationService.suggestOwnChannelLogin()).thenReturn(Optional.empty());

        ParseResults<Object> parseResults = dispatcher.parse("twitch connect ", new Object());
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parseResults).join();

        assertTrue(suggestions.getList().isEmpty());
    }

    @Test
    void connectSuggestions_whenAuthenticatedAndNotConnected_suggestsOwnLogin() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        TwitchCommand<Object> command = new TwitchCommand<>(applicationService, executor);
        dispatcher.register(command.build(source -> feedback -> {}));
        when(applicationService.suggestOwnChannelLogin()).thenReturn(Optional.of("testuser"));

        ParseResults<Object> parseResults = dispatcher.parse("twitch connect ", new Object());
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parseResults).join();

        assertEquals(1, suggestions.getList().size());
        assertEquals("testuser", suggestions.getList().get(0).getText());
    }

    @Test
    void connectSuggestions_whenAuthenticatedAndAlreadyConnected_returnsNoSuggestions() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        TwitchCommand<Object> command = new TwitchCommand<>(applicationService, executor);
        dispatcher.register(command.build(source -> feedback -> {}));
        when(applicationService.suggestOwnChannelLogin()).thenReturn(Optional.empty());

        ParseResults<Object> parseResults = dispatcher.parse("twitch connect ", new Object());
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parseResults).join();

        assertTrue(suggestions.getList().isEmpty());
    }

    private static final class RecordingExecutor implements Executor {
        private final Queue<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.remove().run();
        }

        int pendingCount() {
            return commands.size();
        }
    }
}
