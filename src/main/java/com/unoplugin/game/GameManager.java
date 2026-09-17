package com.unoplugin.game;

import com.unoplugin.hand.HandManager;
import com.unoplugin.table.TableManager;
import com.unoplugin.table.UnoTable;
import com.unoplugin.util.Fx;
import com.unoplugin.util.Messages;
import com.unoplugin.util.NameCache;
import com.unoplugin.util.Settings;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns running {@link UnoGame}s, wires them to the {@link HandManager} (each player's fan),
 * shows a status bossbar, asks for a wild's colour, drives simple bot opponents, and keeps
 * an idle player from freezing the table.
 * Implements {@link HandManager.CardActions} so play/draw input flows into the game rules.
 */
public final class GameManager implements Listener, HandManager.CardActions {

    private final Plugin plugin;
    private final HandManager handManager;
    private final Messages messages;
    private final Settings settings;
    private final NameCache names;
    private final Fx fx;
    private TableManager tableManager;                              // set after construction

    private final Map<UUID, UnoGame> games = new HashMap<>();      // gameId -> game
    private final Map<UUID, UUID> playerGame = new HashMap<>();    // participant -> gameId
    private final Map<UUID, UUID> gameTable = new HashMap<>();     // gameId -> table it's at
    private final Map<UUID, BossBar> bars = new HashMap<>();       // gameId -> bossbar
    /**
     * gameId -> the "your turn" bar, shown only to whoever the table is waiting on.
     *
     * <p>A second bar rather than the shared one reworded, because an Adventure bar is one
     * object every viewer sees — and a separate bar rather than an action bar, because the
     * fan rewrites the action bar on every scroll and would wipe the notice within a second.
     */
    private final Map<UUID, BossBar> turnBars = new HashMap<>();
    private final Map<UUID, PileRenderer> piles = new HashMap<>(); // gameId -> table piles
    private final Map<UUID, List<BukkitTask>> turnTasks = new HashMap<>(); // gameId -> idle timers
    private final Map<UUID, String> botNames = new HashMap<>();    // bot id -> name
    private final Map<UUID, UUID> lastTurn = new HashMap<>();      // gameId -> who it was on
    private final Map<UUID, Set<UUID>> onOneCard = new HashMap<>(); // gameId -> already "UNO!"d
    private final Map<UUID, Set<UUID>> tableReady = new HashMap<>(); // tableId -> said "ready"
    private final Set<UUID> bots = new HashSet<>();
    private GameListener listener;                                  // optional (the bet layer)

    /**
     * How far each pile sits from the table centre, along the axis set in {@link #launch}.
     *
     * <p>A card is about half a block across at the pile scale, so the two piles have to be
     * more than that apart or they overlap into one shape whichever way you look at them.
     */
    private static final double PILE_SPREAD = 0.55;

    /**
     * The "your turn" title is brief on purpose: a quick player has often already played by
     * the time a standard-length title would fade, and it would still be telling them it's
     * their turn. The turn bar is what lasts.
     */
    private static final Title.Times TURN_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(250));

    /** Lets another subsystem (the wagering layer) react to how a hand ends. */
    public interface GameListener {
        /** The hand is over. {@code winner} is null if it ended without one, and may be a bot. */
        void onGameEnd(UUID gameId, UUID winner);

        /** A player dropped out mid-hand (disconnect or /uno quit). The hand carries on. */
        void onForfeit(UUID gameId, UUID player);

        /** Is there a live pot on this table? Guards /uno stop against settling a wager. */
        boolean hasPotAtTable(UUID tableId);
    }

    public GameManager(Plugin plugin, HandManager handManager, Messages messages,
                       Settings settings, NameCache names, Fx fx) {
        this.plugin = plugin;
        this.handManager = handManager;
        this.messages = messages;
        this.settings = settings;
        this.names = names;
        this.fx = fx;
    }

    /** Wire in the table registry so piles can centre on the host's seated table. */
    public void setTableManager(TableManager tableManager) {
        this.tableManager = tableManager;
    }

    public void setGameListener(GameListener listener) {
        this.listener = listener;
    }

    public boolean isInGame(UUID playerId) {
        return playerGame.containsKey(playerId);
    }

    public boolean isBotId(UUID id) {
        return bots.contains(id);
    }

    // ------------------------------------------------------------- start a game

    /** Start a solo test game: the host plus {@code botCount} simple bots. */
    public void startTest(Player host, int botCount) {
        if (playerGame.containsKey(host.getUniqueId())) {
            messages.send(host, "game.already-in-game");
            return;
        }
        if (tableManager == null) {
            messages.send(host, "game.tables-unavailable");
            return;
        }
        // A hand needs a table to be played on. Without one the piles land on the ground
        // wherever the caller happens to be standing — cards in the dirt, nothing for anyone
        // else to walk up to, and no table id for BusyCheck to protect the hand with.
        UnoTable table = tableManager.seatedTable(host.getUniqueId());
        if (table == null) {
            messages.send(host, "game.sit-first-play");
            return;
        }
        // Two hands dealt at one table put both sets of piles on the same square of felt and
        // leave BusyCheck unable to say which one is holding it. startSeated already refuses
        // this by way of every seated player's playerGame; a solo bot game has to check the
        // table itself, because the other hand may be nobody who is sitting here now.
        if (hasGameAtTable(table.id())) {
            messages.send(host, "game.table-in-use");
            return;
        }
        if (hasOpenPot(table.id())) {
            messages.send(host, "game.table-has-bet");
            return;
        }
        // A table has four stairs, so dealing to more than that leaves the extras standing
        // around a hand they can't sit at. startSeated already clamps to the same limit.
        botCount = Math.max(1, Math.min(botCount, table.maxPlayers() - 1));
        List<UUID> players = new ArrayList<>();
        players.add(host.getUniqueId());
        for (int i = 1; i <= botCount; i++) {
            players.add(newBot("Bot " + i));
        }
        launch(players, table);
    }

    /** Start a real game with everyone seated at the initiator's table, plus optional bots. */
    public void startSeated(Player initiator, int extraBots) {
        if (tableManager == null) {
            messages.send(initiator, "game.tables-unavailable");
            return;
        }
        UnoTable table = tableManager.seatedTable(initiator.getUniqueId());
        if (table == null) {
            messages.send(initiator, "game.sit-first");
            return;
        }
        if (hasOpenPot(table.id())) {
            // The ante's heap sits in the middle of the felt, where the piles would land, and
            // the people anteing up agreed to a hand for stakes — not to a friendly one dealt
            // over the top of it. The bet deals its own hand when everyone locks in.
            messages.send(initiator, "game.table-has-bet");
            return;
        }
        List<UUID> humans = new ArrayList<>();
        for (UUID u : tableManager.seatedPlayersAt(table.id())) {
            if (playerGame.containsKey(u)) {
                messages.send(initiator, "game.other-in-game", "player", displayName(u));
                return;
            }
            if (Bukkit.getPlayer(u) != null) {
                humans.add(u);
            }
        }
        int max = table.maxPlayers();
        if (humans.size() > max) {
            humans = new ArrayList<>(humans.subList(0, max));
        }
        extraBots = Math.max(0, Math.min(extraBots, max - humans.size()));
        List<UUID> players = new ArrayList<>(humans);
        for (int i = 1; i <= extraBots; i++) {
            players.add(newBot("Bot " + i));
        }
        if (players.size() < 2) {
            messages.send(initiator, "game.need-players");
            return;
        }
        launch(players, table);
    }

    /**
     * Start a hand for an exact player list decided elsewhere (the wagering layer picks who
     * has anted up). Returns the game id so the caller can tie its pot to this hand, or null
     * if someone in the list is already playing.
     */
    public UUID startWager(List<UUID> humans, int extraBots, UnoTable table) {
        for (UUID u : humans) {
            if (playerGame.containsKey(u)) {
                return null;
            }
        }
        // Bail out BEFORE minting bots: newBot registers them in `bots`/`botNames` for good,
        // so giving up afterwards leaks a UUID and a name on every abandoned attempt.
        if (humans.size() + extraBots < 2) {
            return null;
        }
        List<UUID> players = new ArrayList<>(humans);
        for (int i = 1; i <= extraBots; i++) {
            players.add(newBot("Bot " + i));
        }
        return launch(players, table);
    }

    private UUID newBot(String name) {
        UUID b = UUID.randomUUID();
        bots.add(b);
        botNames.put(b, name);
        return b;
    }

    /**
     * Common game bring-up for a fixed player list, with the piles centred on {@code table}'s
     * surface.
     *
     * <p>Every caller holds a real table by the time it gets here — a hand dealt without one
     * put its piles on the ground wherever the caller happened to be standing, and left
     * {@link #hasGameAtTable} with nothing to protect the hand by.
     */
    private UUID launch(List<UUID> players, UnoTable table) {
        UUID gameId = UUID.randomUUID();
        UnoGame game = new UnoGame(gameId, players, settings.rules());
        game.setNamer(this::displayName);
        game.start(settings.startingHandSize());
        games.put(gameId, game);
        gameTable.put(gameId, table.id());
        for (UUID p : players) {
            playerGame.put(p, gameId);
        }
        bars.put(gameId, BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
        turnBars.put(gameId, BossBar.bossBar(messages.get("game.your-turn-bar"), 1f,
                BossBar.Color.GREEN, BossBar.Overlay.PROGRESS));

        Location centre = table.anchor();
        float pileYaw = table.yaw();
        double r = Math.toRadians(pileYaw);
        // The piles are laid out on the diagonal, BETWEEN the seats, and that is the whole
        // point of the 45°. Seats sit at the four cardinal points of the table, so putting
        // the piles on the forward or the right axis lines them up nose-to-tail for the two
        // seats on that axis: the draw pile is a solid block of cards three times the height
        // of the flat discard heap, and at seated eye height it hides the discard completely.
        // Off the seat axes, every seat sees the two piles side by side instead.
        double axis = r + Math.PI / 4;
        Vector spread = new Vector(Math.cos(axis), 0, Math.sin(axis));
        double top = centre.getY() + UnoTable.SURFACE_Y; // bottom card flush on the felt
        Location discardLoc = centre.clone().add(spread.clone().multiply(-PILE_SPREAD));
        discardLoc.setY(top);
        Location drawLoc = centre.clone().add(spread.clone().multiply(PILE_SPREAD));
        drawLoc.setY(top);

        PileRenderer pile = new PileRenderer(plugin, discardLoc, drawLoc, pileYaw);
        pile.spawn(game.top(), game.drawPileSize());
        piles.put(gameId, pile);

        for (UUID p : players) {
            if (isBot(p)) {
                continue;
            }
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                messages.send(pl, "game.started", "count", players.size());
                messages.send(pl, "game.controls");
                messages.title(pl, "game.start-title", "game.start-subtitle",
                        "count", players.size());
            }
        }
        fx.deal(pile.discardLocation());
        afterMove(game); // renders every hand, shows the bar, drives the first bot if needed
        return gameId;
    }

    // ---------------------------------------------------------- ready-up / deal

    /**
     * "I'm in" — {@code /uno ready}, or the button that arrives when you sit down.
     *
     * <p>This is the casual half of the front door, and it exists to delete a step. Getting a
     * game going used to mean everyone sat, and then one person who knew the command typed
     * {@code /uno start}; whoever that was had to notice the table was full, and everyone else
     * had to wait on them. Now each player says once that they're in, and the hand deals
     * itself the moment the last one does. Nobody is dealt into a hand they hadn't agreed to.
     *
     * <p>{@code /uno start} still deals on demand, for the table that doesn't want to wait
     * for the friend who is AFK.
     */
    public void ready(Player player) {
        if (tableManager == null) {
            messages.send(player, "game.tables-unavailable");
            return;
        }
        UUID id = player.getUniqueId();
        UnoTable table = tableManager.seatedTable(id);
        if (table == null) {
            messages.send(player, "game.sit-first");
            return;
        }
        if (playerGame.containsKey(id)) {
            messages.send(player, "game.already-in-game");
            return;
        }
        if (hasGameAtTable(table.id())) {
            messages.send(player, "game.table-in-use");
            return;
        }
        Set<UUID> readySet = tableReady.computeIfAbsent(table.id(), k -> new LinkedHashSet<>());
        if (!readySet.add(id)) {
            messages.send(player, "game.already-ready");
            return;
        }
        // Someone may have stood up since they said ready. Reconciling against the seats here
        // rather than trusting the set is what lets the "everyone's in" test below be an
        // exact match instead of a guess.
        List<UUID> seated = tableManager.seatedPlayersAt(table.id());
        readySet.retainAll(seated);

        broadcastTable(table.id(), messages.get("game.ready",
                "player", player.getName(), "ready", readySet.size(), "seated", seated.size()));

        if (readySet.size() == seated.size() && seated.size() >= table.minPlayers()) {
            startSeated(player, 0);
            return;
        }
        if (seated.size() < table.minPlayers()) {
            messages.send(player, "game.ready-alone");
        } else {
            messages.send(player, "game.ready-waiting",
                    "count", seated.size() - readySet.size(),
                    "deal", messages.button("game.deal-now-button", "/uno start"));
        }
    }

    /** Everyone at this table is back to undecided — used when stakes enter the picture. */
    public void clearReady(UUID tableId) {
        tableReady.remove(tableId);
    }

    /**
     * Standing up: drop out of the hand AND withdraw a pending "ready".
     *
     * <p>Wired as one half of the table layer's stand-up hook so both happen on one route,
     * whether the player typed {@code /uno leave}, pressed shift, or disconnected. The other
     * half is {@code BetManager.onStandUp} — during an ante there is no hand here to forfeit,
     * and a stake left behind by somebody who has walked away is the bug that pairing fixes.
     */
    public boolean onStandUp(Player player, UUID tableId) {
        Set<UUID> readySet = tableReady.get(tableId);
        if (readySet != null) {
            readySet.remove(player.getUniqueId());
        }
        return forfeit(player);
    }

    /** Withdraw one player's "ready" wherever it was given. */
    private void clearReadyFor(UUID playerId) {
        for (Set<UUID> readySet : tableReady.values()) {
            readySet.remove(playerId);
        }
    }

    /** Offer everyone still sitting at a table the casual/stakes choice again. */
    private void promptTable(UUID tableId) {
        if (tableManager != null && tableId != null) {
            tableManager.promptModeAt(tableId, "table.again");
        }
    }

    /** Say something to everyone sitting at a table, in or out of a hand. */
    private void broadcastTable(UUID tableId, Component message) {
        if (tableManager == null) {
            return;
        }
        for (UUID id : tableManager.seatedPlayersAt(tableId)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    // ------------------------------------------------------- HandManager hooks

    @Override
    public boolean play(Player player, int selectedIndex) {
        UnoGame game = gameOf(player.getUniqueId());
        if (game == null) {
            return false;
        }
        UUID id = player.getUniqueId();
        // Out of turn, the same click means jump-in if the rule is on. Trying it here rather
        // than making the player press something else is the point of the rule: you spot the
        // matching card and slap it down. It falls through to the ordinary "not your turn"
        // refusal when the card doesn't match, so nothing is lost when it isn't a jump-in.
        if (game.rules().jumpIn() && !id.equals(game.currentPlayer())) {
            UnoGame.PlayResult jump = game.jumpIn(id, selectedIndex);
            if (jump.status != UnoGame.PlayResult.Status.ILLEGAL) {
                broadcast(game, messages.get("game.jump-in", "player", displayName(id)));
                handleResult(game, id, jump, player);
                return true;
            }
        }
        handleResult(game, id, game.play(id, selectedIndex), player);
        return true;
    }

    @Override
    public boolean draw(Player player) {
        UnoGame game = gameOf(player.getUniqueId());
        if (game == null) {
            return false;
        }
        handleResult(game, player.getUniqueId(), game.draw(player.getUniqueId()), player);
        return true;
    }

    // ----------------------------------------------------------- result flow

    private void handleResult(UnoGame game, UUID actor, UnoGame.PlayResult r, Player actorPlayer) {
        switch (r.status) {
            case ILLEGAL -> {
                if (actorPlayer != null) {
                    actorPlayer.sendActionBar(Component.text(r.message, NamedTextColor.RED));
                    fx.denied(actorPlayer);
                }
            }
            case NEED_COLOR -> {
                if (isBot(actor)) {
                    handleResult(game, actor, game.chooseColor(actor, botWildColor(game, actor)), null);
                } else {
                    if (actorPlayer != null) {
                        promptColor(actorPlayer);
                    }
                    // An unanswered colour prompt stalls the table just as hard as an idle turn.
                    armTurnTimer(game, actor);
                }
            }
            case NEED_SWAP -> {
                updateDiscard(game, r.card);
                if (isBot(actor)) {
                    handleResult(game, actor, game.chooseSwap(actor, botSwapTarget(game, actor)), null);
                } else {
                    if (actorPlayer != null) {
                        openSwapGui(actorPlayer, game);
                    }
                    // An unanswered swap stalls the table exactly like an unanswered wild.
                    armTurnTimer(game, actor);
                }
            }
            case NEED_CHALLENGE -> {
                updateDiscard(game, r.card);
                playEffects(game, r);
                UUID victim = r.target;
                if (isBot(victim)) {
                    // A bot never challenges: it has no way to reason about the bluff, and
                    // guessing would just tax whoever it is sitting next to.
                    handleResult(game, victim, game.respondToDraw4(victim, false), null);
                } else {
                    promptChallenge(game, victim);
                    armTurnTimer(game, victim);
                }
                renderHands(game);
                updateBar(game);
            }
            case CHALLENGED -> {
                afterMove(game);
            }
            case STACKED -> {
                updateDiscard(game, r.card);
                playEffects(game, r);
                announceStack(game, r);
                afterMove(game);
            }
            case DREW -> {
                drawEffects(game, r, actorPlayer);
                afterMove(game);
            }
            case OK -> {
                updateDiscard(game, r.card);
                playEffects(game, r);
                afterMove(game);
            }
            case WIN -> {
                updateDiscard(game, r.card);
                playEffects(game, r);
                winEffects(game, actor);
                afterMove(game); // broadcasts the win + final state (no bot move since over)
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> endGame(game), 40L);
            }
        }
    }

    /** Drop the just-played card onto the discard pile, face-up. */
    private void updateDiscard(UnoGame game, Card card) {
        PileRenderer pile = piles.get(game.id());
        if (pile == null || card == null) {
            return;
        }
        pile.addToDiscard(card);
    }

    // ------------------------------------------------------------------- effects

    /**
     * The sound and light of a played card: the snap on the pile in the card's own colour,
     * then whatever that particular card did to somebody.
     *
     * <p>A card's victim is read off {@link UnoGame.PlayResult#target} rather than worked out
     * here — by the time this runs the turn has already moved past them.
     */
    private void playEffects(UnoGame game, UnoGame.PlayResult r) {
        PileRenderer pile = piles.get(game.id());
        if (pile == null || r.card == null) {
            return;
        }
        Location table = pile.discardLocation();
        Card card = r.card;
        Color colour = dustColor(card.isWild() ? game.activeColor() : card.color());
        fx.cardPlayed(table, colour);
        Location victim = locationOf(r.target, table);
        switch (card.kind()) {
            case SKIP -> fx.skip(victim);
            case REVERSE -> fx.reverse(table);
            case DRAW2 -> fx.penalty(victim, 2);
            case WILD -> fx.wild(table, colour);
            case WILD_DRAW4 -> {
                fx.wild(table, colour);
                fx.penalty(victim, 4);
            }
            default -> { }
        }
    }

    /** A card off the deck — or the flat thunk of a deck with nothing left in it. */
    private void drawEffects(UnoGame game, UnoGame.PlayResult r, Player actorPlayer) {
        PileRenderer pile = piles.get(game.id());
        if (pile == null) {
            return;
        }
        if (r.card == null) {
            fx.deckEmpty(pile.drawLocation());
        } else {
            fx.cardDrawn(actorPlayer, pile.drawLocation());
        }
    }

    /** Fireworks over the table and over whoever just took the hand. */
    private void winEffects(UnoGame game, UUID winner) {
        PileRenderer pile = piles.get(game.id());
        Player won = Bukkit.getPlayer(winner);
        if (pile != null) {
            fx.win(pile.discardLocation(), won);
        }
        for (UUID p : game.players()) {
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                messages.title(pl, "game.win-title", "game.win-subtitle",
                        "player", displayName(winner));
            }
        }
    }

    /**
     * Mark whose turn it is, once per change of turn.
     *
     * <p>Every game event re-renders the table, so this has to fire on the change rather than
     * on the render — otherwise a player gets their turn chime again every time anybody else
     * so much as draws a card.
     *
     * <p>Three signals, because any one of them is easy to miss: a brief title the moment the
     * turn arrives, the chime, and the turn bar, which stays up for as long as the table is
     * waiting on them — so a player who looked away during the title can still tell.
     */
    private void turnEffects(UnoGame game) {
        UUID current = game.currentPlayer();
        UUID previous = lastTurn.put(game.id(), current);
        if (current.equals(previous)) {
            return;
        }
        BossBar turnBar = turnBars.get(game.id());
        Player before = previous == null ? null : Bukkit.getPlayer(previous);
        if (before != null && turnBar != null) {
            before.hideBossBar(turnBar);
        }
        Player pl = Bukkit.getPlayer(current);
        if (pl != null) {
            fx.yourTurn(pl);
            messages.title(pl, "game.your-turn-title", "game.your-turn-subtitle", TURN_TITLE_TIMES);
            if (turnBar != null) {
                pl.showBossBar(turnBar);
            }
        }
        PileRenderer pile = piles.get(game.id());
        fx.turnMarker(locationOf(current, pile == null ? null : pile.discardLocation()));
    }

    /**
     * Announce anyone down to their last card — once, until they pick cards back up.
     *
     * <p>This is the plugin calling UNO <em>for</em> the player, which is what happens when the
     * callout rule is off. With it on, {@link #checkExposure} takes over and the player has to
     * call it themselves.
     */
    private void unoEffects(UnoGame game) {
        Set<UUID> called = onOneCard.computeIfAbsent(game.id(), k -> new HashSet<>());
        PileRenderer pile = piles.get(game.id());
        for (UUID p : game.players()) {
            if (game.handSize(p) != 1) {
                called.remove(p);
                continue;
            }
            if (!called.add(p)) {
                continue;
            }
            broadcast(game, messages.get("game.uno", "player", displayName(p)));
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                messages.title(pl, "game.uno-title", "game.uno-subtitle");
            }
            if (pile != null) {
                fx.uno(pl, pile.discardLocation());
            }
        }
    }

    // ------------------------------------------------------------ UNO call-outs

    /** One open callout window: who is exposed, in which game, and the task that closes it. */
    private record Exposure(UUID gameId, BukkitTask closer, BukkitTask botCall) { }

    /** Keyed by the exposed player — a player is only ever in one game at a time. */
    private final Map<UUID, Exposure> exposures = new HashMap<>();

    /**
     * Open a callout window on anyone newly down to one card, and shut one that no longer applies.
     *
     * <p>Called on every move, so it has to be idempotent: a player already inside their window
     * must not be re-prompted, and the window has to close the moment their hand stops being
     * one card — including because somebody swapped it away from them under seven-O.
     */
    private void checkExposure(UnoGame game) {
        if (!game.rules().unoCallout()) {
            unoEffects(game);
            return;
        }
        for (UUID p : game.players()) {
            Exposure open = exposures.get(p);
            boolean stillOne = game.handSize(p) == 1;
            if (!stillOne || game.isOver()) {
                if (open != null && open.gameId().equals(game.id())) {
                    closeExposure(p, null);
                }
                continue;
            }
            if (game.hasCalledUno(p) || open != null) {
                continue;
            }
            openExposure(game, p);
        }
    }

    /**
     * Start the countdown on a player who has just reached one card.
     *
     * <p>They get a button to call UNO; everybody else gets a button to call them out. Both are
     * click-to-run so nobody has to type a command against a clock — the window is a few
     * seconds and typing a name would decide it on keyboard speed rather than attention.
     */
    private void openExposure(UnoGame game, UUID player) {
        int seconds = game.rules().unoWindowSeconds();
        PileRenderer pile = piles.get(game.id());
        Player pl = Bukkit.getPlayer(player);
        if (pl != null) {
            messages.send(pl, "game.uno-call-prompt", "seconds", seconds,
                    "button", button("game.uno-call-button", "/uno uno"));
            messages.title(pl, "game.uno-title", "game.uno-subtitle");
            fx.uno(pl, pile == null ? null : pile.discardLocation());
        }
        Component calloutButton = button("game.uno-callout-button", "/uno callout " + displayName(player));
        for (UUID other : game.players()) {
            if (other.equals(player) || isBot(other)) {
                continue;
            }
            Player op = Bukkit.getPlayer(other);
            if (op != null) {
                messages.send(op, "game.uno-callout-prompt",
                        "player", displayName(player), "seconds", seconds, "button", calloutButton);
                fx.uno(op, pile == null ? null : pile.discardLocation());
            }
        }

        UUID gid = game.id();
        BukkitTask closer = plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> closeExposure(player, gid), (long) seconds * 20L);
        // A bot calls its own UNO somewhere inside the window rather than instantly, so beating
        // it to the call-out is a real race won by paying attention — not a coin flip, and not
        // an arbitrary "bots sometimes forget" constant.
        BukkitTask botCall = null;
        if (isBot(player)) {
            long delay = 20L + ThreadLocalRandom.current().nextInt(Math.max(1, seconds * 20 - 30));
            botCall = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                UnoGame g = games.get(gid);
                if (g != null && g.callUno(player)) {
                    broadcast(g, messages.get("game.uno-called", "player", displayName(player)));
                    closeExposure(player, gid);
                }
            }, delay);
        }
        exposures.put(player, new Exposure(gid, closer, botCall));
    }

    /**
     * Shut a callout window.
     *
     * <p>{@code gameId} non-null means the countdown ran out: the player survived it and is
     * marked as having called, so they aren't prompted again on the same card. Null means the
     * window is being torn down for another reason (they were caught, they called, their hand
     * changed) and no such grace is given.
     */
    private void closeExposure(UUID player, UUID gameId) {
        Exposure open = exposures.remove(player);
        if (open != null) {
            open.closer().cancel();
            if (open.botCall() != null) {
                open.botCall().cancel();
            }
        }
        if (gameId == null) {
            return;
        }
        UnoGame game = games.get(gameId);
        if (game != null && game.isExposed(player)) {
            game.callUno(player); // they rode out the window — safe until they draw again
            Player pl = Bukkit.getPlayer(player);
            if (pl != null) {
                messages.send(pl, "game.uno-safe");
            }
        }
    }

    /** Drop every open window belonging to a game that is finishing. */
    private void clearExposures(UUID gameId) {
        for (UUID p : new ArrayList<>(exposures.keySet())) {
            Exposure e = exposures.get(p);
            if (e != null && e.gameId().equals(gameId)) {
                closeExposure(p, null);
            }
        }
    }

    /** {@code /uno uno} — call it on yourself. */
    public void callUno(Player player) {
        UnoGame game = gameOf(player.getUniqueId());
        if (game == null) {
            messages.send(player, "game.not-in-game");
            return;
        }
        if (!game.rules().unoCallout()) {
            messages.send(player, "game.uno-not-enabled");
            return;
        }
        if (!game.callUno(player.getUniqueId())) {
            messages.send(player, "game.uno-not-on-one");
            return;
        }
        closeExposure(player.getUniqueId(), null);
        broadcast(game, messages.get("game.uno-called", "player", displayName(player.getUniqueId())));
        PileRenderer pile = piles.get(game.id());
        fx.uno(player, pile == null ? null : pile.discardLocation());
    }

    /** {@code /uno callout <player>} — catch somebody who never called. */
    public void callOut(Player accuser, String targetName) {
        UnoGame game = gameOf(accuser.getUniqueId());
        if (game == null) {
            messages.send(accuser, "game.not-in-game");
            return;
        }
        if (!game.rules().unoCallout()) {
            messages.send(accuser, "game.uno-not-enabled");
            return;
        }
        UUID target = resolveAtTable(game, targetName);
        if (target == null) {
            messages.send(accuser, "game.callout-no-target", "player", targetName);
            return;
        }
        UnoGame.CalloutResult result = game.callOut(accuser.getUniqueId(), target);
        if (!result.allowed()) {
            messages.send(accuser, "game.callout-not-allowed");
            return;
        }
        if (result.caught()) {
            closeExposure(target, null);
            broadcast(game, messages.get("game.callout-caught",
                    "accuser", displayName(accuser.getUniqueId()),
                    "player", displayName(target), "count", result.drawn()));
        } else {
            broadcast(game, messages.get("game.callout-wrong",
                    "accuser", displayName(accuser.getUniqueId()),
                    "player", displayName(target), "count", result.drawn()));
        }
        Player punished = Bukkit.getPlayer(result.punished());
        if (punished != null) {
            fx.denied(punished);
        }
        afterMove(game);
    }

    /** Everyone in this player's hand except themselves, by display name, for tab completion. */
    public List<String> opponentNames(UUID playerId) {
        UnoGame game = gameOf(playerId);
        if (game == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (UUID p : game.players()) {
            if (!p.equals(playerId)) {
                out.add(displayName(p));
            }
        }
        return out;
    }

    /** Match a typed name against the players actually in this game. */
    private UUID resolveAtTable(UnoGame game, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (UUID p : game.players()) {
            if (displayName(p).equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /** A click-to-run chat button whose label comes from messages.yml. */
    private Component button(String labelKey, String command) {
        return messages.get(labelKey).clickEvent(ClickEvent.runCommand(command));
    }

    /** Where to put an effect meant for a player: their own spot, or the table for a bot. */
    private Location locationOf(UUID player, Location fallback) {
        Player pl = player == null ? null : Bukkit.getPlayer(player);
        return pl == null ? fallback : pl.getLocation();
    }

    /** Broadcast the last event, re-render everyone, update the bar, and let a bot move. */
    private void afterMove(UnoGame game) {
        if (!game.lastEvent().isEmpty()) {
            broadcast(game, messages.get("game.event", "event", game.lastEvent()));
        }
        renderHands(game);
        updateBar(game);
        checkExposure(game);
        PileRenderer pile = piles.get(game.id());
        if (pile != null) {
            pile.setDrawCount(game.drawPileSize()); // step the deck block down a size
        }
        if (game.isOver()) {
            cancelTurnTimer(game.id());
            // endGame is a couple of seconds off yet; the winner shouldn't spend them being
            // told it's their turn.
            BossBar turnBar = turnBars.get(game.id());
            for (UUID p : game.players()) {
                Player pl = Bukkit.getPlayer(p);
                if (pl != null && turnBar != null) {
                    pl.hideBossBar(turnBar);
                }
            }
            return;
        }
        turnEffects(game);
        if (isBot(game.currentPlayer())) {
            cancelTurnTimer(game.id());
            UUID gid = game.id();
            long delay = 12L + ThreadLocalRandom.current().nextInt(17); // ~0.6-1.4s, varied
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> botMove(gid), delay);
        } else {
            armTurnTimer(game, game.currentPlayer());
        }
    }

    private void botMove(UUID gid) {
        UnoGame game = games.get(gid);
        if (game == null || game.isOver()) {
            return;
        }
        UUID bot = game.currentPlayer();
        if (!isBot(bot)) {
            return;
        }
        List<Integer> legal = game.legalIndices(bot);
        UnoGame.PlayResult r;
        if (legal.isEmpty()) {
            r = game.draw(bot);
        } else {
            int idx = legal.get(ThreadLocalRandom.current().nextInt(legal.size())); // random, not scripted
            r = game.play(bot, idx);
        }
        handleResult(game, bot, r, null);
    }

    // ------------------------------------------------------------- idle players

    /**
     * Arm the idle timer for whoever the game is waiting on.
     *
     * <p>Without this, one player alt-tabbing freezes the hand, the table and the pot for
     * everyone else indefinitely. On expiry we make the smallest legal move for them: pick a
     * colour if a wild is pending, otherwise draw (which passes the turn).
     */
    private void armTurnTimer(UnoGame game, UUID waitingOn) {
        UUID gid = game.id();
        cancelTurnTimer(gid);
        int timeout = settings.turnTimeoutSeconds();
        if (timeout <= 0 || game.isOver() || isBot(waitingOn)) {
            return;
        }
        int snapshot = game.moveCount();
        List<BukkitTask> tasks = new ArrayList<>(2);
        int warn = settings.turnWarningSeconds();
        if (warn > 0) {
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                UnoGame g = games.get(gid);
                if (g == null || g.moveCount() != snapshot) {
                    return;
                }
                Player p = Bukkit.getPlayer(waitingOn);
                if (p != null) {
                    messages.actionBar(p, "game.turn-warning", "seconds", warn);
                    fx.turnWarning(p);
                }
            }, (long) (timeout - warn) * 20L));
        }
        tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> forceTurn(gid, waitingOn, snapshot), (long) timeout * 20L));
        turnTasks.put(gid, tasks);
    }

    private void cancelTurnTimer(UUID gameId) {
        List<BukkitTask> tasks = turnTasks.remove(gameId);
        if (tasks != null) {
            tasks.forEach(BukkitTask::cancel);
        }
    }

    /** The idle player's turn, played for them. */
    private void forceTurn(UUID gid, UUID actor, int snapshot) {
        UnoGame game = games.get(gid);
        if (game == null || game.isOver() || game.moveCount() != snapshot) {
            return; // they moved after all
        }
        Player p = Bukkit.getPlayer(actor);
        if (actor.equals(game.pendingColorPlayer())) {
            handleResult(game, actor, game.chooseColor(actor, preferredColor(game, actor)), p);
            return;
        }
        if (actor.equals(game.pendingSwapPlayer())) {
            if (p != null) {
                p.closeInventory();
            }
            handleResult(game, actor, game.chooseSwap(actor, botSwapTarget(game, actor)), p);
            return;
        }
        if (actor.equals(game.pendingChallengePlayer())) {
            // Idle out of a challenge by taking the cards: challenging on their behalf could
            // cost them two more than staying quiet would have.
            handleResult(game, actor, game.respondToDraw4(actor, false), p);
            return;
        }
        if (!actor.equals(game.currentPlayer())) {
            return;
        }
        broadcast(game, messages.get("game.turn-timeout", "player", displayName(actor)));
        handleResult(game, actor, game.draw(actor), p);
    }

    // -------------------------------------------------------------- end a hand

    private void endGame(UnoGame game) {
        UUID gid = game.id();
        if (!games.containsKey(gid)) {
            return; // already ended (win-delay + quit can both fire)
        }
        UUID tableId = gameTable.get(gid);
        // Asked BEFORE the bet layer is told the hand is over, because that is the last
        // moment the pot still exists. A wagered table must not be offered "play again"
        // while its winner is still deciding whether to let it ride — the bet layer prompts
        // the table itself once the pot has actually settled.
        boolean wagered = tableId != null && listener != null && listener.hasPotAtTable(tableId);
        UUID winner = game.winner();
        if (winner != null) {
            broadcast(game, messages.get("game.win", "player", displayName(winner)));
        }
        cancelTurnTimer(gid);
        clearExposures(gid); // scheduled closers must not outlive the game they belong to
        lastTurn.remove(gid);
        onOneCard.remove(gid);
        BossBar bar = bars.remove(gid);
        BossBar turnBar = turnBars.remove(gid);
        PileRenderer pile = piles.remove(gid);
        if (pile != null) {
            pile.remove();
        }
        for (UUID p : game.players()) {
            playerGame.remove(p);
            botNames.remove(p);
            bots.remove(p);
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                handManager.hide(pl);
                if (bar != null) {
                    pl.hideBossBar(bar);
                }
                if (turnBar != null) {
                    pl.hideBossBar(turnBar);
                }
            }
        }
        games.remove(gid);
        gameTable.remove(gid);
        // A fresh hand needs fresh consent: nobody is dealt in again on a "ready" they gave
        // before the last hand was even played.
        tableReady.remove(tableId);
        if (listener != null) {
            listener.onGameEnd(gid, winner); // the bet layer settles the pot
        }
        if (!wagered) {
            promptTable(tableId);
        }
    }

    // ------------------------------------------------------------ admin access

    /** Every running game id. */
    public Collection<UUID> gameIds() {
        return List.copyOf(games.keySet());
    }

    /** The game a player is in, or null. */
    public UUID gameIdOf(UUID playerId) {
        return playerGame.get(playerId);
    }

    /** The table a game is being played at, or null if there is no such game. */
    public UUID tableOfGame(UUID gameId) {
        return gameTable.get(gameId);
    }

    /** True if a hand is in progress at this table — it must not be removed underneath one. */
    public boolean hasGameAtTable(UUID tableId) {
        return tableId != null && gameTable.containsValue(tableId);
    }

    /** True if the bet layer has a pot open at this table, in any state. */
    private boolean hasOpenPot(UUID tableId) {
        return listener != null && listener.hasPotAtTable(tableId);
    }

    public List<UUID> playersOf(UUID gameId) {
        UnoGame game = games.get(gameId);
        return game == null ? List.of() : game.players();
    }

    public UUID currentTurnOf(UUID gameId) {
        UnoGame game = games.get(gameId);
        return game == null ? null : game.currentPlayer();
    }

    /**
     * Force a hand to finish (the admin escape hatch). It ends with no winner, so the bet
     * layer refunds the pot to whoever staked it rather than handing it to anyone.
     */
    public boolean forceEnd(UUID gameId) {
        UnoGame game = games.get(gameId);
        if (game == null) {
            return false;
        }
        broadcast(game, messages.get("game.ended-by-admin"));
        endGame(game);
        return true;
    }

    /** Force-end every running hand. Returns how many were ended. */
    public int forceEndAll() {
        int n = 0;
        for (UUID gid : List.copyOf(games.keySet())) {
            if (forceEnd(gid)) {
                n++;
            }
        }
        return n;
    }

    // -------------------------------------------------------------- rendering

    private void renderHands(UnoGame game) {
        for (UUID p : game.players()) {
            if (isBot(p)) {
                continue;
            }
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                handManager.show(pl, game.handNames(p));
            }
        }
    }

    private void updateBar(UnoGame game) {
        BossBar bar = bars.get(game.id());
        if (bar == null) {
            return;
        }
        Card top = game.top();
        Card.Color active = game.activeColor();
        bar.name(messages.get("game.bossbar",
                "top", top.label(),
                "colour", Component.text(cap(active.lower()), textColor(active)),
                "player", displayName(game.currentPlayer()),
                "dir", game.direction() > 0 ? "↻" : "↺",
                "deck", game.drawPileSize()));
        bar.color(barColor(active));
        BossBar turnBar = turnBars.get(game.id());
        if (turnBar != null) {
            // In the colour to play, bar and text. A colour an admin writes into the line still
            // wins over the text's.
            turnBar.name(messages.get("game.your-turn-bar").colorIfAbsent(textColor(active)));
            turnBar.color(barColor(active));
        }
        for (UUID p : game.players()) {
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                pl.showBossBar(bar);
            }
        }
    }

    private void broadcast(UnoGame game, Component message) {
        for (UUID p : game.players()) {
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                pl.sendMessage(message);
            }
        }
    }

    // ----------------------------------------------------------- wild colour

    /**
     * Ask for a wild's colour in chat: four click-to-run buttons, red and blue over yellow and
     * green, the way the colours sit on the card.
     *
     * <p>Chat rather than a window, so nothing covers the table while the player decides. There
     * is no "closed without choosing" any more — an unanswered prompt is caught by the turn
     * timer, which picks the colour they hold most of.
     */
    private void promptColor(Player p) {
        messages.send(p, "game.colour-prompt",
                "red_button", button("game.colour-red-button", "/uno colour red"),
                "blue_button", button("game.colour-blue-button", "/uno colour blue"),
                "yellow_button", button("game.colour-yellow-button", "/uno colour yellow"),
                "green_button", button("game.colour-green-button", "/uno colour green"));
    }

    /**
     * {@code /uno colour <red|blue|yellow|green>} — answer the wild you just played.
     *
     * <p>A button left in chat from an earlier wild, or clicked twice, lands here with nothing
     * pending and is told so rather than colouring somebody else's card.
     */
    public void chooseColor(Player player, String name) {
        UnoGame game = gameOf(player.getUniqueId());
        if (game == null || !player.getUniqueId().equals(game.pendingColorPlayer())) {
            messages.send(player, "game.no-colour");
            return;
        }
        Card.Color color = parseColor(name);
        if (color == null) {
            promptColor(player); // typed by hand and misspelt: show the buttons again
            return;
        }
        handleResult(game, player.getUniqueId(), game.chooseColor(player.getUniqueId(), color), player);
    }

    /** A colour a wild can be given, by name, or null — WILD itself is not one of them. */
    private static Card.Color parseColor(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "red" -> Card.Color.RED;
            case "blue" -> Card.Color.BLUE;
            case "yellow" -> Card.Color.YELLOW;
            case "green" -> Card.Color.GREEN;
            default -> null;
        };
    }

    // ------------------------------------------------------------ seven-O swap

    /**
     * Pick whose hand to take after playing a 7.
     *
     * <p>A head per opponent, labelled with how many cards they are holding — the only thing
     * anyone actually decides on.
     */
    private void openSwapGui(Player p, UnoGame game) {
        SwapPickerHolder holder = new SwapPickerHolder(game.id());
        List<UUID> others = new ArrayList<>();
        for (UUID other : game.players()) {
            if (!other.equals(p.getUniqueId())) {
                others.add(other);
            }
        }
        int size = Math.max(9, ((others.size() - 1) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(holder, size, messages.get("game.swap-title"));
        holder.inventory = inv;
        for (int i = 0; i < others.size() && i < size; i++) {
            UUID other = others.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            meta.displayName(messages.get("game.swap-entry",
                    "player", displayName(other), "count", game.handSize(other)));
            head.setItemMeta(meta);
            inv.setItem(i, head);
            holder.slots.put(i, other);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onSwapClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SwapPickerHolder holder)) {
            return;
        }
        event.setCancelled(true);
        UUID target = holder.slots.get(event.getRawSlot());
        if (target == null || !(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        // Next tick, not here. Bukkit forbids closing an inventory from inside its own click
        // event, and choosing re-renders the fan — writing to the very inventory the server is
        // still in the middle of settling this click against.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().getHolder() == holder) {
                p.closeInventory();
            }
            UnoGame game = games.get(holder.gameId);
            // Checked, because two clicks in one tick schedule two of these.
            if (game != null && p.getUniqueId().equals(game.pendingSwapPlayer())) {
                handleResult(game, p.getUniqueId(), game.chooseSwap(p.getUniqueId(), target), p);
            }
        });
    }

    @EventHandler
    public void onSwapClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SwapPickerHolder holder)
                || !(event.getPlayer() instanceof Player p)) {
            return;
        }
        // Closed without picking — take the biggest hand next tick so the table isn't stalled.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            UnoGame game = games.get(holder.gameId);
            if (game != null && p.getUniqueId().equals(game.pendingSwapPlayer())) {
                handleResult(game, p.getUniqueId(),
                        game.chooseSwap(p.getUniqueId(), botSwapTarget(game, p.getUniqueId())), p);
            }
        });
    }

    /** Who a bot (or an unanswered prompt) swaps with: whoever is holding the most cards. */
    private UUID botSwapTarget(UnoGame game, UUID actor) {
        UUID best = null;
        int most = -1;
        for (UUID p : game.players()) {
            if (p.equals(actor)) {
                continue;
            }
            int n = game.handSize(p);
            if (n > most) {
                most = n;
                best = p;
            }
        }
        return best;
    }

    // --------------------------------------------------------- the +4 challenge

    /** Offer the target of a +4 the choice of taking it or calling the bluff. */
    private void promptChallenge(UnoGame game, UUID victim) {
        Player pl = Bukkit.getPlayer(victim);
        if (pl == null) {
            return;
        }
        messages.send(pl, "game.draw4-prompt",
                "challenge", button("game.draw4-challenge-button", "/uno challenge"),
                "accept", button("game.draw4-accept-button", "/uno takeit"));
    }

    /** {@code /uno challenge} and {@code /uno takeit} — answer a +4 aimed at you. */
    public void respondToDraw4(Player player, boolean challenge) {
        UnoGame game = gameOf(player.getUniqueId());
        if (game == null || !player.getUniqueId().equals(game.pendingChallengePlayer())) {
            messages.send(player, "game.no-challenge");
            return;
        }
        handleResult(game, player.getUniqueId(),
                game.respondToDraw4(player.getUniqueId(), challenge), player);
    }

    // ------------------------------------------------------------ draw stacking

    /** Tell the table a stack is building and what the player on the spot can do about it. */
    private void announceStack(UnoGame game, UnoGame.PlayResult r) {
        UUID onTheSpot = r.target;
        for (UUID p : game.players()) {
            Player pl = Bukkit.getPlayer(p);
            if (pl == null) {
                continue;
            }
            if (p.equals(onTheSpot)) {
                messages.send(pl, game.canStack(p) ? "game.stack-you-can" : "game.stack-you-cant",
                        "count", r.amount);
                fx.penalty(pl.getLocation(), r.amount);
            } else {
                messages.send(pl, "game.stack-building",
                        "player", displayName(onTheSpot), "count", r.amount);
            }
        }
    }

    /** The colour the player holds most of (for auto-pick); RED if none. */
    private Card.Color preferredColor(UnoGame game, UUID p) {
        int[] counts = new int[4];
        for (String name : game.handNames(p)) {
            Card c = Card.parse(name);
            if (!c.isWild()) {
                counts[c.color().ordinal()]++;
            }
        }
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return Card.Color.values()[best];
    }

    /** Bot wild colour: weighted toward what it holds, but random (so it isn't scripted). */
    private Card.Color botWildColor(UnoGame game, UUID p) {
        int[] weight = new int[4];
        int total = 0;
        for (String name : game.handNames(p)) {
            Card c = Card.parse(name);
            if (!c.isWild()) {
                weight[c.color().ordinal()]++;
            }
        }
        for (int i = 0; i < 4; i++) {
            weight[i] += 1; // base chance so any colour is possible
            total += weight[i];
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < 4; i++) {
            if (roll < weight[i]) {
                return Card.Color.values()[i];
            }
            roll -= weight[i];
        }
        return Card.Color.RED;
    }

    // --------------------------------------------------------------- cleanup

    /**
     * A quit is a forfeit, not an abort: the leaver drops out and everyone else plays on.
     * (Ending the whole hand would make disconnecting a free escape from a losing bet.)
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Also cleared by the stand-up hook when the table ejects them, but the order the two
        // quit handlers run in isn't ours to decide and clearing twice costs nothing.
        clearReadyFor(event.getPlayer().getUniqueId());
        forfeit(event.getPlayer());
    }

    /**
     * Drop one player out of their hand; everyone else plays on.
     *
     * <p>Shared by disconnecting and by {@code /uno quit} ON PURPOSE. Typing the command has to
     * cost exactly what pulling the plug costs, or "I'm losing" becomes a reason to alt-F4 —
     * which for a wagered hand means the stake stays in the pot either way
     * ({@link GameListener#onForfeit}).
     *
     * @return false if the player wasn't in a hand at all.
     */
    public boolean forfeit(Player player) {
        UUID id = player.getUniqueId();
        UnoGame game = gameOf(id);
        if (game == null) {
            return false;
        }
        if (game.isOver()) {
            // Won already, and only sitting out the celebration delay before endGame runs.
            // Forfeiting here tells the bet layer somebody bailed on a hand that is decided —
            // which costs them their stake on paper and prints it to the whole table — while
            // UnoGame.forfeit itself no-ops on an over game. Let the scheduled endGame settle
            // everyone, exactly as it would have if they had waited two seconds.
            return true;
        }
        broadcast(game, messages.get("game.left", "player", player.getName()));
        playerGame.remove(id);
        handManager.hide(player);
        // Hide the bar HERE, not in endGame: game.forfeit() drops the leaver out of players(),
        // so by the time the hand ends they are no longer in the loop that hides it and the bar
        // is stranded on their screen, frozen on the last state, until they relog.
        BossBar bar = bars.get(game.id());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        BossBar turnBar = turnBars.get(game.id());
        if (turnBar != null) {
            player.hideBossBar(turnBar);
        }
        if (listener != null) {
            listener.onForfeit(game.id(), id);
        }
        game.forfeit(id);
        if (game.isOver() || humansLeft(game) == 0) {
            endGame(game);
        } else {
            afterMove(game);
        }
        return true;
    }

    /**
     * End the whole hand a player is sitting in — {@code /uno stop}.
     *
     * <p>Deliberately NOT allowed to settle a wager: ending a hand refunds the pot, so letting a
     * player who is behind call it off would make {@code /uno stop} a free undo on a losing bet.
     * With a pot up, {@code /uno quit} (which forfeits) is the only way out for a player, and
     * {@code /uno end} the only way out for an admin.
     */
    public StopResult stopGameOf(Player player) {
        UUID gameId = gameIdOf(player.getUniqueId());
        if (gameId == null) {
            return StopResult.NOT_IN_GAME;
        }
        UUID tableId = tableOfGame(gameId);
        if (tableId != null && listener != null && listener.hasPotAtTable(tableId)) {
            return StopResult.WAGERED;
        }
        return forceEnd(gameId) ? StopResult.OK : StopResult.NOT_IN_GAME;
    }

    /** Outcome of {@code /uno stop}. */
    public enum StopResult { OK, NOT_IN_GAME, WAGERED }

    private int humansLeft(UnoGame game) {
        int n = 0;
        for (UUID p : game.players()) {
            if (!isBot(p) && Bukkit.getPlayer(p) != null) {
                n++;
            }
        }
        return n;
    }

    public void shutdown() {
        for (UUID gid : List.copyOf(turnTasks.keySet())) {
            cancelTurnTimer(gid);
        }
        for (UnoGame game : new ArrayList<>(games.values())) {
            endGame(game);
        }
    }

    // ----------------------------------------------------------------- helpers

    private UnoGame gameOf(UUID playerId) {
        UUID gid = playerGame.get(playerId);
        return gid == null ? null : games.get(gid);
    }

    private boolean isBot(UUID id) {
        return bots.contains(id);
    }

    /** Never blocks: bots resolve locally, everyone else comes out of the name cache. */
    public String displayName(UUID id) {
        String bot = botNames.get(id);
        return bot != null ? bot : names.name(id);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A card colour as particle dust — the same four colours the cards are printed in. */
    private static Color dustColor(Card.Color c) {
        return switch (c) {
            case RED -> Color.fromRGB(0xD6, 0x3A, 0x2E);
            case GREEN -> Color.fromRGB(0x3F, 0xA3, 0x4D);
            case BLUE -> Color.fromRGB(0x2A, 0x6F, 0xD6);
            case YELLOW -> Color.fromRGB(0xF2, 0xC2, 0x30);
            default -> Color.WHITE;
        };
    }

    private static NamedTextColor textColor(Card.Color c) {
        return switch (c) {
            case RED -> NamedTextColor.RED;
            case GREEN -> NamedTextColor.GREEN;
            case BLUE -> NamedTextColor.BLUE;
            case YELLOW -> NamedTextColor.YELLOW;
            default -> NamedTextColor.WHITE;
        };
    }

    private static BossBar.Color barColor(Card.Color c) {
        return switch (c) {
            case RED -> BossBar.Color.RED;
            case GREEN -> BossBar.Color.GREEN;
            case BLUE -> BossBar.Color.BLUE;
            case YELLOW -> BossBar.Color.YELLOW;
            default -> BossBar.Color.WHITE;
        };
    }

    /** The seven-O "whose hand do you want?" window, and which slot means which player. */
    private static final class SwapPickerHolder implements InventoryHolder {
        final UUID gameId;
        final Map<Integer, UUID> slots = new HashMap<>();
        Inventory inventory;

        SwapPickerHolder(UUID gameId) {
            this.gameId = gameId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
