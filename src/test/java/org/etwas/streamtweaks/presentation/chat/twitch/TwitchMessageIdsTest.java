package org.etwas.streamtweaks.presentation.chat.twitch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TwitchMessageIdsTest {

    @Test
    void acceptsUuidLikeIds() {
        assertTrue(TwitchMessageIds.isValid("e5b1c1a0-1c1e-4f5a-9f1b-2a3c4d5e6f70"));
    }

    @Test
    void acceptsShortAlphanumericIds() {
        assertTrue(TwitchMessageIds.isValid("abc-123"));
        assertTrue(TwitchMessageIds.isValid("abc_123"));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertFalse(TwitchMessageIds.isValid(null));
        assertFalse(TwitchMessageIds.isValid(""));
    }

    @Test
    void rejectsIdsLongerThanTheLimit() {
        assertTrue(TwitchMessageIds.isValid("a".repeat(TwitchMessageIds.MAX_LENGTH)));
        assertFalse(TwitchMessageIds.isValid("a".repeat(TwitchMessageIds.MAX_LENGTH + 1)));
    }

    @Test
    void rejectsControlCharacters() {
        assertFalse(TwitchMessageIds.isValid("abc\ndef"));
        assertFalse(TwitchMessageIds.isValid("abc\rdef"));
        assertFalse(TwitchMessageIds.isValid("abc\tdef"));
        assertFalse(TwitchMessageIds.isValid("abc" + (char) 0x00 + "def"));
        assertFalse(TwitchMessageIds.isValid("abc" + (char) 0x1b + "def"));
    }

    @Test
    void rejectsCharactersOutsideTheAllowedSet() {
        assertFalse(TwitchMessageIds.isValid("abc def"));
        assertFalse(TwitchMessageIds.isValid("abc/def"));
        assertFalse(TwitchMessageIds.isValid("abc.def"));
        assertFalse(TwitchMessageIds.isValid("abc:def"));
    }
}
