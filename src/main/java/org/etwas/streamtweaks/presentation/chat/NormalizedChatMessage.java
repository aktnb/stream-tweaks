package org.etwas.streamtweaks.presentation.chat;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.etwas.streamtweaks.platform.PlatformId;

/**
 * プラットフォーム固有の表現から変換済みの、チャット統合表示用のメッセージ。
 *
 * <p>各プラットフォーム固有のPresenter（{@code TwitchChatMessagePresenter} 等）が、
 * プラットフォーム固有のペイロードから絵文字解決・ユーザー名スタイル決定までを終えた上で
 * この形に変換し、{@link IntegratedChatMessagePresenter#present(NormalizedChatMessage)} に渡す。
 *
 * @param platform このメッセージの送信元プラットフォーム
 * @param messageId プラットフォーム内で一意なメッセージID（削除通知との照合に使う）
 * @param channelId メッセージが投稿されたチャンネル・配信のID
 * @param authorId 投稿者のプラットフォーム内ユーザーID
 * @param authorDisplay 投稿者名として表示するComponent（色付け等の装飾込み）
 * @param content メッセージ本文として表示するComponent（絵文字解決込み）
 * @param badges 投稿者が装着しているバッジを表示順に並べたComponentのリスト（画像アイコン1個1個）。
 *     {@code authorDisplay} に事前合成せず専用フィールドにしているのは、投稿者名表示と
 *     バッジ表示の責務を分離し、{@link IntegratedChatMessagePresenter#compose} 側で
 *     挿入位置やレイアウトを制御できるようにするため。空リストは「バッジなし」を表す
 *     （未解決・未知のバッジは呼び出し側で個別にスキップ済みの前提）。
 */
public record NormalizedChatMessage(
        PlatformId platform,
        String messageId,
        String channelId,
        String authorId,
        Component authorDisplay,
        Component content,
        List<Component> badges) {

    public NormalizedChatMessage {
        badges = badges == null ? List.of() : List.copyOf(badges);
    }

    /**
     * バッジ情報を持たないプラットフォーム・呼び出し元向けの補助コンストラクタ。
     * {@code badges} は空リストになる。
     */
    public NormalizedChatMessage(
            PlatformId platform,
            String messageId,
            String channelId,
            String authorId,
            Component authorDisplay,
            Component content) {
        this(platform, messageId, channelId, authorId, authorDisplay, content, List.of());
    }
}
