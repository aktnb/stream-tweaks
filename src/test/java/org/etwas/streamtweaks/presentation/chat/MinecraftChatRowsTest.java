package org.etwas.streamtweaks.presentation.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import org.etwas.streamtweaks.platform.PlatformId;
import org.junit.jupiter.api.Test;

/**
 * 行削除ロジック本体（{@link MinecraftChatRows#removeTaggedRows}）のテスト。
 *
 * <p>Mixinはユニットテストでは適用されないため、{@code StreamTweaksTaggedComponent} を実装した
 * Componentのモックでタグ付き行を再現する。判定ロジック（instanceof + キー照合）自体は本物を通る。
 */
class MinecraftChatRowsTest {

    private static DisplayedMessageKey key(String messageId) {
        return new DisplayedMessageKey(PlatformId.TWITCH, messageId);
    }

    /** MOD が流したタグ付きメッセージの行を作る。 */
    private static GuiMessage taggedRow(DisplayedMessageKey key) {
        Component content = mock(Component.class, withSettings().extraInterfaces(StreamTweaksTaggedComponent.class));
        when(((StreamTweaksTaggedComponent) content).streamTweaks$getMessageKey())
                .thenReturn(key);
        return row(content);
    }

    private static GuiMessage taggedRow(String messageId) {
        return taggedRow(key(messageId));
    }

    /** MOD 以外（バニラのシステムメッセージなど）が流した、タグを持たない行を作る。 */
    private static GuiMessage untaggedRow() {
        return row(Component.literal("vanilla system message"));
    }

    private static GuiMessage row(Component content) {
        return new GuiMessage(0, content, null, GuiMessageSource.SYSTEM_SERVER, null);
    }

    @Test
    void removesOnlyTheRowsWhoseTagMatches() {
        GuiMessage target = taggedRow("msg-1");
        GuiMessage other = taggedRow("msg-2");
        GuiMessage vanilla = untaggedRow();
        List<GuiMessage> allMessages = new ArrayList<>(List.of(target, other, vanilla));

        Set<DisplayedMessageKey> removed = MinecraftChatRows.removeTaggedRows(allMessages, Set.of(key("msg-1")));

        assertEquals(Set.of(key("msg-1")), removed);
        assertEquals(List.of(other, vanilla), allMessages);
    }

    @Test
    void removesEveryRowSharingTheSameMessageId() {
        // EventSubのat-least-once配信で同じメッセージが二重表示された状況
        GuiMessage firstCopy = taggedRow("dup-1");
        GuiMessage secondCopy = taggedRow("dup-1");
        GuiMessage unrelated = taggedRow("msg-9");
        List<GuiMessage> allMessages = new ArrayList<>(List.of(firstCopy, secondCopy, unrelated));

        Set<DisplayedMessageKey> removed = MinecraftChatRows.removeTaggedRows(allMessages, Set.of(key("dup-1")));

        assertEquals(Set.of(key("dup-1")), removed);
        assertEquals(List.of(unrelated), allMessages, "重複表示された行が両方とも消えること");
    }

    @Test
    void removesMultipleDifferentMessagesInOnePass() {
        GuiMessage first = taggedRow("msg-1");
        GuiMessage second = taggedRow("msg-2");
        GuiMessage third = taggedRow("msg-3");
        List<GuiMessage> allMessages = new ArrayList<>(List.of(first, second, third));

        Set<DisplayedMessageKey> removed =
                MinecraftChatRows.removeTaggedRows(allMessages, Set.of(key("msg-1"), key("msg-3")));

        assertEquals(Set.of(key("msg-1"), key("msg-3")), removed);
        assertEquals(List.of(second), allMessages);
    }

    @Test
    void neverTouchesRowsWithoutATag() {
        GuiMessage vanilla = untaggedRow();
        List<GuiMessage> allMessages = new ArrayList<>(List.of(vanilla));

        Set<DisplayedMessageKey> removed = MinecraftChatRows.removeTaggedRows(allMessages, Set.of(key("msg-1")));

        assertTrue(removed.isEmpty());
        assertEquals(List.of(vanilla), allMessages, "バニラのメッセージを巻き込まないこと");
    }

    @Test
    void reportsOnlyTheIdsItActuallyRemoved() {
        GuiMessage present = taggedRow("msg-1");
        List<GuiMessage> allMessages = new ArrayList<>(List.of(present));

        // msg-2 は既にチャット欄から流れて存在しない
        Set<DisplayedMessageKey> removed =
                MinecraftChatRows.removeTaggedRows(allMessages, Set.of(key("msg-1"), key("msg-2")));

        assertEquals(Set.of(key("msg-1")), removed);
        assertTrue(allMessages.isEmpty());
    }

    @Test
    void doesNothingForAnEmptyRequest() {
        GuiMessage row = taggedRow("msg-1");
        List<GuiMessage> allMessages = new ArrayList<>(List.of(row));

        Set<DisplayedMessageKey> removed = MinecraftChatRows.removeTaggedRows(allMessages, Set.of());

        assertTrue(removed.isEmpty());
        assertEquals(List.of(row), allMessages);
    }

    @Test
    void handlesRowsWhoseTagIsNull() {
        // タグ付けに失敗した（Mixin未適用など）Componentが混ざっていても落ちないこと
        Component content = mock(Component.class, withSettings().extraInterfaces(StreamTweaksTaggedComponent.class));
        when(((StreamTweaksTaggedComponent) content).streamTweaks$getMessageKey())
                .thenReturn(null);
        List<GuiMessage> allMessages = new ArrayList<>(List.of(row(content)));

        Set<DisplayedMessageKey> removed = MinecraftChatRows.removeTaggedRows(allMessages, Set.of(key("msg-1")));

        assertTrue(removed.isEmpty());
        assertEquals(1, allMessages.size());
    }

    /**
     * 現時点で {@link PlatformId} は {@code TWITCH} のみ（YAGNIのため他プラットフォームは未追加）だが、
     * タグ付け・削除がプラットフォームを含む複合キー（{@link DisplayedMessageKey}）で行われることで、
     * {@code messageId} 文字列だけが一致しても異なるキーとして扱われることを確認する。
     * 将来2つ目の {@link PlatformId} が追加された際は、「同一messageId・異なるplatform」の
     * 2行を用意し、片方だけを指定した削除でもう一方が残るケースをここに追加すること。
     */
    @Test
    void onlyTheRowTaggedWithTheExactKeyIsRemoved() {
        DisplayedMessageKey target = key("shared-id");
        GuiMessage targetRow = taggedRow(target);
        GuiMessage otherRow = taggedRow(key("shared-id-other"));
        List<GuiMessage> allMessages = new ArrayList<>(List.of(targetRow, otherRow));

        Set<DisplayedMessageKey> removed = MinecraftChatRows.removeTaggedRows(allMessages, Set.of(target));

        assertEquals(Set.of(target), removed);
        assertEquals(List.of(otherRow), allMessages, "指定したキー以外の行は残ること");
    }
}
