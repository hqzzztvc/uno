package com.unoplugin.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The card value type: naming (the universal id), parsing and the legality matrix. */
class CardTest {

    @Test
    @DisplayName("every card in a standard deck survives a name/parse round trip")
    void nameParseRoundTrip() {
        for (Card c : Deck.standardDeck()) {
            assertEquals(c, Card.parse(c.name()),
                    "round trip failed for " + c.name());
        }
    }

    @Test
    @DisplayName("names match the resource-pack texture keys")
    void names() {
        assertEquals("red_5", Card.number(Card.Color.RED, 5).name());
        assertEquals("green_skip", Card.action(Card.Color.GREEN, Card.Kind.SKIP).name());
        assertEquals("blue_reverse", Card.action(Card.Color.BLUE, Card.Kind.REVERSE).name());
        assertEquals("yellow_draw2", Card.action(Card.Color.YELLOW, Card.Kind.DRAW2).name());
        assertEquals("wild", Card.WILD.name());
        assertEquals("wild_draw4", Card.WILD_DRAW4.name());
    }

    @Test
    @DisplayName("labels read as human text")
    void labels() {
        assertEquals("Red 5", Card.number(Card.Color.RED, 5).label());
        assertEquals("Green Skip", Card.action(Card.Color.GREEN, Card.Kind.SKIP).label());
        assertEquals("Yellow +2", Card.action(Card.Color.YELLOW, Card.Kind.DRAW2).label());
        assertEquals("Wild +4", Card.WILD_DRAW4.label());
    }

    @Test
    @DisplayName("equality is by value, not identity")
    void valueEquality() {
        assertEquals(Card.number(Card.Color.RED, 5), Card.number(Card.Color.RED, 5));
        assertEquals(Card.number(Card.Color.RED, 5).hashCode(),
                Card.number(Card.Color.RED, 5).hashCode());
        assertNotEquals(Card.number(Card.Color.RED, 5), Card.number(Card.Color.RED, 6));
        assertNotEquals(Card.number(Card.Color.RED, 5), Card.number(Card.Color.BLUE, 5));
        assertNotEquals(Card.action(Card.Color.RED, Card.Kind.SKIP),
                Card.action(Card.Color.RED, Card.Kind.REVERSE));
    }

    @Test
    @DisplayName("a wild is always playable")
    void wildAlwaysPlayable() {
        Card top = Card.number(Card.Color.RED, 5);
        assertTrue(Card.WILD.playableOn(top, Card.Color.RED));
        assertTrue(Card.WILD.playableOn(top, Card.Color.BLUE));
        assertTrue(Card.WILD_DRAW4.playableOn(top, Card.Color.GREEN));
    }

    @Test
    @DisplayName("matching the active colour is always legal")
    void colourMatch() {
        Card top = Card.number(Card.Color.RED, 5);
        assertTrue(Card.number(Card.Color.RED, 9).playableOn(top, Card.Color.RED));
        assertTrue(Card.action(Card.Color.RED, Card.Kind.SKIP).playableOn(top, Card.Color.RED));
    }

    @Test
    @DisplayName("numbers match numbers, symbols match symbols")
    void rankMatch() {
        Card top = Card.number(Card.Color.RED, 5);
        assertTrue(Card.number(Card.Color.BLUE, 5).playableOn(top, Card.Color.RED));
        assertFalse(Card.number(Card.Color.BLUE, 6).playableOn(top, Card.Color.RED));
        assertFalse(Card.action(Card.Color.BLUE, Card.Kind.SKIP).playableOn(top, Card.Color.RED));

        Card skipTop = Card.action(Card.Color.RED, Card.Kind.SKIP);
        assertTrue(Card.action(Card.Color.BLUE, Card.Kind.SKIP).playableOn(skipTop, Card.Color.RED));
        assertFalse(Card.action(Card.Color.BLUE, Card.Kind.REVERSE).playableOn(skipTop, Card.Color.RED));
        assertFalse(Card.number(Card.Color.BLUE, 5).playableOn(skipTop, Card.Color.RED));
    }

    @Test
    @DisplayName("the active colour beats the printed colour of the top card")
    void activeColourWinsOverTopCard() {
        // A wild was played on a red top and blue chosen: blue cards are now legal, red are not.
        Card top = Card.WILD;
        assertTrue(Card.number(Card.Color.BLUE, 3).playableOn(top, Card.Color.BLUE));
        assertFalse(Card.number(Card.Color.RED, 3).playableOn(top, Card.Color.BLUE));
    }
}
