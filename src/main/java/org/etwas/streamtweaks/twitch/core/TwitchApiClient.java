package org.etwas.streamtweaks.twitch.core;

import java.util.concurrent.CompletableFuture;

/**
 * Twitch Helix APIとのやり取りを抽象化するインターフェース
 * EventSubに限らない汎用的なAPIアクセスを提供する
 */
public interface TwitchApiClient {
    /**
     * ログイン名からユーザーIDを取得する
     *
     * @param login ユーザーのログイン名
     * @return ユーザーID
     */
    CompletableFuture<UserId> getUserId(Login login);
}
