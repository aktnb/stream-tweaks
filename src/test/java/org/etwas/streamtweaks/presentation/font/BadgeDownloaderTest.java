package org.etwas.streamtweaks.presentation.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * バッジ画像はTwitch Helix APIレスポンス由来のURLをそのまま取得するため、
 * {@link EmoteDownloader} と異なりURL自体の信頼性検証（スキーム・ホストの完全一致許可リスト）が
 * 必須になる。このテストではセキュリティレビューで指摘されたHigh項目
 * （ホスト完全一致・ファイル名バリデーション・サイズ上限）に加えて、
 * マルチチャンネル接続時のキャッシュ衝突（同一set_id/versionIdでもチャンネルが異なれば
 * 画像が異なりうる）を重点的に検証する。
 */
@ExtendWith(MockitoExtension.class)
class BadgeDownloaderTest {

    private static final UserId BROADCASTER = new UserId("broadcaster1");
    private static final UserId OTHER_BROADCASTER = new UserId("broadcaster2");
    private static final BadgeKey KEY = new BadgeKey("moderator", "1");
    private static final String VALID_URL = "https://static-cdn.jtvnw.net/badges/v1/moderator/4";

    @TempDir
    Path tempDir;

    @Mock
    HttpClient httpClient;

    @Mock
    HttpResponse<InputStream> httpResponse;

    @Mock
    NativeImage mockImage;

    BadgeDownloader downloader;

    @BeforeEach
    void setUp() {
        downloader = new BadgeDownloader(tempDir, httpClient, is -> mockImage);
    }

    // --- ホスト許可リスト（完全一致） ---

