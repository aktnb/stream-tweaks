package org.etwas.streamtweaks.presentation.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.etwas.streamtweaks.presentation.mixin.ChatComponentAccessor;

/**
 * Minecraftのチャット欄から、{@link DisplayedMessageKey} でタグ付けされた表示行を取り除く。
 *
 * <p>Minecraftメインスレッド専用（{@link ChatRowRemover} 経由で呼ばれる）。
 */
public final class MinecraftChatRows {

    private MinecraftChatRows() {
        throw new AssertionError("Cannot instantiate MinecraftChatRows");
    }

    /**
     * 指定されたキーを持つ行をチャット欄からすべて取り除く。
     *
     * @return 実際に取り除けたキーの集合
     */
    public static Set<DisplayedMessageKey> remove(Set<DisplayedMessageKey> keys) {
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = getChat(client);
        if (chat == null) {
            return Set.of();
        }

        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        Set<DisplayedMessageKey> removed = removeTaggedRows(accessor.streamTweaks$getAllMessages(), keys);

        if (!removed.isEmpty()) {
            accessor.streamTweaks$refreshTrimmedMessages();
            // 行が減ったぶんスクロール位置が範囲外になりうる。scrollChat(0) はバニラ自身の
            // クランプ処理（上限は行数-1ページ、下限は0）だけを走らせるので、
            // ユーザーのスクロール位置を保ったまま有効範囲に収められる。
            chat.scrollChat(0);
        }
        return removed;
    }

    /**
     * チャット行を再分割し、動的に追加されたグリフの遅延bakeをクライアントスレッド上で先に済ませる。
     */
    public static void refreshTrimmedMessages() {
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = getChat(client);
        if (chat == null) {
            return;
        }

        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        accessor.streamTweaks$refreshTrimmedMessages();
    }

    private static ChatComponent getChat(Minecraft client) {
        if (client == null || client.gui == null || client.gui.hud == null) {
            return null;
        }
        return client.gui.hud.getChat();
    }

    /**
     * 表示行のリストから、指定されたキーを持つ行をすべて取り除く。
     *
     * <p>Minecraftの状態に触れない部分を切り出したもの。渡されたリストを直接変更する。
     * 同一キーの行が複数ある場合（at-least-once配信による重複表示）は
     * そのすべてを取り除く。
     *
     * @param allMessages チャット欄の表示行（変更される）
     * @param keys 取り除く対象のキー
     * @return 実際に取り除けたキーの集合
     */
    public static Set<DisplayedMessageKey> removeTaggedRows(
            List<GuiMessage> allMessages, Set<DisplayedMessageKey> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        Set<DisplayedMessageKey> removed = new HashSet<>();
        allMessages.removeIf(guiMessage -> {
            DisplayedMessageKey key = messageKeyOf(guiMessage.content());
            if (key != null && keys.contains(key)) {
                removed.add(key);
                return true;
            }
            return false;
        });
        return removed;
    }

    /**
     * Componentに付与された {@link DisplayedMessageKey} を返す。MOD以外が流したメッセージでは null。
     */
    private static DisplayedMessageKey messageKeyOf(Component content) {
        return content instanceof StreamTweaksTaggedComponent tagged ? tagged.streamTweaks$getMessageKey() : null;
    }
}
