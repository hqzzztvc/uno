package com.legallynotuno.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules engine. */
class UnoGameTest {

    private static List<UUID> players(int n) {
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static UnoGame started(int playerCount) {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(playerCount));
        game.start();
        return game;
    }

    /** Every card in the game, wherever it is. Must always be exactly one deck. */
    private static int totalCards(UnoGame game) {
        int n = game.drawPileSize() + game.discardSize();
        for (UUID p : game.players()) {
            n += game.handSize(p);
        }
        return n;
    }

    // ------------------------------------------------------------------ dealing

    @Test
    @DisplayName("start deals seven each and flips one card")
    void startDeals() {
        UnoGame game = started(4);
        for (UUID p : game.players()) {
            assertEquals(7, game.handSize(p));
        }
        assertEquals(1, game.discardSize());
        assertEquals(108 - 28 - 1, game.drawPileSize());
        assertFalse(game.isOver());
        assertNull(game.winner());
    }

    @Test
    @DisplayName("the starting card is always an effect-free number")
    void startingCardIsANumber() {
        for (int i = 0; i < 300; i++) {
            UnoGame game = started(4);
            assertEquals(Card.Kind.NUMBER, game.top().kind(),
                    "the flipped starting card must not have an effect");
            assertSame(game.top().color(), game.activeColor());
        }
    }

    @Test
    @DisplayName("start never destroys cards while looking for a starting number")
    void startConservesTheDeck() {
        // Finding 10: action cards turned over on the way to the first number used to be
        // dropped on the floor — neither discarded nor returned — shrinking the deck.
        for (int i = 0; i < 300; i++) {
            UnoGame game = started(4);
            assertEquals(108, totalCards(game),
                    "a freshly started game must still hold exactly 108 cards");
        }
    }

