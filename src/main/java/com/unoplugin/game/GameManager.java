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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns running {@link UnoGame}s, wires them to the {@link HandManager} (each player's fan),
 * shows a status bossbar, runs the wild-colour GUI, drives simple bot opponents, and keeps
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
    private final Map<UUID, PileRenderer> piles = new HashMap<>(); // gameId -> table piles
    private final Map<UUID, List<BukkitTask>> turnTasks = new HashMap<>(); // gameId -> idle timers
    private final Map<UUID, String> botNames = new HashMap<>();    // bot id -> name
    private final Map<UUID, UUID> lastTurn = new HashMap<>();      // gameId -> who it was on
    private final Map<UUID, Set<UUID>> onOneCard = new HashMap<>(); // gameId -> already "UNO!"d
    private final Set<UUID> bots = new HashSet<>();
    private GameListener listener;                                  // optional (the bet layer)

    /** Lets another subsystem (the wagering layer) react to how a hand ends. */
    public interface GameListener {
        /** The hand is over. {@code winner} is null if it ended without one, and may be a bot. */
        void onGameEnd(UUID gameId, UUID winner);

        /** A player dropped out mid-hand (disconnect). The hand carries on without them. */
        void onForfeit(UUID gameId, UUID player);
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
        botCount = Math.max(1, Math.min(9, botCount));
        List<UUID> players = new ArrayList<>();
        players.add(host.getUniqueId());
        for (int i = 1; i <= botCount; i++) {
            players.add(newBot("Bot " + i));
        }
        UnoTable table = tableManager == null ? null : tableManager.seatedTable(host.getUniqueId());
        launch(players, table, host);
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
        launch(players, table, initiator);
    }

    /**
     * Start a hand for an exact player list decided elsewhere (the wagering layer picks who
     * has anted up). Returns the game id so the caller can tie its pot to this hand, or null
     * if someone in the list is already playing.
     */
    public UUID startWager(List<UUID> humans, int extraBots, UnoTable table, Player anchor) {
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
        return launch(players, table, anchor);
    }

    private UUID newBot(String name) {
        UUID b = UUID.randomUUID();
        bots.add(b);
        botNames.put(b, name);
        return b;
    }

    /**
     * Common game bring-up for a fixed player list. {@code table} (nullable) centres the
     * piles on its surface; otherwise they drop a couple of blocks in front of {@code anchor}.
     */
    private UUID launch(List<UUID> players, UnoTable table, Player anchor) {
        UUID gameId = UUID.randomUUID();
        UnoGame game = new UnoGame(gameId, players);
        game.setNamer(this::displayName);
        game.start(settings.startingHandSize());
        games.put(gameId, game);
        gameTable.put(gameId, table == null ? null : table.id());
        for (UUID p : players) {
            playerGame.put(p, gameId);
        }
        bars.put(gameId, BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));

        Location discardLoc;
        Location drawLoc;
        float pileYaw;
        if (table != null) {
            Location centre = table.anchor();
            pileYaw = table.yaw();
            double r = Math.toRadians(pileYaw);
            Vector right = new Vector(Math.cos(r), 0, Math.sin(r));
            // Bottom card sits flush on the casino felt surface.
            discardLoc = centre.clone().add(right.clone().multiply(-0.38));
            discardLoc.setY(centre.getY() + UnoTable.SURFACE_Y);
            drawLoc = centre.clone().add(right.clone().multiply(0.38));
            drawLoc.setY(centre.getY() + UnoTable.SURFACE_Y);
        } else {
            Location base = anchor.getLocation();
            Vector fwd = base.getDirection().setY(0);
            if (fwd.lengthSquared() < 1.0e-6) {
                fwd = new Vector(0, 0, 1);
            }
            fwd.normalize();
            Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
            pileYaw = base.getYaw();
            discardLoc = base.clone().add(fwd.clone().multiply(2.5)).add(0, 0.06, 0);
            drawLoc = discardLoc.clone().add(right.clone().multiply(0.9));
        }
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

    // ------------------------------------------------------- HandManager hooks

    @Override
    public boolean play(Player player, int selectedIndex) {
        UnoGame game = gameOf(player.getUniqueId());
        if (game == null) {
            return false;
        }
        handleResult(game, player.getUniqueId(), game.play(player.getUniqueId(), selectedIndex), player);
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
                        openColorGui(actorPlayer, game);
                    }
                    // An unanswered colour prompt stalls the table just as hard as an idle turn.
                    armTurnTimer(game, actor);
                }
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
     */
    private void turnEffects(UnoGame game) {
        UUID current = game.currentPlayer();
        if (current.equals(lastTurn.put(game.id(), current))) {
            return;
        }
        Player pl = Bukkit.getPlayer(current);
        if (pl != null) {
            fx.yourTurn(pl);
            messages.actionBar(pl, "game.your-turn");
        }
        PileRenderer pile = piles.get(game.id());
        fx.turnMarker(locationOf(current, pile == null ? null : pile.discardLocation()));
    }

    /**
     * Call UNO for anyone down to their last card — once, until they pick cards back up.
     * A player who draws back up to two and returns to one gets called again, as they should.
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
        unoEffects(game);
        PileRenderer pile = piles.get(game.id());
        if (pile != null) {
            pile.setDrawCount(game.drawPileSize()); // step the deck block down a size
        }
        if (game.isOver()) {
            cancelTurnTimer(game.id());
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
            if (p != null) {
                p.closeInventory();
            }
            handleResult(game, actor, game.chooseColor(actor, preferredColor(game, actor)), p);
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
        UUID winner = game.winner();
        if (winner != null) {
            broadcast(game, messages.get("game.win", "player", displayName(winner)));
        }
        cancelTurnTimer(gid);
        lastTurn.remove(gid);
        onOneCard.remove(gid);
        BossBar bar = bars.remove(gid);
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
            }
        }
        games.remove(gid);
        gameTable.remove(gid);
        if (listener != null) {
            listener.onGameEnd(gid, winner); // the bet layer settles the pot
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

    /** The table a game is being played at, or null for a table-less test game. */
    public UUID tableOfGame(UUID gameId) {
        return gameTable.get(gameId);
    }

    /** True if a hand is in progress at this table — it must not be removed underneath one. */
    public boolean hasGameAtTable(UUID tableId) {
        return tableId != null && gameTable.containsValue(tableId);
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

    private void openColorGui(Player p, UnoGame game) {
        ColorPickerHolder holder = new ColorPickerHolder(game.id());
        Inventory inv = Bukkit.createInventory(holder, 9, messages.get("game.colour-title"));
        holder.inventory = inv;
        inv.setItem(2, pane(Material.RED_STAINED_GLASS_PANE, messages.get("game.colour-red")));
        inv.setItem(3, pane(Material.GREEN_STAINED_GLASS_PANE, messages.get("game.colour-green")));
        inv.setItem(5, pane(Material.BLUE_STAINED_GLASS_PANE, messages.get("game.colour-blue")));
        inv.setItem(6, pane(Material.YELLOW_STAINED_GLASS_PANE, messages.get("game.colour-yellow")));
        p.openInventory(inv);
    }

    @EventHandler
    public void onColorClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ColorPickerHolder holder)) {
            return;
        }
        event.setCancelled(true);
        ItemStack it = event.getCurrentItem();
        if (it == null) {
            return;
        }
        Card.Color color = switch (it.getType()) {
            case RED_STAINED_GLASS_PANE -> Card.Color.RED;
            case GREEN_STAINED_GLASS_PANE -> Card.Color.GREEN;
            case BLUE_STAINED_GLASS_PANE -> Card.Color.BLUE;
            case YELLOW_STAINED_GLASS_PANE -> Card.Color.YELLOW;
            default -> null;
        };
        if (color == null || !(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        UnoGame game = games.get(holder.gameId);
        p.closeInventory();
        if (game != null) {
            handleResult(game, p.getUniqueId(), game.chooseColor(p.getUniqueId(), color), p);
        }
    }

    @EventHandler
    public void onColorClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ColorPickerHolder holder)
                || !(event.getPlayer() instanceof Player p)) {
            return;
        }
        // Closed without choosing — auto-pick next tick so the game doesn't stall.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            UnoGame game = games.get(holder.gameId);
            if (game != null && p.getUniqueId().equals(game.pendingColorPlayer())) {
                handleResult(game, p.getUniqueId(),
                        game.chooseColor(p.getUniqueId(), preferredColor(game, p.getUniqueId())), p);
            }
        });
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
        UUID id = event.getPlayer().getUniqueId();
        UnoGame game = gameOf(id);
        if (game == null) {
            return;
        }
        broadcast(game, messages.get("game.left", "player", event.getPlayer().getName()));
        playerGame.remove(id);
        if (listener != null) {
            listener.onForfeit(game.id(), id);
        }
        game.forfeit(id);
        if (game.isOver() || humansLeft(game) == 0) {
            endGame(game);
        } else {
            afterMove(game);
        }
    }

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

    private static ItemStack pane(Material mat, Component name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private static final class ColorPickerHolder implements InventoryHolder {
        final UUID gameId;
        Inventory inventory;

        ColorPickerHolder(UUID gameId) {
            this.gameId = gameId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
