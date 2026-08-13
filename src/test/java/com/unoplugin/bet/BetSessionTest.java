package com.unoplugin.bet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pot's bookkeeping, checked without a server.
 *
 * <p>Only the parts that touch no Bukkit type: who is in, who has locked in, and whether that
 * is enough to deal. Staking itself needs an {@code ItemStack} and belongs on a live server.
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
}
