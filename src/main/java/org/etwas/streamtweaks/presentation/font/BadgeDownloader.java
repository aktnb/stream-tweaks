package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitch Helix APIのレスポンスに含まれる画像URLからバッジ画像を非同期ダウンロードし
 * ファイルキャッシュに保存する。
 *
 * <p>{@link EmoteDownloader} との違い: 絵文字はIDから固定のCDN URLパターンを組み立てられるが、
 * バッジ画像のURLはAPIレスポンスにそのまま含まれる値を使うしかない。そのため、EmoteDownloader
 * では不要だった「レスポンス由来のURLをそのまま外部HTTPリクエストに使ってよいか」という
 * 検証（スキーム・ホストの完全一致許可リスト）が必須になる。
 *
 * <p>セキュリティ上の方針:
 * <ul>
 *   <li>URLは {@code https} かつホストが許可リストと完全一致する場合のみ許可する
 *       （{@code contains}/{@code endsWith} 等の部分一致は
 *       {@code static-cdn.jtvnw.net.evil.com} のようなバイパスを許すため使わない）</li>
 *   <li>{@code broadcasterId}/{@code set_id}/バージョンIDはキャッシュファイル名に使う前に
 *       文字集合を検証し、さらに正規化後のパスがキャッシュディレクトリ配下に留まることも
 *       二重に検証する（{@link EmoteDownloader} と同じ多層防御）</li>
 *   <li>キャッシュファイル名に{@code broadcasterId}を含める。同じ{@code set_id}/バージョンID
 *       でもチャンネル固有バッジ（サブスクライバーバッジ等）は画像がチャンネルごとに異なりうるため、
 *       broadcasterIdを含めないと別チャンネルの画像をキャッシュヒットで誤って使い回してしまう</li>
 *   <li>実際にダウンロードしたバイト数に上限を設ける（{@code Content-Length} 詐称に依存しない）</li>
 *   <li>リダイレクトを追わない（{@link HttpClient.Redirect#NEVER} を明示指定）</li>
 *   <li>Twitch Helix APIの認証情報（Authorization/Client-Id）は一切付与しない。
 *       CDNへの資格情報漏洩を避けるため、Helix API呼び出し用のHTTPクライアント設定とは
 *       完全に分離する</li>
 * </ul>
 *
 * <p>MinecraftClient には依存しない。全ダウンロード処理はワーカースレッドで実行される。
 */
public class BadgeDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(BadgeDownloader.class);
    private static final Pattern VALID_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Set<String> ALLOWED_HOSTS = Set.of("static-cdn.jtvnw.net");
    private static final int MAX_SIZE_BYTES = 512 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface ImageDecoder {
        NativeImage decode(InputStream is) throws IOException;
    }

    private final Path cacheDir;
    private final HttpClient httpClient;
    private final ImageDecoder imageDecoder;

    public BadgeDownloader(Path cacheDir) {
        this(
                cacheDir,
                HttpClient.newBuilder()
                        // レスポンスがリダイレクトを返してきても追わない。将来HttpClientの
                        // デフォルト挙動が変わってもSSRF対策が暗黙に無効化されないよう明示する。
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(REQUEST_TIMEOUT)
                        .build(),
                NativeImage::read);
    }

    BadgeDownloader(Path cacheDir, HttpClient httpClient, ImageDecoder imageDecoder) {
        this.cacheDir = cacheDir;
        this.httpClient = httpClient;
        this.imageDecoder = imageDecoder;
    }

    /**
     * バッジ画像をダウンロードする。{@code imageUrl} はTwitch Helix APIレスポンス由来の値。
     *
     * @param broadcasterId このバッジを表示するチャンネルのID（キャッシュファイル名の組み立てに使う）
     * @param key バッジの複合キー（キャッシュファイル名の組み立てに使う）
     * @param imageUrl ダウンロード元URL
     */
    public CompletableFuture<Optional<NativeImage>> download(UserId broadcasterId, BadgeKey key, String imageUrl) {
        if (!isValidId(broadcasterId.value()) || !isValidId(key.setId()) || !isValidId(key.versionId())) {
            LOGGER.warn("Invalid badge key rejected: broadcaster={}, key={}", broadcasterId, key);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        Optional<URI> validatedUri = validateUrl(imageUrl);
        if (validatedUri.isEmpty()) {
            LOGGER.warn("Rejected badge image URL for broadcaster={}, key={}: {}", broadcasterId, key, imageUrl);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        Path cachePath = cacheDir.resolve(cacheFileName(broadcasterId, key)).normalize();
        if (!cachePath.startsWith(cacheDir.normalize())) {
            LOGGER.error("Path traversal detected for badge: broadcaster={}, key={}", broadcasterId, key);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.exists(cachePath)) {
                    try (InputStream is = Files.newInputStream(cachePath)) {
                        return Optional.of(imageDecoder.decode(is));
                    }
                }
                return fetchAndCache(broadcasterId, key, validatedUri.get(), cachePath);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Badge download interrupted: broadcaster={}, key={}", broadcasterId, key);
                return Optional.<NativeImage>empty();
            } catch (Exception e) {
                LOGGER.error("Failed to load badge: broadcaster={}, key={}", broadcasterId, key, e);
                return Optional.<NativeImage>empty();
            }
        });
    }

    private static boolean isValidId(String value) {
        return value != null && VALID_ID.matcher(value).matches();
    }

    /**
     * キャッシュファイル名を組み立てる。{@code broadcasterId} を含めるのは、同じ
     * {@code set_id}/バージョンIDでもチャンネル固有バッジは画像がチャンネルごとに異なりうるため
     * （クラスJavadoc参照）。
     */
    private static String cacheFileName(UserId broadcasterId, BadgeKey key) {
        return broadcasterId.value() + "_" + key.setId() + "_" + key.versionId() + ".png";
    }

    /**
     * URLが {@code https} かつホストが許可リストと完全一致する場合のみ許可する。
     * 部分一致（{@code contains}/{@code endsWith}）は使わない
     * （{@code static-cdn.jtvnw.net.evil.com} のようなバイパスを防ぐため）。
     */
    private static Optional<URI> validateUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(imageUrl);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!"https".equals(uri.getScheme())) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host)) {
            return Optional.empty();
        }
        return Optional.of(uri);
    }

    private Optional<NativeImage> fetchAndCache(UserId broadcasterId, BadgeKey key, URI uri, Path cachePath)
            throws IOException, InterruptedException {
        // Helix API呼び出し用のHTTPクライアント（Authorization/Client-Idヘッダ付き）とは
        // 完全に別のインスタンスであり、このリクエストには認証ヘッダを一切付与しない。
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            LOGGER.warn(
                    "Failed to download badge broadcaster={}, key={}: HTTP {}",
                    broadcasterId,
                    key,
                    response.statusCode());
            return Optional.empty();
        }

        byte[] bytes;
        try (InputStream body = response.body()) {
            bytes = body.readNBytes(MAX_SIZE_BYTES + 1);
        }

        if (bytes.length > MAX_SIZE_BYTES) {
            LOGGER.warn("Badge broadcaster={}, key={} exceeds size limit: {} bytes", broadcasterId, key, bytes.length);
            return Optional.empty();
        }

        Files.createDirectories(cacheDir);
        writeAtomic(cachePath, bytes);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            return Optional.of(imageDecoder.decode(is));
        }
    }

    private void writeAtomic(Path cachePath, byte[] bytes) throws IOException {
        Path tmpPath = Files.createTempFile(cacheDir, "badge", ".tmp");
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
