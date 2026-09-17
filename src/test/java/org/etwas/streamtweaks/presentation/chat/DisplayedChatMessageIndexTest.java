package org.etwas.streamtweaks.presentation.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.etwas.streamtweaks.platform.PlatformId;
import org.junit.jupiter.api.Test;

class DisplayedChatMessageIndexTest {

    private final DisplayedChatMessageIndex index = new DisplayedChatMessageIndex();

    private static DisplayedChatMessage message(String channelId) {
        return new DisplayedChatMessage(channelId, "author-1");
    }

    private static DisplayedMessageKey key(String messageId) {
        return new DisplayedMessageKey(PlatformId.TWITCH, messageId);
    }

    @Test
    void forgetReturnsRememberedMessageAndRemovesIt() {
        index.remember(key("msg-1"), message("channel-1"));

        Optional<DisplayedChatMessage> first = index.forget(key("msg-1"));

        assertTrue(first.isPresent());
        assertEquals("channel-1", first.get().channelId());
        assertEquals("author-1", first.get().authorId());
        assertTrue(index.forget(key("msg-1")).isEmpty(), "一度取り出したら索引から消えていること");
    }

    @Test
    void forgetReturnsEmptyForUnknownMessageId() {
        assertTrue(index.forget(key("unknown")).isEmpty());
    }

    @Test
    void findReturnsTheEntryWithoutRemovingIt() {
        index.remember(key("msg-1"), message("channel-1"));

        assertTrue(index.find(key("msg-1")).isPresent());
        assertTrue(index.find(key("msg-1")).isPresent(), "findは索引を変更しないこと");
        assertEquals(1, index.size());
        assertTrue(index.forget(key("msg-1")).isPresent());
    }

    @Test
    void findReturnsEmptyForUnknownMessageId() {
        assertTrue(index.find(key("unknown")).isEmpty());
    }

    @Test
    void evictsOldestEntryOnceLimitIsExceeded() {
        for (int i = 0; i < DisplayedChatMessageIndex.MAX_TRACKED; i++) {
            index.remember(key("msg-" + i), message("channel-1"));
        }
        assertEquals(DisplayedChatMessageIndex.MAX_TRACKED, index.size());
        assertTrue(index.forget(key("msg-0")).isPresent(), "上限ちょうどまでは保持されること");

        // forgetで1件減っているので2件足して上限を1件超えさせる
        index.remember(key("msg-0"), message("channel-1"));
        index.remember(key("overflow"), message("channel-1"));

        assertEquals(DisplayedChatMessageIndex.MAX_TRACKED, index.size());
        assertTrue(index.forget(key("msg-1")).isEmpty(), "最も古いエントリが押し出されること");
        assertTrue(index.forget(key("overflow")).isPresent(), "最新のエントリは残ること");
    }

    @Test
    void rememberingSameMessageIdTwiceOverwritesAndRefreshesRecency() {
        index.remember(key("duplicate"), message("channel-1"));
        index.remember(key("filler"), message("channel-1"));
        index.remember(key("duplicate"), message("channel-2"));

        assertEquals(2, index.size(), "同じIDの再送で件数は増えないこと");

        // duplicateが入れ直されたことで、押し出されるのはfillerの側になる
        for (int i = 0; i < DisplayedChatMessageIndex.MAX_TRACKED - 1; i++) {
            index.remember(key("pad-" + i), message("channel-1"));
        }

        assertTrue(index.forget(key("filler")).isEmpty());
        Optional<DisplayedChatMessage> duplicate = index.forget(key("duplicate"));
        assertTrue(duplicate.isPresent());
        assertEquals("channel-2", duplicate.get().channelId(), "後から届いた内容で上書きされること");
    }

    /**
     * 異なる {@code messageId} の2エントリが独立して find/forget できることを確認する。
     *
     * <p><b>注意</b>: このテストは {@code platform} フィールドがキーの一致判定に実際に
     * 寄与していることまでは検証できていない（同一platform・異なるmessageIdの組しか
     * 用意できないため）。{@link PlatformId} は現時点で {@code TWITCH} のみ（YAGNIのため
     * 他プラットフォームは未追加）で、テストのためだけに本番用の値を先取りして追加すること
     * はしない。将来2つ目の {@link PlatformId} が追加された際は、必ず「異なるplatform・
     * 同一messageIdの2キーが独立してfind/forgetできる」ケースを追加すること。
     */
    @Test
    void indexTracksEntriesWithDifferentMessageIdsIndependently() {
        DisplayedMessageKey keyA = new DisplayedMessageKey(PlatformId.TWITCH, "shared-id-a");
        DisplayedMessageKey keyB = new DisplayedMessageKey(PlatformId.TWITCH, "shared-id-b");

        index.remember(keyA, message("channel-1"));
        index.remember(keyB, message("channel-2"));

        assertTrue(index.find(keyA).isPresent());
        assertTrue(index.find(keyB).isPresent());

        index.forget(keyA);

        assertTrue(index.find(keyA).isEmpty(), "片方をforgetしてももう片方には影響しないこと");
        assertTrue(index.find(keyB).isPresent(), "独立して追跡されること");
    }
}