    @Test
    @DisplayName("a huge configured hand size is clamped so the deal can't empty the deck")
    void oversizedHandsAreClamped() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(6));
        game.start(50); // 6 x 50 = 300 cards from a 108-card deck
        assertEquals(108, totalCards(game));
        assertEquals(Card.Kind.NUMBER, game.top().kind());
        for (UUID p : game.players()) {
            assertTrue(game.handSize(p) > 0 && game.handSize(p) <= 50);
        }
    }

    @Test
    @DisplayName("a small configured hand size is honoured")
    void smallHandSizeHonoured() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(4));
        game.start(3);
        for (UUID p : game.players()) {
            assertEquals(3, game.handSize(p));
        }
        assertEquals(108, totalCards(game));
    }

    // ------------------------------------------------------------------- guards

    @Test
    @DisplayName("only the player whose turn it is may act")
    void turnIsEnforced() {
        UnoGame game = started(3);
        UUID other = game.players().get(1);
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, game.play(other, 0).status);
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, game.draw(other).status);
    }

    @Test
    @DisplayName("out-of-range card indices are rejected")
    void badIndexRejected() {
        UnoGame game = started(3);
        UUID current = game.currentPlayer();
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, game.play(current, -1).status);
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, game.play(current, 99).status);
    }

    @Test
    @DisplayName("an illegal card is refused and stays in hand")
    void illegalCardRefused() {
        UnoGame game = started(3);
        UUID current = game.currentPlayer();
        int before = game.handSize(current);
        List<Integer> legal = game.legalIndices(current);
        for (int i = 0; i < before; i++) {
            if (!legal.contains(i)) {
                assertEquals(UnoGame.PlayResult.Status.ILLEGAL, game.play(current, i).status);
                assertEquals(before, game.handSize(current));
                return;
            }
        }
    }

    @Test
    @DisplayName("drawing hands one card over and passes the turn")
    void drawAddsOneAndPasses() {
        UnoGame game = started(3);
        UUID current = game.currentPlayer();
        int before = game.handSize(current);
        int deckBefore = game.drawPileSize();

        UnoGame.PlayResult r = game.draw(current);
        assertEquals(UnoGame.PlayResult.Status.DREW, r.status);
        assertNotNull(r.card);
        assertEquals(before + 1, game.handSize(current));
        assertEquals(deckBefore - 1, game.drawPileSize());
        assertFalse(current.equals(game.currentPlayer()), "the turn should have passed");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("a wild blocks play until its colour is chosen")
    void wildBlocksUntilColourChosen() {
        UnoGame game = started(3);
        UUID current = game.currentPlayer();
        int wildIndex = -1;
        List<String> hand = game.handNames(current);
        for (int i = 0; i < hand.size(); i++) {
            if (Card.parse(hand.get(i)).isWild()) {
                wildIndex = i;
                break;
            }
        }
        if (wildIndex < 0) {
            return; // this deal had no wild; the fuzz test covers the path anyway
        }
        assertEquals(UnoGame.PlayResult.Status.NEED_COLOR, game.play(current, wildIndex).status);
        assertEquals(current, game.pendingColorPlayer());
        // Nothing may happen until the colour lands.
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, game.draw(game.currentPlayer()).status);

        game.chooseColor(current, Card.Color.BLUE);
        assertEquals(Card.Color.BLUE, game.activeColor());
        assertNull(game.pendingColorPlayer());
        assertEquals(108, totalCards(game));
    }

    // ------------------------------------------------------------------ forfeit

    @Test
    @DisplayName("a forfeit drops the player and banks their cards under the discard top")
    void forfeitBanksCards() {
        UnoGame game = started(4);
        UUID leaver = game.players().get(1);
        Card topBefore = game.top();

        game.forfeit(leaver);

        assertEquals(3, game.players().size());
        assertFalse(game.players().contains(leaver));
        assertEquals(topBefore, game.top(), "the live top card must stay on top");
        assertEquals(108, totalCards(game), "a leaver's cards stay in the game");
        assertFalse(game.isOver());
    }

    @Test
    @DisplayName("the turn stays on a valid player through forfeits")
    void forfeitKeepsTurnValid() {
        UnoGame game = started(5);
        game.forfeit(game.players().get(0));
        assertTrue(game.players().contains(game.currentPlayer()));
        game.forfeit(game.players().get(2));
        assertTrue(game.players().contains(game.currentPlayer()));
        game.forfeit(game.currentPlayer());
        assertTrue(game.players().contains(game.currentPlayer()));
    }

    @Test
    @DisplayName("last player standing wins")
    void lastStandingWins() {
        UnoGame game = started(3);
        UUID survivor = game.players().get(2);
        game.forfeit(game.players().get(0));
        game.forfeit(game.players().get(0));
        assertTrue(game.isOver());
        assertEquals(survivor, game.winner());
    }

    @Test
    @DisplayName("everyone leaving ends the game with no winner")
    void everyoneLeavingEndsGame() {
        UnoGame game = started(2);
        game.forfeit(game.players().get(0));
        assertTrue(game.isOver());
        // One survivor won; further forfeits are ignored on a finished game.
        assertNotNull(game.winner());
    }

    @Test
    @DisplayName("forfeiting the pending-colour player unblocks the game")
    void forfeitClearsPendingColour() {
        UnoGame game = started(3);
        UUID current = game.currentPlayer();
        int wildIndex = -1;
        List<String> hand = game.handNames(current);
        for (int i = 0; i < hand.size(); i++) {
            if (Card.parse(hand.get(i)).isWild()) {
                wildIndex = i;
                break;
            }
        }
        if (wildIndex < 0) {
            return;
        }
        game.play(current, wildIndex);
        game.forfeit(current);
        assertNull(game.pendingColorPlayer(), "a leaver must not hold the table hostage");
    }

    // ------------------------------------------------------- the last card bites

    /**
     * Deal one card each and keep dealing until the opener holds a legal +2. That is the whole
     * scenario: the +2 both wins the hand and lands on somebody, and the win used to be
     * declared first, which threw the penalty away.
     */
    @Test
    @DisplayName("a +2 played as the last card still makes the next player draw")
    void winningDrawTwoStillPenalises() {
        for (int attempt = 0; attempt < 2000; attempt++) {
            UnoGame game = new UnoGame(UUID.randomUUID(), players(2));
            game.start(1);
            UUID actor = game.currentPlayer();
            List<Integer> legal = game.legalIndices(actor);
            if (legal.isEmpty()) {
                continue;
            }
            int index = legal.get(0);
            if (Card.parse(game.handNames(actor).get(index)).kind() != Card.Kind.DRAW2) {
                continue;
            }
            UUID victim = game.players().get(1);
            int before = game.handSize(victim);

            UnoGame.PlayResult r = game.play(actor, index);

            assertEquals(UnoGame.PlayResult.Status.WIN, r.status);
            assertEquals(actor, game.winner());
            assertEquals(victim, r.target, "the +2 still had somebody to land on");
            assertEquals(before + 2, game.handSize(victim), "the winning +2 must still be dealt");
            assertEquals(108, totalCards(game));
            return;
        }
        throw new AssertionError("2000 deals never produced a legal +2 in a one-card hand");
    }

    /** Same rule through the wild path, where the penalty lands only after the colour is picked. */
    @Test
    @DisplayName("a Wild +4 played as the last card still makes the next player draw")
    void winningWildDrawFourStillPenalises() {
        for (int attempt = 0; attempt < 2000; attempt++) {
            UnoGame game = new UnoGame(UUID.randomUUID(), players(2));
            game.start(1);
            UUID actor = game.currentPlayer();
            if (!"wild_draw4".equals(game.handNames(actor).get(0))) {
                continue;
            }
            UUID victim = game.players().get(1);
            int before = game.handSize(victim);

            assertEquals(UnoGame.PlayResult.Status.NEED_COLOR, game.play(actor, 0).status);
            UnoGame.PlayResult r = game.chooseColor(actor, Card.Color.BLUE);

            assertEquals(UnoGame.PlayResult.Status.WIN, r.status);
            assertEquals(victim, r.target);
            assertEquals(before + 4, game.handSize(victim), "the winning +4 must still be dealt");
            assertEquals(108, totalCards(game));
            return;
        }
        throw new AssertionError("2000 deals never dealt a wild +4 as a one-card hand");
    }

    @Test
    @DisplayName("a finished game accepts no more colour choices")
    void chooseColourRejectedAfterTheGameEnds() {
        UnoGame game = started(2);
        game.forfeit(game.players().get(0)); // last player standing wins
        assertTrue(game.isOver());
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL,
                game.chooseColor(game.players().get(0), Card.Color.RED).status);
    }

    // -------------------------------------------------------------- exhaustion

    @Test
    @DisplayName("an exhausted deck passes the turn instead of duplicating cards")
    void exhaustedDeckPassesTheTurn() {
        // Finding 11: the deck used to rebuild itself here, putting a second copy of every
        // card players were holding into play.
        UnoGame game = started(2);
        int guard = 0;
        while (game.drawPileSize() > 0 && guard++ < 500) {
            game.draw(game.currentPlayer());
        }
        assertEquals(0, game.drawPileSize());
        assertEquals(108, totalCards(game));

        UnoGame.PlayResult r = game.draw(game.currentPlayer());
        assertEquals(UnoGame.PlayResult.Status.DREW, r.status);
        assertNull(r.card, "there was no card to give");
        assertEquals(108, totalCards(game), "no card may be conjured out of nothing");
    }

    // ------------------------------------------------------------------- fuzz

    @RepeatedTest(40)
    @DisplayName("a full random game stays legal and terminates with one deck of cards")
    void randomGameHoldsInvariants() {
        Random rng = new Random();
        UnoGame game = started(2 + rng.nextInt(5));
        int moves = 0;

        while (!game.isOver() && moves++ < 4000) {
            assertEquals(108, totalCards(game), "card count drifted after " + moves + " moves");
            assertTrue(game.players().contains(game.currentPlayer()), "turn landed off the table");
            assertNotNull(game.top());

            UUID actor = game.currentPlayer();
            List<Integer> legal = game.legalIndices(actor);
            UnoGame.PlayResult r = legal.isEmpty()
                    ? game.draw(actor)
                    : game.play(actor, legal.get(rng.nextInt(legal.size())));

            if (r.status == UnoGame.PlayResult.Status.NEED_COLOR) {
                r = game.chooseColor(actor, Card.Color.values()[rng.nextInt(4)]);
            }
            assertFalse(r.status == UnoGame.PlayResult.Status.ILLEGAL,
                    "a move chosen from legalIndices was rejected: " + r.message);
        }

        assertTrue(game.isOver(), "the game should have finished inside 4000 moves");
        assertEquals(108, totalCards(game));
        if (game.winner() != null) {
            assertEquals(0, game.handSize(game.winner()), "the winner should be out of cards");
        }
    }

    @Test
    @DisplayName("the move counter advances on every state change")
    void moveCounterAdvances() {
        UnoGame game = started(3);
        assertEquals(0, game.moveCount());
        game.draw(game.currentPlayer());
        assertEquals(1, game.moveCount());
        int before = game.moveCount();
        game.draw(game.players().get((game.players().indexOf(game.currentPlayer()) + 1) % 3));
        assertEquals(before, game.moveCount(), "a rejected move changes nothing");
        game.forfeit(game.currentPlayer());
        assertTrue(game.moveCount() > before);
    }
}
