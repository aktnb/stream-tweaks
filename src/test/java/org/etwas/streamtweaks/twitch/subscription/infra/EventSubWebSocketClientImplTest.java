package org.etwas.streamtweaks.twitch.subscription.infra;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventSubWebSocketClientImplTest {

    private EventSubWebSocketClientImpl client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * buildAsync() 自体がWebSocketハンドシェイク前に失敗するケース（接続拒否）で、
     * connect() が返すFutureが例外で完了することを検証する。
     *
     * <p>リグレッション対象: 以前は buildAsync() の戻り値を破棄していたため、この場合
     * onOpen/onError のどちらも呼ばれず、connect() が返すFutureが永久にpendingのまま
     * ハングし、EventSubConnectionCoordinator の接続状態もCONNECTINGのまま復帰不能になっていた。
     */
    @Test
    void connect_whenHandshakeFailsImmediately_completesExceptionallyInsteadOfHanging() throws Exception {
        int closedPort;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            closedPort = serverSocket.getLocalPort();
        }

        client = new EventSubWebSocketClientImpl();

        CompletableFuture<SessionId> future = client.connect("ws://127.0.0.1:" + closedPort + "/");

        // タイムアウトせず例外完了すること自体がリグレッションの検証。
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertNotNull(ex.getCause());
    }
}
