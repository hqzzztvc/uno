package com.legallynotuno.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The draw pile: a shuffled standard 108-card deck. When it runs dry it reshuffles
 * the discard pile (all but the current top card) back in.
 *
 * <p>When there is genuinely nothing left to draw — an empty draw pile <em>and</em> a
 * discard pile with nothing under the top card — {@link #draw} returns {@code null}.
 * It must never rebuild a fresh deck to cover that case: players are still holding cards
 * from the current one, so a rebuild puts a second copy of every card into play.
 */
public final class Deck {

    /** Cards in a standard deck that are not plain numbers (skip/reverse/+2/wilds). */
    public static final int NON_NUMBER_COUNT = 32;

    /** Cards in a full standard deck. */
    public static final int FULL_SIZE = 108;

    private final Deque<Card> draw = new ArrayDeque<>();

    public Deck() {
        reset();
    }

    /** Rebuild a full shuffled 108-card deck. Only valid when no hands are dealt. */
    public void reset() {
        List<Card> cards = standardDeck();
        Collections.shuffle(cards, ThreadLocalRandom.current());
        draw.clear();
        draw.addAll(cards);
    }

    /** The 108 cards of a standard deck (unshuffled). */
    public static List<Card> standardDeck() {
        List<Card> d = new ArrayList<>(FULL_SIZE);
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
     * Draw the top card, or {@code null} if the deck is exhausted and there is nothing in
     * the discard pile to recycle. {@code discard} is the live discard list (may be mutated:
     * everything under the top card is shuffled back into the draw pile).
     *
     * <p>A {@code null} means "no card available" and the caller must handle it — drawing
     * simply doesn't happen. Rebuilding the deck here would duplicate every card still in
     * a player's hand.
     */
    public Card draw(List<Card> discard) {
        if (draw.isEmpty()) {
            recycle(discard);
        }
        return draw.poll();
    }

    /** Shuffle everything below the discard top back into the draw pile. */
    private void recycle(List<Card> discard) {
        if (discard == null || discard.size() <= 1) {
            return; // nothing to recycle — the caller gets null
        }
        Card top = discard.get(discard.size() - 1);
        List<Card> rest = new ArrayList<>(discard.subList(0, discard.size() - 1));
        discard.clear();
        discard.add(top);
        Collections.shuffle(rest, ThreadLocalRandom.current());
        draw.addAll(rest);
    }

    /**
     * Put cards back into the draw pile and reshuffle. Used when a card is drawn and then
     * rejected (the starting flip skips action cards) — without this the deck silently
     * shrinks by a few cards every hand.
     */
    public void returnCards(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        List<Card> all = new ArrayList<>(draw);
        all.addAll(cards);
        Collections.shuffle(all, ThreadLocalRandom.current());
        draw.clear();
        draw.addAll(all);
    }

    public int size() {
        return draw.size();
    }

    public boolean isEmpty() {
        return draw.isEmpty();
    }
}
