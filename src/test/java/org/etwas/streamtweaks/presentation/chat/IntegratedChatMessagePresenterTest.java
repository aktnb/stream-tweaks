package org.etwas.streamtweaks.presentation.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import org.etwas.streamtweaks.platform.PlatformId;
import org.junit.jupiter.api.Test;

/**
 * {@link IntegratedChatMessagePresenter} のうち、Minecraftインスタンスに依存せず
 * 単体テストできる部分（Component組み立て・削除追跡登録・削除実行）を検証する。
 *
 * <p>{@link IntegratedChatMessagePresenter#present(NormalizedChatMessage)} 自体は
 * {@code Minecraft.getInstance()} に依存しヘッドレス環境では実行できないため、
 * プレフィックス付与の確認はComponent組み立てを切り出した
 * {@link IntegratedChatMessagePresenter#compose(NormalizedChatMessage)} を通して行う。
 */
class IntegratedChatMessagePresenterTest {

    private final DisplayedChatMessageIndex index = new DisplayedChatMessageIndex();
    private final List<Set<DisplayedMessageKey>> removedBatches = new ArrayList<>();
    private final DrainingExecutor clientExecutor = new DrainingExecutor();
    private final ChatRowRemover rowRemover = new ChatRowRemover(clientExecutor, ids -> {
        removedBatches.add(ids);
        return ids;
    });
    private final IntegratedChatMessagePresenter presenter = new IntegratedChatMessagePresenter(index, rowRemover);

    private static NormalizedChatMessage message(String messageId, String channelId) {
        return new NormalizedChatMessage(
                PlatformId.TWITCH,
                messageId,
                channelId,
                "author-1",
                Component.literal("Author"),
                Component.literal("hello"));
    }

    private static DisplayedMessageKey key(String messageId) {
        return new DisplayedMessageKey(PlatformId.TWITCH, messageId);
    }

    @Test
    void composePrependsThePlatformPrefix() {
        Component composed = presenter.compose(message("abc-123", "channel-1"));

        Component expectedPrefix = PlatformChatStyles.prefixFor(PlatformId.TWITCH);
        assertEquals(expectedPrefix, composed.getSiblings().get(0), "先頭にプラットフォームのプレフィックスが付くこと");
    }

    @Test
    void composeIncludesAuthorDisplayAndContent() {
        Component authorDisplay = Component.literal("SomeUser");
        Component content = Component.literal("hi there");
        NormalizedChatMessage msg = new NormalizedChatMessage(
                PlatformId.TWITCH, "abc-123", "channel-1", "author-1", authorDisplay, content);

        Component composed = presenter.compose(msg);

        assertTrue(composed.getSiblings().contains(authorDisplay), "投稿者名がComponentに含まれること");
        assertTrue(composed.getSiblings().contains(content), "本文がComponentに含まれること");
    }

    @Test
    void composeWithoutBadgesProducesSameStructureAsBefore() {
        NormalizedChatMessage withoutBadges = message("abc-123", "channel-1");
        NormalizedChatMessage explicitEmptyBadges = new NormalizedChatMessage(
                PlatformId.TWITCH,
                "abc-123",
                "channel-1",
                "author-1",
                Component.literal("Author"),
                Component.literal("hello"),
                List.of());

        assertEquals(presenter.compose(withoutBadges), presenter.compose(explicitEmptyBadges));
    }

    @Test
    void composeIncludesBadgesBeforeAuthorDisplayInOrder() {
        Component badge1 = Component.literal("");
        Component badge2 = Component.literal("");
        Component authorDisplay = Component.literal("SomeUser");
        Component content = Component.literal("hi there");
        NormalizedChatMessage msg = new NormalizedChatMessage(
                PlatformId.TWITCH,
                "abc-123",
                "channel-1",
                "author-1",
                authorDisplay,
                content,
                List.of(badge1, badge2));

        Component composed = presenter.compose(msg);

        List<Component> siblings = composed.getSiblings();
        int badge1Index = siblings.indexOf(badge1);
        int badge2Index = siblings.indexOf(badge2);
        int authorIndex = siblings.indexOf(authorDisplay);
        assertTrue(badge1Index >= 0 && badge2Index >= 0 && authorIndex >= 0, "バッジ・投稿者名が全て含まれること");
        assertTrue(badge1Index < badge2Index, "バッジは配列の順序どおりに並ぶこと");
        assertTrue(badge2Index < authorIndex, "バッジは投稿者名より前に表示されること");
    }

    @Test
    void trackForDeletionRegistersTheDisplayedMessageUnderItsKey() {
        Component composed = Component.literal("displayed");

        presenter.trackForDeletion(composed, message("abc-123", "channel-1"));

        var tracked = index.find(key("abc-123"));
        assertTrue(tracked.isPresent(), "キーに登録されること");
        assertEquals("channel-1", tracked.get().channelId());
        assertEquals("author-1", tracked.get().authorId());
    }

    @Test
    void trackForDeletionSkipsMessagesWithoutAMessageId() {
        // messageIdの妥当性検証（文字集合・長さ等）はプラットフォーム固有Presenter側の責務
        // （TwitchChatMessagePresenterTest参照）。ここではnull/空という一般的な不正値のみ検証する。
        presenter.trackForDeletion(Component.literal("displayed"), message("", "channel-1"));
        presenter.trackForDeletion(Component.literal("displayed"), message(null, "channel-1"));

        assertEquals(0, index.size());
    }

    @Test
    void trackForDeletionSkipsMessagesWithoutChannelId() {
        presenter.trackForDeletion(Component.literal("displayed"), message("abc-123", ""));

        assertEquals(0, index.size(), "チャンネルIDが無いと削除通知と照合できないため追跡しないこと");
    }

    @Test
    void presentDeleteRemovesTheRowWhenTracked() {
        index.remember(key("abc-123"), new DisplayedChatMessage("channel-1", "author-1"));

        presenter.presentDelete(PlatformId.TWITCH, "abc-123", "channel-1");
        clientExecutor.drain();

        assertEquals(1, removedBatches.size());
        assertEquals(Set.of(key("abc-123")), removedBatches.get(0));
        assertTrue(index.find(key("abc-123")).isEmpty(), "索引からも取り除かれること");
    }

    @Test
    void presentDeleteDoesNotRemoveWhenChannelIdMismatches() {
        index.remember(key("abc-123"), new DisplayedChatMessage("channel-1", "author-1"));

        presenter.presentDelete(PlatformId.TWITCH, "abc-123", "channel-9999");
        clientExecutor.drain();

        assertTrue(removedBatches.isEmpty(), "別チャンネルからの削除要求は適用しないこと");
        assertTrue(index.find(key("abc-123")).isPresent(), "索引も消費されないこと");
    }

    @Test
    void presentDeleteIgnoresUntrackedMessage() {
        presenter.presentDelete(PlatformId.TWITCH, "never-displayed", "channel-1");
        clientExecutor.drain();

        assertTrue(removedBatches.isEmpty());
    }
}
