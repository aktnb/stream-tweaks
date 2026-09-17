package org.etwas.streamtweaks.twitch.subscription.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageNotificationTest {

    private final Gson gson = new Gson();

    @Test
    void deserializesFragmentsWithMixedEmoteAndText() {
        String json =
                """
                {
                  "text": "Hello PogChamp world",
                  "fragments": [
                    {
                      "type": "text",
                      "text": "Hello ",
                      "emote": null
                    },
                    {
                      "type": "emote",
                      "text": "PogChamp",
                      "emote": {
                        "id": "305954156",
                        "emote_set_id": "301590448",
                        "owner_id": "112233",
                        "format": ["static", "animated"]
                      }
                    },
                    {
                      "type": "text",
                      "text": " world",
                      "emote": null
                    }
                  ]
                }
                """;

        ChatMessageNotification.Message message = gson.fromJson(json, ChatMessageNotification.Message.class);

        assertEquals("Hello PogChamp world", message.text());
        assertNotNull(message.fragments());
        assertEquals(3, message.fragments().size());

        ChatMessageNotification.Fragment textFragment = message.fragments().get(0);
        assertEquals("text", textFragment.type());
        assertEquals("Hello ", textFragment.text());
        assertFalse(textFragment.isEmote());
        assertNull(textFragment.emote());

        ChatMessageNotification.Fragment emoteFragment = message.fragments().get(1);
        assertEquals("emote", emoteFragment.type());
        assertEquals("PogChamp", emoteFragment.text());
        assertTrue(emoteFragment.isEmote());
        assertNotNull(emoteFragment.emote());
        assertEquals("305954156", emoteFragment.emote().id());
        assertEquals("301590448", emoteFragment.emote().emoteSetId());
        assertEquals("112233", emoteFragment.emote().ownerId());
        assertEquals(List.of("static", "animated"), emoteFragment.emote().format());
    }

    @Test
    void deserializesMessageWithNullFragments() {
        String json =
                """
                {
                  "text": "Hello world"
                }
                """;

        ChatMessageNotification.Message message = gson.fromJson(json, ChatMessageNotification.Message.class);

        assertEquals("Hello world", message.text());
        assertNull(message.fragments());
    }

    @Test
    void deserializesMessageWithEmptyFragments() {
        String json =
                """
                {
                  "text": "",
                  "fragments": []
                }
                """;

        ChatMessageNotification.Message message = gson.fromJson(json, ChatMessageNotification.Message.class);

        assertNotNull(message.fragments());
        assertTrue(message.fragments().isEmpty());
    }

    @Test
    void deserializesTextFragmentWithNullEmote() {
        String json =
                """
                {
                  "text": "just text",
                  "fragments": [
                    {
                      "type": "text",
                      "text": "just text",
                      "emote": null
                    }
                  ]
                }
                """;

        ChatMessageNotification.Message message = gson.fromJson(json, ChatMessageNotification.Message.class);

        ChatMessageNotification.Fragment fragment = message.fragments().get(0);
        assertEquals("text", fragment.type());
        assertEquals("just text", fragment.text());
        assertFalse(fragment.isEmote());
        assertNull(fragment.emote());
    }

    @Test
    void deserializesEmoteWithNullId() {
        String json =
                """
                {
                  "text": "PogChamp",
                  "fragments": [
                    {
                      "type": "emote",
                      "text": "PogChamp",
                      "emote": {
                        "emote_set_id": "301590448",
                        "owner_id": "112233"
                      }
                    }
                  ]
                }
                """;

        ChatMessageNotification.Message message = gson.fromJson(json, ChatMessageNotification.Message.class);

        ChatMessageNotification.Fragment fragment = message.fragments().get(0);
        assertTrue(fragment.isEmote());
        assertNull(fragment.emote().id());
        assertNull(fragment.emote().format());
    }

    @Test
    void deserializesChatMessageEventWithColor() {
        String json =
                """
                {
                  "broadcaster_user_id": "12345",
                  "broadcaster_user_login": "broadcaster",
                  "broadcaster_user_name": "Broadcaster",
                  "chatter_user_id": "67890",
                  "chatter_user_login": "chatter",
                  "chatter_user_name": "Chatter",
                  "message_id": "abc-123",
                  "message": {
                    "text": "Hello world"
                  },
                  "color": "#1E90FF"
                }
                """;

        ChatMessageNotification.ChatMessageEvent event =
                gson.fromJson(json, ChatMessageNotification.ChatMessageEvent.class);

        assertEquals("12345", event.broadcasterUserId());
        assertEquals("chatter", event.chatterUserLogin());
        assertEquals("Chatter", event.chatterUserName());
        assertEquals("Hello world", event.message().text());
        assertEquals("#1E90FF", event.color());
    }

    @Test
    void deserializesChatMessageEventWithBadges() {
        String json =
                """
                {
                  "broadcaster_user_id": "12345",
                  "broadcaster_user_login": "broadcaster",
                  "broadcaster_user_name": "Broadcaster",
                  "chatter_user_id": "67890",
                  "chatter_user_login": "chatter",
                  "chatter_user_name": "Chatter",
                  "message_id": "abc-123",
                  "message": {
                    "text": "Hello world"
                  },
                  "color": "#1E90FF",
                  "badges": [
                    {"set_id": "moderator", "id": "1", "info": ""},
                    {"set_id": "subscriber", "id": "3", "info": "9"}
                  ]
                }
                """;

        ChatMessageNotification.ChatMessageEvent event =
                gson.fromJson(json, ChatMessageNotification.ChatMessageEvent.class);

        assertNotNull(event.badges());
        assertEquals(2, event.badges().size());
        assertEquals("moderator", event.badges().get(0).setId());
        assertEquals("1", event.badges().get(0).id());
        assertEquals("", event.badges().get(0).info());
        assertEquals("subscriber", event.badges().get(1).setId());
        assertEquals("3", event.badges().get(1).id());
        assertEquals("9", event.badges().get(1).info());
    }

    @Test
    void deserializesChatMessageEventWithNullBadges() {
        String json =
                """
                {
                  "broadcaster_user_id": "12345",
                  "broadcaster_user_login": "broadcaster",
                  "broadcaster_user_name": "Broadcaster",
                  "chatter_user_id": "67890",
                  "chatter_user_login": "chatter",
                  "chatter_user_name": "Chatter",
                  "message_id": "abc-123",
                  "message": {
                    "text": "Hello world"
                  },
                  "color": "#1E90FF"
                }
                """;

        ChatMessageNotification.ChatMessageEvent event =
                gson.fromJson(json, ChatMessageNotification.ChatMessageEvent.class);

        assertNull(event.badges());
    }

    @Test
    void deserializesChatMessageEventWithEmptyColor() {
        String json =
                """
                {
                  "broadcaster_user_id": "12345",
                  "broadcaster_user_login": "broadcaster",
                  "broadcaster_user_name": "Broadcaster",
                  "chatter_user_id": "67890",
                  "chatter_user_login": "chatter",
                  "chatter_user_name": "Chatter",
                  "message_id": "abc-123",
                  "message": {
                    "text": "Hello world"
                  },
                  "color": ""
                }
                """;

        ChatMessageNotification.ChatMessageEvent event =
                gson.fromJson(json, ChatMessageNotification.ChatMessageEvent.class);

        assertEquals("", event.color());
    }
}
