package org.etwas.streamtweaks.presentation.chat.twitch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EventSubのnotificationを {@code payload.subscription.type} で振り分ける。
 *
 * <p>{@code EventSubOrchestrator} が受け取る {@code Consumer<String>} としてそのまま差し込めるため、
 * 購読レイヤ側には手を入れない。ハンドラには解析済みの {@code payload} を渡し、二重パースを避ける。
 *
 * <p>ログにペイロード本体やメッセージ本文は出さない。未知タイプのみ、
 * 制御文字を除去し長さを切り詰めた上でDEBUG出力する。
 */
public final class EventSubNotificationRouter implements Consumer<String> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventSubNotificationRouter.class);
    private static final int MAX_LOGGED_TYPE_LENGTH = 64;

    private final Gson gson;
    private final Map<String, Consumer<JsonObject>> handlers;

    /**
     * @param handlers イベントタイプ文字列 → ハンドラ。生成後は変更されない。
     */
    public EventSubNotificationRouter(Gson gson, Map<String, Consumer<JsonObject>> handlers) {
        this.gson = gson;
        this.handlers = Map.copyOf(handlers);
    }

    @Override
    public void accept(String rawNotification) {
        JsonObject payload = parsePayload(rawNotification);
        if (payload == null) {
            return;
        }
        String eventType = extractEventType(payload);
        if (eventType == null) {
            return;
        }

        Consumer<JsonObject> handler = handlers.get(eventType);
        if (handler == null) {
            LOGGER.debug("No handler registered for EventSub notification type: {}", sanitizeForLog(eventType));
            return;
        }
        handler.accept(payload);
    }

    private JsonObject parsePayload(String rawNotification) {
        JsonObject json;
        try {
            json = gson.fromJson(rawNotification, JsonObject.class);
        } catch (JsonSyntaxException e) {
            // ペイロードは出力しない（メッセージ本文が含まれるため）
            LOGGER.error("Failed to parse EventSub notification");
            return null;
        }
        if (json == null) {
            return null;
        }
        return asObject(json.get("payload"));
    }

    private String extractEventType(JsonObject payload) {
        JsonObject subscription = asObject(payload.get("subscription"));
        if (subscription == null) {
            return null;
        }
        JsonElement type = subscription.get("type");
        if (type == null || !type.isJsonPrimitive()) {
            return null;
        }
        return type.getAsString();
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * 外部由来の文字列をログに出す前に、制御文字を除去し長さを切り詰める。
     */
    static String sanitizeForLog(String value) {
        StringBuilder sanitized = new StringBuilder();
        int length = Math.min(value.length(), MAX_LOGGED_TYPE_LENGTH);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            sanitized.append(Character.isISOControl(c) ? '?' : c);
        }
        if (value.length() > MAX_LOGGED_TYPE_LENGTH) {
            sanitized.append("...");
        }
        return sanitized.toString();
    }
}
