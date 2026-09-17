package org.etwas.streamtweaks.twitch.badge.domain;

import java.util.Map;
import java.util.Optional;

/**
 * 1チャンネル分の、グローバルバッジとチャンネル固有バッジをマージ済みのルックアップ。
 *
 * <p>同一の {@link BadgeKey} がグローバル・チャンネル固有の両方に存在する場合は、
 * マージ時にチャンネル固有側を優先する（呼び出し側の {@code BadgeCatalogRepository} が保証する）。
 * イミュータブルなので複数チャンネル・複数スレッドから安全に共有参照できる。
 */
public final class ChannelBadgeCatalog {

    private static final ChannelBadgeCatalog EMPTY = new ChannelBadgeCatalog(Map.of());

    private final Map<BadgeKey, ChatBadge> badges;

    public ChannelBadgeCatalog(Map<BadgeKey, ChatBadge> badges) {
        this.badges = Map.copyOf(badges);
    }

    public static ChannelBadgeCatalog empty() {
        return EMPTY;
    }

    /**
     * 指定したキーのバッジを引く。未知のset_id・廃止されたバージョン等、見つからない場合は
     * {@link Optional#empty()} を返す（呼び出し側でそのまま「表示しない」フォールバックにできる）。
     */
    public Optional<ChatBadge> lookup(BadgeKey key) {
        return Optional.ofNullable(badges.get(key));
    }

    public int size() {
        return badges.size();
    }
}
