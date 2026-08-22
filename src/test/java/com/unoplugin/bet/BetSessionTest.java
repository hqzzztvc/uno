package com.unoplugin.bet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pot's bookkeeping, checked without a server.
 *
 * <p>Only the parts that touch no Bukkit type: who is in, who has locked in, whether that is
 * enough to deal, and the money half of a stake. Staking an ITEM needs an {@code ItemStack}
 * and belongs on a live server; money is a plain double and can be pinned here, which is
 * exactly where it should be — it is the half of a pot with no visible representation to
 * catch a mistake in.
 */
class BetSessionTest {

    private static BetSession session() {
        return new BetSession(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("two humans both locked in is a table")
    void twoReadyHumansStart() {
        BetSession s = session();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        s.live().add(a);
        s.live().add(b);
        s.setReady(a);
        assertFalse(s.allReady(), "one of the two hasn't locked in yet");
        s.setReady(b);
        assertTrue(s.allReady());
    }

    /**
     * One human plus bots is a table too — {@code /gamble go} always counted them, and this
     * used to return false forever, so a solo wager could never start by locking in.
     */
    @Test
    @DisplayName("bots count toward having enough players")
    void botsCountTowardTheHeadCount() {
        BetSession s = session();
        UUID host = UUID.randomUUID();
        s.live().add(host);
        s.setReady(host);
        assertFalse(s.allReady(), "one player and no bots is not a game");

        s.setBotCount(2);
        assertTrue(s.allReady(), "one human plus two bots is a table");
    }

    @Test
    @DisplayName("bots alone are never enough to deal")
    void botsAloneDoNotStart() {
        BetSession s = session();
        s.setBotCount(4);
        assertFalse(s.allReady(), "nobody staked — there is no hand to play");
    }

    @Test
    @DisplayName("a forfeit drops you from the running but leaves your items attributed")
    void forfeitLeavesAttribution() {
        BetSession s = session();
        UUID quitter = UUID.randomUUID();
        s.live().add(quitter);
        s.setReady(quitter);

        s.forfeit(quitter);

        assertFalse(s.isLive(quitter), "a forfeit is out of the running");
        assertFalse(s.isReady(quitter));
    }

    // ------------------------------------------------------------------- money

    @Test
    @DisplayName("staking money puts you in the bet and adds up across raises")
    void moneyStakesAccumulate() {
        BetSession s = session();
        UUID a = UUID.randomUUID();

        s.stakeMoney(a, 250.0);
        assertTrue(s.isLive(a), "putting money up is buying in");
        s.stakeMoney(a, 125.5);

        assertEquals(375.5, s.stakeOf(a).money(), 1e-9);
        assertEquals(375.5, s.potMoney(), 1e-9);
        assertTrue(s.hasStaked(a));
    }

    @Test
    @DisplayName("the pot totals every staker's money")
    void potIsTheSumOfStakes() {
        BetSession s = session();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        s.stakeMoney(a, 100.0);
        s.stakeMoney(b, 40.0);

        assertEquals(140.0, s.pot().money(), 1e-9);
        assertEquals(2, s.stakers().size());
    }

    /**
     * Withdrawing has to hand back the money as well as the items. A refund that quietly
     * keeps half the stake is indistinguishable from theft, and money is the half with
     * nothing on the table to notice its absence.
     */
    @Test
    @DisplayName("withdrawing returns the money and clears it from the pot")
    void withdrawReturnsMoney() {
        BetSession s = session();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        s.stakeMoney(a, 100.0);
        s.stakeMoney(b, 60.0);

        Stake back = s.withdraw(a);

        assertEquals(100.0, back.money(), 1e-9);
        assertEquals(60.0, s.potMoney(), 1e-9, "only the other player's money is left in");
        assertFalse(s.isLive(a));
        assertFalse(s.hasStaked(a));
    }

    /**
     * A forfeit leaves the money in the pot but still filed under the player who put it
     * there — the same rule items follow, and for the same reason: a crash mid-hand has to
     * refund it to somebody, and it is theirs until a hand is played for it.
     */
    @Test
    @DisplayName("forfeited money stays in the pot, still attributed")
    void forfeitedMoneyStaysInThePot() {
        BetSession s = session();
        UUID quitter = UUID.randomUUID();
        s.stakeMoney(quitter, 80.0);

        s.forfeit(quitter);

        assertFalse(s.isLive(quitter));
        assertEquals(80.0, s.potMoney(), 1e-9, "the stake does not leave with them");
        assertEquals(80.0, s.stakeOf(quitter).money(), 1e-9, "still refundable to them");
    }

    /**
     * A win that rides re-files the whole pot under the rider. Money has to move with it, or
     * the losers' escrow keeps holding cash that has already been handed on — which the next
     * relog pays out a second time, minting it.
     */
    @Test
    @DisplayName("letting it ride moves the money to the rider too")
    void reattributeCarriesMoney() {
        BetSession s = session();
        UUID winner = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        s.stakeMoney(winner, 100.0);
        s.stakeMoney(loser, 100.0);

        s.reattributeTo(winner);

        assertEquals(1, s.stakers().size(), "the pot is the rider's alone now");
        assertEquals(200.0, s.stakeOf(winner).money(), 1e-9);
        assertEquals(0.0, s.stakeOf(loser).money(), 1e-9);
        assertEquals(200.0, s.potMoney(), 1e-9, "nothing was created or destroyed");
    }

    /** A pure-money stake is a real stake: the item count is zero but the pot is not empty. */
    @Test
    @DisplayName("a money-only pot is not an empty pot")
    void moneyOnlyPotIsNotEmpty() {
        BetSession s = session();
        s.stakeMoney(UUID.randomUUID(), 500.0);

        assertEquals(0, s.potSize(), "no items in it");
        assertFalse(s.pot().isEmpty(), "but there is very much something on the table");
        assertTrue(s.pot().hasMoney());
    }
}
