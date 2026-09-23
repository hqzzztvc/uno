package com.legallynotuno.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The draw pile — composition, recycling, and the "never fabricate cards" rule. */
class DeckTest {

    private static Map<String, Integer> census(List<Card> cards) {
        Map<String, Integer> counts = new HashMap<>();
        for (Card c : cards) {
            counts.merge(c.name(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    @DisplayName("a standard deck is 108 cards with the right multiplicities")
    void standardComposition() {
        List<Card> deck = Deck.standardDeck();
        assertEquals(108, deck.size());

        Map<String, Integer> counts = census(deck);
        for (String colour : new String[]{"red", "green", "blue", "yellow"}) {
            assertEquals(1, counts.get(colour + "_0"), colour + " should have one 0");
            for (int n = 1; n <= 9; n++) {
                assertEquals(2, counts.get(colour + "_" + n), colour + " should have two " + n + "s");
            }
            assertEquals(2, counts.get(colour + "_skip"));
            assertEquals(2, counts.get(colour + "_reverse"));
            assertEquals(2, counts.get(colour + "_draw2"));
        }
        assertEquals(4, counts.get("wild"));
        assertEquals(4, counts.get("wild_draw4"));
    }

    @Test
    @DisplayName("drawing the whole deck yields exactly the standard deck, once")
    void drawsEveryCardExactlyOnce() {
        Deck deck = new Deck();
        List<Card> discard = new ArrayList<>();
        List<Card> drawn = new ArrayList<>();
        for (int i = 0; i < 108; i++) {
            Card c = deck.draw(discard);
            assertNotNull(c, "deck ran dry after " + i + " draws");
            drawn.add(c);
        }
        assertEquals(census(Deck.standardDeck()), census(drawn));
        assertEquals(0, deck.size());
    }

    @Test
    @DisplayName("an exhausted deck returns null instead of conjuring a fresh one")
    void exhaustedDeckReturnsNull() {
        // Finding 11: reset() here would put a second copy of every card into play.
        Deck deck = new Deck();
        List<Card> discard = new ArrayList<>();
        for (int i = 0; i < 108; i++) {
            deck.draw(discard);
        }
        assertNull(deck.draw(discard), "empty deck + empty discard must yield null");
        assertNull(deck.draw(List.of()), "a one-card discard has nothing under the top to recycle");
        assertEquals(0, deck.size());
    }

    @Test
    @DisplayName("an empty deck recycles the discard pile, keeping its top card")
    void recyclesDiscard() {
        Deck deck = new Deck();
        List<Card> discard = new ArrayList<>();
        List<Card> drawn = new ArrayList<>();
        for (int i = 0; i < 108; i++) {
            drawn.add(deck.draw(discard));
        }
        // Play them all out onto the discard pile.
        discard.addAll(drawn);
        Card top = discard.get(discard.size() - 1);

        Card next = deck.draw(discard);
        assertNotNull(next, "the discard pile should have been recycled");
        assertEquals(1, discard.size(), "only the top card stays on the discard pile");
        assertEquals(top, discard.get(0), "the live top card must not be recycled away");
        assertEquals(106, deck.size(), "107 recycled, one just drawn");
    }

    @Test
    @DisplayName("returned cards go back into the pile rather than vanishing")
    void returnCardsRestoresSize() {
        Deck deck = new Deck();
        List<Card> discard = new ArrayList<>();
        List<Card> pulled = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pulled.add(deck.draw(discard));
        }
        assertEquals(103, deck.size());
        deck.returnCards(pulled);
        assertEquals(108, deck.size());

        List<Card> all = new ArrayList<>();
        Card c;
        while ((c = deck.draw(discard)) != null) {
            all.add(c);
        }
        assertEquals(census(Deck.standardDeck()), census(all), "the deck is whole again");
    }

    @Test
    @DisplayName("returning nothing is a no-op")
    void returnCardsHandlesEmpty() {
        Deck deck = new Deck();
        deck.returnCards(null);
        deck.returnCards(List.of());
        assertEquals(108, deck.size());
        assertTrue(!deck.isEmpty());
    }
}
