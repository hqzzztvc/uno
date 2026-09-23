package com.legallynotuno.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * One game: hands, draw/discard piles, turn order &amp; direction, the active colour,
 * and all the rules (legal-move checking, card effects, wild colour choice, win).
 *
 * <p>Pure logic — no Bukkit anywhere in this class, so it is directly unit-testable.
 * Rendering, messaging, name resolution and bots live in {@code GameManager}.
 */
public final class UnoGame {

    /** Cards dealt to each player when the config doesn't say otherwise. */
    public static final int DEFAULT_HAND_SIZE = 7;

    private final UUID id;
    private final List<UUID> players;
    private final Map<UUID, List<Card>> hands = new HashMap<>();
    private final Deck deck = new Deck();
    private final List<Card> discard = new ArrayList<>();
    private final RuleSet rules;

    private Card.Color activeColor;
    private int turn = 0;
    private int direction = 1;
    private boolean over = false;
    private UUID winner;
    private UUID pendingColorPlayer; // must choose a colour after playing a wild
    private Card pendingColorCard;
    private String lastEvent = "";
    /** Bumped on every state change; lets a scheduled timeout tell "still the same turn". */
    private int moves = 0;

    /**
     * Draw cards owed by whoever is to move, when stacking is on.
     *
     * <p>Zero means nothing is pending. Above zero, the current player's only legal moves are
     * to stack another draw card on top or to absorb the lot — {@link #canPlay} enforces that,
     * and {@link #draw} is what absorbs.
     */
    private int pendingDraw = 0;
    /** Which draw card started the pile, so cross-stacking rules can be applied to it. */
    private Card.Kind pendingDrawKind;

    /** Played a 7 under seven-O and owes us a player to swap hands with. */
    private UUID pendingSwapPlayer;

    /** Was just hit by a +4 and may challenge it before drawing. */
    private UUID pendingChallengePlayer;
    /** Who played that +4, and what they'd have had to match to make it legal. */
    private UUID draw4Player;
    private Card.Color draw4RequiredColor;
    /**
     * The +4 player's hand at the moment they played it, minus the +4 itself.
     *
     * <p>Snapshotted because a challenge is judged on what they COULD have played instead, and
     * by the time anyone challenges they may have drawn from a stack or had cards taken by a
     * seven-O swap. Judging the live hand would convict the innocent.
     */
    private List<Card> draw4Hand = List.of();

    /** Who has called UNO while down to one card — cleared the moment they leave one card. */
    private final java.util.Set<UUID> calledUno = new java.util.HashSet<>();

    /** Resolves a player UUID to a display name (GameManager supplies the real one). */
    private Function<UUID, String> namer = p -> p.toString().substring(0, 8);

    public UnoGame(UUID id, List<UUID> players) {
        this(id, players, RuleSet.VANILLA);
    }

    public UnoGame(UUID id, List<UUID> players, RuleSet rules) {
        this.id = id;
        this.players = new ArrayList<>(players);
        this.rules = rules == null ? RuleSet.VANILLA : rules;
    }

    /** The house rules this game is being played under. */
    public RuleSet rules() {
        return rules;
    }

    public void setNamer(Function<UUID, String> namer) {
        this.namer = namer;
    }

    /** Shuffle, deal {@link #DEFAULT_HAND_SIZE} each, flip a number card to start. */
    public void start() {
        start(DEFAULT_HAND_SIZE);
    }

    /**
     * Shuffle, deal {@code handSize} each, flip a number card to start.
     *
     * <p>{@code handSize} is clamped so the deal always leaves enough behind for the flip to
     * find a number card — otherwise a large configured hand size could empty the deck.
     */
    public void start(int handSize) {
        deck.reset();
        hands.clear();
        discard.clear();

        int perPlayer = dealSize(handSize);
        for (UUID p : players) {
            List<Card> h = new ArrayList<>();
            for (int i = 0; i < perPlayer; i++) {
                Card c = deck.draw(discard);
                if (c == null) {
                    break;
                }
                h.add(c);
            }
            hands.put(p, h);
        }

        Card first = flipStartingCard();
        discard.add(first);
        activeColor = first.color();
        turn = 0;
        direction = 1;
        over = false;
        winner = null;
        pendingColorPlayer = null;
        pendingColorCard = null;
        pendingDraw = 0;
        pendingDrawKind = null;
        pendingSwapPlayer = null;
        pendingChallengePlayer = null;
        draw4Player = null;
        draw4RequiredColor = null;
        draw4Hand = List.of();
        calledUno.clear();
        moves = 0;
        lastEvent = "Game started";
    }

    /**
     * How many cards each player actually gets. The deal must leave more than the deck's
     * non-number cards behind, so {@link #flipStartingCard} is guaranteed to find a number.
     */
    private int dealSize(int requested) {
        int spare = Deck.NON_NUMBER_COUNT + 2;
        int room = Math.max(1, (deck.size() - spare) / Math.max(1, players.size()));
        return Math.max(1, Math.min(requested, room));
    }

