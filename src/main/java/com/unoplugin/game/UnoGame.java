package com.unoplugin.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * One UNO game: hands, draw/discard piles, turn order &amp; direction, the active colour,
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

    /** Resolves a player UUID to a display name (GameManager supplies the real one). */
    private Function<UUID, String> namer = p -> p.toString().substring(0, 8);

    public UnoGame(UUID id, List<UUID> players) {
        this.id = id;
        this.players = new ArrayList<>(players);
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
            if (h.get(i).playableOn(top(), activeColor)) {
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
                if (h.get(i).playableOn(top(), activeColor)) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    public boolean hasLegalMove(UUID p) {
        return firstLegalIndex(p) >= 0;
    }

    // ------------------------------------------------------------------- actions

    /** Play the card at {@code handIndex} for {@code player}. */
    public PlayResult play(UUID player, int handIndex) {
        if (over) {
            return PlayResult.illegal("The game is over.");
        }
        if (pendingColorPlayer != null) {
            return PlayResult.illegal("Waiting for a colour choice.");
        }
        if (!player.equals(currentPlayer())) {
            return PlayResult.illegal("It's not your turn.");
        }
        List<Card> hand = hands.get(player);
        if (hand == null || handIndex < 0 || handIndex >= hand.size()) {
            return PlayResult.illegal("No such card.");
        }
        Card card = hand.get(handIndex);
        if (!card.playableOn(top(), activeColor)) {
            return PlayResult.illegal("Can't play " + card.label() + " on " + top().label()
                    + " (" + activeColor.lower() + ").");
        }
        hand.remove(handIndex);
        discard.add(card);
        moves++;
        if (card.isWild()) {
            pendingColorPlayer = player;
            pendingColorCard = card;
            return PlayResult.needColor();
        }
        activeColor = card.color();
        return resolve(card, player);
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
        Card card = pendingColorCard;
        pendingColorPlayer = null;
        pendingColorCard = null;
        moves++;
        return resolve(card, player);
    }

    /** Draw one card and pass the turn. */
    public PlayResult draw(UUID player) {
        if (over) {
            return PlayResult.illegal("The game is over.");
        }
        if (pendingColorPlayer != null) {
            return PlayResult.illegal("Waiting for a colour choice.");
        }
        if (!player.equals(currentPlayer())) {
            return PlayResult.illegal("It's not your turn.");
        }
        Card c = deck.draw(discard);
        moves++;
        if (c == null) {
            // Nothing left anywhere. Rebuilding the deck here would put a second copy of
            // every card players are holding into play, so the turn simply passes.
            lastEvent = namer.apply(player) + " had nothing to draw — the turn passes";
            advance(1);
            return PlayResult.drew(null);
        }
        hands.get(player).add(c);
        lastEvent = namer.apply(player) + " drew a card";
        advance(1);
        return PlayResult.drew(c);
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
        if (player.equals(pendingColorPlayer)) {
            pendingColorPlayer = null;
            pendingColorCard = null;
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
        if (hands.get(player).isEmpty()) {
            // A card still does what it says when it is the one that goes out: under the
            // official rules a +2 or +4 played as the last card still makes the next player
            // draw. It has to happen BEFORE the win is declared — once `over` is set there is
            // no turn left to move and no "next player" to hand the penalty to.
            UUID hit = peek(1);
            int penalty = switch (card.kind()) {
                case DRAW2 -> drawTo(hit, 2);
                case WILD_DRAW4 -> drawTo(hit, 4);
                default -> 0;
            };
            over = true;
            winner = player;
            lastEvent = namer.apply(player) + " played " + card.label() + " and won!"
                    + (penalty > 0 ? " " + namer.apply(hit) + " still draws " + penalty + "." : "");
            return PlayResult.win(card, penalty > 0 ? hit : null);
        }
        UUID next = peek(1);
        UUID affected = null; // whoever the card lands on, for the caller to react to
        switch (card.kind()) {
            case SKIP -> {
                advance(2);
                affected = next;
                lastEvent = namer.apply(player) + " played Skip — " + namer.apply(next) + " skipped";
            }
            case REVERSE -> {
                if (players.size() == 2) {
                    advance(2);
                    lastEvent = namer.apply(player) + " played Reverse (skip in 2-player)";
                } else {
                    direction = -direction;
                    advance(1);
                    lastEvent = namer.apply(player) + " played Reverse — direction flipped";
                }
            }
            case DRAW2 -> {
                int got = drawTo(next, 2);
                advance(2);
                affected = next;
                lastEvent = namer.apply(player) + " played +2 — " + namer.apply(next)
                        + " draws " + got + " & skipped";
            }
            case WILD -> {
                advance(1);
                lastEvent = namer.apply(player) + " played Wild — colour is now " + activeColor.lower();
            }
            case WILD_DRAW4 -> {
                int got = drawTo(next, 4);
                advance(2);
                affected = next;
                lastEvent = namer.apply(player) + " played Wild +4 — " + namer.apply(next)
                        + " draws " + got + " & skipped; colour " + activeColor.lower();
            }
            default -> {
                advance(1);
                lastEvent = namer.apply(player) + " played " + card.label();
            }
        }
        return PlayResult.ok(card, affected);
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
        public enum Status { OK, ILLEGAL, NEED_COLOR, WIN, DREW }

        public final Status status;
        public final String message;
        public final Card card;
        /**
         * Who the card landed on — the player skipped or made to draw — or null for a card
         * that only affects the table. The turn has already moved past them by the time the
         * caller sees this, so it cannot be worked out after the fact.
         */
        public final UUID target;

        private PlayResult(Status status, String message, Card card, UUID target) {
            this.status = status;
            this.message = message;
            this.card = card;
            this.target = target;
        }

        public static PlayResult ok(Card card) {
            return ok(card, null);
        }

        public static PlayResult ok(Card card, UUID target) {
            return new PlayResult(Status.OK, null, card, target);
        }

        public static PlayResult illegal(String message) {
            return new PlayResult(Status.ILLEGAL, message, null, null);
        }

        public static PlayResult needColor() {
            return new PlayResult(Status.NEED_COLOR, null, null, null);
        }

        public static PlayResult win(Card card) {
            return win(card, null);
        }

        /** A win whose final card still landed a penalty on somebody ({@code target}). */
        public static PlayResult win(Card card, UUID target) {
            return new PlayResult(Status.WIN, null, card, target);
        }

        /** {@code card} is null when the deck had nothing left to give. */
        public static PlayResult drew(Card card) {
            return new PlayResult(Status.DREW, null, card, null);
        }
    }
}
