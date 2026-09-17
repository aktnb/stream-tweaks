package org.etwas.streamtweaks.presentation.chat;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.etwas.streamtweaks.platform.PlatformId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 複数プラットフォームのチャットメッセージをMinecraftのチャット欄へ統合表示する集約点。
 *
 * <p>各プラットフォーム固有のPresenter（{@code TwitchChatMessagePresenter} 等）が、
 * プラットフォーム固有の変換（絵文字解決・ユーザー名色決定等）を終えた {@link NormalizedChatMessage}
 * をここへ渡す。ここでプレフィックス付与・チャット送信・削除索引への登録までを行う。
 *
 * <p>削除は {@link #presentDelete(PlatformId, String, String)} で扱う。{@code channelId} による
 * 多層防御（別チャンネルの通知が同じ message_id を主張してきた場合の拒否）もここで行う。
 */
public final class IntegratedChatMessagePresenter {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntegratedChatMessagePresenter.class);

    private final DisplayedChatMessageIndex displayedIndex;
    private final ChatRowRemover rowRemover;

    public IntegratedChatMessagePresenter(DisplayedChatMessageIndex displayedIndex, ChatRowRemover rowRemover) {
        this.displayedIndex = displayedIndex;
        this.rowRemover = rowRemover;
    }

    /**
     * メッセージをチャット欄へ表示する。
     *
     * <p>メインスレッドから呼ぶこと。呼び出し側で絵文字解決等の変換が完了している前提。
     */
    public void present(NormalizedChatMessage message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        Component composed = compose(message);
        trackForDeletion(composed, message);
        client.player.sendSystemMessage(composed);
    }

    /**
     * プレフィックス・投稿者名・本文からチャット表示用のComponentを組み立てる。
     *
     * <p>Minecraftインスタンスに依存しないため、テストから直接検証できるようパッケージプライベートにしている。
     */
    // 削除時にタグを読み出せるよう、静的型は Component（インターフェース）にしておく。
    Component compose(NormalizedChatMessage message) {
        MutableComponent composed = Component.empty()
                .append(PlatformChatStyles.prefixFor(message.platform()))
                .append(Component.literal(" "));
        List<Component> badges = message.badges();
        if (!badges.isEmpty()) {
            // バッジは届いた順（Twitchのbadges配列の順序）どおりに並べる。
            for (Component badge : badges) {
                composed.append(badge);
            }
            composed.append(Component.literal(" "));
        }
        return composed
                .append(message.authorDisplay())
                .append(Component.literal(": "))
                .append(message.content());
    }

    /**
     * 表示するメッセージにキーをタグ付けし、削除通知に備えて索引へ登録する。
     *
     * <p>Mixinが適用されずタグ付けできなかった場合でも索引には登録する。
     * そうすることで削除時に行が見つからないことを {@link ChatRowRemover} が検知しWARNを出せる。
     *
     * <p>Minecraftインスタンスに依存しないため、テストから直接検証できるようpublicにしている
     * （プラットフォーム固有Presenterの統合テストからも呼ばれる）。
     */
    public void trackForDeletion(Component composed, NormalizedChatMessage message) {
        String messageId = message.messageId();
        if (messageId == null || messageId.isBlank()) {
            // messageIdの妥当性検証はプラットフォーム固有Presenter側の責務。ここでは
            // null/空はそもそも追跡できない不正値として弾くだけに留める。
            LOGGER.debug("Chat message has no message id and will not be tracked for deletion");
            return;
        }
        if (message.channelId() == null || message.channelId().isBlank()) {
            // チャンネルIDがないと削除通知との照合ができないため、追跡しても消せない。
            LOGGER.debug("Chat message has no channel id and will not be tracked for deletion");
            return;
        }
        DisplayedMessageKey key = new DisplayedMessageKey(message.platform(), messageId);
        if (composed instanceof StreamTweaksTaggedComponent tagged) {
            tagged.streamTweaks$setMessageKey(key);
        }
        displayedIndex.remember(key, new DisplayedChatMessage(message.channelId(), message.authorId()));
    }

    /**
     * 削除通知を処理する。{@code platform} 引数は必ず呼び出し元（プラットフォーム固有Presenter）が
     * 自身の識別子として固定的に渡すこと。通知ペイロードの中身から読み取った値を渡してはならない
     * （なりすまし防止の前提条件）。
     *
     * <p>メインスレッドから呼ぶこと。
     */
    public void presentDelete(PlatformId platform, String messageId, String channelId) {
        DisplayedMessageKey key = new DisplayedMessageKey(platform, messageId);
        // 検証を通るまで索引からは取り除かない。不正な通知でエントリを消費してしまうと、
        // 後から届く正当な削除通知が効かなくなるため。
        var displayed = displayedIndex.find(key);
        if (displayed.isEmpty()) {
            // 追跡上限を超えて溢れた古いメッセージ。要件どおり無視する（異常ではない）。
            LOGGER.debug("Chat message delete notification for an untracked message");
            return;
        }
        if (!channelId.equals(displayed.get().channelId())) {
            // 別チャンネルの通知が同じキーを主張してきたケース。念のため適用しない。
            LOGGER.warn("Ignored chat message delete notification from a mismatched channel");
            return;
        }
        displayedIndex.forget(key);
        rowRemover.enqueue(key);
    }
}
