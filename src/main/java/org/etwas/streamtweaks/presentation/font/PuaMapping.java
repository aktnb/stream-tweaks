package org.etwas.streamtweaks.presentation.font;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * emote ID → Unicode PUA コードポイントのマッピング（LRU キャッシュ）。
 *
 * <p>U+F000 から順に割り当てる（{@link WellKnownPuaCodePoints} に含まれるコードポイントは
 * リソースパックとの競合を避けるためスキップする）。上限 200 エントリを超えると
 * 最も古いエントリを evict し、evict されたコードポイントをフリーリストに戻して再利用することで
 * PUA 領域（U+F000〜U+F8FF）の枯渇を防ぐ。evict 時にはコールバックに evict されたコードポイントを通知する。
 *
 * <p>Minecraft メインスレッドからの単一スレッドアクセスを前提とする（スレッドセーフではない）。
 * セッション内限定。再起動時にインスタンスを作り直すことでカウンタがリセットされる。
 *
 * <p>設計上の注意: evict 後に同じ emote ID が再要求されると別のコードポイントが割り当てられる
 * （フリーリストの先頭 = 最近 evict されたスロット）。既存の Text オブジェクトには古いコードポイントが
 * 残るため、T10 の displayChatMessage 実装時にチャット履歴の再描画方針を検討すること。
 */
public class PuaMapping {

    private static final int BASE_CODE_POINT = 0xF000;
    private static final int MAX_CODE_POINT = 0xF8FF;
    private static final int MAX_ENTRIES = 200;

    private int nextCodePoint = BASE_CODE_POINT;
    private final Deque<Integer> freeList = new ArrayDeque<>();
    private final Consumer<Integer> onEvict;
    // コードポイント → emoteId の逆引き。isCurrentMapping で使用。
    // LRU のアクセス順序に影響しないよう通常の HashMap として保持する。
    private final Map<Integer, String> reverseMap = new HashMap<>();

    private final LinkedHashMap<String, Integer> cache = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            if (size() > MAX_ENTRIES) {
                int evictedCodePoint = eldest.getValue();
                reverseMap.remove(evictedCodePoint);
                freeList.addFirst(evictedCodePoint);
                try {
                    onEvict.accept(evictedCodePoint);
                } catch (Exception e) {
                    // コールバック例外で LRU 状態が壊れないよう継続する
                }
                return true;
            }
            return false;
        }
    };

    public PuaMapping(Consumer<Integer> onEvict) {
        this.onEvict = onEvict;
    }

    /**
     * 指定した emote ID に対応する PUA コードポイントを返す。
     * 未登録の場合は新しいコードポイントを割り当てて返す。
     *
     * @param emoteId Twitch の emote ID（null 不可・空文字不可）
     * @throws IllegalArgumentException emoteId が null または空文字の場合
     * @throws IllegalStateException PUA 領域が枯渇した場合（フリーリストも空の場合）
     */
    public int getOrAssign(String emoteId) {
        Objects.requireNonNull(emoteId, "emoteId must not be null");
        if (emoteId.isBlank()) {
            throw new IllegalArgumentException("emoteId must not be blank");
        }
        int codePoint = cache.computeIfAbsent(emoteId, id -> allocate());
        reverseMap.put(codePoint, emoteId);
        return codePoint;
    }

    /**
     * 指定したコードポイントが今も emoteId に対応しているかを返す。
     * ダウンロード完了コールバックで evict による再割り当てを検出するために使用する。
     * LRU のアクセス順序は更新しない。
     */
    public boolean isCurrentMapping(String emoteId, int codePoint) {
        return Objects.equals(reverseMap.get(codePoint), emoteId);
    }

    private int allocate() {
        if (!freeList.isEmpty()) {
            return freeList.removeFirst();
        }
        while (nextCodePoint <= MAX_CODE_POINT && WellKnownPuaCodePoints.CODE_POINTS.contains(nextCodePoint)) {
            nextCodePoint++;
        }
        if (nextCodePoint > MAX_CODE_POINT) {
            throw new IllegalStateException("PUA code point exhausted: all slots from U+F000 to U+F8FF are in use");
        }
        return nextCodePoint++;
    }
}