    /**
     * Turn over cards until a plain number shows — the classic effect-free starting card.
     *
     * <p>Everything turned over on the way goes <em>back into the deck</em>. Throwing those
     * cards away (neither discarded nor returned) silently shrinks the deck every hand.
     */
    private Card flipStartingCard() {
        List<Card> rejected = new ArrayList<>();
        Card first = null;
        Card c;
        while ((c = deck.draw(discard)) != null) {
            if (c.kind() == Card.Kind.NUMBER) {
                first = c;
                break;
            }
            rejected.add(c);
        }
        if (first == null) {
            // Unreachable with a standard deck thanks to dealSize(), but if the deck were
            // ever exhausted, settle for any coloured card rather than leaving no top card.
            for (Iterator<Card> it = rejected.iterator(); it.hasNext(); ) {
                Card r = it.next();
                if (!r.isWild()) {
                    first = r;
                    it.remove();
                    break;
                }
            }
        }
        deck.returnCards(rejected);
        if (first == null) {
            throw new IllegalStateException("Deck exhausted before a starting card could be flipped");
        }
        return first;
    }

    // ----------------------------------------------------------------- accessors

    /** This game's own id (not a table id — a game outlives the table it was dealt at). */
    public UUID id() {
        return id;
    }

    public List<UUID> players() {
        return Collections.unmodifiableList(players);
    }

    public UUID currentPlayer() {
        return players.get(turn);
    }

    public Card top() {
        return discard.get(discard.size() - 1);
    }

    public Card.Color activeColor() {
        return activeColor;
    }

    public boolean isOver() {
        return over;
    }

    public UUID winner() {
        return winner;
    }

    public int direction() {
        return direction;
    }

    public int drawPileSize() {
        return deck.size();
    }

    /** Cards on the discard pile, including the face-up top card. */
    public int discardSize() {
        return discard.size();
    }

    public UUID pendingColorPlayer() {
        return pendingColorPlayer;
    }

    public String lastEvent() {
        return lastEvent;
    }

    /** Monotonic counter of state changes — compare before/after to detect "nothing moved". */
    public int moveCount() {
        return moves;
    }

    public int handSize(UUID p) {
        List<Card> h = hands.get(p);
        return h == null ? 0 : h.size();
    }

