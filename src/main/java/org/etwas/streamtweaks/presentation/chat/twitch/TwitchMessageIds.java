package org.etwas.streamtweaks.presentation.chat.twitch;

/**
 * Twitchの {@code message_id} に対する簡易バリデーション。
 *
 * <p>{@code message_id} は外部（Twitch）から届く値であり、そのままログ出力やMapのキーに使う前に
 * 素性を確認する。TwitchはUUID形式を返すが、形式を厳密に固定すると仕様変更で機能が停止するため、
 * 「長さの上限」と「安全な文字集合（制御文字を含まない）」のみを検査する緩めの方針とする。
 */
public final class TwitchMessageIds {

    /** 実際のTwitch message_id はUUID（36文字）。余裕を見た上限。 */
    static final int MAX_LENGTH = 128;

    private TwitchMessageIds() {
        throw new AssertionError("Cannot instantiate TwitchMessageIds");
    }

    /**
     * 追跡・削除対象として受け入れてよい {@code message_id} かどうかを判定する。
     *
     * @param messageId Twitchから届いた message_id（null可）
     * @return null・空・長すぎる・許可外の文字を含む場合は false
     */
    public static boolean isValid(String messageId) {
        if (messageId == null || messageId.isEmpty() || messageId.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < messageId.length(); i++) {
            if (!isAllowedCharacter(messageId.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowedCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }
}