    @Test
    void acceptsAllowedHostOverHttps() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[10]));

        Optional<NativeImage> result = downloader.download(BROADCASTER, KEY, VALID_URL).join();

        assertTrue(result.isPresent());
    }

    @Test
    void rejectsHttpScheme() {
        Optional<NativeImage> result = downloader
                .download(BROADCASTER, KEY, "http://static-cdn.jtvnw.net/badges/v1/moderator/4")
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsHostThatOnlyContainsAllowedHostAsSuffixSpoof() {
        // static-cdn.jtvnw.net.evil.com のような部分一致バイパスを拒否できること
        Optional<NativeImage> result = downloader
                .download(BROADCASTER, KEY, "https://static-cdn.jtvnw.net.evil.com/badges/v1/moderator/4")
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsSubdomainOfAllowedHost() {
        // evil.static-cdn.jtvnw.net のようなサブドメインも完全一致でないため拒否すること
        Optional<NativeImage> result = downloader
                .download(BROADCASTER, KEY, "https://evil.static-cdn.jtvnw.net/badges/v1/moderator/4")
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsUnrelatedHost() {
        Optional<NativeImage> result = downloader
                .download(BROADCASTER, KEY, "https://evil.example.com/badges/v1/moderator/4")
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsMalformedUrl() {
        Optional<NativeImage> result =
                downloader.download(BROADCASTER, KEY, "not a url").join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsNullOrBlankUrl() {
        assertFalse(downloader.download(BROADCASTER, KEY, null).join().isPresent());
        assertFalse(downloader.download(BROADCASTER, KEY, "").join().isPresent());
    }

    // --- broadcasterId / set_id / versionId のファイル名バリデーション ---

    @Test
    void rejectsInvalidBroadcasterId() {
        Optional<NativeImage> result = downloader
                .download(new UserId("../evil"), KEY, VALID_URL)
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsInvalidSetId() {
        Optional<NativeImage> result = downloader
                .download(BROADCASTER, new BadgeKey("../evil", "1"), VALID_URL)
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsInvalidVersionId() {
        Optional<NativeImage> result = downloader
                .download(BROADCASTER, new BadgeKey("moderator", "../../evil"), VALID_URL)
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void rejectsOverlongId() {
        Optional<NativeImage> result = downloader
                .download(BROADCASTER, new BadgeKey("a".repeat(65), "1"), VALID_URL)
                .join();

        assertFalse(result.isPresent());
        verifyNoHttpCall();
    }

    @Test
    void acceptsValidIdCharacterSet() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[10]));

        Optional<NativeImage> result = downloader
                .download(BROADCASTER, new BadgeKey("sub-gifter", "1000"), VALID_URL)
                .join();

        assertTrue(result.isPresent());
        assertTrue(Files.exists(tempDir.resolve("broadcaster1_sub-gifter_1000.png")));
    }

    // --- マルチチャンネル接続時のキャッシュ衝突バグの回帰テスト ---
    // 同一set_id/versionId（例: subscriber tier3）でも、サブスクライバーバッジ等の
    // チャンネル固有バッジは画像がチャンネルごとに異なりうる。broadcasterIdをキャッシュ
    // ファイル名に含めないと、先にダウンロードしたチャンネルの画像を別チャンネルの
    // バッジとして誤って使い回してしまう（過去に発生したバグ）。

    @Test
    void sameBadgeKeyOnDifferentChannelsAreCachedToDifferentFiles() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}), new ByteArrayInputStream(new byte[] {
                    4, 5, 6
                }));

        downloader.download(BROADCASTER, KEY, VALID_URL).join();
        downloader.download(OTHER_BROADCASTER, KEY, "https://static-cdn.jtvnw.net/badges/v1/moderator-other/4")
                .join();

        assertTrue(Files.exists(tempDir.resolve("broadcaster1_moderator_1.png")));
        assertTrue(Files.exists(tempDir.resolve("broadcaster2_moderator_1.png")));
        assertFalse(
                Arrays.equals(
                        Files.readAllBytes(tempDir.resolve("broadcaster1_moderator_1.png")),
                        Files.readAllBytes(tempDir.resolve("broadcaster2_moderator_1.png"))),
                "チャンネルごとに別内容のファイルとしてキャッシュされること（画像の使い回し衝突が起きないこと）");
    }

    @Test
    void channelACacheHitDoesNotAffectChannelBDownload() throws Exception {
        // チャンネルAが先にキャッシュ済みでも、チャンネルBは自分専用のキャッシュファイルを
        // 参照するため、キャッシュヒットせず正しくHTTPリクエストが飛ぶこと。
        Files.write(tempDir.resolve("broadcaster1_moderator_1.png"), new byte[] {9, 9, 9});
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        Optional<NativeImage> result =
                downloader.download(OTHER_BROADCASTER, KEY, VALID_URL).join();

        assertTrue(result.isPresent());
        verify(httpClient).send(any(), any());
        assertTrue(Files.exists(tempDir.resolve("broadcaster2_moderator_1.png")));
    }

    @Test
    void sameBroadcasterAndKeyHitsCacheOnSecondCall() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[10]));

        downloader.download(BROADCASTER, KEY, VALID_URL).join();
        downloader.download(BROADCASTER, KEY, VALID_URL).join();

        verify(httpClient).send(any(), any());
    }

    // --- ダウンロードサイズ上限 ---

    @Test
    void oversizeResponseReturnsEmptyAndIsNotCached() throws Exception {
        byte[] bigBytes = new byte[512 * 1024 + 2];
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(bigBytes));

        Optional<NativeImage> result = downloader.download(BROADCASTER, KEY, VALID_URL).join();

        assertFalse(result.isPresent());
        assertFalse(Files.exists(tempDir.resolve("broadcaster1_moderator_1.png")));
    }

    @Test
    void exactlyAtSizeLimitIsAccepted() throws Exception {
        byte[] bytes = new byte[512 * 1024];
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(bytes));

        Optional<NativeImage> result = downloader.download(BROADCASTER, KEY, VALID_URL).join();

        assertTrue(result.isPresent());
    }

    // --- キャッシュヒット / HTTPエラー ---

    @Test
    void cacheHitSkipsDownload() throws Exception {
        Files.write(tempDir.resolve("broadcaster1_moderator_1.png"), new byte[10]);

        Optional<NativeImage> result = downloader.download(BROADCASTER, KEY, VALID_URL).join();

        assertTrue(result.isPresent());
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void httpErrorReturnsEmpty() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(404);

        Optional<NativeImage> result = downloader.download(BROADCASTER, KEY, VALID_URL).join();

        assertFalse(result.isPresent());
    }

    // --- 認証ヘッダ不付与の確認 ---

    @Test
    void requestDoesNotIncludeHelixAuthHeaders() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[10]));

        downloader.download(BROADCASTER, KEY, VALID_URL).join();

        verify(httpClient)
                .send(
                        argThat((HttpRequest req) -> req.headers().firstValue("Authorization").isEmpty()
                                && req.headers().firstValue("Client-Id").isEmpty()),
                        any());
    }

    // --- デフォルトHttpClientの設定（リダイレクトポリシー） ---

    @Test
    void defaultHttpClientNeverFollowsRedirects() throws Exception {
        BadgeDownloader defaultDownloader = new BadgeDownloader(tempDir);

        Field httpClientField = BadgeDownloader.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        HttpClient client = (HttpClient) httpClientField.get(defaultDownloader);

        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
    }

    private void verifyNoHttpCall() {
        try {
            verify(httpClient, never()).send(any(), any());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
