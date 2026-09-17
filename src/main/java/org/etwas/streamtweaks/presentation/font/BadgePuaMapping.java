package org.etwas.streamtweaks.presentation.font;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.etwas.streamtweaks.twitch.badge.domain.BadgeKey;
import org.etwas.streamtweaks.twitch.core.UserId;

/**
 * (チャンネル, バッジの複合キー（{@link BadgeKey}）) → Unicode私用領域(PUA)コードポイントのマッピング。
 *
 * <p>絵文字用の {@link PuaMapping} とはPUAレンジを分けて {@code U+E000}〜{@code U+EFFF} を使う
 * （絵文字は {@code U+F000}〜{@code U+F8FF}）。{@link WellKnownPuaCodePoints} に含まれる
 * コードポイントはリソースパックとの競合を避けるため採番時にスキップする。
 * バッジは種類数が少なく長期間不変なため、
 * {@link PuaMapping} のLRU evict戦略とは異なり、一度割り当てたコードポイントは
 * インスタンスが破棄されるまで保持し続ける（evictしない）。
 *
 * <p><b>チャンネル(broadcasterId)をキーに含める理由:</b> {@code set_id}+バージョンIDが同じでも、
 * サブスクライバーバッジ等のチャンネル固有バッジは画像そのものがチャンネルごとに異なりうる。
 * broadcasterIdを含めずに {@link BadgeKey} だけをキーにすると、チャンネルAで
 * {@code (subscriber, "3")} の画像を先に登録した後、別のチャンネルBで同じ
 * {@code (subscriber, "3")} だが実際には異なる画像のバッジが来た場合に、既存のコードポイント・
 * グリフをそのまま使い回してしまい、Bの視聴者にAの画像が誤表示される。
 * グローバルバッジ（moderator/vip/turbo等、画像がチャンネル間で共通）についても一律で
 * broadcasterIdを含めるのは無駄なPUA消費に見えるが、バッジの延べ種類数は少なく
 * レンジ（4096件、{@link WellKnownPuaCodePoints} によるスキップ分を除いても実用上十分な数）に
 * 対して現実的な同時接続チャンネル数では枯渇しないため、
 * 「グローバル/チャンネル固有を区別する」より実装がシンプルで確実なこちらを採用する。
 *
 * <p>レンジ（4096件）を使い切った場合は例外を投げず {@link Optional#empty()} を返す。
 * 呼び出し側はこれを「バッジが見つからない」場合と同様に扱い、非表示にフォールバックすればよい。
 *
 * <p>Minecraft メインスレッドからの単一スレッドアクセスを前提とする（スレッドセーフではない）。
 */
public class BadgePuaMapping {

    private static final int BASE_CODE_POINT = 0xE000;
    private static final int MAX_CODE_POINT = 0xEFFF;

    private final Map<ChannelBadgeKey, Integer> assignments = new HashMap<>();
    private int nextCodePoint = BASE_CODE_POINT;

    /**
     * 指定したチャンネル・バッジキーに対応するPUAコードポイントを返す。未登録の場合は新しく割り当てる。
     * レンジが枯渇している場合は {@link Optional#empty()} を返す。
     */
    public Optional<Integer> getOrAssign(UserId broadcasterId, BadgeKey key) {
        ChannelBadgeKey compositeKey = compositeKey(broadcasterId, key);
        Integer existing = assignments.get(compositeKey);
        if (existing != null) {
            return Optional.of(existing);
        }
        while (nextCodePoint <= MAX_CODE_POINT && WellKnownPuaCodePoints.CODE_POINTS.contains(nextCodePoint)) {
            nextCodePoint++;
        }
        if (nextCodePoint > MAX_CODE_POINT) {
            return Optional.empty();
        }
        int codePoint = nextCodePoint++;
        assignments.put(compositeKey, codePoint);
        return Optional.of(codePoint);
    }

    /** 指定したチャンネル・バッジキーが既にコードポイントを割り当て済みかどうかを返す。 */
    public boolean isAssigned(UserId broadcasterId, BadgeKey key) {
        return assignments.containsKey(compositeKey(broadcasterId, key));
    }

    private static ChannelBadgeKey compositeKey(UserId broadcasterId, BadgeKey key) {
        Objects.requireNonNull(broadcasterId, "broadcasterId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        return new ChannelBadgeKey(broadcasterId, key);
    }

    private record ChannelBadgeKey(UserId broadcasterId, BadgeKey badgeKey) {}
}
