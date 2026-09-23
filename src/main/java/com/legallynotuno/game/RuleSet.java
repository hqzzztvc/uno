package com.legallynotuno.game;

/**
 * Which house rules a game is played under.
 *
 * <p>The game has no single agreed rulebook — nearly every table plays some variant, and the ones
 * people argue about (stacking a +2, laying three 7s at once, jumping in) change the game
 * enough that they cannot all be on at once. So they are toggles, and **every one defaults to
 * off**: a server that upgrades gets exactly the game it had before until somebody turns
 * something on deliberately.
 *
 * <p>Pure data with no Bukkit anywhere, like the rest of this package, so {@link UnoGame} stays
 * unit-testable and a test can construct any combination directly rather than through config.
 *
 * @param stacking          answer a +2/+4 with your own instead of drawing; the penalty piles up
 * @param draw4OnDraw2      may a Wild +4 be stacked onto a +2
 * @param draw2OnDraw4      may a +2 be stacked onto a Wild +4
 * @param multiPlay         lay several cards of the same rank in one turn
 * @param multiPlayMax      most cards one turn may lay ({@code 0} = no limit)
 * @param jumpIn            play the exact same card out of turn, and play jumps to you
 * @param sevenO            a 7 swaps hands with a player you pick, a 0 rotates every hand
 * @param drawToMatch       keep drawing until something is playable instead of drawing one
 * @param challengeDraw4    a +4 played while holding the active colour can be challenged
 * @param unoCallout        a player on one card who hasn't called UNO can be called out
 * @param unoWindowSeconds  how long the callout window stays open
 * @param unoPenalty        cards drawn by a player who is caught not having called
 * @param falseCalloutPenalty cards drawn for calling out somebody who was actually safe
 */
public record RuleSet(
        boolean stacking,
        boolean draw4OnDraw2,
        boolean draw2OnDraw4,
        boolean multiPlay,
        int multiPlayMax,
        boolean jumpIn,
        boolean sevenO,
        boolean drawToMatch,
        boolean challengeDraw4,
        boolean unoCallout,
        int unoWindowSeconds,
        int unoPenalty,
        int falseCalloutPenalty) {

    /** The game as the official rulebook has it — what every game plays under by default. */
    public static final RuleSet VANILLA = new RuleSet(
            false, false, false,
            false, 0,
            false, false, false, false,
            false, 5, 2, 2);

    /** A copy with only the UNO callout turned on, for tests that don't care about the rest. */
    public static RuleSet withCallout(int windowSeconds, int penalty, int falsePenalty) {
        return new RuleSet(false, false, false, false, 0, false, false, false, false,
                true, windowSeconds, penalty, falsePenalty);
    }

    /** A copy with only draw-card stacking turned on. */
    public static RuleSet withStacking(boolean draw4OnDraw2, boolean draw2OnDraw4) {
        return new RuleSet(true, draw4OnDraw2, draw2OnDraw4, false, 0, false, false, false, false,
                false, 5, 2, 2);
    }

    /** A copy with only same-rank multi-play turned on. */
    public static RuleSet withMultiPlay(int max) {
        return new RuleSet(false, false, false, true, max, false, false, false, false,
                false, 5, 2, 2);
    }

    /** True if this turn's play may lay {@code count} cards at once. */
    public boolean allowsCount(int count) {
        if (count <= 1) {
            return true;
        }
        if (!multiPlay) {
            return false;
        }
        return multiPlayMax <= 0 || count <= multiPlayMax;
    }
}
