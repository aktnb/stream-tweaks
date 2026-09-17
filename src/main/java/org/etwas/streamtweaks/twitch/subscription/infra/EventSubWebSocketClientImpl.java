package org.etwas.streamtweaks.twitch.subscription.infra;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.etwas.streamtweaks.twitch.subscription.websocket.EventSubWebSocketClient;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitch EventSub WebSocketクライアントの実装（インフラ層）
 *
 * @see <a href="https://dev.twitch.tv/docs/eventsub/handling-websocket-events/">Handling WebSocket Events</a>
 * @see <a href="https://dev.twitch.tv/docs/eventsub/websocket-reference/">WebSocket Reference</a>
 */
public class EventSubWebSocketClientImpl implements EventSubWebSocketClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventSubWebSocketClientImpl.class);
    // https://dev.twitch.tv/docs/eventsub/handling-websocket-events/#connecting-to-the-eventsub-websocket-server
    private static final String WEBSOCKET_URL = "wss://eventsub.wss.twitch.tv/ws";

    private final HttpClient httpClient;
    private final Gson gson;
    private final ScheduledExecutorService scheduler;

    /** 現在有効な接続。reconnect中は新接続に差し替わり、旧接続はこれと一致しなくなる。 */
    private volatile WebSocket webSocket;

    private EventSubWebSocketListener listener;
    private SessionId currentSessionId;
    private ScheduledFuture<?> keepaliveTimeoutTask;
    private int keepaliveTimeoutSeconds = 10;
    private volatile boolean shutdown;

    public EventSubWebSocketClientImpl() {
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
        this.scheduler = Executors.newScheduledThreadPool(1);
    }

    @Override
    public CompletableFuture<SessionId> connect() {
        if (shutdown) {
            return CompletableFuture.failedFuture(new IllegalStateException("EventSub WebSocket client is shut down"));
        }
        return connect(WEBSOCKET_URL);
    }

    @Override
    public void disconnect() {
        WebSocket current = webSocket;
        // 先にフィールドを空にすることで、この後届くonCloseを「破棄済み接続のもの」として無視させる。
        webSocket = null;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnect");
        }
        handleDisconnection();
    }

    @Override
    public void shutdown() {
        shutdown = true;
        disconnect();
        scheduler.shutdownNow();
    }

    @Override
    public Optional<SessionId> getCurrentSessionId() {
        return Optional.ofNullable(currentSessionId);
    }

    @Override
    public void setListener(EventSubWebSocketListener listener) {
        this.listener = listener;
    }

    // パッケージプライベート: テストから到達不能なURLを注入し、
    // ハンドシェイク失敗時にsessionFutureが確実に完了することを検証できるようにする。
    CompletableFuture<SessionId> connect(String url) {
        CompletableFuture<SessionId> sessionFuture = new CompletableFuture<>();

        CompletableFuture<WebSocket> handshake = httpClient.newWebSocketBuilder().buildAsync(
                URI.create(url), new WebSocket.Listener() {

                    /**
                     * この接続専用の受信バッファ。インスタンスフィールドで共有すると、
                     * reconnect中に旧接続と新接続のonTextが並行して書き込み、JSONが混線しうる。
                     */
                    private final StringBuilder messageBuffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        LOGGER.info("EventSub WebSocket connected");
                        EventSubWebSocketClientImpl.this.webSocket = webSocket;
                        webSocket.request(1);
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messageBuffer.append(data);

                        if (last) {
                            String message = messageBuffer.toString();
                            messageBuffer.setLength(0);
                            handleMessage(message, sessionFuture);
                        }

                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                        webSocket.request(1);
                        return WebSocket.Listener.super.onPing(webSocket, message);
                    }

                    @Override
                    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                        webSocket.request(1);
                        return WebSocket.Listener.super.onPong(webSocket, message);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        LOGGER.info("EventSub WebSocket closed: {} - {}", statusCode, reason);
                        if (isCurrentConnection(webSocket)) {
                            handleDisconnection();
                        } else {
                            LOGGER.debug("Ignored close of a superseded EventSub connection");
                        }
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        LOGGER.error("EventSub WebSocket error", error);
                        if (isCurrentConnection(webSocket)) {
                            handleDisconnection();
                        }
                        sessionFuture.completeExceptionally(error);
                    }
                });

        // buildAsync()自体が失敗した場合（DNS解決失敗・TLSハンドシェイク失敗・接続不能等）、
        // WebSocketがまだ存在しないためonOpen/onErrorのどちらも呼ばれず、sessionFutureが
        // 永久にpendingのまま残ってしまう（ensureConnected()がCONNECTING状態のまま復帰不能になる）。
        // それを防ぐため、buildAsyncの失敗を明示的にsessionFutureへ伝播させる。
        handshake.exceptionally(ex -> {
            LOGGER.error("Failed to establish EventSub WebSocket connection", ex);
            sessionFuture.completeExceptionally(ex);
            return null;
        });

        return sessionFuture;
    }

    private void handleMessage(String message, CompletableFuture<SessionId> sessionFuture) {
        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            JsonObject metadata = json.getAsJsonObject("metadata");
            String messageType = metadata.get("message_type").getAsString();

            LOGGER.debug("Received EventSub message: {}", messageType);

            // https://dev.twitch.tv/docs/eventsub/websocket-reference/#message-types
            switch (messageType) {
                case "session_welcome":
                    // https://dev.twitch.tv/docs/eventsub/websocket-reference/#welcome-message
                    handleWelcome(json, sessionFuture);
                    break;
                case "session_keepalive":
                    // https://dev.twitch.tv/docs/eventsub/websocket-reference/#keepalive-message
                    handleKeepalive();
                    break;
                case "notification":
                    // https://dev.twitch.tv/docs/eventsub/websocket-reference/#notification-message
                    handleNotification(message);
                    break;
                case "session_reconnect":
                    // https://dev.twitch.tv/docs/eventsub/websocket-reference/#reconnect-message
                    handleReconnect(json);
                    break;
                case "revocation":
                    // https://dev.twitch.tv/docs/eventsub/websocket-reference/#revocation-message
                    handleRevocation(message);
                    break;
                default:
                    LOGGER.warn("Unknown EventSub message type: {}", messageType);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to handle EventSub message", e);
        }
    }

    private void handleWelcome(JsonObject json, CompletableFuture<SessionId> sessionFuture) {
        JsonObject payload = json.getAsJsonObject("payload");
        JsonObject session = payload.getAsJsonObject("session");

        String sessionId = session.get("id").getAsString();
        this.currentSessionId = new SessionId(sessionId);

        if (session.has("keepalive_timeout_seconds")) {
            this.keepaliveTimeoutSeconds =
                    session.get("keepalive_timeout_seconds").getAsInt();
        }

        LOGGER.info("EventSub session established: {} (keepalive: {}s)", sessionId, keepaliveTimeoutSeconds);

        resetKeepaliveTimeout();
        sessionFuture.complete(this.currentSessionId);
    }

    private void handleKeepalive() {
        LOGGER.debug("EventSub keepalive received");
        resetKeepaliveTimeout();
    }

    private void handleNotification(String payload) {
        resetKeepaliveTimeout();
        if (listener != null) {
            listener.onNotification(payload);
        }
    }

    /**
     * 渡された接続が「現在有効な接続」かどうか。
     *
     * <p>reconnect時は新接続を確立してから旧接続を閉じるため、旧接続のonClose/onErrorが
     * 新接続の稼働中に届く。これを切断として扱うと、確立したばかりのセッションが即座に
     * 破棄されてしまうため、破棄済み接続からのイベントは無視する。
     */
    private boolean isCurrentConnection(WebSocket candidate) {
        return this.webSocket == candidate;
    }

    private void handleReconnect(JsonObject json) {
        JsonObject payload = json.getAsJsonObject("payload");
        JsonObject session = payload.getAsJsonObject("session");

        if (!session.has("reconnect_url") || session.get("reconnect_url").isJsonNull()) {
            LOGGER.warn("reconnect_url is missing in session_reconnect message");
            handleDisconnection();
            return;
        }

        String reconnectUrl = session.get("reconnect_url").getAsString();

        if (reconnectUrl.isBlank()) {
            LOGGER.warn("Empty reconnect_url in session_reconnect message");
            handleDisconnection();
            return;
        }

        URI uri;
        try {
            uri = URI.create(reconnectUrl);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid reconnect_url rejected");
            handleDisconnection();
            return;
        }

        if (!"wss".equals(uri.getScheme()) || !"eventsub.wss.twitch.tv".equals(uri.getHost())) {
            LOGGER.warn("Unexpected reconnect_url rejected: scheme={}, host={}", uri.getScheme(), uri.getHost());
            handleDisconnection();
            return;
        }

        LOGGER.info("EventSub reconnect requested to: {}", uri.getHost());

        // connect() のonOpenで webSocket フィールドは新接続に差し替わるため、
        // 閉じるべき旧接続をここで捕まえておく。
        WebSocket previousWebSocket = this.webSocket;

        connect(reconnectUrl)
                .thenAccept(newSessionId -> {
                    LOGGER.info("EventSub reconnected with new session: {}", newSessionId.value());
                    currentSessionId = newSessionId;

                    if (listener != null) {
                        listener.onReconnected(newSessionId);
                    }

                    // 古い接続を閉じる（新接続を閉じてしまわないよう捕まえておいた参照を使う）
                    if (previousWebSocket != null) {
                        previousWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Failed to reconnect EventSub WebSocket", ex);
                    handleDisconnection();
                    return null;
                });
    }

    private void handleRevocation(String payload) {
        if (listener != null) {
            listener.onRevocation(payload);
        }
    }

    private void handleDisconnection() {
        cancelKeepaliveTimeout();
        currentSessionId = null;

        if (listener != null) {
            listener.onDisconnected();
        }
    }

    private void resetKeepaliveTimeout() {
        if (shutdown || scheduler.isShutdown()) {
            return;
        }

        cancelKeepaliveTimeout();

        // タイマー発火時にフィールドを読み直すと、reconnect直後の窓で「監視していたのとは別の
        // （確立したばかりの）接続」をabortしうる。監視対象をここで固定しておく。
        WebSocket monitored = this.webSocket;

        try {
            keepaliveTimeoutTask = scheduler.schedule(
                    () -> {
                        if (monitored == null || !isCurrentConnection(monitored)) {
                            LOGGER.debug("Ignored keepalive timeout of a superseded EventSub connection");
                            return;
                        }
                        LOGGER.warn("EventSub keepalive timeout ({}s)", keepaliveTimeoutSeconds);
                        monitored.abort();
                        handleDisconnection();
                    },
                    keepaliveTimeoutSeconds + 1,
                    TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Ignored keepalive timeout scheduling after EventSub shutdown");
        }
    }

    private void cancelKeepaliveTimeout() {
        if (keepaliveTimeoutTask != null) {
            keepaliveTimeoutTask.cancel(false);
            keepaliveTimeoutTask = null;
        }
    }
}
