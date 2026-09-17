package org.etwas.streamtweaks.presentation.chat;

import org.jetbrains.annotations.Nullable;

/**
 * このMODがチャット欄へ流した {@code Component} に、{@link DisplayedMessageKey} を直接持たせるための
 * ダックインターフェース（Mixinで {@code MutableComponent} に実装される）。
 *
 * <p>削除時に「どの表示行がどのメッセージか」を特定する手段として、
 * {@code Component} インスタンスの参照等価に依存しない。参照等価はMinecraft側の受け渡し経路が
 * 変わった瞬間に無言で機能停止するため、値としてタグを持たせる方式を採る。
 * それでも一致が取れなかった場合は {@link ChatRowRemover} がWARNログを出して検知できるようにしている。
 *
 * <p>キーにプラットフォーム識別子（{@link org.etwas.streamtweaks.platform.PlatformId}）を含む
 * {@link DisplayedMessageKey} を保持することで、複数プラットフォームを同時に扱っても
 * メッセージIDの衝突による誤削除が起きないようにしている。
 */
public interface StreamTweaksTaggedComponent {

    /**
     * このComponentに紐づく {@link DisplayedMessageKey}。MOD以外が生成したComponentでは null。
     */
    @Nullable
    DisplayedMessageKey streamTweaks$getMessageKey();

    void streamTweaks$setMessageKey(@Nullable DisplayedMessageKey key);
}
