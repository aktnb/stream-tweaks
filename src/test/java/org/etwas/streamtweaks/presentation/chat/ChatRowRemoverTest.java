package org.etwas.streamtweaks.presentation.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.etwas.streamtweaks.platform.PlatformId;
import org.junit.jupiter.api.Test;

class ChatRowRemoverTest {

    private final List<Set<DisplayedMessageKey>> batches = new ArrayList<>();

    private static DisplayedMessageKey key(String messageId) {
        return new DisplayedMessageKey(PlatformId.TWITCH, messageId);
    }

    private ChatRowRemover remover(DrainingExecutor executor) {
        return new ChatRowRemover(executor, ids -> {
            batches.add(ids);
            return ids;
        });
    }

    @Test
    void collapsesMultipleDeletionsIntoASingleRemovalPass() {
        DrainingExecutor executor = new DrainingExecutor();
        ChatRowRemover remover = remover(executor);

        remover.enqueue(key("msg-1"));
        remover.enqueue(key("msg-2"));
        remover.enqueue(key("msg-3"));
        assertEquals(1, executor.pendingTasks(), "flushタスクは1つだけ積まれること");

        executor.drain();

        assertEquals(1, batches.size(), "チャット欄の走査は1回にまとめられること");
        assertEquals(Set.of(key("msg-1"), key("msg-2"), key("msg-3")), batches.get(0));
    }

    @Test
    void schedulesANewFlushAfterThePreviousOneCompleted() {
        DrainingExecutor executor = new DrainingExecutor();
        ChatRowRemover remover = remover(executor);

        remover.enqueue(key("msg-1"));
        executor.drain();
        remover.enqueue(key("msg-2"));
        executor.drain();

        assertEquals(2, batches.size());
        assertEquals(Set.of(key("msg-1")), batches.get(0));
        assertEquals(Set.of(key("msg-2")), batches.get(1));
    }

    @Test
    void doesNotInvokeRemovalWhenNothingIsPending() {
        DrainingExecutor executor = new DrainingExecutor();
        ChatRowRemover remover = remover(executor);

        remover.enqueue(key("msg-1"));
        executor.drain();
        executor.drain();

        assertEquals(1, batches.size());
    }

    @Test
    void deduplicatesTheSameMessageIdWithinOneBatch() {
        DrainingExecutor executor = new DrainingExecutor();
        ChatRowRemover remover = remover(executor);

        remover.enqueue(key("msg-1"));
        remover.enqueue(key("msg-1"));
        executor.drain();

        assertEquals(1, batches.size());
        assertEquals(Set.of(key("msg-1")), batches.get(0));
    }

    @Test
    void doesNotThrowWhenRemovalFindsNothing() {
        DrainingExecutor executor = new DrainingExecutor();
        // 1件も取り除けなかった場合（WARNログが出るケース）
        ChatRowRemover remover = new ChatRowRemover(executor, ids -> Set.of());

        remover.enqueue(key("msg-1"));
        executor.drain();

        assertTrue(batches.isEmpty());
    }

    @Test
    void swallowsExceptionsFromRemovalSoTheClientDoesNotCrash() {
        DrainingExecutor executor = new DrainingExecutor();
        ChatRowRemover remover = new ChatRowRemover(executor, ids -> {
            throw new IllegalStateException("chat hud is not available");
        });

        remover.enqueue(key("msg-1"));
        // Minecraftのタスク処理内で例外を投げるとクライアントごと落ちるため、ここで止める
        executor.drain();

        // 例外後も次の削除を受け付けられること
        remover.enqueue(key("msg-2"));
        executor.drain();
    }

    @Test
    void toleratesRemovalReturningNull() {
        DrainingExecutor executor = new DrainingExecutor();
        ChatRowRemover remover = new ChatRowRemover(executor, ids -> null);

        remover.enqueue(key("msg-1"));
        executor.drain();
    }

    @Test
    void clearDiscardsPendingDeletions() {
        DrainingExecutor executor = new DrainingExecutor();
        ChatRowRemover remover = remover(executor);

        remover.enqueue(key("msg-1"));
        // ワールド退出などでチャット欄がクリアされたケース
        remover.clear();
        executor.drain();

        assertTrue(batches.isEmpty(), "破棄した予約は実行されないこと");
    }
}