    public List<String> handNames(UUID p) {
        List<Card> h = hands.get(p);
        if (h == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(h.size());
        for (Card c : h) {
            names.add(c.name());
        }
        return names;
    }

    /** First legal card index for the player, or -1 if none (used by bots / hints). */
    public int firstLegalIndex(UUID p) {
        List<Card> h = hands.get(p);
        if (h == null) {
            return -1;
        }
        for (int i = 0; i < h.size(); i++) {
            if (canPlay(h.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** All legal card indices for the player (bots pick one at random). */
    public List<Integer> legalIndices(UUID p) {
        List<Integer> out = new ArrayList<>();
        List<Card> h = hands.get(p);
        if (h != null) {
            for (int i = 0; i < h.size(); i++) {
                if (canPlay(h.get(i))) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    public boolean hasLegalMove(UUID p) {
        return firstLegalIndex(p) >= 0;
    }

    /**
     * Whether this card may go down right now — the ONE place legality is decided.
     *
     * <p>Normally that is the card's own {@link Card#playableOn}. While a stack of draw cards
     * is pending it is not: the only way out is another draw card or taking the pile, so a
     * matching colour counts for nothing. Routing every caller (play, bots, hints, jump-in)
     * through here is what stops a bot cheerfully playing a green 5 to escape a +8.
     */
    private boolean canPlay(Card c) {
        if (pendingDraw > 0) {
            return stacksOntoPending(c);
        }
        return c.playableOn(top(), activeColor);
    }

    /** Whether this draw card may be added to the pending pile, under the cross-stack rules. */
    private boolean stacksOntoPending(Card c) {
        if (!rules.stacking() || pendingDrawKind == null) {
            return false;
        }
        return switch (c.kind()) {
            // A +2 always answers a +2. Answering a +4 with one is the contentious case, so
            // it is its own toggle.
            case DRAW2 -> pendingDrawKind == Card.Kind.DRAW2 || rules.draw2OnDraw4();
            case WILD_DRAW4 -> pendingDrawKind == Card.Kind.WILD_DRAW4 || rules.draw4OnDraw2();
            default -> false;
        };
    }

    /** Cards the player owes right now, or 0 — the bossbar and prompts read this. */
    public int pendingDraw() {
        return pendingDraw;
    }

    /** True if the player to move can answer the pending stack rather than eat it. */
    public boolean canStack(UUID p) {
        List<Card> h = hands.get(p);
        if (h == null || pendingDraw <= 0) {
            return false;
        }
        for (Card c : h) {
            if (stacksOntoPending(c)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- actions

    /** Play the card at {@code handIndex} for {@code player}. */
    public PlayResult play(UUID player, int handIndex) {
        return play(player, List.of(handIndex));
    }

    /**
     * Play one or more cards of the same rank for {@code player}.
     *
     * <p>One card is the ordinary move. Several is the "lay all your 7s at once" house rule,
     * and they must all be the same rank — that is what makes it a single move rather than a
     * free turn. Every card's effect still happens: two Skips skip two players, two +2s put
     * four cards on somebody.
     */
    public PlayResult play(UUID player, List<Integer> handIndices) {
        if (over) {
            return PlayResult.illegal("The game is over.");
        }
        if (pendingColorPlayer != null) {
            return PlayResult.illegal("Waiting for a colour choice.");
        }
        if (pendingSwapPlayer != null) {
            return PlayResult.illegal("Waiting for a hand to swap with.");
        }
        if (pendingChallengePlayer != null) {
            return PlayResult.illegal("Waiting on the +4 challenge.");
        }
        if (!player.equals(currentPlayer())) {
            return PlayResult.illegal("It's not your turn.");
        }
        List<Card> hand = hands.get(player);
        List<Card> cards = resolveIndices(hand, handIndices);
        if (cards == null) {
            return PlayResult.illegal("No such card.");
        }
        if (!rules.allowsCount(cards.size())) {
            return PlayResult.illegal(rules.multiPlay()
                    ? "You can play at most " + rules.multiPlayMax() + " cards at once."
                    : "You can only play one card at a time.");
        }
        if (!sameRank(cards)) {
            return PlayResult.illegal("Cards played together must all be the same number or symbol.");
        }
        Card first = cards.get(0);
        if (!canPlay(first)) {
            return PlayResult.illegal(pendingDraw > 0
                    ? "You owe " + pendingDraw + " cards — stack a draw card or take them."
                    : "Can't play " + first.label() + " on " + top().label()
                            + " (" + activeColor.lower() + ").");
        }
        return commitPlay(player, hand, handIndices, cards);
    }

    /**
     * Play a card out of turn because it is EXACTLY the top card — the jump-in rule.
     *
     * <p>Colour and rank both have to match; matching only the rank is an ordinary play and
     * would let anyone steal any turn. Play jumps to the player who jumped in, and carries on
     * from them, which is the whole point of the rule.
     */
    public PlayResult jumpIn(UUID player, int handIndex) {
        if (!rules.jumpIn()) {
            return PlayResult.illegal("Jump-in is not enabled.");
        }
        if (over || pendingColorPlayer != null || pendingSwapPlayer != null
                || pendingChallengePlayer != null) {
            return PlayResult.illegal("Not right now.");
        }
        if (pendingDraw > 0) {
            // Jumping into a live stack would let a third party dodge the pile they aren't
            // even facing, and hand the penalty to someone who never had a turn.
            return PlayResult.illegal("Can't jump in while a draw stack is running.");
        }
        int idx = players.indexOf(player);
        if (idx < 0 || player.equals(currentPlayer())) {
            return PlayResult.illegal("It's already your turn.");
        }
        List<Card> hand = hands.get(player);
        if (hand == null || handIndex < 0 || handIndex >= hand.size()) {
            return PlayResult.illegal("No such card.");
        }
        Card card = hand.get(handIndex);
        if (!card.equals(top()) || card.isWild()) {
            // Wilds are excluded deliberately: every wild is identical, so allowing them would
            // make a jump-in available to anyone holding one at any time.
            return PlayResult.illegal("Jump-in needs the exact same card that's showing.");
        }
        turn = idx; // play jumps to them, and resolve() advances from here
        return commitPlay(player, hand, List.of(handIndex), List.of(card));
    }

    /** Shared tail of play and jumpIn: move the cards, then work out what they did. */
    private PlayResult commitPlay(UUID player, List<Card> hand,
                                  List<Integer> handIndices, List<Card> cards) {
        colorBeforePlay = activeColor; // before a wild overwrites it — see the field's note
        removeAll(hand, handIndices);
        discard.addAll(cards);
        moves++;
        // Leaving one card behind re-arms the callout: a player who was safe on two cards and
        // has just come back down to one has to call again.
        if (hand.size() != 1) {
            calledUno.remove(player);
        }
        Card last = cards.get(cards.size() - 1);
        if (last.isWild()) {
            pendingColorPlayer = player;
            pendingColorCard = last;
            pendingPlay = cards;
            return PlayResult.needColor();
        }
        activeColor = last.color();
        return resolve(cards, player);
    }

    /** The cards of a multi-card play waiting on their wild's colour. */
    private List<Card> pendingPlay = List.of();

    /** Look up every index, rejecting duplicates and anything out of range. */
    private List<Card> resolveIndices(List<Card> hand, List<Integer> indices) {
        if (hand == null || indices == null || indices.isEmpty()) {
            return null;
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        List<Card> out = new ArrayList<>(indices.size());
        for (Integer i : indices) {
            if (i == null || i < 0 || i >= hand.size() || !seen.add(i)) {
                return null;
            }
            out.add(hand.get(i));
        }
        return out;
    }

    /** Remove several indices at once, highest first so the earlier ones don't shift. */
    private static void removeAll(List<Card> hand, List<Integer> indices) {
        List<Integer> sorted = new ArrayList<>(indices);
        sorted.sort(Collections.reverseOrder());
        for (int i : sorted) {
            hand.remove(i);
        }
    }

    /** Same number, or the same action symbol — what "the same rank" means for multi-play. */
    private static boolean sameRank(List<Card> cards) {
        Card first = cards.get(0);
        for (Card c : cards) {
            if (c.kind() != first.kind()) {
                return false;
            }
            if (c.kind() == Card.Kind.NUMBER && c.number() != first.number()) {
                return false;
            }
        }
        return true;
    }

    /** Complete a pending wild by choosing its colour. */
    public PlayResult chooseColor(UUID player, Card.Color color) {
        if (over) {
            return PlayResult.illegal("The game is over.");
        }
        if (pendingColorPlayer == null || !player.equals(pendingColorPlayer)) {
            return PlayResult.illegal("No colour choice pending.");
        }
        activeColor = color == null || color == Card.Color.WILD ? Card.Color.RED : color;
        List<Card> cards = pendingPlay.isEmpty() ? List.of(pendingColorCard) : pendingPlay;
        pendingColorPlayer = null;
        pendingColorCard = null;
        pendingPlay = List.of();
        moves++;
        return resolve(cards, player);
    }

    /**
     * Name the player whose hand you are taking, after playing a 7 under seven-O.
     *
     * <p>Swapping with yourself is refused rather than treated as a pass: it would be a way to
     * play a 7 for free, which is exactly what the rule exists to prevent.
     */
    public PlayResult chooseSwap(UUID player, UUID target) {
        if (over) {
            return PlayResult.illegal("The game is over.");
        }
        if (pendingSwapPlayer == null || !player.equals(pendingSwapPlayer)) {
            return PlayResult.illegal("No hand swap pending.");
        }
        if (target == null || target.equals(player) || !hands.containsKey(target)) {
            return PlayResult.illegal("Pick another player to swap hands with.");
        }
        List<Card> mine = hands.get(player);
        hands.put(player, hands.get(target));
        hands.put(target, mine);
        List<Card> cards = pendingPlay;
        pendingSwapPlayer = null;
        pendingPlay = List.of();
        moves++;
        recheckUnoCalls();
        int extraSkips = 0;
        for (Card c : cards) {
            if (c.kind() == Card.Kind.SKIP || c.kind() == Card.Kind.DRAW2
                    || c.kind() == Card.Kind.WILD_DRAW4) {
                extraSkips++;
            }
        }
        advance(1 + extraSkips);
        lastEvent = namer.apply(player) + " played a 7 and swapped hands with " + namer.apply(target);
        // The swap can hand somebody their last card, so the win check happens after it.
        if (hands.get(player).isEmpty()) {
            over = true;
            winner = player;
            lastEvent = namer.apply(player) + " swapped into an empty hand and won!";
            return PlayResult.win(cards.get(cards.size() - 1), null);
        }
        return PlayResult.ok(cards.get(cards.size() - 1), target);
    }

    /**
     * Answer a +4 played at you: challenge it, or take the cards.
     *
     * <p>The official rule. A +4 is only legal if its player had nothing matching the colour
     * that was showing. Challenge a bluff and they draw the four instead; challenge an honest
     * one and you draw two extra on top.
     */
    public PlayResult respondToDraw4(UUID player, boolean challenge) {
        if (over) {
            return PlayResult.illegal("The game is over.");
        }
        if (pendingChallengePlayer == null || !player.equals(pendingChallengePlayer)) {
            return PlayResult.illegal("No +4 to answer.");
        }
        UUID bluffer = draw4Player;
        List<Card> cards = pendingPlay;
        int extraSkips = pendingSkips;
        boolean wasBluff = heldColor(draw4Hand, draw4RequiredColor);
        pendingChallengePlayer = null;
        draw4Player = null;
        draw4Hand = List.of();
        pendingPlay = List.of();
        pendingSkips = 0;
        moves++;

        int drawn;
        UUID punished;
        if (!challenge) {
            drawn = drawTo(player, 4);
            punished = player;
            advance(1 + extraSkips);
            lastEvent = namer.apply(player) + " takes the +4 — draws " + drawn + " & skipped";
        } else if (wasBluff) {
            drawn = drawTo(bluffer, 4);
            punished = bluffer;
            // The turn is still sitting on the player who played the +4 — resolve() handed the
            // challenge back without advancing. One step puts it on the challenger, which is
            // the reward for being right: they draw nothing and they get to play.
            advance(1);
            lastEvent = namer.apply(player) + " challenged and was right — "
                    + namer.apply(bluffer) + " was bluffing and draws " + drawn;
        } else {
            drawn = drawTo(player, 6);
            punished = player;
            advance(1 + extraSkips);
            lastEvent = namer.apply(player) + " challenged and was wrong — draws " + drawn;
        }
        recheckUnoCalls();
        return PlayResult.challenged(cards.get(cards.size() - 1), punished, drawn, challenge && wasBluff);
    }

    /** Did that hand hold anything of the colour the +4 was played over? */
    private static boolean heldColor(List<Card> hand, Card.Color color) {
        if (color == null) {
            return false;
        }
        for (Card c : hand) {
            if (c.color() == color) {
                return true;
            }
        }
        return false;
    }

    /** Whoever must answer a +4 right now, or null. */
    public UUID pendingChallengePlayer() {
        return pendingChallengePlayer;
    }

    /** Whoever must pick a hand to swap with right now, or null. */
    public UUID pendingSwapPlayer() {
        return pendingSwapPlayer;
    }

    /** Draw a card and pass the turn — or take the pending stack, or draw until playable. */
    public PlayResult draw(UUID player) {
        if (over) {
            return PlayResult.illegal("The game is over.");
        }
        if (pendingColorPlayer != null) {
            return PlayResult.illegal("Waiting for a colour choice.");
        }
        if (pendingSwapPlayer != null) {
            return PlayResult.illegal("Waiting for a hand to swap with.");
        }
        if (pendingChallengePlayer != null) {
            return PlayResult.illegal("Waiting on the +4 challenge.");
        }
        if (!player.equals(currentPlayer())) {
            return PlayResult.illegal("It's not your turn.");
        }
        moves++;
        calledUno.remove(player); // picking cards back up ends any UNO they had called

        if (pendingDraw > 0) {
            int owed = pendingDraw;
            int got = drawTo(player, owed);
            pendingDraw = 0;
            pendingDrawKind = null;
            advance(1);
            lastEvent = namer.apply(player) + " takes the stack — draws " + got;
            return PlayResult.drew(null, got);
        }

        if (rules.drawToMatch()) {
            return drawUntilPlayable(player);
        }

        Card c = deck.draw(discard);
        if (c == null) {
            // Nothing left anywhere. Rebuilding the deck here would put a second copy of
            // every card players are holding into play, so the turn simply passes.
            lastEvent = namer.apply(player) + " had nothing to draw — the turn passes";
            advance(1);
            return PlayResult.drew(null, 0);
        }
        hands.get(player).add(c);
        lastEvent = namer.apply(player) + " drew a card";
        advance(1);
        return PlayResult.drew(c, 1);
    }

    /**
     * Keep drawing until something is playable, and KEEP the turn when it is.
     *
     * <p>Holding the turn is the point of the rule — you draw until you can go, then you go.
     * Passing anyway would make it strictly worse than drawing one card.
     */
    private PlayResult drawUntilPlayable(UUID player) {
        List<Card> hand = hands.get(player);
        int taken = 0;
        Card last = null;
        while (true) {
            Card c = deck.draw(discard);
            if (c == null) {
                break;
            }
            hand.add(c);
            last = c;
            taken++;
            if (canPlay(c)) {
                lastEvent = namer.apply(player) + " drew " + taken
                        + (taken == 1 ? " card" : " cards") + " and can play";
                return PlayResult.drew(c, taken);
            }
        }
        // Deck genuinely empty and still nothing playable — the turn has to move on.
        lastEvent = taken == 0
                ? namer.apply(player) + " had nothing to draw — the turn passes"
                : namer.apply(player) + " drew " + taken + " and still can't play";
        advance(1);
        return PlayResult.drew(last, taken);
    }

    /**
     * Drop a player out of the hand mid-game (they disconnected). Their cards go back under
     * the discard top so the deck can still recycle them, and the turn index is re-anchored
     * onto whoever should move next. Last player standing wins by default.
     */
    public void forfeit(UUID player) {
        int idx = players.indexOf(player);
        if (over || idx < 0) {
            return;
        }
        List<Card> hand = hands.remove(player);
        if (hand != null && !hand.isEmpty() && !discard.isEmpty()) {
            discard.addAll(discard.size() - 1, hand); // below the top card — still the live top
        }
        players.remove(idx);
        moves++;
        calledUno.remove(player);
        // Every pending prompt this player owed an answer to has to be dropped with them, or
        // the table waits forever on somebody who isn't there. The cards already dealt stand.
        if (player.equals(pendingColorPlayer)) {
            pendingColorPlayer = null;
            pendingColorCard = null;
            pendingPlay = List.of();
        }
        if (player.equals(pendingSwapPlayer)) {
            pendingSwapPlayer = null;
            pendingPlay = List.of();
        }
        if (player.equals(pendingChallengePlayer)) {
            // They were owed four cards and left instead; nobody is left to take them, so the
            // +4 simply lapses rather than being re-aimed at an uninvolved player.
            pendingChallengePlayer = null;
            draw4Player = null;
            draw4Hand = List.of();
            pendingPlay = List.of();
            pendingSkips = 0;
        }
        if (player.equals(draw4Player)) {
            // The accused left mid-challenge: there is nobody to punish for a bluff, so the
            // challenge can't be judged. Drop it and let the target simply take the cards.
            draw4Player = null;
            draw4Hand = List.of();
        }
        // A stack in flight belongs to whoever is now to move, not to the player who left.
        if (players.isEmpty()) {
            pendingDraw = 0;
            pendingDrawKind = null;
        }
        if (players.isEmpty()) {
            over = true;
            winner = null;
            lastEvent = namer.apply(player) + " left — game over";
            return;
        }
        // Removing an earlier index shifts everyone down; removing the *current* player leaves
        // the next-in-turn sitting at the same index going forwards, one back going in reverse.
        if (idx < turn || (idx == turn && direction < 0)) {
            turn--;
        }
        turn = ((turn % players.size()) + players.size()) % players.size();
        lastEvent = namer.apply(player) + " left the hand";
        if (players.size() == 1) {
            over = true;
            winner = players.get(0);
            lastEvent = namer.apply(winner) + " wins — everyone else left!";
        }
    }

    private PlayResult resolve(Card card, UUID player) {
        return resolve(List.of(card), player);
    }

    /**
     * Work out what a play did to the table, for one card or several of the same rank.
     *
     * <p>Effects add up rather than replace each other: each Skip, +2 or +4 in the stack moves
     * the turn one player further on, each Reverse flips the direction, and the draw penalties
     * total. That is the only reading of multi-play that keeps a single Skip and a pair of
     * Skips consistent with each other.
     */
    private PlayResult resolve(List<Card> cards, UUID player) {
        Card last = cards.get(cards.size() - 1);
        int drawAmount = 0;
        int extraSkips = 0;
        int reverses = 0;
        for (Card c : cards) {
            switch (c.kind()) {
                case SKIP -> extraSkips++;
                case REVERSE -> reverses++;
                case DRAW2 -> {
                    drawAmount += 2;
                    extraSkips++;
                }
                case WILD_DRAW4 -> {
                    drawAmount += 4;
                    extraSkips++;
                }
                default -> { }
            }
        }
        // A reverse with only two players left has nobody to hand the direction to, so it acts
        // as a skip — the standard ruling, and applied per card so a pair still behaves.
        if (reverses > 0) {
            if (players.size() == 2) {
                extraSkips += reverses;
            } else if (reverses % 2 == 1) {
                direction = -direction;
            }
        }

        if (hands.get(player).isEmpty()) {
            return finishWin(cards, player, drawAmount);
        }

        // Seven-O looks at the rank rather than the count: laying three 7s is still one swap.
        if (rules.sevenO() && last.kind() == Card.Kind.NUMBER) {
            if (last.number() == 7 && players.size() > 1) {
                pendingSwapPlayer = player;
                pendingPlay = cards;
                lastEvent = namer.apply(player) + " played a 7 — choosing a hand to swap with";
                return PlayResult.needSwap(last);
            }
            if (last.number() == 0 && players.size() > 1) {
                rotateHands();
                advance(1 + extraSkips);
                lastEvent = namer.apply(player) + " played a 0 — every hand moves round";
                return PlayResult.ok(last, null);
            }
        }

        UUID target = drawAmount > 0 ? peek(1) : null;
        if (drawAmount > 0 && rules.stacking()) {
            // Don't deal it yet: the next player gets the chance to answer with their own draw
            // card and pass a bigger pile along. draw() is what finally collects it.
            pendingDraw += drawAmount;
            pendingDrawKind = last.kind();
            advance(1); // to the player who must answer, NOT past them
            lastEvent = namer.apply(player) + " played " + label(cards) + " — "
                    + namer.apply(currentPlayer()) + " must stack or draw " + pendingDraw;
            return PlayResult.stacked(last, currentPlayer(), pendingDraw);
        }

        if (drawAmount > 0) {
            if (last.kind() == Card.Kind.WILD_DRAW4 && rules.challengeDraw4()
                    && cards.size() == 1) {
                // The challenge has to happen before the cards are dealt, or an upheld
                // challenge means taking four back off somebody.
                pendingChallengePlayer = target;
                draw4Player = player;
                draw4RequiredColor = colorBeforePlay;
                draw4Hand = List.copyOf(hands.get(player));
                pendingPlay = cards;
                pendingSkips = extraSkips;
                lastEvent = namer.apply(player) + " played Wild +4 at " + namer.apply(target);
                return PlayResult.needChallenge(last, target);
            }
            int got = drawTo(target, drawAmount);
            advance(1 + extraSkips);
            lastEvent = namer.apply(player) + " played " + label(cards) + " — "
                    + namer.apply(target) + " draws " + got + " & skipped";
            return PlayResult.ok(last, target);
        }

        UUID skipped = extraSkips > 0 ? peek(1) : null;
        advance(1 + extraSkips);
        lastEvent = describe(cards, player, skipped, reverses);
        return PlayResult.ok(last, skipped);
    }

    /** Extra turn-steps owed once a pending +4 challenge is settled. */
    private int pendingSkips = 0;

    /**
     * The last card goes out — but it still does what it says.
     *
     * <p>Under the official rules a +2 or +4 played as the last card still makes the next
     * player draw, and it has to happen BEFORE the win is declared: once {@code over} is set
     * there is no turn left to move and nobody to hand the penalty to.
     */
    private PlayResult finishWin(List<Card> cards, UUID player, int drawAmount) {
        UUID hit = peek(1);
        int owed = drawAmount + pendingDraw; // a win off the back of a stack still collects
        int penalty = owed > 0 ? drawTo(hit, owed) : 0;
        pendingDraw = 0;
        pendingDrawKind = null;
        over = true;
        winner = player;
        calledUno.remove(player);
        lastEvent = namer.apply(player) + " played " + label(cards) + " and won!"
                + (penalty > 0 ? " " + namer.apply(hit) + " still draws " + penalty + "." : "");
        return PlayResult.win(cards.get(cards.size() - 1), penalty > 0 ? hit : null);
    }

    /**
     * The colour that was showing when this play started — what a +4 must NOT have matched.
     *
     * <p>Captured in {@link #commitPlay}, not read back off the discard pile. A +4 is a wild,
     * so by the time the challenge is set up its player has already chosen a new colour and
     * {@code activeColor} is that choice; and the card underneath may itself be a wild, whose
     * own colour says nothing. Either way, reconstructing it after the fact convicts the wrong
     * people.
     */
    private Card.Color colorBeforePlay;

    /** Every hand moves one seat in the direction of play — the "0" half of seven-O. */
    private void rotateHands() {
        int n = players.size();
        List<List<Card>> moved = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // Take from the player the direction of play came FROM, so hands travel with it.
            int from = (((i - direction) % n) + n) % n;
            moved.add(hands.get(players.get(from)));
        }
        for (int i = 0; i < n; i++) {
            hands.put(players.get(i), moved.get(i));
        }
        recheckUnoCalls();
    }

    /** A hand that is no longer on one card can't still be holding a called UNO. */
    private void recheckUnoCalls() {
        calledUno.removeIf(p -> handSize(p) != 1);
    }

    private String label(List<Card> cards) {
        if (cards.size() == 1) {
            return cards.get(0).label();
        }
        return cards.size() + "x " + cards.get(0).label();
    }

    private String describe(List<Card> cards, UUID player, UUID skipped, int reverses) {
        String who = namer.apply(player);
        String what = label(cards);
        if (skipped != null) {
            return who + " played " + what + " — " + namer.apply(skipped) + " skipped";
        }
        if (reverses > 0) {
            return who + " played " + what + " — direction flipped";
        }
        if (cards.get(cards.size() - 1).isWild()) {
            return who + " played " + what + " — colour is now " + activeColor.lower();
        }
        return who + " played " + what;
    }

    // --------------------------------------------------------------- calling UNO

    /**
     * Call UNO for yourself. Only means anything while you are actually on one card.
     *
     * @return true if the call landed (and so closes the callout window on you).
     */
    public boolean callUno(UUID player) {
        if (over || handSize(player) != 1) {
            return false;
        }
        return calledUno.add(player);
    }

    /** True if this player is on one card and has already called it. */
    public boolean hasCalledUno(UUID player) {
        return calledUno.contains(player);
    }

    /** True if this player could be called out right now: one card, no call. */
    public boolean isExposed(UUID player) {
        return !over && handSize(player) == 1 && !calledUno.contains(player);
    }

    /**
     * Call somebody out for sitting on one card without saying so.
     *
     * <p>Getting it wrong costs the accuser, which is the only thing stopping everyone
     * spamming a call-out at every player on every turn.
     *
     * @return what happened, for the caller to broadcast.
     */
    public CalloutResult callOut(UUID accuser, UUID target) {
        if (over || !rules.unoCallout()) {
            return CalloutResult.notAllowed();
        }
        if (accuser.equals(target) || !hands.containsKey(target) || !hands.containsKey(accuser)) {
            return CalloutResult.notAllowed();
        }
        if (isExposed(target)) {
            int drawn = drawTo(target, rules.unoPenalty());
            moves++;
            lastEvent = namer.apply(target) + " never called UNO — caught by "
                    + namer.apply(accuser) + ", draws " + drawn;
            return CalloutResult.caught(target, drawn);
        }
        int drawn = drawTo(accuser, rules.falseCalloutPenalty());
        moves++;
        lastEvent = namer.apply(accuser) + " called out " + namer.apply(target)
                + " for nothing — draws " + drawn;
        return CalloutResult.wrong(accuser, drawn);
    }

    /** Outcome of a call-out, so GameManager can say what happened without re-deriving it. */
    public record CalloutResult(boolean allowed, boolean caught, UUID punished, int drawn) {
        static CalloutResult notAllowed() {
            return new CalloutResult(false, false, null, 0);
        }

        static CalloutResult caught(UUID target, int drawn) {
            return new CalloutResult(true, true, target, drawn);
        }

        static CalloutResult wrong(UUID accuser, int drawn) {
            return new CalloutResult(true, false, accuser, drawn);
        }
    }

    /** Deal {@code n} penalty cards, stopping early if the deck is genuinely exhausted. */
    private int drawTo(UUID p, int n) {
        List<Card> hand = hands.get(p);
        if (hand == null) {
            return 0;
        }
        int given = 0;
        for (int i = 0; i < n; i++) {
            Card c = deck.draw(discard);
            if (c == null) {
                break;
            }
            hand.add(c);
            given++;
        }
        return given;
    }

    private void advance(int steps) {
        int n = players.size();
        turn = (((turn + direction * steps) % n) + n) % n;
    }

    private UUID peek(int steps) {
        int n = players.size();
        return players.get((((turn + direction * steps) % n) + n) % n);
    }

    /** Result of a play/draw, for GameManager to act on. */
    public static final class PlayResult {
        public enum Status {
            OK, ILLEGAL, NEED_COLOR, WIN, DREW,
            /** A draw card went down and the next player may answer it (stacking is on). */
            STACKED,
            /** A 7 went down under seven-O and its player owes us somebody to swap with. */
            NEED_SWAP,
            /** A +4 went down and its target may challenge it before drawing. */
            NEED_CHALLENGE,
            /** A +4 challenge was settled. */
            CHALLENGED
        }

        public final Status status;
        public final String message;
        public final Card card;
        /**
         * Who the card landed on — the player skipped or made to draw — or null for a card
         * that only affects the table. The turn has already moved past them by the time the
         * caller sees this, so it cannot be worked out after the fact.
         */
        public final UUID target;
        /** How many cards actually changed hands, for the message and the effects. */
        public final int amount;
        /** For {@link Status#CHALLENGED}: whether the challenge was upheld. */
        public final boolean upheld;

        private PlayResult(Status status, String message, Card card, UUID target,
                           int amount, boolean upheld) {
            this.status = status;
            this.message = message;
            this.card = card;
            this.target = target;
            this.amount = amount;
            this.upheld = upheld;
        }

        public static PlayResult ok(Card card) {
            return ok(card, null);
        }

        public static PlayResult ok(Card card, UUID target) {
            return new PlayResult(Status.OK, null, card, target, 0, false);
        }

        public static PlayResult illegal(String message) {
            return new PlayResult(Status.ILLEGAL, message, null, null, 0, false);
        }

        public static PlayResult needColor() {
            return new PlayResult(Status.NEED_COLOR, null, null, null, 0, false);
        }

        public static PlayResult win(Card card) {
            return win(card, null);
        }

        /** A win whose final card still landed a penalty on somebody ({@code target}). */
        public static PlayResult win(Card card, UUID target) {
            return new PlayResult(Status.WIN, null, card, target, 0, false);
        }

        /** {@code card} is null when the deck had nothing left to give. */
        public static PlayResult drew(Card card) {
            return drew(card, card == null ? 0 : 1);
        }

        public static PlayResult drew(Card card, int amount) {
            return new PlayResult(Status.DREW, null, card, null, amount, false);
        }

        /** {@code target} must now stack or take {@code amount} cards. */
        public static PlayResult stacked(Card card, UUID target, int amount) {
            return new PlayResult(Status.STACKED, null, card, target, amount, false);
        }

        public static PlayResult needSwap(Card card) {
            return new PlayResult(Status.NEED_SWAP, null, card, null, 0, false);
        }

        public static PlayResult needChallenge(Card card, UUID target) {
            return new PlayResult(Status.NEED_CHALLENGE, null, card, target, 4, false);
        }

        public static PlayResult challenged(Card card, UUID punished, int amount, boolean upheld) {
            return new PlayResult(Status.CHALLENGED, null, card, punished, amount, upheld);
        }
    }
}
