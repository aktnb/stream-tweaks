package org.etwas.streamtweaks.twitch.badge;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.etwas.streamtweaks.twitch.badge.api.TwitchChatBadgeApi;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.badge.domain.ChannelBadgeCatalog;
import org.etwas.streamtweaks.twitch.badge.domain.ChatBadge;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * チャンネルごとのバッジカタログ（グローバル+チャンネル固有バッジのマージ結果）をキャッシュする。
 *
 * <p>取得はチャンネル接続のクリティカルパスに置かず、常にベストエフォート・非同期で行う。
 * 取得完了前、または取得に失敗した場合は {@link #lookup(UserId, BadgeKey)} が空を返すため、
 * 呼び出し側はバッジなし表示にフォールバックすればよい。
 *
 * <p>一度ロードに成功したチャンネルは再取得しない（{@code connect}/{@code disconnect} を
 * 繰り返してもAPIを叩き直さない）。理由: バッジセットはチャンネル単位でほぼ静的なデータであり、
 * 再接続のたびに同じHTTPコストを払う必要がないため。失敗時はキャッシュに残さず、
 * 次回の {@link #ensureLoaded(UserId)} で再試行できるようにする。
 *
 * <p>全チャンネル分のクリアは {@link #clearAll()}（logout時のみ呼ぶ想定）で行う。
 * 個別チャンネルのdisconnect時には呼ばないこと（再接続時の再取得コストを避けるため）。
 *
 * <p>スレッドセーフ（{@link ConcurrentHashMap} + イミュータブルな {@link CompletableFuture} で構成）。
 */
public class BadgeCatalogRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(BadgeCatalogRepository.class);

    private final TwitchChatBadgeApi api;
    private final Map<String, CompletableFuture<ChannelBadgeCatalog>> cache = new ConcurrentHashMap<>();

    public BadgeCatalogRepository(TwitchChatBadgeApi api) {
        this.api = api;
    }

    /**
     * 指定チャンネルのバッジカタログの読み込みを開始する。既にロード済み・ロード中の場合は何もしない
     * （二重取得防止）。戻り値を持たないのは、呼び出し側（{@code TwitchApplicationService.connect}）が
     * 完全にfire-and-forgetで扱うことを設計上保証するため。
     *
     * <p>実装上の注意: 失敗時にキャッシュから自分自身のエントリを取り除く処理を、
     * {@code ConcurrentHashMap.computeIfAbsent} の再マッピング関数の中で行うと、
     * 依存Futureが（テスト等で）既に完了済みだった場合に完了コールバックが
     * {@code computeIfAbsent} と同一スレッド・同一呼び出しスタック内で同期的に走り、
     * 同じマップへの再入操作とみなされて {@code IllegalStateException("Recursive update")}
     * になる。そのため {@code computeIfAbsent} は使わず、{@code containsKey} による事前チェックと
     * {@code putIfAbsent} を組み合わせ、失敗時のクリーンアップは{@code putIfAbsent}の外側
     * （このメソッドのトップレベル）で行う。
     */
    public void ensureLoaded(UserId broadcasterId) {
        String id = broadcasterId.value();
        if (cache.containsKey(id)) {
            return;
        }

        CompletableFuture<Map<BadgeKey, ChatBadge>> globalFuture = api.getGlobalBadges();
        CompletableFuture<Map<BadgeKey, ChatBadge>> channelFuture = api.getChannelBadges(broadcasterId);
        CompletableFuture<ChannelBadgeCatalog> rawFuture =
                globalFuture.thenCombine(channelFuture, (global, channel) -> {
                    // チャンネル固有バッジ（カスタム画像のsubscriber/bits等）を優先してマージする。
                    Map<BadgeKey, ChatBadge> merged = new HashMap<>(global);
                    merged.putAll(channel);
                    return new ChannelBadgeCatalog(merged);
                });
        // lookup()が例外を踏まないよう、キャッシュに格納するのは正常完了に正規化したFutureにする。
        CompletableFuture<ChannelBadgeCatalog> normalizedFuture = rawFuture.exceptionally(ex -> {
            LOGGER.warn("Failed to load badge catalog for broadcaster {}", id, ex);
            return ChannelBadgeCatalog.empty();
        });

        CompletableFuture<ChannelBadgeCatalog> previous = cache.putIfAbsent(id, normalizedFuture);
        if (previous != null) {
            // 別スレッドが既に読み込みを開始していた（まれな競合）。このメソッドが発行した
            // globalFuture/channelFutureへのリクエストは無駄になるが、二重表示や不整合は生じない。
            return;
        }
        rawFuture.whenComplete((catalog, ex) -> {
            if (ex != null) {
                // 失敗はキャッシュに残さず、次回のensureLoadedで再試行できるようにする。
                // 自分が入れたエントリだけを取り除く（他スレッドが上書き済みなら何もしない）。
                cache.remove(id, normalizedFuture);
            }
        });
    }

    /**
     * 指定チャンネルのバッジを引く。未ロード・ロード中・見つからない場合はすべて
     * {@link Optional#empty()} を返す（呼び出し側で一律「表示しない」フォールバックにできる）。
     */
    public Optional<ChatBadge> lookup(UserId broadcasterId, BadgeKey key) {
        CompletableFuture<ChannelBadgeCatalog> future = cache.get(broadcasterId.value());
        if (future == null || !future.isDone()) {
            return Optional.empty();
        }
        return future.join().lookup(key);
    }

    /**
     * 指定チャンネルのバッジカタログが読み込み完了しているかどうかを返す。
     *
     * <p>{@link #lookup(UserId, BadgeKey)} は「未ロード」と「ロード済みだが見つからない」を
     * どちらも {@link Optional#empty()} として一律に扱うため区別できない。呼び出し側で
     * 「一時的な未ロードなのか、恒久的に存在しないバッジなのか」を診断ログのために
     * 切り分けたい場合に使う。
     */
    public boolean isLoaded(UserId broadcasterId) {
        CompletableFuture<ChannelBadgeCatalog> future = cache.get(broadcasterId.value());
        return future != null && future.isDone();
    }

    /** 全チャンネル分のキャッシュを破棄する。logout時にのみ呼ぶこと。 */
    public void clearAll() {
        cache.clear();
    }
}
