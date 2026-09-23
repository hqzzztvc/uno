package com.legallynotuno.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional house rules.
 *
 * <p>Every test here also asserts the 108-card invariant, because that is what every one of
 * these rules is most likely to break: stacking defers a penalty, multi-play moves several
 * cards at once, seven-O moves whole hands between players and a challenge takes cards back.
 * All four are ways to lose or duplicate a card without anything else noticing.
 *
 * <p>Hands are dealt from a real shuffled deck, so setting up a specific situation means
 * dealing until one turns up. {@link #dealUntil} is that loop; it fails loudly rather than
 * silently passing if the situation never appears.
 */
class HouseRulesTest {

    private static List<UUID> players(int n) {
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    /** Every card in the game, wherever it is. Must always be exactly one deck. */
    private static int totalCards(UnoGame game) {
        int n = game.drawPileSize() + game.discardSize();
        for (UUID p : game.players()) {
            n += game.handSize(p);
        }
        return n;
    }

    /** Deal repeatedly until a game comes up matching {@code wanted}, or give up loudly. */
    private static UnoGame dealUntil(int playerCount, int handSize, RuleSet rules,
                                     Predicate<UnoGame> wanted, String what) {
        for (int attempt = 0; attempt < 5000; attempt++) {
            UnoGame game = new UnoGame(UUID.randomUUID(), players(playerCount), rules);
            game.start(handSize);
            if (wanted.test(game)) {
                return game;
            }
        }
        throw new AssertionError("5000 deals never produced: " + what);
    }

    /** Index of the first card of this kind in the player's hand, or -1. */
    private static int indexOfKind(UnoGame game, UUID p, Card.Kind kind) {
        List<String> names = game.handNames(p);
        for (int i = 0; i < names.size(); i++) {
            if (Card.parse(names.get(i)).kind() == kind) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the first LEGAL card of this kind in the player's hand, or -1. */
    private static int legalIndexOfKind(UnoGame game, UUID p, Card.Kind kind) {
        for (int i : game.legalIndices(p)) {
            if (Card.parse(game.handNames(p).get(i)).kind() == kind) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------- stacking

    @Test
    @DisplayName("with stacking off, a +2 is dealt immediately and the next player is skipped")
    void withoutStackingThePenaltyIsImmediate() {
        UnoGame game = dealUntil(3, 7, RuleSet.VANILLA,
                g -> legalIndexOfKind(g, g.currentPlayer(), Card.Kind.DRAW2) >= 0,
                "a legal +2 in the opener's hand");
        UUID actor = game.currentPlayer();
        UUID victim = game.players().get((game.players().indexOf(actor) + 1) % 3);
        int before = game.handSize(victim);

        UnoGame.PlayResult r = game.play(actor, legalIndexOfKind(game, actor, Card.Kind.DRAW2));

        assertEquals(UnoGame.PlayResult.Status.OK, r.status);
        assertEquals(before + 2, game.handSize(victim), "the +2 should have been dealt at once");
        assertEquals(0, game.pendingDraw(), "nothing should be left pending");
        assertNotEquals(victim, game.currentPlayer(), "the victim is skipped");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("with stacking on, a +2 is held pending and lands on whoever can't answer")
    void stackingDefersThePenalty() {
        RuleSet rules = RuleSet.withStacking(true, false);
        UnoGame game = dealUntil(3, 7, rules,
                g -> legalIndexOfKind(g, g.currentPlayer(), Card.Kind.DRAW2) >= 0,
                "a legal +2 in the opener's hand");
        UUID actor = game.currentPlayer();
        UUID victim = game.players().get((game.players().indexOf(actor) + 1) % 3);
        int before = game.handSize(victim);

        UnoGame.PlayResult r = game.play(actor, legalIndexOfKind(game, actor, Card.Kind.DRAW2));

        assertEquals(UnoGame.PlayResult.Status.STACKED, r.status);
        assertEquals(2, game.pendingDraw(), "the +2 should be waiting, not dealt");
        assertEquals(before, game.handSize(victim), "nobody draws until somebody gives in");
        assertEquals(victim, game.currentPlayer(), "the victim gets a turn to answer it");
        assertEquals(victim, r.target);
        assertEquals(108, totalCards(game));

        // Giving in takes the whole pile and passes the turn.
        int owed = game.pendingDraw();
        UnoGame.PlayResult drew = game.draw(victim);
        assertEquals(UnoGame.PlayResult.Status.DREW, drew.status);
        assertEquals(before + owed, game.handSize(victim));
        assertEquals(0, game.pendingDraw());
        assertNotEquals(victim, game.currentPlayer());
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("a pending stack can only be answered with another draw card")
    void aPendingStackBlocksOrdinaryCards() {
        RuleSet rules = RuleSet.withStacking(true, false);
        UnoGame game = dealUntil(3, 7, rules,
                g -> legalIndexOfKind(g, g.currentPlayer(), Card.Kind.DRAW2) >= 0,
                "a legal +2 in the opener's hand");
        UUID actor = game.currentPlayer();
        game.play(actor, legalIndexOfKind(game, actor, Card.Kind.DRAW2));

        UUID victim = game.currentPlayer();
        for (int i : game.legalIndices(victim)) {
            Card c = Card.parse(game.handNames(victim).get(i));
            assertTrue(c.kind() == Card.Kind.DRAW2 || c.kind() == Card.Kind.WILD_DRAW4,
                    c.label() + " should not be legal against a pending stack");
        }
        // A plain number is refused even when it matches the colour.
        int number = indexOfKind(game, victim, Card.Kind.NUMBER);
        if (number >= 0) {
            UnoGame.PlayResult r = game.play(victim, number);
            assertEquals(UnoGame.PlayResult.Status.ILLEGAL, r.status);
        }
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("cross-stacking toggles decide whether a +4 may answer a +2")
    void crossStackingIsToggleable() {
        for (boolean allowed : new boolean[]{true, false}) {
            RuleSet rules = RuleSet.withStacking(allowed, false);
            UnoGame game = dealUntil(3, 7, rules,
                    g -> legalIndexOfKind(g, g.currentPlayer(), Card.Kind.DRAW2) >= 0,
                    "a legal +2 in the opener's hand");
            game.play(game.currentPlayer(), legalIndexOfKind(game, game.currentPlayer(), Card.Kind.DRAW2));
            UUID victim = game.currentPlayer();
            int wild4 = indexOfKind(game, victim, Card.Kind.WILD_DRAW4);
            if (wild4 < 0) {
                continue; // this deal can't exercise it; the other value of `allowed` might
            }
            boolean legal = game.legalIndices(victim).contains(wild4);
            assertEquals(allowed, legal,
                    "draw4-on-draw2=" + allowed + " should decide whether the +4 is playable");
            assertEquals(108, totalCards(game));
        }
    }

    @Test
    @DisplayName("stacked penalties add up as they pass round the table")
    void stackedPenaltiesAccumulate() {
        RuleSet rules = RuleSet.withStacking(true, true);
        UnoGame game = dealUntil(4, 7, rules,
                g -> legalIndexOfKind(g, g.currentPlayer(), Card.Kind.DRAW2) >= 0,
                "a legal +2 in the opener's hand");
        game.play(game.currentPlayer(), legalIndexOfKind(game, game.currentPlayer(), Card.Kind.DRAW2));
        assertEquals(2, game.pendingDraw());

        UUID second = game.currentPlayer();
        int answer = legalIndexOfKind(game, second, Card.Kind.DRAW2);
        if (answer >= 0) {
            game.play(second, answer);
            assertEquals(4, game.pendingDraw(), "two +2s owe four cards");
            assertEquals(108, totalCards(game));
        }
    }

    // ------------------------------------------------------------- multi-play

    @Test
    @DisplayName("several cards of one rank go down together and each still takes effect")
    void multiPlayLaysSeveralOfARank() {
        RuleSet rules = RuleSet.withMultiPlay(0);
        UnoGame game = dealUntil(4, 7, rules, g -> {
            UUID p = g.currentPlayer();
            List<Integer> legal = g.legalIndices(p);
            if (legal.isEmpty()) {
                return false;
            }
            Card first = Card.parse(g.handNames(p).get(legal.get(0)));
            return first.kind() == Card.Kind.NUMBER && sameRankCount(g, p, first) >= 2;
        }, "an opener holding two of the same number, one of them legal");

        UUID actor = game.currentPlayer();
        List<Integer> legal = game.legalIndices(actor);
        Card rank = Card.parse(game.handNames(actor).get(legal.get(0)));
        List<Integer> indices = indicesOfRank(game, actor, rank, legal.get(0));
        int handBefore = game.handSize(actor);
        int discardBefore = game.discardSize();

        UnoGame.PlayResult r = game.play(actor, indices);

        assertNotEquals(UnoGame.PlayResult.Status.ILLEGAL, r.status,
                r.message == null ? "" : r.message);
        assertEquals(handBefore - indices.size(), game.handSize(actor));
        assertEquals(discardBefore + indices.size(), game.discardSize(),
                "every card played has to reach the pile");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("cards of different ranks can't be played together")
    void multiPlayRejectsMixedRanks() {
        RuleSet rules = RuleSet.withMultiPlay(0);
        UnoGame game = dealUntil(3, 7, rules, g -> {
            List<String> names = g.handNames(g.currentPlayer());
            return !Card.parse(names.get(0)).equals(Card.parse(names.get(1)))
                    && Card.parse(names.get(0)).kind() != Card.parse(names.get(1)).kind();
        }, "an opener whose first two cards are different ranks");

        UnoGame.PlayResult r = game.play(game.currentPlayer(), List.of(0, 1));
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, r.status);
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("multi-play is refused entirely when the rule is off")
    void multiPlayIsOffByDefault() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.VANILLA);
        game.start(7);
        UnoGame.PlayResult r = game.play(game.currentPlayer(), List.of(0, 1));
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, r.status);
        assertTrue(r.message.contains("one card at a time"), r.message);
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("max-cards caps how many can go down at once")
    void multiPlayRespectsItsCap() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.withMultiPlay(2));
        game.start(7);
        UnoGame.PlayResult r = game.play(game.currentPlayer(), List.of(0, 1, 2));
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, r.status);
        assertTrue(r.message.contains("at most 2"), r.message);
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("the same card can't be played twice in one move")
    void multiPlayRejectsDuplicateIndices() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.withMultiPlay(0));
        game.start(7);
        UnoGame.PlayResult r = game.play(game.currentPlayer(), List.of(1, 1));
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, r.status);
        assertEquals(108, totalCards(game));
    }

    private static int sameRankCount(UnoGame game, UUID p, Card rank) {
        int n = 0;
        for (String name : game.handNames(p)) {
            Card c = Card.parse(name);
            if (c.kind() == rank.kind()
                    && (c.kind() != Card.Kind.NUMBER || c.number() == rank.number())) {
                n++;
            }
        }
        return n;
    }

    private static List<Integer> indicesOfRank(UnoGame game, UUID p, Card rank, int first) {
        List<Integer> out = new ArrayList<>();
        out.add(first);
        List<String> names = game.handNames(p);
        for (int i = 0; i < names.size(); i++) {
            if (i == first) {
                continue;
            }
            Card c = Card.parse(names.get(i));
            if (c.kind() == rank.kind()
                    && (c.kind() != Card.Kind.NUMBER || c.number() == rank.number())) {
                out.add(i);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- jump-in

    @Test
    @DisplayName("jump-in needs the exact card, and is refused when the rule is off")
    void jumpInIsExactMatchOnly() {
        UnoGame off = new UnoGame(UUID.randomUUID(), players(3), RuleSet.VANILLA);
        off.start(7);
        UUID notTurn = off.players().get(1);
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, off.jumpIn(notTurn, 0).status);

        RuleSet rules = new RuleSet(false, false, false, false, 0, true, false, false, false,
                false, 5, 2, 2);
        UnoGame game = dealUntil(3, 7, rules, g -> {
            UUID other = g.players().get(1);
            return g.handNames(other).contains(g.top().name()) && !g.top().isWild();
        }, "a non-current player holding the exact top card");

        UUID jumper = game.players().get(1);
        int idx = game.handNames(jumper).indexOf(game.top().name());
        int before = game.handSize(jumper);

        UnoGame.PlayResult r = game.jumpIn(jumper, idx);

        assertNotEquals(UnoGame.PlayResult.Status.ILLEGAL, r.status,
                r.message == null ? "" : r.message);
        assertEquals(before - 1, game.handSize(jumper));
        assertEquals(108, totalCards(game));
    }

    // ----------------------------------------------------------------- seven-O

    @Test
    @DisplayName("a 7 swaps hands with the chosen player, and the cards all survive it")
    void sevenSwapsHands() {
        RuleSet rules = new RuleSet(false, false, false, false, 0, false, true, false, false,
                false, 5, 2, 2);
        UnoGame game = dealUntil(3, 7, rules, g -> {
            UUID p = g.currentPlayer();
            for (int i : g.legalIndices(p)) {
                Card c = Card.parse(g.handNames(p).get(i));
                if (c.kind() == Card.Kind.NUMBER && c.number() == 7) {
                    return true;
                }
            }
            return false;
        }, "an opener holding a legal 7");

        UUID actor = game.currentPlayer();
        int idx = -1;
        for (int i : game.legalIndices(actor)) {
            Card c = Card.parse(game.handNames(actor).get(i));
            if (c.kind() == Card.Kind.NUMBER && c.number() == 7) {
                idx = i;
                break;
            }
        }
        UUID target = game.players().get((game.players().indexOf(actor) + 1) % 3);
        int theirsBefore = game.handSize(target);

        UnoGame.PlayResult r = game.play(actor, idx);
        assertEquals(UnoGame.PlayResult.Status.NEED_SWAP, r.status);
        assertEquals(actor, game.pendingSwapPlayer());
        // Measured AFTER the play: the 7 has already left, and it is the remaining hand that
        // gets handed over.
        int mineAfterPlaying = game.handSize(actor);

        UnoGame.PlayResult swap = game.chooseSwap(actor, target);
        assertNotEquals(UnoGame.PlayResult.Status.ILLEGAL, swap.status);
        assertEquals(theirsBefore, game.handSize(actor), "took their hand");
        assertEquals(mineAfterPlaying, game.handSize(target), "gave them mine");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("swapping with yourself is refused")
    void sevenCannotSwapWithYourself() {
        RuleSet rules = new RuleSet(false, false, false, false, 0, false, true, false, false,
                false, 5, 2, 2);
        UnoGame game = dealUntil(3, 7, rules, g -> {
            UUID p = g.currentPlayer();
            for (int i : g.legalIndices(p)) {
                Card c = Card.parse(g.handNames(p).get(i));
                if (c.kind() == Card.Kind.NUMBER && c.number() == 7) {
                    return true;
                }
            }
            return false;
        }, "an opener holding a legal 7");
        UUID actor = game.currentPlayer();
        int idx = -1;
        for (int i : game.legalIndices(actor)) {
            Card c = Card.parse(game.handNames(actor).get(i));
            if (c.kind() == Card.Kind.NUMBER && c.number() == 7) {
                idx = i;
                break;
            }
        }
        game.play(actor, idx);
        assertEquals(UnoGame.PlayResult.Status.ILLEGAL, game.chooseSwap(actor, actor).status);
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("a 0 moves every hand one seat and loses nothing")
    void zeroRotatesEveryHand() {
        RuleSet rules = new RuleSet(false, false, false, false, 0, false, true, false, false,
                false, 5, 2, 2);
        UnoGame game = dealUntil(3, 7, rules, g -> {
            UUID p = g.currentPlayer();
            for (int i : g.legalIndices(p)) {
                Card c = Card.parse(g.handNames(p).get(i));
                if (c.kind() == Card.Kind.NUMBER && c.number() == 0) {
                    return true;
                }
            }
            return false;
        }, "an opener holding a legal 0");

        UUID actor = game.currentPlayer();
        int idx = -1;
        for (int i : game.legalIndices(actor)) {
            Card c = Card.parse(game.handNames(actor).get(i));
            if (c.kind() == Card.Kind.NUMBER && c.number() == 0) {
                idx = i;
                break;
            }
        }
        List<Integer> sizesBefore = new ArrayList<>();
        for (UUID p : game.players()) {
            sizesBefore.add(game.handSize(p));
        }
        sizesBefore.set(game.players().indexOf(actor), sizesBefore.get(game.players().indexOf(actor)) - 1);

        game.play(actor, idx);

        int total = 0;
        for (UUID p : game.players()) {
            total += game.handSize(p);
        }
        int expected = 0;
        for (int n : sizesBefore) {
            expected += n;
        }
        assertEquals(expected, total, "a rotation must not create or destroy a card");
        assertEquals(108, totalCards(game));
    }

    // ----------------------------------------------------------- draw to match

    @Test
    @DisplayName("draw-to-match keeps drawing until something is playable, and keeps the turn")
    void drawToMatchKeepsTheTurn() {
        RuleSet rules = new RuleSet(false, false, false, false, 0, false, false, true, false,
                false, 5, 2, 2);
        UnoGame game = dealUntil(3, 7, rules,
                g -> g.legalIndices(g.currentPlayer()).isEmpty(),
                "an opener with nothing playable");

        UUID actor = game.currentPlayer();
        int before = game.handSize(actor);

        UnoGame.PlayResult r = game.draw(actor);

        assertEquals(UnoGame.PlayResult.Status.DREW, r.status);
        assertTrue(game.handSize(actor) > before, "should have drawn at least one card");
        if (!game.legalIndices(actor).isEmpty()) {
            assertEquals(actor, game.currentPlayer(),
                    "having found a playable card, the turn stays with them");
        }
        assertEquals(108, totalCards(game));
    }

    // --------------------------------------------------------- the +4 challenge

    @Test
    @DisplayName("an honest +4 stands, and challenging it costs the challenger extra")
    void challengingAnHonestDrawFourBackfires() {
        RuleSet rules = new RuleSet(false, false, false, false, 0, false, false, false, true,
                false, 5, 2, 2);
        // "Honest" means the player held nothing of the colour that was showing.
        UnoGame game = dealUntil(3, 7, rules, g -> {
            UUID p = g.currentPlayer();
            if (indexOfKind(g, p, Card.Kind.WILD_DRAW4) < 0) {
                return false;
            }
            for (String name : g.handNames(p)) {
                if (Card.parse(name).color() == g.activeColor()) {
                    return false;
                }
            }
            return true;
        }, "an opener with a +4 and nothing of the active colour");

        UUID actor = game.currentPlayer();
        UUID victim = game.players().get((game.players().indexOf(actor) + 1) % 3);
        int victimBefore = game.handSize(victim);

        game.play(actor, indexOfKind(game, actor, Card.Kind.WILD_DRAW4));
        UnoGame.PlayResult afterColor = game.chooseColor(actor, Card.Color.RED);
        assertEquals(UnoGame.PlayResult.Status.NEED_CHALLENGE, afterColor.status);
        assertEquals(victim, game.pendingChallengePlayer());

        UnoGame.PlayResult r = game.respondToDraw4(victim, true);

        assertEquals(UnoGame.PlayResult.Status.CHALLENGED, r.status);
        assertFalse(r.upheld, "the +4 was legal, so the challenge must fail");
        assertEquals(victimBefore + 6, game.handSize(victim), "a wrong challenge costs 4+2");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("a bluffed +4 is caught and its player draws instead")
    void challengingABluffPunishesTheBluffer() {
        RuleSet rules = new RuleSet(false, false, false, false, 0, false, false, false, true,
                false, 5, 2, 2);
        UnoGame game = dealUntil(3, 7, rules, g -> {
            UUID p = g.currentPlayer();
            if (indexOfKind(g, p, Card.Kind.WILD_DRAW4) < 0) {
                return false;
            }
            for (String name : g.handNames(p)) {
                if (Card.parse(name).color() == g.activeColor()) {
                    return true; // they could have played a colour match — the +4 is a bluff
                }
            }
            return false;
        }, "an opener with a +4 AND a card of the active colour");

        UUID actor = game.currentPlayer();
        UUID victim = game.players().get((game.players().indexOf(actor) + 1) % 3);
        int blufferBefore = game.handSize(actor);
        int victimBefore = game.handSize(victim);

        game.play(actor, indexOfKind(game, actor, Card.Kind.WILD_DRAW4));
        game.chooseColor(actor, Card.Color.RED);

        UnoGame.PlayResult r = game.respondToDraw4(victim, true);

        assertTrue(r.upheld, "the +4 was a bluff and the challenge should stand");
        assertEquals(victimBefore, game.handSize(victim), "the challenger draws nothing");
        // blufferBefore already counts the +4 they played, so they are down one and up four.
        assertEquals(blufferBefore - 1 + 4, game.handSize(actor), "the bluffer takes the four");
        assertEquals(victim, game.currentPlayer(), "being right means you get your turn");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("taking a +4 without challenging draws four and skips you")
    void takingTheDrawFour() {
        RuleSet rules = new RuleSet(false, false, false, false, 0, false, false, false, true,
                false, 5, 2, 2);
        UnoGame game = dealUntil(3, 7, rules,
                g -> indexOfKind(g, g.currentPlayer(), Card.Kind.WILD_DRAW4) >= 0,
                "an opener holding a +4");

        UUID actor = game.currentPlayer();
        UUID victim = game.players().get((game.players().indexOf(actor) + 1) % 3);
        int before = game.handSize(victim);

        game.play(actor, indexOfKind(game, actor, Card.Kind.WILD_DRAW4));
        game.chooseColor(actor, Card.Color.BLUE);
        UnoGame.PlayResult r = game.respondToDraw4(victim, false);

        assertEquals(UnoGame.PlayResult.Status.CHALLENGED, r.status);
        assertEquals(before + 4, game.handSize(victim));
        assertNotEquals(victim, game.currentPlayer(), "taking it still skips you");
        assertEquals(108, totalCards(game));
    }

    // ------------------------------------------------------------ calling UNO

    @Test
    @DisplayName("a player on one card is exposed until they call it")
    void oneCardExposesUntilCalled() {
        RuleSet rules = RuleSet.withCallout(5, 2, 2);
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), rules);
        game.start(1); // everyone holds exactly one card

        UUID p = game.players().get(0);
        assertTrue(game.isExposed(p), "one card and no call is exposed");
        assertFalse(game.hasCalledUno(p));

        assertTrue(game.callUno(p), "the call should land");
        assertTrue(game.hasCalledUno(p));
        assertFalse(game.isExposed(p), "having called, they're safe");
        assertFalse(game.callUno(p), "calling twice changes nothing");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("calling UNO on more than one card does nothing")
    void cannotCallOnMoreThanOneCard() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.withCallout(5, 2, 2));
        game.start(7);
        assertFalse(game.callUno(game.players().get(0)));
        assertFalse(game.isExposed(game.players().get(0)));
    }

    @Test
    @DisplayName("catching a silent player makes them draw the penalty")
    void callingOutAnExposedPlayerPenalisesThem() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.withCallout(5, 3, 2));
        game.start(1);
        UUID target = game.players().get(0);
        UUID accuser = game.players().get(1);
        int before = game.handSize(target);

        UnoGame.CalloutResult r = game.callOut(accuser, target);

        assertTrue(r.allowed());
        assertTrue(r.caught());
        assertEquals(target, r.punished());
        assertEquals(3, r.drawn());
        assertEquals(before + 3, game.handSize(target));
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("calling out somebody who already called costs the accuser instead")
    void aFalseCalloutPenalisesTheAccuser() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.withCallout(5, 2, 4));
        game.start(1);
        UUID target = game.players().get(0);
        UUID accuser = game.players().get(1);
        game.callUno(target);
        int targetBefore = game.handSize(target);
        int accuserBefore = game.handSize(accuser);

        UnoGame.CalloutResult r = game.callOut(accuser, target);

        assertTrue(r.allowed());
        assertFalse(r.caught());
        assertEquals(accuser, r.punished());
        assertEquals(accuserBefore + 4, game.handSize(accuser), "the accuser pays for being wrong");
        assertEquals(targetBefore, game.handSize(target), "the target is untouched");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("drawing back up clears a call, so returning to one card exposes you again")
    void drawingClearsTheCall() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.withCallout(5, 2, 2));
        game.start(1);
        UUID actor = game.currentPlayer();
        game.callUno(actor);
        assertTrue(game.hasCalledUno(actor));

        game.draw(actor);

        assertFalse(game.hasCalledUno(actor), "picking cards back up ends the call");
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("call-outs do nothing when the rule is off")
    void calloutNeedsTheRuleOn() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.VANILLA);
        game.start(1);
        UnoGame.CalloutResult r = game.callOut(game.players().get(1), game.players().get(0));
        assertFalse(r.allowed());
        assertEquals(108, totalCards(game));
    }

    @Test
    @DisplayName("you can't call yourself out")
    void cannotCallYourselfOut() {
        UnoGame game = new UnoGame(UUID.randomUUID(), players(3), RuleSet.withCallout(5, 2, 2));
        game.start(1);
        UUID p = game.players().get(0);
        assertFalse(game.callOut(p, p).allowed());
        assertEquals(108, totalCards(game));
    }

    // -------------------------------------------------------------- defaults

    @Test
    @DisplayName("every house rule is off in the default rule set")
    void everythingIsOffByDefault() {
        RuleSet r = RuleSet.VANILLA;
        assertFalse(r.stacking());
        assertFalse(r.multiPlay());
        assertFalse(r.jumpIn());
        assertFalse(r.sevenO());
        assertFalse(r.drawToMatch());
        assertFalse(r.challengeDraw4());
        assertFalse(r.unoCallout());
        assertTrue(r.allowsCount(1), "one card at a time is always allowed");
        assertFalse(r.allowsCount(2));
        assertNotNull(r);
    }
}
