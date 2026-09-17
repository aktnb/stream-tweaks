package org.etwas.streamtweaks.presentation.font;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmoteDownloaderTest {

    @TempDir
    Path tempDir;

    @Mock
    HttpClient httpClient;

    @Mock
    HttpResponse<InputStream> httpResponse;

    @Mock
    NativeImage mockImage;

    @Mock
    NativeImage mockFrameImage;

    EmoteDownloader downloader;

    @BeforeEach
    void setUp() {
        EmoteDownloader.ImageDecoder imageDecoder = is -> mockImage;
        EmoteDownloader.AnimationDecoder animationDecoder = is -> List.of(new EmoteFrame(mockFrameImage, 100));
        downloader = new EmoteDownloader(tempDir, httpClient, imageDecoder, animationDecoder);
    }

    @Test
    void invalidEmoteIdReturnsEmpty() {
        assertFalse(downloader.download("../evil").join().isPresent());
        assertFalse(downloader.download("").join().isPresent());
        assertFalse(downloader.download("a".repeat(65)).join().isPresent());
        assertFalse(downloader.download("emote with spaces").join().isPresent());
    }

    @Test
    void cacheHitSkipsDownload() throws Exception {
        Files.write(tempDir.resolve("emote123.png"), new byte[10]);

        Optional<EmoteAnimation> result = downloader.download("emote123").join();

        assertTrue(result.isPresent());
        assertFalse(result.get().isAnimated());
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void cacheMissDownloadsAndCachesFile() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[100]));

        Optional<EmoteAnimation> result = downloader.download("newemote").join();

        assertTrue(result.isPresent());
        assertTrue(Files.exists(tempDir.resolve("newemote.png")));
    }

    @Test
    void httpErrorReturnsEmpty() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(404);

        Optional<EmoteAnimation> result = downloader.download("notfound").join();

        assertFalse(result.isPresent());
    }

    @Test
    void oversizeResponseReturnsEmpty() throws Exception {
        byte[] bigBytes = new byte[512 * 1024 + 2];
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(bigBytes));

        Optional<EmoteAnimation> result = downloader.download("toobig").join();

        assertFalse(result.isPresent());
        assertFalse(Files.exists(tempDir.resolve("toobig.png")));
    }

    @Test
    void animatedDownloadUsesAnimatedUrlAndGifExtension() throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[10]));

        Optional<EmoteAnimation> result = downloader.download("animemote", true).join();

        assertTrue(result.isPresent());
        assertTrue(result.get().isAnimated() || result.get().frames().size() == 1);
        assertTrue(Files.exists(tempDir.resolve("animemote.gif")));
        verify(httpClient)
                .send(
                        argThat((HttpRequest req) -> req.uri()
                                .toString()
                                .equals("https://static-cdn.jtvnw.net/emoticons/v2/animemote/animated/dark/2.0")),
                        any());
    }

    @Test
    void animatedDownloadEmptyFramesReturnsEmpty() throws Exception {
        EmoteDownloader.ImageDecoder imageDecoder = is -> mockImage;
        EmoteDownloader.AnimationDecoder emptyDecoder = is -> List.of();
        downloader = new EmoteDownloader(tempDir, httpClient, imageDecoder, emptyDecoder);
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[10]));

        Optional<EmoteAnimation> result = downloader.download("brokengif", true).join();

        assertFalse(result.isPresent());
    }

    @Test
    void downloadWithFallbackFallsBackToStaticWhenAnimatedFails() throws Exception {
        EmoteDownloader.ImageDecoder imageDecoder = is -> mockImage;
        EmoteDownloader.AnimationDecoder emptyDecoder = is -> List.of();
        downloader = new EmoteDownloader(tempDir, httpClient, imageDecoder, emptyDecoder);
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn(new ByteArrayInputStream(new byte[10]), new ByteArrayInputStream(new byte[10]));

        Optional<EmoteAnimation> result =
                downloader.downloadWithFallback("brokengif", true).join();

        assertTrue(result.isPresent());
        assertFalse(result.get().isAnimated());
        assertTrue(Files.exists(tempDir.resolve("brokengif.png")));
    }

    @Test
    void downloadWithFallbackNonAnimatedUsesStaticDirectly() throws Exception {
        Files.write(tempDir.resolve("emote123.png"), new byte[10]);

        Optional<EmoteAnimation> result =
                downloader.downloadWithFallback("emote123", false).join();

        assertTrue(result.isPresent());
        verify(httpClient, never()).send(any(), any());
    }
}
