package org.etwas.streamtweaks.twitch.core.infra;

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
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.TwitchApiClient;
import org.etwas.streamtweaks.twitch.core.TwitchConstants;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitch Helix APIの実装（インフラ層）
 * HTTPリクエストを使ってTwitch APIと通信する
 *
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#get-users">Get Users</a>
 */
public class TwitchApiClientImpl implements TwitchApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwitchApiClientImpl.class);
    // https://dev.twitch.tv/docs/api/reference/#get-users
    private static final String USERS_URL = "https://api.twitch.tv/helix/users";
    // Twitch APIが応答不能になった場合にsendAsync()のFutureが永久にpendingのまま残るのを防ぐ
    // （getUserId()は/twitch connect・disconnectのクリティカルパスにあり、ハングするとコマンドが
    // 応答なしのまま復帰不能になる）。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final TwitchCredentialRepository credentialRepository;
    private final HttpClient client;
    private final Gson gson;

    public TwitchApiClientImpl(TwitchCredentialRepository credentialRepository) {
        this.credentialRepository = credentialRepository;
        this.client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        this.gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    @Override
    public CompletableFuture<UserId> getUserId(Login login) {
        return CompletableFuture.supplyAsync(() -> {
                    TwitchCredential credential = credentialRepository.loadCredential();
                    if (credential == null || credential.accessToken() == null) {
                        throw new IllegalStateException("No credentials available");
                    }
                    return credential;
                })
                .thenCompose(credential -> sendGetUserRequest(login, credential));
    }

    private CompletableFuture<UserId> sendGetUserRequest(Login login, TwitchCredential credential) {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USERS_URL + "?login=" + login.value()))
                .header("Authorization", "Bearer " + credential.accessToken().value())
                .header("Client-Id", TwitchConstants.CLIENT_ID)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 401) {
                LOGGER.error("Failed to get user. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("トークンが無効または期限切れです。/twitch login で再認証してください");
            }
            if (response.statusCode() != 200) {
                LOGGER.error("Failed to get user. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to get user: " + response.statusCode());
            }

            GetUsersResponse responseBody = gson.fromJson(response.body(), GetUsersResponse.class);

            if (responseBody.data == null || responseBody.data.length == 0) {
                throw new RuntimeException("User not found: " + login.value());
            }

            return new UserId(responseBody.data[0].id);
        });
    }

    // レスポンス用のDTO
    // https://dev.twitch.tv/docs/api/reference/#get-users
    static class GetUsersResponse {
        UserData[] data;
    }

    static class UserData {
        String id;
        String login;
        String displayName;
        String broadcasterType;
        String description;
        String profileImageUrl;
        String offlineImageUrl;
        String createdAt;
    }
}
