package org.etwas.streamtweaks.twitch.core;

/**
 * Twitch APIに関連する共通定数
 */
public final class TwitchConstants {
    /**
     * Twitch アプリケーションのクライアントID
     */
    public static final String CLIENT_ID = "p5xrtcp49if1zj6b86y356htualkth";

    /**
     * Twitch OAuth2 リダイレクトURI
     */
    public static final String REDIRECT_URI = "http://localhost:7654/callback";

    /**
     * Twitch OAuth2 認証に必要なスコープ
     */
    public static final String[] REQUIRED_SCOPES = {"user:read:chat"};

    private TwitchConstants() {
        // ユーティリティクラスのためインスタンス化を防止
        throw new AssertionError("Cannot instantiate TwitchConstants");
    }
}
