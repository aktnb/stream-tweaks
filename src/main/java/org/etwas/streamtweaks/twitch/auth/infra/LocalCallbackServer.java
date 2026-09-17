package org.etwas.streamtweaks.twitch.auth.infra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.auth.AccessToken;
import org.etwas.streamtweaks.twitch.auth.CallbackServer;
import org.etwas.streamtweaks.twitch.auth.OAuthCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalCallbackServer implements CallbackServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalCallbackServer.class);
    private static final int PORT = 7654;
    private static final String INVALID_CALLBACK_HTML =
            "<h1>Invalid authentication response</h1><p>Missing required OAuth parameters.</p>";
    private static final String CALLBACK_ERROR_HTML =
            "<h1>Error processing authentication</h1><p>Please close this window and try again.</p>";
    private HttpServer httpServer;

    private CompletableFuture<OAuthCallback> callbackFuture = new CompletableFuture<>();

    @Override
    public void start() {
        LOGGER.info("Starting local callback server on port {}", PORT);
        if (httpServer != null
                && httpServer.getAddress() != null
                && httpServer.getAddress().getPort() == PORT) {
            throw new IllegalStateException("Callback server is already running");
        }
        callbackFuture = new CompletableFuture<>();
        try {
            httpServer = HttpServer.create(new java.net.InetSocketAddress("localhost", PORT), 0);
            httpServer.createContext("/callback", exchange -> {
                String html = HtmlTemplate.render("callback.html", java.util.Collections.emptyMap());
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);
                try (var os = exchange.getResponseBody()) {
                    os.write(html.getBytes(StandardCharsets.UTF_8));
                }
            });
            httpServer.createContext("/callback/consume", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                    return;
                }
                try {
                    String body;
                    try (var is = exchange.getRequestBody()) {
                        body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }

                    Map<String, String> params = parseFormBody(body);
                    LOGGER.info("Received OAuth callback consume request");

                    String token = params.get("access_token");
                    String state = params.get("state");
                    if (token == null || token.isBlank() || state == null || state.isBlank()) {
                        LOGGER.warn("OAuth callback is missing required parameters");
                        sendHtmlResponse(exchange, 400, INVALID_CALLBACK_HTML);
                        return;
                    }

                    String html = HtmlTemplate.render("callback-consume.html", java.util.Collections.emptyMap());
                    sendHtmlResponse(exchange, 200, html);

                    callbackFuture.complete(new OAuthCallback(new AccessToken(token), state));
                } catch (InvalidCallbackRequestException e) {
                    LOGGER.warn("Rejected invalid OAuth callback request: {}", e.getMessage());
                    sendHtmlResponse(exchange, 400, INVALID_CALLBACK_HTML);
                } catch (Exception e) {
                    LOGGER.error("Error processing callback", e);
                    try {
                        sendHtmlResponse(exchange, 500, CALLBACK_ERROR_HTML);
                    } catch (Exception ex) {
                        LOGGER.error("Failed to send error response", ex);
                    }
                }
            });
            httpServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start local callback server", e);
        }
    }

    @Override
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    @Override
    public CompletableFuture<OAuthCallback> onCallback() {
        return callbackFuture;
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String entry : body.split("&")) {
            String[] keyValue = entry.split("=", 2);
            if (keyValue.length < 2) {
                continue;
            }

            String key = decodeFormComponent(keyValue[0]);
            String value = decodeFormComponent(keyValue[1]);
            if (params.putIfAbsent(key, value) != null) {
                throw new InvalidCallbackRequestException("Duplicate OAuth callback parameter");
            }
        }
        return params;
    }

    private static String decodeFormComponent(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidCallbackRequestException("Malformed OAuth callback parameter");
        }
    }

    private static void sendHtmlResponse(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, htmlBytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(htmlBytes);
        }
    }

    private static final class InvalidCallbackRequestException extends RuntimeException {
        private InvalidCallbackRequestException(String message) {
            super(message);
        }
    }
}
