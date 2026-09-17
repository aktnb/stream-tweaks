package org.etwas.streamtweaks.presentation.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.etwas.streamtweaks.platform.PlatformId;

/**
 * プラットフォームごとのチャットメッセージ先頭プレフィックス（例: "[Twitch]"）を組み立てる。
 *
 * <p>見た目は従来の {@code ChatMessagePresenter}（現 {@code TwitchChatMessagePresenter}）が
 * 組み立てていたものと変わらないよう、
 * 翻訳キー・色をそのまま踏襲する。
 */
public final class PlatformChatStyles {

    private PlatformChatStyles() {
        throw new AssertionError("Cannot instantiate PlatformChatStyles");
    }

    public static Component prefixFor(PlatformId platform) {
        return switch (platform) {
            case TWITCH ->
                Component.translatable("message.stream-tweaks.chatMessagePrefix")
                        .withStyle(ChatFormatting.LIGHT_PURPLE);
        };
    }
}
