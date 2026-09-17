package org.etwas.streamtweaks.twitch.badge.domain;

/**
 * 1バッジ分の表示情報。
 *
 * @param key このバッジの複合キー
 * @param imageUrl バッジ画像のURL（Twitch Helix APIレスポンスに含まれる {@code image_url_4x}）
 */
public record ChatBadge(BadgeKey key, String imageUrl) {}
