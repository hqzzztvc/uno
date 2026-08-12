package com.unoplugin.game;

import com.unoplugin.hand.HandManager;
import com.unoplugin.table.TableManager;
import com.unoplugin.table.UnoTable;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns running {@link UnoGame}s, wires them to the {@link HandManager} (each player's fan),
 * shows a status bossbar, runs the wild-colour GUI, and drives simple bot opponents.
 * Implements {@link HandManager.CardActions} so play/draw input flows into the game rules.
 */
public final class GameManager implements Listener, HandManager.CardActions {

    private final Plugin plugin;
    private final HandManager handManager;
    private TableManager tableManager;                              // set after construction

    private final Map<UUID, UnoGame> games = new HashMap<>();      // gameId -> game
    private final Map<UUID, UUID> playerGame = new HashMap<>();    // participant -> gameId
    private final Map<UUID, BossBar> bars = new HashMap<>();       // gameId -> bossbar
    private final Map<UUID, PileRenderer> piles = new HashMap<>(); // gameId -> table piles
    private final Map<UUID, String> botNames = new HashMap<>();    // bot id -> name
    private final Set<UUID> bots = new HashSet<>();
    private GameListener listener;                                  // optional (the bet layer)

    /** Lets another subsystem (the wagering layer) react to how a hand ends. */
    public interface GameListener {
        /** The hand is over. {@code winner} is null if it ended without one, and may be a bot. */
        void onGameEnd(UUID gameId, UUID winner);

        /** A player dropped out mid-hand (disconnect). The hand carries on without them. */
        void onForfeit(UUID gameId, UUID player);
    }

    public GameManager(Plugin plugin, HandManager handManager) {
        this.plugin = plugin;
        this.handManager = handManager;
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
            host.sendMessage("§eYou're already in a game. Finish it first.");
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
            initiator.sendMessage("§cTables aren't available right now.");
            return;
        }
        UnoTable table = tableManager.seatedTable(initiator.getUniqueId());
        if (table == null) {
            initiator.sendMessage("§eSit at a table first (right-click a seat), then run §6/uno start§e.");
            return;
        }
        List<UUID> humans = new ArrayList<>();
        for (UUID u : tableManager.seatedPlayersAt(table.id())) {
            if (playerGame.containsKey(u)) {
                initiator.sendMessage("§c" + displayName(u) + " is already in a game.");
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
            initiator.sendMessage("§eNeed at least 2 players — sit a friend down too, or add bots: §6/uno start <bots>§e.");
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
        List<UUID> players = new ArrayList<>(humans);
        for (int i = 1; i <= extraBots; i++) {
            players.add(newBot("Bot " + i));
        }
        if (players.size() < 2) {
            return null;
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
        game.start();
        games.put(gameId, game);
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
            // Bottom card sits flush on the casino felt surface (top ~0.75).
            double surfaceY = 0.757;
            discardLoc = centre.clone().add(right.clone().multiply(-0.38));
            discardLoc.setY(centre.getY() + surfaceY);
            drawLoc = centre.clone().add(right.clone().multiply(0.38));
            drawLoc.setY(centre.getY() + surfaceY);
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
                pl.sendMessage("§6§lUNO §r§7started — " + players.size() + " players. Good luck!");
                pl.sendMessage("§7A/D or wheel = pick · §aleft-click/Q = play · §eright-click/F = draw");
            }
        }
        afterMove(game); // renders every hand, shows the bar, drives the first bot if needed
        return gameId;
    }

    // ------------------------------------------------------- HandManager hooks

    @Override
    public boolean play(Player player, int selectedIndex) {
        UnoGame game = gameOf(player.getUniqueId());
        if (game == null) {
            return false; // not in a game — let HandManager use its test stub
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
                    actorPlayer.sendActionBar(Component.text("§c" + r.message));
                }
            }
            case NEED_COLOR -> {
                if (isBot(actor)) {
                    handleResult(game, actor, game.chooseColor(actor, botWildColor(game, actor)), null);
                } else if (actorPlayer != null) {
                    openColorGui(actorPlayer, game);
                }
            }
            case DREW -> afterMove(game);
            case OK -> {
                updateDiscard(game, actorPlayer, r.card);
                afterMove(game);
            }
            case WIN -> {
                updateDiscard(game, actorPlayer, r.card);
                afterMove(game); // broadcasts the win + final state (no bot move since over)
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> endGame(game), 40L);
            }
        }
    }

    /** Drop the just-played card onto the discard pile, face-up. */
    private void updateDiscard(UnoGame game, Player actorPlayer, Card card) {
        PileRenderer pile = piles.get(game.tableId());
        if (pile == null || card == null) {
            return;
        }
        pile.addToDiscard(card);
    }

    /** Broadcast the last event, re-render everyone, update the bar, and let a bot move. */
    private void afterMove(UnoGame game) {
        if (!game.lastEvent().isEmpty()) {
            broadcast(game, "§7" + game.lastEvent());
        }
        renderHands(game);
        updateBar(game);
        PileRenderer pile = piles.get(game.tableId());
        if (pile != null) {
            pile.setDrawCount(game.drawPileSize()); // shrink/grow the deck stack
        }
        if (!game.isOver() && isBot(game.currentPlayer())) {
            UUID gid = game.tableId();
            long delay = 12L + ThreadLocalRandom.current().nextInt(17); // ~0.6-1.4s, varied
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> botMove(gid), delay);
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

    private void endGame(UnoGame game) {
        UUID gid = game.tableId();
        if (!games.containsKey(gid)) {
            return; // already ended (win-delay + quit can both fire)
        }
        UUID winner = game.winner();
        if (winner != null) {
            broadcast(game, "§6§l" + displayName(winner) + " wins! 🎉");
        }
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
        if (listener != null) {
            listener.onGameEnd(gid, winner); // the bet layer settles the pot
        }
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
        BossBar bar = bars.get(game.tableId());
        if (bar == null) {
            return;
        }
        Card top = game.top();
        String dir = game.direction() > 0 ? "↻" : "↺";
        bar.name(Component.text("§fTop: §6" + top.label()
                + "  §f| Colour: " + colorTag(game.activeColor()) + cap(game.activeColor().lower())
                + "  §f| Turn: §b" + displayName(game.currentPlayer()) + " " + dir
                + "  §f| Deck: §7" + game.drawPileSize()));
        bar.color(barColor(game.activeColor()));
        for (UUID p : game.players()) {
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                pl.showBossBar(bar);
            }
        }
    }

    private void broadcast(UnoGame game, String msg) {
        for (UUID p : game.players()) {
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) {
                pl.sendMessage(msg);
            }
        }
    }

    // ----------------------------------------------------------- wild colour

    private void openColorGui(Player p, UnoGame game) {
        ColorPickerHolder holder = new ColorPickerHolder(game.tableId());
        Inventory inv = Bukkit.createInventory(holder, 9, Component.text("Pick a colour for your wild"));
        holder.inventory = inv;
        inv.setItem(2, pane(Material.RED_STAINED_GLASS_PANE, "§c§lRed"));
        inv.setItem(3, pane(Material.GREEN_STAINED_GLASS_PANE, "§a§lGreen"));
        inv.setItem(5, pane(Material.BLUE_STAINED_GLASS_PANE, "§9§lBlue"));
        inv.setItem(6, pane(Material.YELLOW_STAINED_GLASS_PANE, "§e§lYellow"));
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

    /** The colour the player holds most of (for bots / auto-pick); RED if none. */
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
        broadcast(game, "§c" + event.getPlayer().getName() + " left the hand.");
        playerGame.remove(id);
        if (listener != null) {
            listener.onForfeit(game.tableId(), id);
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

    private String displayName(UUID id) {
        String bot = botNames.get(id);
        if (bot != null) {
            return bot;
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            return p.getName();
        }
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : "Player";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String colorTag(Card.Color c) {
        return switch (c) {
            case RED -> "§c";
            case GREEN -> "§a";
            case BLUE -> "§9";
            case YELLOW -> "§e";
            default -> "§f";
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

    private static ItemStack pane(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name));
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
