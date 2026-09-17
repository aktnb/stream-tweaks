package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitch CDN からエモート画像を非同期ダウンロードしファイルキャッシュに保存する。
 *
 * <p>MinecraftClient には依存しない。全ダウンロード処理はワーカースレッドで実行される。
 * キャッシュは静止画を cacheDir/{emoteId}.png、アニメーションを cacheDir/{emoteId}.gif として保存し、
 * 次回以降はファイルから読み込む。拡張子を分けることで static/animated 切替時のフォーマット誤読を防ぐ。
 */
public class EmoteDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmoteDownloader.class);
    private static final Pattern VALID_EMOTE_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final String CDN_URL_STATIC = "https://static-cdn.jtvnw.net/emoticons/v2/%s/static/dark/1.0";
    private static final String CDN_URL_ANIMATED = "https://static-cdn.jtvnw.net/emoticons/v2/%s/animated/dark/2.0";
    private static final int MAX_SIZE_BYTES = 512 * 1024;
    // CDNが応答しない場合、download()はCompletableFuture.supplyAsync（ForkJoinPool.commonPool）上で
    // httpClient.send()により同期ブロックする。タイムアウトが無いと、複数のエモートが同時にハングした際に
    // commonPoolのワーカースレッドを占有し尽くし、このMod以外を含むJVM全体のsupplyAsync呼び出しが
    // 巻き添えで停止しうる。BadgeDownloaderと同じ方針でタイムアウトを明示する。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface ImageDecoder {
        NativeImage decode(InputStream is) throws IOException;
    }

    @FunctionalInterface
    public interface AnimationDecoder {
        List<EmoteFrame> decode(InputStream is) throws IOException;
    }

    private final Path cacheDir;
    private final HttpClient httpClient;
    private final ImageDecoder imageDecoder;
    private final AnimationDecoder animationDecoder;

    public EmoteDownloader(Path cacheDir) {
        this(
                cacheDir,
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
                NativeImage::read,
                GifFrameDecoder::decode);
    }

    EmoteDownloader(
            Path cacheDir, HttpClient httpClient, ImageDecoder imageDecoder, AnimationDecoder animationDecoder) {
        this.cacheDir = cacheDir;
        this.httpClient = httpClient;
        this.imageDecoder = imageDecoder;
        this.animationDecoder = animationDecoder;
    }

    /** 静止画としてダウンロードする。 */
    public CompletableFuture<Optional<EmoteAnimation>> download(String emoteId) {
        return download(emoteId, false);
    }

    /**
     * emote をダウンロードする。animated=true の場合は GIF アニメーションとして取得を試みる。
     */
    public CompletableFuture<Optional<EmoteAnimation>> download(String emoteId, boolean animated) {
        if (!VALID_EMOTE_ID.matcher(emoteId).matches()) {
            LOGGER.warn("Invalid emote ID rejected: {}", emoteId);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        Path cachePath = cacheDir.resolve(emoteId + cacheExtension(animated)).normalize();
        if (!cachePath.startsWith(cacheDir.normalize())) {
            LOGGER.error("Path traversal detected: {}", emoteId);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.exists(cachePath)) {
                    try (InputStream is = Files.newInputStream(cachePath)) {
                        return decode(is, animated);
                    }
                }
                return fetchAndCache(emoteId, cachePath, animated);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Emote download interrupted: {}", emoteId);
                return Optional.<EmoteAnimation>empty();
            } catch (Exception e) {
                LOGGER.error("Failed to load emote: {}", emoteId, e);
                return Optional.<EmoteAnimation>empty();
            }
        });
    }

    /**
     * animated=true でのダウンロード・デコードに失敗した場合、静止画に自動フォールバックする。
     */
    public CompletableFuture<Optional<EmoteAnimation>> downloadWithFallback(String emoteId, boolean animated) {
        if (!animated) {
            return download(emoteId, false);
        }
        return download(emoteId, true)
                .thenCompose(result ->
                        result.isPresent() ? CompletableFuture.completedFuture(result) : download(emoteId, false));
    }

    private static String cacheExtension(boolean animated) {
        return animated ? ".gif" : ".png";
    }

    private Optional<EmoteAnimation> decode(InputStream is, boolean animated) throws IOException {
        if (!animated) {
            return Optional.of(EmoteAnimation.ofStatic(imageDecoder.decode(is)));
        }
        List<EmoteFrame> frames = animationDecoder.decode(is);
        if (frames.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EmoteAnimation(frames));
    }

    private Optional<EmoteAnimation> fetchAndCache(String emoteId, Path cachePath, boolean animated)
            throws IOException, InterruptedException {
        String url = animated ? CDN_URL_ANIMATED : CDN_URL_STATIC;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.formatted(emoteId)))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            LOGGER.warn("Failed to download emote {}: HTTP {}", emoteId, response.statusCode());
            return Optional.empty();
        }

        byte[] bytes;
        try (InputStream body = response.body()) {
            bytes = body.readNBytes(MAX_SIZE_BYTES + 1);
        }

        if (bytes.length > MAX_SIZE_BYTES) {
            LOGGER.warn("Emote {} exceeds size limit: {} bytes", emoteId, bytes.length);
            return Optional.empty();
        }

        Files.createDirectories(cacheDir);
        writeAtomic(cachePath, emoteId, bytes);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            return decode(is, animated);
        }
    }

    private void writeAtomic(Path cachePath, String emoteId, byte[] bytes) throws IOException {
        Path tmpPath = Files.createTempFile(cacheDir, emoteId, ".tmp");
        try {
            Files.write(tmpPath, bytes);
            try {
                Files.move(tmpPath, cachePath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmpPath);
            throw e;
        }
    }
}
