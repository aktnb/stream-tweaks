package org.etwas.streamtweaks.twitch.badge.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.auth.AccessToken;
import org.etwas.streamtweaks.twitch.auth.TwitchCredential;
import org.etwas.streamtweaks.twitch.auth.TwitchCredentialRepository;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.badge.domain.ChatBadge;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.TwitchConstants;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TwitchChatBadgeApiImpl} のHTTPレベルの振る舞い（URL組み立て・認証ヘッダ・
 * ステータスコード別ハンドリング・JSONパースの防御性）を検証する。
 *
 * <p>{@link org.etwas.streamtweaks.twitch.badge.BadgeCatalogRepositoryTest} は
 * {@code TwitchChatBadgeApi} インターフェースをモックしているため、実際のHTTPリクエスト組み立てや
 * レスポンスハンドリングまでは検証できていなかった。このテストではそこを直接カバーする。
 */
@ExtendWith(MockitoExtension.class)
class TwitchChatBadgeApiImplTest {

    private static final UserId BROADCASTER = new UserId("12345");
    private static final TwitchCredential CREDENTIAL = new TwitchCredential(
            new AccessToken("test-token"), java.util.List.of(), new UserId("999"), new Login("me"));

    @Mock
    TwitchCredentialRepository credentialRepository;

    @Mock
    HttpClient httpClient;

    @Mock
    HttpResponse<String> httpResponse;

    TwitchChatBadgeApiImpl api;

    @BeforeEach
    void setUp() {
        api = new TwitchChatBadgeApiImpl(credentialRepository, httpClient, new Gson());
    }

    // --- URL組み立て ---

    @Test
    void getGlobalBadgesRequestsTheGlobalEndpoint() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse("{\"data\": []}");

        api.getGlobalBadges().join();

        verify(httpClient)
                .sendAsync(
                        argThat((HttpRequest req) -> req.uri()
                                .toString()
                                .equals("https://api.twitch.tv/helix/chat/badges/global")),
                        any());
    }

    @Test
    void getChannelBadgesRequestsTheChannelEndpointWithBroadcasterId() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse("{\"data\": []}");

        api.getChannelBadges(BROADCASTER).join();

        verify(httpClient)
                .sendAsync(
                        argThat((HttpRequest req) -> req.uri()
                                .toString()
                                .equals("https://api.twitch.tv/helix/chat/badges?broadcaster_id=12345")),
                        any());
    }

    @Test
    void requestIncludesAuthorizationAndClientIdHeaders() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse("{\"data\": []}");

        api.getGlobalBadges().join();

        verify(httpClient)
                .sendAsync(
                        argThat((HttpRequest req) -> req.headers()
                                        .firstValue("Authorization")
                                        .orElse("")
                                        .equals("Bearer test-token")
                                && req.headers()
                                        .firstValue("Client-Id")
                                        .orElse("")
                                        .equals(TwitchConstants.CLIENT_ID)),
                        any());
    }

    // --- ステータスコード別ハンドリング ---

    @Test
    void unauthorizedResponseThrowsReauthenticationMessage() {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        doReturn(CompletableFuture.completedFuture(httpResponse))
                .when(httpClient)
                .sendAsync(any(), any());
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn("{}");

        Exception thrown = assertThrows(Exception.class, () -> api.getGlobalBadges().join());
        assertTrue(rootCause(thrown).getMessage().contains("再認証"));
    }

    @Test
    void nonOkResponseThrowsWithStatusCode() {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        doReturn(CompletableFuture.completedFuture(httpResponse))
                .when(httpClient)
                .sendAsync(any(), any());
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("internal error");

        Exception thrown = assertThrows(Exception.class, () -> api.getGlobalBadges().join());
        assertTrue(rootCause(thrown).getMessage().contains("500"));
    }

    @Test
    void missingCredentialThrowsIllegalStateException() {
        when(credentialRepository.loadCredential()).thenReturn(null);

        Exception thrown = assertThrows(Exception.class, () -> api.getGlobalBadges().join());
        assertTrue(rootCause(thrown) instanceof IllegalStateException);
    }

    /** join()がCompletionExceptionでラップするかどうかの内部仕様に依存しないよう根本原因まで辿る。 */
    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    // --- JSONパースの防御性 ---

    @Test
    void parsesFullResponseWithMultipleSetsAndVersions() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse(
                """
                {
                  "data": [
                    {
                      "set_id": "moderator",
                      "versions": [
                        {"id": "1", "image_url_1x": "a1", "image_url_2x": "a2", "image_url_4x": "a4"}
                      ]
                    },
                    {
                      "set_id": "subscriber",
                      "versions": [
                        {"id": "0", "image_url_4x": "s0-4x"},
                        {"id": "3", "image_url_4x": "s3-4x"}
                      ]
                    }
                  ]
                }
                """);

        Map<BadgeKey, ChatBadge> result = api.getGlobalBadges().join();

        assertEquals(3, result.size());
        assertEquals("a4", result.get(new BadgeKey("moderator", "1")).imageUrl());
        assertEquals("s0-4x", result.get(new BadgeKey("subscriber", "0")).imageUrl());
        assertEquals("s3-4x", result.get(new BadgeKey("subscriber", "3")).imageUrl());
    }

    @Test
    void missingDataFieldReturnsEmptyMap() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse("{}");

        Map<BadgeKey, ChatBadge> result = api.getGlobalBadges().join();

        assertTrue(result.isEmpty());
    }

    @Test
    void setWithoutVersionsFieldIsSkipped() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse(
                """
                {"data": [{"set_id": "moderator"}]}
                """);

        Map<BadgeKey, ChatBadge> result = api.getGlobalBadges().join();

        assertTrue(result.isEmpty());
    }

    @Test
    void setWithoutSetIdIsSkipped() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse(
                """
                {"data": [{"versions": [{"id": "1", "image_url_4x": "a4"}]}]}
                """);

        Map<BadgeKey, ChatBadge> result = api.getGlobalBadges().join();

        assertTrue(result.isEmpty());
    }

    @Test
    void versionWithoutImageUrl4xIsSkipped() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse(
                """
                {"data": [{"set_id": "moderator", "versions": [{"id": "1"}]}]}
                """);

        Map<BadgeKey, ChatBadge> result = api.getGlobalBadges().join();

        assertTrue(result.isEmpty());
    }

    @Test
    void versionWithoutIdIsSkipped() throws Exception {
        when(credentialRepository.loadCredential()).thenReturn(CREDENTIAL);
        stubSuccessfulResponse(
                """
                {"data": [{"set_id": "moderator", "versions": [{"image_url_4x": "a4"}]}]}
                """);

        Map<BadgeKey, ChatBadge> result = api.getGlobalBadges().join();

        assertTrue(result.isEmpty());
    }

    private void stubSuccessfulResponse(String body) throws Exception {
        doReturn(CompletableFuture.completedFuture(httpResponse))
                .when(httpClient)
                .sendAsync(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(body);
    }
}
