package org.etwas.streamtweaks.presentation.mixin;

import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * チャット欄の表示行を完全に取り除くためのアクセサ。
 *
 * <p>バニラの {@code ChatComponent#deleteMessage(MessageSignature)} は該当行を削除せず
 * 「削除されました」マーカーへ置換する（かつ追加から60tick未満なら遅延させる）ため、
 * 「行を完全に非表示にする」という要件を満たせない。そのため {@code allMessages} を直接操作する。
 *
 * <p>再構築に公開APIの {@code rescaleChat()} を使うと {@code resetChatScroll()} が伴い、
 * ユーザーのスクロール位置が飛ぶため、{@code refreshTrimmedMessages()} を直接呼ぶ。
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("allMessages")
    List<GuiMessage> streamTweaks$getAllMessages();

    @Invoker("refreshTrimmedMessages")
    void streamTweaks$refreshTrimmedMessages();
}
