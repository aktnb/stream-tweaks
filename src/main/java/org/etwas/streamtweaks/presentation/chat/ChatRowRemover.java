package org.etwas.streamtweaks.presentation.chat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 削除対象の {@link DisplayedMessageKey} をためて、まとめてチャット欄から行を取り除く。
 *
 * <p><strong>バースト対策</strong>: 削除通知1件ごとにチャット欄の再構築
 * （{@code refreshTrimmedMessages()}）を走らせるとメインスレッド負荷になるため、
 * 同じタスクドレイン（実質1tick）内に届いた削除をまとめ、走査と再構築を1回に集約する。
 * {@code Minecraft#execute} はキューが空になるまで処理を続けるため、
 * 蓄積中に投入したflushタスクは「その時点でキューに積まれていた全タスクの後」に実行される。
 * これにより、先に積まれた表示タスクを追い越して削除が走ることはない。
 *
 * <p><strong>スレッド安全性</strong>: {@link #enqueue} を含めメインスレッド専用。同期化していない。
 */
public final class ChatRowRemover {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatRowRemover.class);

    private final Executor clientExecutor;
    private final Function<Set<DisplayedMessageKey>, Set<DisplayedMessageKey>> rowRemoval;
    private final Set<DisplayedMessageKey> pending = new LinkedHashSet<>();
    private boolean flushScheduled;

    /**
     * @param clientExecutor Minecraftメインスレッドへタスクを積むExecutor
     * @param rowRemoval 指定されたキーの行を取り除き、<em>実際に取り除けた</em> キーを返す関数
     */
    public ChatRowRemover(
            Executor clientExecutor, Function<Set<DisplayedMessageKey>, Set<DisplayedMessageKey>> rowRemoval) {
        this.clientExecutor = clientExecutor;
        this.rowRemoval = rowRemoval;
    }

    /**
     * 未処理の削除予約を破棄する。
     *
     * <p>ワールド退出・サーバー切断時に呼ぶこと。チャット欄がクリアされた後に残った予約を
     * 実行しても行は見つからず、WARNログを出すだけになるため。メインスレッドから呼ぶこと。
     */
    public void clear() {
        pending.clear();
    }

    /**
     * 削除対象を予約する。メインスレッドから呼ぶこと。
     */
    public void enqueue(DisplayedMessageKey key) {
        pending.add(key);
        if (!flushScheduled) {
            flushScheduled = true;
            clientExecutor.execute(this::flush);
        }
    }

    private void flush() {
        // 先にフラグを倒すことで、この後に届いた削除が次のflushで確実に処理されるようにする。
        flushScheduled = false;
        if (pending.isEmpty()) {
            return;
        }
        Set<DisplayedMessageKey> batch = Set.copyOf(pending);
        pending.clear();

        Set<DisplayedMessageKey> removed;
        try {
            removed = rowRemoval.apply(batch);
        } catch (RuntimeException e) {
            // チャット行の削除は表示上の演出にすぎない。ここで例外を投げるとMinecraftの
            // タスク処理ごとクラッシュするため、握り潰してログに留める。
            LOGGER.error("Failed to remove deleted chat messages from the chat hud", e);
            return;
        }

        // 索引上は「表示済み」だったのに行が見つからなかったケース。
        // Minecraft側の受け渡し経路が変わってタグが失われた場合などにここで気づけるようにする。
        // なお、ワールド移動などでチャット欄がクリアされた後に削除通知が届いた場合も
        // ここに該当する（この場合は異常ではない）。
        int missed = removed == null
                ? batch.size()
                : (int) batch.stream().filter(id -> !removed.contains(id)).count();
        if (missed > 0) {
            // 件数だけを1行にまとめる（毎回外れる状態になったときにログを溢れさせないため）
            LOGGER.warn(
                    "{} of {} chat message(s) marked as displayed were not found in the chat hud",
                    missed,
                    batch.size());
        }
    }
}
