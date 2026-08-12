package com.unoplugin.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The draw pile: a shuffled standard 108-card UNO deck. When it runs dry it reshuffles
 * the discard pile (all but the current top card) back in.
 */
public final class Deck {

    private final Deque<Card> draw = new ArrayDeque<>();

    public Deck() {
        reset();
    }

    /** Rebuild a full shuffled 108-card deck. */
    public void reset() {
        List<Card> cards = standardDeck();
        Collections.shuffle(cards, ThreadLocalRandom.current());
        draw.clear();
        draw.addAll(cards);
    }

    /** The 108 cards of a standard UNO deck (unshuffled). */
    public static List<Card> standardDeck() {
        List<Card> d = new ArrayList<>(108);
        for (Card.Color c : new Card.Color[]{Card.Color.RED, Card.Color.GREEN, Card.Color.BLUE, Card.Color.YELLOW}) {
            d.add(Card.number(c, 0)); // one 0 per colour
            for (int n = 1; n <= 9; n++) {
                d.add(Card.number(c, n)); // two each of 1-9
                d.add(Card.number(c, n));
            }
            for (Card.Kind k : new Card.Kind[]{Card.Kind.SKIP, Card.Kind.REVERSE, Card.Kind.DRAW2}) {
                d.add(Card.action(c, k)); // two each of skip/reverse/+2
                d.add(Card.action(c, k));
            }
        }
        for (int i = 0; i < 4; i++) {
            d.add(Card.WILD);
            d.add(Card.WILD_DRAW4);
        }
        return d;
    }

    /**
     * Draw the top card. If empty, reshuffle the discard pile (keeping its current top)
     * back into the draw pile. {@code discard} is the live discard list (may be mutated).
     */
    public Card draw(List<Card> discard) {
        if (draw.isEmpty()) {
            if (discard != null && discard.size() > 1) {
                Card top = discard.get(discard.size() - 1);
                List<Card> rest = new ArrayList<>(discard.subList(0, discard.size() - 1));
                discard.clear();
                discard.add(top);
                Collections.shuffle(rest, ThreadLocalRandom.current());
                draw.addAll(rest);
            } else {
                reset(); // safety net — shouldn't normally happen
            }
        }
        return draw.poll();
    }

    public int size() {
        return draw.size();
    }
}
