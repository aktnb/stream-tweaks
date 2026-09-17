package org.etwas.streamtweaks.presentation.mixin;

import net.minecraft.network.chat.MutableComponent;
import org.etwas.streamtweaks.presentation.chat.DisplayedMessageKey;
import org.etwas.streamtweaks.presentation.chat.StreamTweaksTaggedComponent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * このMODがチャット欄へ流したメッセージに {@code DisplayedMessageKey}（プラットフォーム識別子 +
 * メッセージID）を持たせる。
 *
 * <p>{@code GuiMessage} はrecordであり、JVM仕様上recordクラスへのインスタンスフィールド追加は
 * 安全とは言えないため、{@code GuiMessage.content()} に格納される {@code MutableComponent} 側に
 * タグを持たせている。MOD以外が生成したComponentではフィールドは null のままである。
 *
 * <p><strong>保守上の注意</strong>: このMixinはゲーム中のすべての {@code MutableComponent}
 * （ツールチップ・GUIラベル等を含む）に参照フィールドを1つ追加する。インスタンス数は多いが
 * 追加コストは1参照分であり実用上は無視できる。フィールド追加のみでメソッドへのインジェクションを
 * 伴わないため、Minecraft更新時に壊れにくく、他MODのMixinとも衝突しない
 * （{@code @Unique} によりフィールド名は自動的に一意化される）。
 */
@Mixin(MutableComponent.class)
public abstract class MutableComponentMixin implements StreamTweaksTaggedComponent {

    @Unique
    @Nullable
    private DisplayedMessageKey streamTweaks$messageKey;

    @Override
    @Nullable
    public DisplayedMessageKey streamTweaks$getMessageKey() {
        return this.streamTweaks$messageKey;
    }

    @Override
    public void streamTweaks$setMessageKey(@Nullable DisplayedMessageKey key) {
        this.streamTweaks$messageKey = key;
    }
}
