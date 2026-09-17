package org.etwas.streamtweaks.presentation.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * チャット欄へ表示済みのメッセージを {@link DisplayedMessageKey}（プラットフォーム識別子 +
 * メッセージID）で引けるようにする索引。
 *
 * <p>キーにプラットフォーム識別子を含めることで、異なるプラットフォームが偶然同じメッセージIDを
 * 発行しても互いに独立して追跡・削除できる。
 *
 * <p><strong>スレッド安全性</strong>: Minecraftメインスレッド専用。同期化していない。
 * 書き込み（{@link #remember}）・読み取り（{@link #find}）・取り出し（{@link #forget}）は
 * すべて {@code IntegratedChatMessagePresenter} の {@code client.execute{}} 内からのみ行うこと。
 * WebSocketスレッドからは直接触らない。
 * この閉じ込めにより「表示 → 削除」の到着順がそのまま実行順になることも保証される
 * （WebSocket通知は単一スレッドで順次処理され、{@code Minecraft#execute} はFIFOのため）。
 *
 * <p><strong>保持件数</strong>: Minecraft側の表示上限（{@code ChatComponent.allMessages} = 100件）には
 * 他MODやサーバーのシステムメッセージも混在するため、100件で打ち切るとMOD自身のメッセージが
 * Minecraft側より先に索引から溢れうる。「表示されていれば消す」という要件に忠実であるため、
 * 余裕をもって {@value #MAX_TRACKED} 件を追跡する。保持するのはキーと少数のIDのみで、
 * メッセージ本文やComponentは持たないためメモリコストは軽微。
 */
public final class DisplayedChatMessageIndex {

    /** 追跡する表示済みメッセージの上限件数。 */
    public static final int MAX_TRACKED = 500;

    private final Map<DisplayedMessageKey, DisplayedChatMessage> index = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<DisplayedMessageKey, DisplayedChatMessage> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    /**
     * 表示したメッセージを追跡対象に加える。
     *
     * <p>プラットフォームのイベント配信はat-least-once配信のことがあり、同一キーが再送されて
     * 同じ内容が二重に表示されることがある。その場合はここで上書きされるが、
     * 削除時は表示行をキーで走査して<em>一致する行をすべて</em>取り除くため、
     * 重複表示された行も同時に消える。
     */
    public void remember(DisplayedMessageKey key, DisplayedChatMessage message) {
        // 再送で同じキーが来た場合に古い挿入位置のまま残らないよう、入れ直して最新扱いにする。
        index.remove(key);
        index.put(key, message);
    }

    /**
     * 追跡中のメッセージを参照する（索引は変更しない）。未追跡の場合は空。
     *
     * <p>削除を適用してよいかの検証は、索引から取り除く前にこのメソッドで行うこと。
     * 検証に失敗した通知でエントリを消費してしまうと、後から届く正当な削除通知が
     * 効かなくなってしまうため。
     */
    public Optional<DisplayedChatMessage> find(DisplayedMessageKey key) {
        return Optional.ofNullable(index.get(key));
    }

    /**
     * 追跡対象から取り出して削除する。未追跡（上限超過で溢れた、または未知）の場合は空。
     */
    public Optional<DisplayedChatMessage> forget(DisplayedMessageKey key) {
        return Optional.ofNullable(index.remove(key));
    }

    /**
     * 追跡中のメッセージをすべて破棄する。
     *
     * <p>ワールド退出・サーバー切断時に呼ぶこと。Minecraft側のチャット欄も同時にクリアされるため、
     * 索引だけが残っていると、その後に届く削除通知が毎回「表示済みなのに行が無い」と誤検知され、
     * WARNログを出し続けることになる。
     */
    public void clear() {
        index.clear();
    }

    /**
     * 現在追跡中の件数（テスト用）。
     */
    int size() {
        return index.size();
    }
}
