package org.etwas.streamtweaks.presentation.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/**
 * Minecraftメインスレッドのタスク処理を模したテスト用Executor。
 *
 * <p>{@code Minecraft#runAllTasks} と同じく、キューが空になるまでFIFOで実行する
 * （処理中に積まれたタスクも同じドレインで実行される）。{@code Runnable::run} の即時実行では
 * 「1tick分の削除をまとめて処理する」というバッチ集約の挙動を再現できないため、
 * 削除まわりのテストはこのExecutorを使う。
 */
public final class DrainingExecutor implements Executor {

    private final Deque<Runnable> queue = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
        queue.add(command);
    }

    /** キューが空になるまで実行する（1tick分のタスク処理に相当）。 */
    public void drain() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    public int pendingTasks() {
        return queue.size();
    }
}
