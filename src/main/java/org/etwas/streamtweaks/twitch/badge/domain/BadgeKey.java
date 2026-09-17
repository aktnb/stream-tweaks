package org.etwas.streamtweaks.twitch.badge.domain;

/**
 * Twitchチャットバッジの複合キー。
 *
 * <p>Twitchのバッジは {@code set_id}（例: "moderator", "subscriber"）と、セット内の
 * バージョンID（例: サブスクライバーバッジの継続月数tier）の組み合わせで一意に決まる。
 * 単一の文字列IDでは表現できないため、絵文字用の {@code String} キーとは別に複合キー型を用意する。
 *
 * @param setId バッジセットのID
 * @param versionId セット内のバージョンID
 */
public record BadgeKey(String setId, String versionId) {}
