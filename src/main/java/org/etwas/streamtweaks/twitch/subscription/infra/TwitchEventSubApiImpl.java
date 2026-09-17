package org.etwas.streamtweaks.twitch.subscription.infra;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.auth.TwitchCredential;
import org.etwas.streamtweaks.twitch.auth.TwitchCredentialRepository;
import org.etwas.streamtweaks.twitch.core.TwitchConstants;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.etwas.streamtweaks.twitch.subscription.api.EventSubSubscription;
import org.etwas.streamtweaks.twitch.subscription.api.TwitchEventSubApi;
import org.etwas.streamtweaks.twitch.subscription.domain.SubscriptionId;
import org.etwas.streamtweaks.twitch.subscription.websocket.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitch EventSub APIの実装（インフラ層）
 * HTTPリクエストを使ってTwitch APIと通信する
 *
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#create-eventsub-subscription">Create EventSub Subscription</a>
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#delete-eventsub-subscription">Delete EventSub Subscription</a>
 */
public class TwitchEventSubApiImpl implements TwitchEventSubApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwitchEventSubApiImpl.class);
    // https://dev.twitch.tv/docs/api/reference/#create-eventsub-subscription
    private static final String EVENTSUB_URL = "https://api.twitch.tv/helix/eventsub/subscriptions";
    // Twitch APIが応答不能になった場合にsendAsync()のFutureが永久にpendingのまま残るのを防ぐ
    // （SubscriptionReconciler.reconcile()のCompletableFuture.allOf()がこれらに依存しており、
    // 1件でもハングすると購読/購読解除全体が完了不能になる）。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final TwitchCredentialRepository credentialRepository;
    private final HttpClient client;
    private final Gson gson;

    public TwitchEventSubApiImpl(TwitchCredentialRepository credentialRepository) {
        this.credentialRepository = credentialRepository;
        this.client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        this.gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    @Override
    public CompletableFuture<EventSubSubscription> createSubscription(
            UserId broadcasterId, String eventType, SessionId sessionId) {
        return CompletableFuture.supplyAsync(() -> {
                    TwitchCredential credential = credentialRepository.loadCredential();
                    if (credential == null || credential.accessToken() == null) {
                        throw new IllegalStateException("No credentials available");
                    }
                    if (credential.userId() == null) {
                        throw new IllegalStateException("No user ID in credentials");
                    }
                    return credential;
                })
                .thenCompose(credential -> {
                    var userId = credential.userId();
                    return sendCreateRequest(broadcasterId, userId, eventType, sessionId, credential);
                });
    }

    @Override
    public CompletableFuture<Void> deleteSubscription(SubscriptionId subscriptionId) {
        return CompletableFuture.supplyAsync(() -> {
                    TwitchCredential credential = credentialRepository.loadCredential();
                    if (credential == null || credential.accessToken() == null) {
                        throw new IllegalStateException("No credentials available");
                    }
                    return credential;
                })
                .thenCompose(credential -> sendDeleteRequest(subscriptionId, credential));
    }

    private CompletableFuture<EventSubSubscription> sendCreateRequest(
            UserId broadcasterId, UserId userId, String eventType, SessionId sessionId, TwitchCredential credential) {

        CreateSubscriptionRequest requestBody = new CreateSubscriptionRequest(
                eventType,
                "1",
                new Condition(broadcasterId.value(), userId.value()),
                new Transport("websocket", sessionId.value()));

        String jsonBody = gson.toJson(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EVENTSUB_URL))
                .header("Authorization", "Bearer " + credential.accessToken().value())
                .header("Client-Id", TwitchConstants.CLIENT_ID)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 401) {
                LOGGER.error(
                        "Failed to create subscription. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("トークンが無効または期限切れです。/twitch login で再認証してください");
            }
            if (response.statusCode() != 202) {
                LOGGER.error(
                        "Failed to create subscription. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to create subscription: " + response.statusCode());
            }

            CreateSubscriptionResponse responseBody = gson.fromJson(response.body(), CreateSubscriptionResponse.class);

            if (responseBody.data == null || responseBody.data.length == 0) {
                throw new RuntimeException("No subscription data in response");
            }

            SubscriptionData data = responseBody.data[0];
            return new EventSubSubscription(new SubscriptionId(data.id), data.type, data.status, broadcasterId);
        });
    }

    private CompletableFuture<Void> sendDeleteRequest(SubscriptionId subscriptionId, TwitchCredential credential) {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EVENTSUB_URL + "?id=" + subscriptionId.value()))
                .header("Authorization", "Bearer " + credential.accessToken().value())
                .header("Client-Id", TwitchConstants.CLIENT_ID)
                .timeout(REQUEST_TIMEOUT)
                .DELETE()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 204) {
                LOGGER.error(
                        "Failed to delete subscription. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to delete subscription: " + response.statusCode());
            }
            return null;
        });
    }

    // リクエスト/レスポンス用のDTO
    // https://dev.twitch.tv/docs/api/reference/#create-eventsub-subscription
    record CreateSubscriptionRequest(String type, String version, Condition condition, Transport transport) {}

    // broadcaster_user_id: 配信者ID、user_id: 購読者（認証ユーザー）ID
    // https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#channelchatmessage
    record Condition(String broadcasterUserId, String userId) {}

    // method: "websocket"、sessionId: session_welcome で受け取ったセッションID
    // https://dev.twitch.tv/docs/eventsub/handling-websocket-events/#websocket-transport
    record Transport(String method, String sessionId) {}

    static class CreateSubscriptionResponse {
        SubscriptionData[] data;
        int total;
        int maxTotalCost;
        int totalCost;
    }

    static class SubscriptionData {
        String id;
        // https://dev.twitch.tv/docs/eventsub/manage-subscriptions/#subscription-statuses
        String status;
        String type;
        String version;
        Condition condition;
        String createdAt;
        Transport transport;
        int cost;
    }
}
