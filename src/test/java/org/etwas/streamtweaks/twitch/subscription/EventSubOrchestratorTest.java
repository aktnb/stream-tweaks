package org.etwas.streamtweaks.twitch.subscription;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.etwas.streamtweaks.twitch.subscription.api.EventSubSubscription;
import org.etwas.streamtweaks.twitch.subscription.api.TwitchEventSubApi;
import org.etwas.streamtweaks.twitch.subscription.domain.SubscriptionId;
import org.etwas.streamtweaks.twitch.subscription.event.EventSubEventType;
import org.etwas.streamtweaks.twitch.subscription.websocket.EventSubWebSocketClient;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventSubOrchestratorTest {

    @Mock
    TwitchEventSubApi eventSubApi;

    @Mock
    EventSubWebSocketClient webSocketClient;

    EventSubOrchestrator orchestrator;
    EventSubWebSocketClient.EventSubWebSocketListener listener;

    static final SessionId SESSION_ID = new SessionId("session-123");
    static final UserId BROADCASTER1 = new UserId("broadcaster1");
    static final UserId BROADCASTER2 = new UserId("broadcaster2");
    static final String CHAT_MESSAGE = EventSubEventType.CHAT_MESSAGE.value();
    static final String CHAT_MESSAGE_DELETE = EventSubEventType.CHAT_MESSAGE_DELETE.value();

    /** 1チャンネルにつき購読されるイベントタイプ数。 */
    static final int EVENT_TYPES_PER_CHANNEL = EventSubEventType.values().length;

    @BeforeEach
    void setUp() {
        orchestrator = new EventSubOrchestrator(eventSubApi, webSocketClient);
        var listenerCaptor = ArgumentCaptor.forClass(EventSubWebSocketClient.EventSubWebSocketListener.class);
        verify(webSocketClient).setListener(listenerCaptor.capture());
        listener = listenerCaptor.getValue();
    }

    /** 引数から決定的にサブスクリプションIDを組み立てる（(チャンネル, タイプ)ごとに一意）。 */
    static SubscriptionId subscriptionId(UserId broadcasterId, String eventType) {
        return new SubscriptionId("sub-" + broadcasterId.value() + "-" + eventType);
    }

    void stubCreateSubscription() {
        when(eventSubApi.createSubscription(any(), any(), any())).thenAnswer(invocation -> {
            UserId broadcasterId = invocation.getArgument(0);
            String eventType = invocation.getArgument(1);
            return CompletableFuture.completedFuture(new EventSubSubscription(
                    subscriptionId(broadcasterId, eventType), eventType, "enabled", broadcasterId));
        });
    }

    @Test
    void subscribe_connectsAndCreatesSubscriptionForEveryEventType() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();

        verify(webSocketClient).connect();
        verify(eventSubApi).createSubscription(BROADCASTER1, CHAT_MESSAGE, SESSION_ID);
        verify(eventSubApi).createSubscription(BROADCASTER1, CHAT_MESSAGE_DELETE, SESSION_ID);
    }

    @Test
    void subscribe_whenDuplicateBroadcaster_doesNotCreateDuplicate() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();
        orchestrator.subscribe(BROADCASTER1).join();

        verify(eventSubApi, times(EVENT_TYPES_PER_CHANNEL)).createSubscription(any(), any(), any());
    }

    @Test
    void unsubscribe_whenSubscribed_deletesEverySubscriptionOfTheChannel() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();
        when(eventSubApi.deleteSubscription(any())).thenReturn(CompletableFuture.completedFuture(null));

        orchestrator.subscribe(BROADCASTER1).join();
        orchestrator.unsubscribe(BROADCASTER1).join();

        verify(eventSubApi).deleteSubscription(subscriptionId(BROADCASTER1, CHAT_MESSAGE));
        verify(eventSubApi).deleteSubscription(subscriptionId(BROADCASTER1, CHAT_MESSAGE_DELETE));
    }

    @Test
    void unsubscribe_whenNotSubscribed_returnsImmediately() {
        orchestrator.unsubscribe(BROADCASTER1).join();

        verify(eventSubApi, never()).deleteSubscription(any());
    }

    @Test
    void unsubscribeAll_deletesAllSubscriptions() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();
        when(eventSubApi.deleteSubscription(any())).thenReturn(CompletableFuture.completedFuture(null));

        orchestrator.subscribe(BROADCASTER1).join();
        orchestrator.subscribe(BROADCASTER2).join();
        orchestrator.unsubscribeAll().join();

        verify(eventSubApi).deleteSubscription(subscriptionId(BROADCASTER1, CHAT_MESSAGE));
        verify(eventSubApi).deleteSubscription(subscriptionId(BROADCASTER1, CHAT_MESSAGE_DELETE));
        verify(eventSubApi).deleteSubscription(subscriptionId(BROADCASTER2, CHAT_MESSAGE));
        verify(eventSubApi).deleteSubscription(subscriptionId(BROADCASTER2, CHAT_MESSAGE_DELETE));
    }

    @Test
    void subscribe_whenApiFailure_doesNotThrow() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        when(eventSubApi.createSubscription(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("No credentials available")));

        orchestrator.subscribe(BROADCASTER1).join();

        verify(eventSubApi, times(EVENT_TYPES_PER_CHANNEL)).createSubscription(any(), any(), any());
    }

    @Test
    void subscribe_whenOneEventTypeFails_retriesOnlyTheMissingOneOnNextReconcile() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        when(eventSubApi.createSubscription(eq(BROADCASTER1), eq(CHAT_MESSAGE), any()))
                .thenReturn(CompletableFuture.completedFuture(new EventSubSubscription(
                        subscriptionId(BROADCASTER1, CHAT_MESSAGE), CHAT_MESSAGE, "enabled", BROADCASTER1)));
        when(eventSubApi.createSubscription(eq(BROADCASTER1), eq(CHAT_MESSAGE_DELETE), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")))
                .thenReturn(CompletableFuture.completedFuture(new EventSubSubscription(
                        subscriptionId(BROADCASTER1, CHAT_MESSAGE_DELETE),
                        CHAT_MESSAGE_DELETE,
                        "enabled",
                        BROADCASTER1)));
        when(eventSubApi.createSubscription(eq(BROADCASTER2), any(), any())).thenAnswer(invocation -> {
            String eventType = invocation.getArgument(1);
            return CompletableFuture.completedFuture(new EventSubSubscription(
                    subscriptionId(BROADCASTER2, eventType), eventType, "enabled", BROADCASTER2));
        });

        orchestrator.subscribe(BROADCASTER1).join();
        // 2回目のsubscribeはdesiredが変わらないため何もしないので、reconcileを別チャンネル追加で誘発する
        orchestrator.subscribe(BROADCASTER2).join();

        // 成功済みの channel.chat.message は作り直されない
        verify(eventSubApi, times(1)).createSubscription(eq(BROADCASTER1), eq(CHAT_MESSAGE), any());
        // 失敗した channel.chat.message_delete だけが再試行される
        verify(eventSubApi, times(2)).createSubscription(eq(BROADCASTER1), eq(CHAT_MESSAGE_DELETE), any());
    }

    @Test
    void onDisconnected_makesNextSubscribeReconnect() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();
        listener.onDisconnected();

        orchestrator.subscribe(BROADCASTER2).join();

        verify(webSocketClient, times(2)).connect();
    }

    @Test
    void onNotification_delegatesToListener() {
        @SuppressWarnings("unchecked")
        Consumer<String> notificationListener = org.mockito.Mockito.mock(Consumer.class);
        orchestrator.setNotificationListener(notificationListener);
        String payload = "{\"payload\":{}}";

        listener.onNotification(payload);

        verify(notificationListener).accept(payload);
    }

    @Test
    void onNotification_beforeListenerIsSet_doesNotThrow() {
        listener.onNotification("{\"payload\":{}}");
    }

    @Test
    void onReconnected_reconcilesPreviousSubscriptions() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();

        SessionId newSessionId = new SessionId("session-456");
        listener.onReconnected(newSessionId);

        verify(eventSubApi).createSubscription(BROADCASTER1, CHAT_MESSAGE, newSessionId);
        verify(eventSubApi).createSubscription(BROADCASTER1, CHAT_MESSAGE_DELETE, newSessionId);
    }

    @Test
    void onRevocation_whenKnownSubscription_resubscribesOnlyTheRevokedType() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();

        listener.onRevocation("{\"payload\":{\"subscription\":{\"id\":\""
                + subscriptionId(BROADCASTER1, CHAT_MESSAGE).value() + "\"}}}");

        verify(eventSubApi, times(2)).createSubscription(eq(BROADCASTER1), eq(CHAT_MESSAGE), any());
        verify(eventSubApi, times(1)).createSubscription(eq(BROADCASTER1), eq(CHAT_MESSAGE_DELETE), any());
    }

    @Test
    void onRevocation_whenUnknownSubscription_doesNotReconcile() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();

        listener.onRevocation("{\"payload\":{\"subscription\":{\"id\":\"unknown-sub\"}}}");

        verify(eventSubApi, times(EVENT_TYPES_PER_CHANNEL)).createSubscription(any(), any(), any());
    }

    @Test
    void onRevocation_whenInvalidPayload_doesNotThrow() {
        listener.onRevocation("invalid-json");
    }

    // --- isConnected ---

    @Test
    void isConnected_beforeConnecting_returnsFalse() {
        assertFalse(orchestrator.isConnected());
    }

    @Test
    void isConnected_afterSubscribe_returnsTrue() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();

        assertTrue(orchestrator.isConnected());
    }

    @Test
    void isConnected_afterDisconnected_returnsFalse() {
        when(webSocketClient.connect()).thenReturn(CompletableFuture.completedFuture(SESSION_ID));
        stubCreateSubscription();

        orchestrator.subscribe(BROADCASTER1).join();
        listener.onDisconnected();

        assertFalse(orchestrator.isConnected());
    }

    // --- close ---

    @Test
    void close_delegatesToWebSocketClientDisconnect() {
        orchestrator.close();

        verify(webSocketClient).disconnect();
    }

    @Test
    void close_calledTwice_doesNotThrow() {
        orchestrator.close();
        orchestrator.close();

        verify(webSocketClient, times(2)).disconnect();
    }

    @Test
    void shutdown_delegatesToWebSocketClientShutdown() {
        orchestrator.shutdown();

        verify(webSocketClient).shutdown();
    }
}
