package org.etwas.streamtweaks.presentation.chat.twitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EventSubNotificationRouterTest {

    private final List<JsonObject> chatMessages = new ArrayList<>();
    private final List<JsonObject> deletes = new ArrayList<>();

    private EventSubNotificationRouter router() {
        Map<String, Consumer<JsonObject>> handlers = Map.of(
                "channel.chat.message", chatMessages::add,
                "channel.chat.message_delete", deletes::add);
        return new EventSubNotificationRouter(new Gson(), handlers);
    }

    private static String notification(String type) {
        return """
                {
                  "metadata": {"message_type": "notification"},
                  "payload": {
                    "subscription": {"id": "sub-1", "type": "%s", "version": "1"},
                    "event": {"message_id": "abc-123"}
                  }
                }
                """
                .formatted(type);
    }

    @Test
    void routesChatMessageNotificationToItsHandler() {
        router().accept(notification("channel.chat.message"));

        assertEquals(1, chatMessages.size());
        assertTrue(deletes.isEmpty());
        // ハンドラにはpayloadが渡される（二重パースを避けるため）
        assertTrue(chatMessages.get(0).has("event"));
        assertEquals(
                "abc-123",
                chatMessages.get(0).getAsJsonObject("event").get("message_id").getAsString());
    }

    @Test
    void routesMessageDeleteNotificationToItsHandler() {
        router().accept(notification("channel.chat.message_delete"));

        assertEquals(1, deletes.size());
        assertTrue(chatMessages.isEmpty());
    }

    @Test
    void ignoresUnknownEventType() {
        router().accept(notification("channel.chat.clear"));

        assertTrue(chatMessages.isEmpty());
        assertTrue(deletes.isEmpty());
    }

    @Test
    void ignoresMalformedJsonWithoutThrowing() {
        router().accept("not-json-at-all");
        router().accept("{\"payload\": ");

        assertTrue(chatMessages.isEmpty());
        assertTrue(deletes.isEmpty());
    }

    @Test
    void ignoresNotificationWithoutPayload() {
        router().accept("{\"metadata\": {\"message_type\": \"notification\"}}");

        assertTrue(chatMessages.isEmpty());
    }

    @Test
    void ignoresNotificationWhosePayloadIsNotAnObject() {
        router().accept("{\"payload\": \"oops\"}");

        assertTrue(chatMessages.isEmpty());
    }

    @Test
    void ignoresNotificationWithoutSubscriptionOrType() {
        router().accept("{\"payload\": {\"event\": {}}}");
        router().accept("{\"payload\": {\"subscription\": {\"id\": \"sub-1\"}}}");
        router().accept("{\"payload\": {\"subscription\": {\"type\": null}}}");
        router().accept("{\"payload\": {\"subscription\": {\"type\": {}}}}");

        assertTrue(chatMessages.isEmpty());
        assertTrue(deletes.isEmpty());
    }

    @Test
    void ignoresJsonNullNotification() {
        router().accept("null");

        assertTrue(chatMessages.isEmpty());
    }

    @Test
    void sanitizeForLogStripsControlCharacters() {
        String sanitized = EventSubNotificationRouter.sanitizeForLog("evil" + (char) 0x0a + "type");

        assertFalse(sanitized.contains(String.valueOf((char) 0x0a)));
        assertEquals("evil?type", sanitized);
    }

    @Test
    void sanitizeForLogTruncatesLongValues() {
        String sanitized = EventSubNotificationRouter.sanitizeForLog("a".repeat(200));

        assertTrue(sanitized.length() < 200);
        assertTrue(sanitized.endsWith("..."));
    }
}
