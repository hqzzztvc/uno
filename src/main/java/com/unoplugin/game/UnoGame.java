package com.unoplugin.game;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * One UNO game: hands, draw/discard piles, turn order & direction, the active colour,
 * and all the rules (legal-move checking, card effects, wild colour choice, win).
 * Pure logic — rendering, messaging and bots live in {@code GameManager}.
 */
public final class UnoGame {

    private final UUID tableId;
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

    /** Resolves a player UUID to a display name (GameManager overrides for bots). */
    private Function<UUID, String> namer = UnoGame::bukkitName;

    public UnoGame(UUID tableId, List<UUID> players) {
        this.tableId = tableId;
        this.players = new ArrayList<>(players);
    }

    public void setNamer(Function<UUID, String> namer) {
        this.namer = namer;
    }

    /** Shuffle, deal 7 each, flip a number card to start. */
    public void start() {
        deck.reset();
        hands.clear();
        discard.clear();
        for (UUID p : players) {
            List<Card> h = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                h.add(deck.draw(discard));
            }
            hands.put(p, h);
        }
        Card first;
        do {
            first = deck.draw(discard);
        } while (first.kind() != Card.Kind.NUMBER); // simple, effect-free starting card
        discard.add(first);
        activeColor = first.color();
        turn = 0;
        direction = 1;
        over = false;
        winner = null;
        pendingColorPlayer = null;
        pendingColorCard = null;
        lastEvent = "Game started";
    }

    // ----------------------------------------------------------------- accessors

    public UUID tableId() {
        return tableId;
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

    public UUID pendingColorPlayer() {
        return pendingColorPlayer;
    }

    public String lastEvent() {
        return lastEvent;
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
        if (pendingColorPlayer == null || !player.equals(pendingColorPlayer)) {
            return PlayResult.illegal("No colour choice pending.");
        }
        activeColor = color;
        Card card = pendingColorCard;
        pendingColorPlayer = null;
        pendingColorCard = null;
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
        if (hand != null && !hand.isEmpty() && discard.size() >= 1) {
            discard.addAll(discard.size() - 1, hand); // below the top card — still the live top
        }
        players.remove(idx);
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
            over = true;
            winner = player;
            lastEvent = namer.apply(player) + " played " + card.label() + " and won!";
            return PlayResult.win(card);
        }
        UUID next = peek(1);
        switch (card.kind()) {
            case SKIP -> {
                advance(2);
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
                drawTo(next, 2);
                advance(2);
                lastEvent = namer.apply(player) + " played +2 — " + namer.apply(next) + " draws 2 & skipped";
            }
            case WILD -> {
                advance(1);
                lastEvent = namer.apply(player) + " played Wild — colour is now " + activeColor.lower();
            }
            case WILD_DRAW4 -> {
                drawTo(next, 4);
                advance(2);
                lastEvent = namer.apply(player) + " played Wild +4 — " + namer.apply(next)
                        + " draws 4 & skipped; colour " + activeColor.lower();
            }
            default -> {
                advance(1);
                lastEvent = namer.apply(player) + " played " + card.label();
            }
        }
        return PlayResult.ok(card);
    }

    private void drawTo(UUID p, int n) {
        for (int i = 0; i < n; i++) {
            hands.get(p).add(deck.draw(discard));
        }
    }

    private void advance(int steps) {
        int n = players.size();
        turn = (((turn + direction * steps) % n) + n) % n;
    }

    private UUID peek(int steps) {
        int n = players.size();
        return players.get((((turn + direction * steps) % n) + n) % n);
    }

    private static String bukkitName(UUID p) {
        String nm = Bukkit.getOfflinePlayer(p).getName();
        return nm != null ? nm : p.toString().substring(0, 8);
    }

    /** Result of a play/draw, for GameManager to act on. */
    public static final class PlayResult {
        public enum Status { OK, ILLEGAL, NEED_COLOR, WIN, DREW }

        public final Status status;
        public final String message;
        public final Card card;

        private PlayResult(Status status, String message, Card card) {
            this.status = status;
            this.message = message;
            this.card = card;
        }

        public static PlayResult ok(Card card) {
            return new PlayResult(Status.OK, null, card);
        }

        public static PlayResult illegal(String message) {
            return new PlayResult(Status.ILLEGAL, message, null);
        }

        public static PlayResult needColor() {
            return new PlayResult(Status.NEED_COLOR, null, null);
        }

        public static PlayResult win(Card card) {
            return new PlayResult(Status.WIN, null, card);
        }

        public static PlayResult drew(Card card) {
            return new PlayResult(Status.DREW, null, card);
        }
    }
}
