package org.etwas.streamtweaks.twitch.auth.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.etwas.streamtweaks.twitch.auth.OAuthCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalCallbackServerTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private LocalCallbackServer callbackServer;

    @BeforeEach
    void setUp() {
        callbackServer = new LocalCallbackServer();
        callbackServer.start();
    }

    @AfterEach
    void tearDown() {
        callbackServer.stop();
    }

    @Test
    void callbackConsume_onValidRequest_completesCallback()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        HttpResponse<String> response = postCallbackBody("access_token=token123&state=state%20123");

        assertEquals(200, response.statusCode());

        OAuthCallback callback = callbackServer.onCallback().get(1, TimeUnit.SECONDS);
        assertEquals("token123", callback.token().value());
        assertEquals("state 123", callback.state());
    }

    @Test
    void callbackConsume_whenRequiredParametersAreMissing_returnsBadRequest() throws IOException, InterruptedException {
        HttpResponse<String> response = postCallbackBody("state=state-only");

        assertEquals(400, response.statusCode());
        assertFalse(callbackServer.onCallback().isDone());
    }

    @Test
    void callbackConsume_whenAccessTokenIsDuplicated_returnsBadRequest() throws IOException, InterruptedException {
        HttpResponse<String> response = postCallbackBody("access_token=first&access_token=second&state=state-123");

        assertEquals(400, response.statusCode());
        assertFalse(callbackServer.onCallback().isDone());
    }

    @Test
    void callbackConsume_whenFormEncodingIsInvalid_returnsBadRequest() throws IOException, InterruptedException {
        HttpResponse<String> response = postCallbackBody("access_token=token%ZZ&state=state-123");

        assertEquals(400, response.statusCode());
        assertFalse(callbackServer.onCallback().isDone());
    }

    private HttpResponse<String> postCallbackBody(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:7654/callback/consume"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
