package org.etwas.streamtweaks.twitch.subscription.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class ChatMessageDeleteNotificationTest {

    private final Gson gson = new Gson();

    @Test
    void deserializesMessageDeleteNotificationPayload() {
        String json =
                """
                {
                  "subscription": {
                    "id": "sub-1",
                    "type": "channel.chat.message_delete",
                    "version": "1"
                  },
                  "event": {
                    "broadcaster_user_id": "12345",
                    "broadcaster_user_login": "streamer",
                    "broadcaster_user_name": "Streamer",
                    "target_user_id": "67890",
                    "target_user_login": "chatter",
                    "target_user_name": "Chatter",
                    "message_id": "e5b1c1a0-1c1e-4f5a-9f1b-2a3c4d5e6f70"
                  }
                }
                """;

        ChatMessageDeleteNotification notification = gson.fromJson(json, ChatMessageDeleteNotification.class);

        assertNotNull(notification.event());
        assertEquals("12345", notification.event().broadcasterUserId());
        assertEquals("67890", notification.event().targetUserId());
        assertEquals(
                "e5b1c1a0-1c1e-4f5a-9f1b-2a3c4d5e6f70", notification.event().messageId());
    }

    @Test
    void deserializesPayloadWithoutEvent() {
        ChatMessageDeleteNotification notification = gson.fromJson(
                "{\"subscription\": {\"type\": \"channel.chat.message_delete\"}}", ChatMessageDeleteNotification.class);

        assertNull(notification.event());
    }

    @Test
    void deserializesEventWithMissingFieldsAsNull() {
        ChatMessageDeleteNotification notification =
                gson.fromJson("{\"event\": {\"message_id\": \"abc-123\"}}", ChatMessageDeleteNotification.class);

        assertEquals("abc-123", notification.event().messageId());
        assertNull(notification.event().broadcasterUserId());
        assertNull(notification.event().targetUserId());
    }
}
