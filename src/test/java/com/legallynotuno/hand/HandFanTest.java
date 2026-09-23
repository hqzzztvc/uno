package com.legallynotuno.hand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The density tiers behind the held fan.
 *
 * <p>This is the one part of {@code HandManager} that is pure arithmetic, and it is also the
 * part the resource pack depends on: {@code generate_held_fan.py} reads {@code MAX_SLOTS} and
 * {@code DENSITIES} out of the Java to bake one model per (tier, slot, card). Two things can
 * go wrong when those numbers are retuned, and neither shows up until someone is holding cards
 * on a live server:
 *
 * <ul>
 *   <li>a tier the plugin can ask for that no longer fits the screen — the outermost card, and
 *       therefore the selected one whenever it is scrolled to the end, hangs off the edge;</li>
 *   <li>a gap in the tier ladder, where some hand size picks a tier wider than MAX_SPAN
 *       because no narrower one qualified.</li>
 * </ul>
 */
class HandFanTest {

    /** A hand can hold every card in the deck bar the one face-up on the pile. */
    private static final int MAX_HAND = 107;

    @Test
    @DisplayName("no hand size fans out wider than the screen allows")
    void spanFitsTheScreen() {
        for (int n = 0; n <= MAX_HAND; n++) {
            assertTrue(HandManager.spanOf(n) <= 112.0 + 0.001,
                    "a hand of " + n + " fans out to " + HandManager.spanOf(n) + " degrees");
        }
    }

    @Test
    @DisplayName("tiers step down as the hand grows, and never back up")
    void tiersOnlyTighten() {
        int previous = 0;
        for (int n = 1; n <= 21; n++) {
            int tier = HandManager.chooseTier(n);
            assertTrue(tier >= previous, "tier widened again at " + n + " cards");
            previous = tier;
        }
    }

    @Test
    @DisplayName("the three tiers cover small, medium and full hands")
    void allThreeTiersAreUsed() {
        // Each tier earns its place: drop one and big hands overflow or small ones cramp.
        assertEquals(0, HandManager.chooseTier(7), "a normal 7-card deal wants the widest fan");
        assertEquals(1, HandManager.chooseTier(12));
        assertEquals(2, HandManager.chooseTier(21), "a full fan wants the tightest tier");
    }

    @Test
    @DisplayName("a hand beyond the fan's slot count still fits — it windows instead")
    void oversizeHandsClampToTheWindow() {
        assertEquals(HandManager.spanOf(21), HandManager.spanOf(MAX_HAND), 0.001);
    }
}
