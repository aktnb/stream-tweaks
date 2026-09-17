package org.etwas.streamtweaks.twitch.badge.infra;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.auth.TwitchCredential;
import org.etwas.streamtweaks.twitch.auth.TwitchCredentialRepository;
import org.etwas.streamtweaks.twitch.badge.api.TwitchChatBadgeApi;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.badge.domain.ChatBadge;
import org.etwas.streamtweaks.twitch.core.TwitchConstants;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitch Helix APIのチャットバッジ取得エンドポイントの実装（インフラ層）。
 * HTTPリクエストを使ってTwitch APIと通信する。認証ヘッダを付与するため、
 * バッジ画像自体をダウンロードする {@code BadgeDownloader}（CDNアクセス、無認証）とは
 * 完全に別のHTTPクライアント・責務として分離している。
 *
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#get-global-chat-badges">Get Global Chat Badges</a>
 * @see <a href="https://dev.twitch.tv/docs/api/reference/#get-channel-chat-badges">Get Channel Chat Badges</a>
 */
public class TwitchChatBadgeApiImpl implements TwitchChatBadgeApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwitchChatBadgeApiImpl.class);
    private static final String GLOBAL_BADGES_URL = "https://api.twitch.tv/helix/chat/badges/global";
    private static final String CHANNEL_BADGES_URL = "https://api.twitch.tv/helix/chat/badges";
    // Twitch APIが応答不能になった場合にsendAsync()のFutureが永久にpendingのまま残るのを防ぐ
    // （BadgeCatalogRepositoryのキャッシュはこのFutureをそのまま保持するため、ハングすると
    // 該当チャンネルのバッジが永久に取得できず、再接続してもリトライされなくなる）。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final TwitchCredentialRepository credentialRepository;
    private final HttpClient client;
    private final Gson gson;

    public TwitchChatBadgeApiImpl(TwitchCredentialRepository credentialRepository) {
        // set_id/image_url_4x のような数字混じりのフィールド名はFieldNamingPolicyの
        // キャメルケース分割が想定通りに働かないため、各DTOフィールドに @SerializedName を
        // 明示する方針（ChatMessageNotificationと同じ流儀）にし、ポリシーは適用しない。
        this(credentialRepository, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), new Gson());
    }

    // HTTPレベルの単体テストのためにHttpClient/Gsonを注入できるようにしたパッケージプライベート
    // コンストラクタ（EmoteDownloader/BadgeDownloaderと同じテスタビリティのパターン）。
    TwitchChatBadgeApiImpl(TwitchCredentialRepository credentialRepository, HttpClient client, Gson gson) {
        this.credentialRepository = credentialRepository;
        this.client = client;
        this.gson = gson;
    }

    @Override
    public CompletableFuture<Map<BadgeKey, ChatBadge>> getGlobalBadges() {
        return fetchBadges(GLOBAL_BADGES_URL);
    }

    @Override
    public CompletableFuture<Map<BadgeKey, ChatBadge>> getChannelBadges(UserId broadcasterId) {
        // broadcasterIdは通常Twitchの数値ID文字列だが、BadgeDownloader側の厳格な入力検証との
        // 一貫性のためクエリパラメータとして安全にエンコードしてから連結する。
        String encodedId = URLEncoder.encode(broadcasterId.value(), StandardCharsets.UTF_8);
        return fetchBadges(CHANNEL_BADGES_URL + "?broadcaster_id=" + encodedId);
    }

    private CompletableFuture<Map<BadgeKey, ChatBadge>> fetchBadges(String url) {
        return CompletableFuture.supplyAsync(() -> {
                    TwitchCredential credential = credentialRepository.loadCredential();
                    if (credential == null || credential.accessToken() == null) {
                        throw new IllegalStateException("No credentials available");
                    }
                    return credential;
                })
                .thenCompose(credential -> sendGetBadgesRequest(url, credential));
    }

    private CompletableFuture<Map<BadgeKey, ChatBadge>> sendGetBadgesRequest(String url, TwitchCredential credential) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + credential.accessToken().value())
                .header("Client-Id", TwitchConstants.CLIENT_ID)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 401) {
                LOGGER.error("Failed to get chat badges. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("トークンが無効または期限切れです。/twitch login で再認証してください");
            }
            if (response.statusCode() != 200) {
                LOGGER.error("Failed to get chat badges. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to get chat badges: " + response.statusCode());
            }

            GetBadgesResponse responseBody = gson.fromJson(response.body(), GetBadgesResponse.class);
            return toBadgeMap(responseBody);
        });
    }

    private static Map<BadgeKey, ChatBadge> toBadgeMap(GetBadgesResponse response) {
        Map<BadgeKey, ChatBadge> result = new HashMap<>();
        if (response == null || response.data() == null) {
            return result;
        }
        for (BadgeSetDto set : response.data()) {
            if (set.setId() == null || set.versions() == null) {
                continue;
            }
            for (BadgeVersionDto version : set.versions()) {
                if (version.id() == null || version.imageUrl4x() == null) {
                    continue;
                }
                BadgeKey key = new BadgeKey(set.setId(), version.id());
                result.put(key, new ChatBadge(key, version.imageUrl4x()));
            }
        }
        return result;
    }

    // レスポンス用のDTO
    // https://dev.twitch.tv/docs/api/reference/#get-global-chat-badges
    record GetBadgesResponse(List<BadgeSetDto> data) {}

    record BadgeSetDto(@SerializedName("set_id") String setId, List<BadgeVersionDto> versions) {}

    record BadgeVersionDto(
            String id,
            @SerializedName("image_url_1x") String imageUrl1x,
            @SerializedName("image_url_2x") String imageUrl2x,
            @SerializedName("image_url_4x") String imageUrl4x) {}
}
