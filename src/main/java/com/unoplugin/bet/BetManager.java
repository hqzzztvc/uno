package com.unoplugin.bet;

import com.unoplugin.game.GameManager;
import com.unoplugin.table.TableManager;
import com.unoplugin.table.UnoTable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Let It Ride" — the wagering layer on top of a normal UNO hand.
 *
 * <p>The loop: <b>/gamble</b> opens an ante at your table, players toss items onto the felt
 * to stake them, <b>/gamble ready</b> locks a stake in, and when everyone's ready the table
 * plays one hand of UNO for the whole pot. The winner then chooses: cash out, or let the pot
 * ride into the next hand and make the table match it.
 *
 * <p>Two rules give the mode its teeth:
 * <ul>
 *   <li><b>Quitting is forfeiting.</b> Disconnect mid-hand and your stake stays in the pot
 *       for whoever wins — otherwise bailing out would be a free undo on a losing bet.</li>
 *   <li><b>Nothing is ever held only in RAM.</b> A staked item leaves the inventory, so every
 *       change is mirrored into {@link EscrowStore} before anything else happens.</li>
 * </ul>
 *
 * <p>Bots may sit in for testing but never gamble; if a bot takes the hand the pot is a push
 * and goes back to whoever staked it.
 */
public final class BetManager implements Listener, GameManager.GameListener {

    /** Height of the casino felt above the table anchor — same constant the piles use. */
    private static final double SURFACE_Y = 0.757;
    /** Pot sits at the dealer's end, clear of the draw/discard piles at ±0.38 sideways. */
    private static final double POT_FORWARD = 0.78;

    private final Plugin plugin;
    private final TableManager tables;
    private final GameManager games;
    private final EscrowStore escrow;

    private final Map<UUID, BetSession> byTable = new HashMap<>();  // tableId  -> session
    private final Map<UUID, UUID> byGame = new HashMap<>();         // gameId   -> tableId

    public BetManager(Plugin plugin, TableManager tables, GameManager games) {
        this.plugin = plugin;
        this.tables = tables;
        this.games = games;
        this.escrow = new EscrowStore(plugin);
    }

    // =====================================================================  commands

    /** Entry point for {@code /gamble …} (and {@code /uno gamble …}). */
    public void command(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("gambling.enabled", true)) {
            msg(player, "§cGambling is disabled on this server.");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "" -> open(player);
            case "ready" -> ready(player);
            case "out", "leave" -> out(player);
            case "pot" -> showPot(player);
            case "go" -> forceStart(player);
            case "cancel" -> cancel(player);
            case "ride" -> ride(player);
            case "cash", "cashout" -> cash(player);
            case "bots" -> setBots(player, args);
            default -> help(player);
        }
    }

    private void help(Player p) {
        msg(p, "§6§lLET IT RIDE §8— §7wager items on a hand of UNO");
        msg(p, "§e/gamble §7— open (or join) the ante at your table");
        msg(p, "§7  then §fdrop items onto the table §7to stake them");
        msg(p, "§e/gamble ready §7— lock your stake in · §e/gamble out §7— take it back");
        msg(p, "§e/gamble pot §7— what's on the line · §e/gamble go §7— host: start now");
        msg(p, "§e/gamble cancel §7— host: call it off and refund everyone");
    }

    /** Open a new ante at the player's table, or join the one already running. */
    private void open(Player p) {
        UnoTable table = tables.seatedTable(p.getUniqueId());
        if (table == null) {
            msg(p, "§eSit at a casino table first §7(right-click a seat)§e, then run §6/gamble§e.");
            return;
        }
        if (games.isInGame(p.getUniqueId())) {
            msg(p, "§cYou're already in a hand. Finish it first.");
            return;
        }
        BetSession s = byTable.get(table.id());
        if (s == null) {
            s = new BetSession(table.id(), p.getUniqueId());
            s.setRenderer(new PotRenderer(plugin, potLocation(table), table.yaw()));
            byTable.put(table.id(), s);
            redraw(s);
            broadcast(s, "§6§l" + p.getName() + " opened a bet! §r§7Winner takes the pot.");
            broadcast(s, "§7Toss items onto the table to stake them, then §e/gamble ready§7.");
            armAnteTimeout(s);
            return;
        }
        if (s.state() != BetSession.State.ANTE) {
            msg(p, "§cA hand is already being played for this pot — wait for it to settle.");
            return;
        }
        if (s.isLive(p.getUniqueId())) {
            msg(p, "§7You're in. Drop items on the table to raise, then §e/gamble ready§7.");
            return;
        }
        // Not live yet — you're only in the bet once something of yours is in the pot.
        msg(p, "§aThe bet is open. §7Drop items onto the table to buy in.");
        showPot(p);
    }

    private void ready(Player p) {
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!s.hasStaked(id) && !id.equals(s.rideWinner())) {
            msg(p, "§eStake something first — drop items onto the table.");
            return;
        }
        if (s.isReady(id)) {
            msg(p, "§7You're already ready. Waiting on the others.");
            return;
        }
        s.setReady(id);
        broadcast(s, "§a" + p.getName() + " is ready §7(" + s.readyCount() + "/" + s.live().size() + ")");
        redraw(s);
        if (s.allReady()) {
            startHand(s);
        }
    }

    /** Pull out of the ante and take your stake back. Only legal before the hand starts. */
    private void out(Player p) {
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (!s.isLive(id)) {
            msg(p, "§7You're not in this bet.");
            return;
        }
        if (id.equals(s.rideWinner())) {
            // The rider owns the whole pot — walking away is just cashing out.
            cash(p);
            return;
        }
        refund(s, id);
        broadcast(s, "§7" + p.getName() + " pulled out of the bet.");
        redraw(s);
        closeIfEmpty(s);
    }

    private void showPot(Player p) {
        BetSession s = sessionAt(p);
        if (s == null) {
            msg(p, "§7No bet running at your table. §e/gamble §7opens one.");
            return;
        }
        msg(p, "§6§lPOT §8— §e" + s.potSize() + " §7item(s) from §e" + s.stakers().size() + " §7player(s)");
        for (UUID staker : s.stakers()) {
            String status = s.isLive(staker)
                    ? (s.isReady(staker) ? "§aready" : "§eanteing")
                    : "§8out";
            msg(p, "§8 · §f" + name(staker) + " §7— " + EscrowStore.count(s.stakeOf(staker))
                    + " item(s) §8[" + status + "§8]");
        }
        if (s.state() == BetSession.State.PLAYING) {
            msg(p, "§7The hand is in play. Win it and it's all yours.");
        }
    }

    /** Host escape hatch: start with whoever is ready, refunding the ones still deciding. */
    private void forceStart(Player p) {
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        if (!p.getUniqueId().equals(s.hostId())) {
            msg(p, "§cOnly " + name(s.hostId()) + " (who opened the bet) can start it early.");
            return;
        }
        for (UUID id : new ArrayList<>(s.live())) {
            if (!s.isReady(id)) {
                refund(s, id);
                Player laggard = Bukkit.getPlayer(id);
                if (laggard != null) {
                    msg(laggard, "§eThe bet started without you — your stake was returned.");
                }
            }
        }
        if (s.live().size() + s.botCount() < 2) {
            msg(p, "§eNot enough ready players to start.");
            redraw(s);
            closeIfEmpty(s);
            return;
        }
        startHand(s);
    }

    private void cancel(Player p) {
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        if (!p.getUniqueId().equals(s.hostId()) && !p.hasPermission("uno.admin")) {
            msg(p, "§cOnly " + name(s.hostId()) + " (who opened the bet) can cancel it.");
            return;
        }
        broadcast(s, "§c" + p.getName() + " called the bet off — everything goes back.");
        refundAll(s);
        close(s);
    }

    private void setBots(Player p, String[] args) {
        if (!p.hasPermission("uno.admin")) {
            msg(p, "§cYou don't have permission.");
            return;
        }
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        int n = 0;
        if (args.length > 1) {
            try {
                n = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                msg(p, "§eUsage: /gamble bots <count>");
                return;
            }
        }
        s.setBotCount(Math.max(0, Math.min(4, n)));
        msg(p, "§7Test bots at this table: §e" + s.botCount()
                + "§7. They don't stake — if a bot wins, the pot is a push and everyone is refunded.");
    }

    // =====================================================================  staking

    /**
     * Tossing an item at the table stakes it. Runs at HIGH so the hand fan's "Q = play card"
     * cancel (registered at NORMAL) is always seen first — you can never stake your own cards.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        BetSession s = sessionAt(p);
        if (s == null || s.state() != BetSession.State.ANTE) {
            return;
        }
        ItemStack stack = event.getItemDrop().getItemStack().clone();
        if (isPluginItem(stack)) {
            msg(p, "§cThat's UNO equipment — you can't put it in the pot.");
            return;
        }
        UUID id = p.getUniqueId();
        if (s.isReady(id)) {
            msg(p, "§eYou've already locked your stake in. §7/gamble out §eto take it back first.");
            return;
        }
        // Kill the real entity immediately: dropped items despawn, get hoovered by hoppers and
        // can be grabbed by anyone. The stack lives in escrow and is drawn as a display instead.
        event.getItemDrop().remove();
        s.stake(id, stack);
        escrow.hold(id, s.stakeOf(id));

        Location table = potLocation(tableOf(s));
        p.getWorld().playSound(table, Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.8f);
        broadcast(s, "§e" + p.getName() + " §7stakes §f" + stack.getAmount() + "× "
                + prettyName(stack) + " §8(pot: " + s.potSize() + ")");
        redraw(s);
    }

    /** UNO's own cards / table items must never end up in a pot. */
    private boolean isPluginItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        NamespacedKey model = meta.getItemModel();
        if (model != null && "uno".equals(model.getNamespace())) {
            return true;
        }
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if ("uno".equals(key.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================  the hand

    private void startHand(BetSession s) {
        UnoTable table = tableOf(s);
        if (table == null) {
            broadcast(s, "§cThe table vanished — refunding everyone.");
            refundAll(s);
            close(s);
            return;
        }
        List<UUID> humans = new ArrayList<>(s.live());
        Player anchor = firstOnline(humans);
        if (anchor == null) {
            refundAll(s);
            close(s);
            return;
        }
        UUID gameId = games.startWager(humans, s.botCount(), table, anchor);
        if (gameId == null) {
            broadcast(s, "§cCouldn't deal the hand (someone's already in a game) — refunding.");
            refundAll(s);
            close(s);
            return;
        }
        s.cancelTimer();
        s.setGameId(gameId);
        s.setState(BetSession.State.PLAYING);
        s.nextHand();
        byGame.put(gameId, s.tableId());
        broadcast(s, "§6§l" + s.potSize() + " items on the line. §r§7Deal!");
        redraw(s);
    }

    /** A player disconnected mid-hand: they're out, their stake stays in the pot. */
    @Override
    public void onForfeit(UUID gameId, UUID player) {
        BetSession s = sessionForGame(gameId);
        if (s == null || !s.isLive(player)) {
            return;
        }
        int lost = EscrowStore.count(s.stakeOf(player));
        s.forfeit(player);
        broadcast(s, "§c" + name(player) + " walked out — their §e" + lost
                + " §citem(s) stay in the pot.");
        redraw(s);
    }

    @Override
    public void onGameEnd(UUID gameId, UUID winner) {
        UUID tableId = byGame.remove(gameId);
        if (tableId == null) {
            return; // a plain /uno game, nothing wagered on it
        }
        BetSession s = byTable.get(tableId);
        if (s == null) {
            return;
        }
        s.setGameId(null);
        if (winner == null) {
            broadcast(s, "§7No winner — the pot goes back to everyone who staked it.");
            refundAll(s);
            close(s);
            return;
        }
        if (games.isBotId(winner)) {
            // Bots don't collect. A bot win is a push, not a house edge.
            broadcast(s, "§7" + name(winner) + " (a bot) took the hand — §fpush§7, everyone is refunded.");
            refundAll(s);
            close(s);
            return;
        }
        boolean rideOn = plugin.getConfig().getBoolean("gambling.ride.enabled", true);
        if (!rideOn || s.potSize() == 0) {
            payout(s, winner);
            return;
        }
        s.setState(BetSession.State.RIDE);
        s.setRideWinner(winner);
        offerRide(s, winner);
    }

    /** Winner's choice: take the pot, or leave it all in for another hand. */
    private void offerRide(BetSession s, UUID winner) {
        int seconds = plugin.getConfig().getInt("gambling.ride.window-seconds", 20);
        int pot = s.potSize();
        broadcast(s, "§6§l" + name(winner) + " takes the hand §r§7— " + pot + " item(s) won.");
        redraw(s);

        Player w = Bukkit.getPlayer(winner);
        if (w != null) {
            w.sendMessage(Component.text("You won " + pot + " item(s). ", NamedTextColor.GOLD)
                    .append(button("[ CASH OUT ]", NamedTextColor.GREEN, "/gamble cash",
                            "Take the " + pot + " item(s) now"))
                    .append(Component.text("  "))
                    .append(button("[ LET IT RIDE ]", NamedTextColor.RED, "/gamble ride",
                            "Leave all " + pot + " item(s) in for the next hand.\n"
                                    + "The table must match it to challenge you.")));
            msg(w, "§8(" + seconds + "s — no answer cashes you out)");
        }
        s.setTimer(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (s.state() == BetSession.State.RIDE) {
                broadcast(s, "§7Time's up — cashing out.");
                payout(s, winner);
            }
        }, Math.max(1, seconds) * 20L));
    }

    private void ride(Player p) {
        BetSession s = sessionAt(p);
        if (s == null || s.state() != BetSession.State.RIDE
                || !p.getUniqueId().equals(s.rideWinner())) {
            msg(p, "§7Nothing to ride on right now.");
            return;
        }
        s.cancelTimer();
        // The pot is his now — re-file every item under the winner so a crash pays him, and
        // so anyone who wants a piece has to put fresh items in.
        s.reattributeTo(p.getUniqueId());
        for (UUID staker : new ArrayList<>(s.stakers())) {
            if (!staker.equals(p.getUniqueId())) {
                escrow.release(staker);
            }
        }
        escrow.hold(p.getUniqueId(), s.potItems());

        s.live().clear();
        s.live().add(p.getUniqueId());
        s.clearReady();
        s.setReady(p.getUniqueId());
        s.setState(BetSession.State.ANTE);

        int challenge = plugin.getConfig().getInt("gambling.ride.challenge-seconds", 90);
        broadcast(s, "§c§l" + p.getName() + " LETS IT RIDE! §r§6" + s.potSize()
                + " §7item(s) stay on the table.");
        broadcast(s, "§7Match it to challenge — drop your ante and §e/gamble ready §7within "
                + challenge + "s.");
        redraw(s);
        s.setTimer(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (s.state() == BetSession.State.ANTE && s.live().size() < 2) {
                broadcast(s, "§7No takers — " + p.getName() + " walks away with it.");
                payout(s, p.getUniqueId());
            }
        }, Math.max(1, challenge) * 20L));
    }

    private void cash(Player p) {
        BetSession s = sessionAt(p);
        if (s == null || s.rideWinner() == null || !p.getUniqueId().equals(s.rideWinner())) {
            msg(p, "§7You've got nothing to cash out.");
            return;
        }
        s.cancelTimer();
        payout(s, p.getUniqueId());
    }

    /** Settle: the whole pot changes hands and the session closes. */
    private void payout(BetSession s, UUID winner) {
        List<ItemStack> pot = s.potItems();
        for (UUID staker : s.stakers()) {
            escrow.release(staker);
        }
        escrow.payTo(winner, pot);
        broadcast(s, "§6§l" + name(winner) + " collects " + EscrowStore.count(pot) + " item(s)!");
        Player w = Bukkit.getPlayer(winner);
        if (w != null) {
            w.getWorld().playSound(w.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        }
        close(s);
    }

    // =====================================================================  refunds

    private void refund(BetSession s, UUID player) {
        List<ItemStack> back = s.withdraw(player);
        escrow.payTo(player, back);
    }

    private void refundAll(BetSession s) {
        for (UUID staker : new ArrayList<>(s.stakers())) {
            refund(s, staker);
        }
    }

    private void close(BetSession s) {
        s.cancelTimer();
        if (s.renderer() != null) {
            s.renderer().remove();
        }
        if (s.gameId() != null) {
            byGame.remove(s.gameId());
        }
        byTable.remove(s.tableId());
    }

    /** Everyone pulled out during the ante — don't leave a ghost session holding the table. */
    private void closeIfEmpty(BetSession s) {
        if (s.state() == BetSession.State.ANTE && s.live().isEmpty()) {
            close(s);
        }
    }

    private void armAnteTimeout(BetSession s) {
        int seconds = plugin.getConfig().getInt("gambling.ante-seconds", 300);
        if (seconds <= 0) {
            return;
        }
        s.setTimer(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (s.state() == BetSession.State.ANTE) {
                broadcast(s, "§7Nobody locked in — the bet expired and everything went back.");
                refundAll(s);
                close(s);
            }
        }, seconds * 20L));
    }

    // =====================================================================  lifecycle

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Anything owed from a crash, a payout to an offline winner, or a stake we were
        // holding when the server went down.
        escrow.deliverPending(event.getPlayer());
    }

    /** Quitting during the ante is free — the forfeit rule only bites once cards are dealt. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (BetSession s : new ArrayList<>(byTable.values())) {
            if (s.state() == BetSession.State.ANTE && s.isLive(id)) {
                if (id.equals(s.rideWinner())) {
                    // The rider left mid-challenge; the pot is already theirs on paper.
                    s.cancelTimer();
                    payout(s, id);
                    return;
                }
                refund(s, id);
                broadcast(s, "§7" + event.getPlayer().getName()
                        + " left before the deal — stake returned.");
                redraw(s);
                closeIfEmpty(s);
                return;
            }
        }
    }

    /** Server stopping: hand everything back rather than leaving pots in limbo. */
    public void shutdown() {
        for (BetSession s : new ArrayList<>(byTable.values())) {
            // Stakes are already recorded in escrow.yml under their owners, so even a hard
            // kill returns them; this just makes a clean stop instant for online players.
            refundAll(s);
            close(s);
        }
    }

    // =====================================================================  helpers

    private void redraw(BetSession s) {
        if (s.renderer() == null) {
            return;
        }
        String label = switch (s.state()) {
            case ANTE -> "§6§lPOT §f" + s.potSize() + " §7· ante open ("
                    + s.readyCount() + "/" + s.live().size() + " ready)";
            case PLAYING -> "§6§lPOT §f" + s.potSize() + " §7· hand "
                    + s.handNumber() + " in play";
            case RIDE -> "§6§lPOT §f" + s.potSize() + " §7· "
                    + name(s.rideWinner()) + " deciding…";
        };
        s.renderer().update(s.potItems(), label);
    }

    /** The session at the player's table, whatever state it's in. */
    private BetSession sessionAt(Player p) {
        UnoTable table = tables.seatedTable(p.getUniqueId());
        if (table != null) {
            BetSession s = byTable.get(table.id());
            if (s != null) {
                return s;
            }
        }
        // Mid-hand a player may have been bumped out of their seat — fall back to membership.
        for (BetSession s : byTable.values()) {
            if (s.stakers().contains(p.getUniqueId()) || s.isLive(p.getUniqueId())) {
                return s;
            }
        }
        return null;
    }

    /** Same, but rejects (with a message) anything that isn't an open ante. */
    private BetSession anteSessionFor(Player p) {
        BetSession s = sessionAt(p);
        if (s == null) {
            msg(p, "§7No bet running at your table. §e/gamble §7opens one.");
            return null;
        }
        if (s.state() != BetSession.State.ANTE) {
            msg(p, "§cThe hand is already being played.");
            return null;
        }
        return s;
    }

    private BetSession sessionForGame(UUID gameId) {
        UUID tableId = byGame.get(gameId);
        return tableId == null ? null : byTable.get(tableId);
    }

    private UnoTable tableOf(BetSession s) {
        for (UnoTable t : tables.tables()) {
            if (t.id().equals(s.tableId())) {
                return t;
            }
        }
        return null;
    }

    /** Where the heap sits: on the felt at the dealer's end, clear of the card piles. */
    private Location potLocation(UnoTable table) {
        if (table == null) {
            return null;
        }
        Location centre = table.anchor();
        double r = Math.toRadians(table.yaw());
        Vector forward = new Vector(-Math.sin(r), 0, Math.cos(r));
        Location at = centre.clone().add(forward.multiply(POT_FORWARD));
        at.setY(centre.getY() + SURFACE_Y);
        at.setYaw(table.yaw());
        at.setPitch(0f);
        return at;
    }

    /** Everyone with a stake in this pot, plus anyone sitting at the table watching. */
    private void broadcast(BetSession s, String message) {
        Set<UUID> audience = new LinkedHashSet<>(s.stakers());
        audience.addAll(s.live());
        audience.addAll(tables.seatedPlayersAt(s.tableId()));
        for (UUID id : audience) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    private Player firstOnline(List<UUID> ids) {
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static Component button(String label, NamedTextColor color, String cmd, String tip) {
        return Component.text(label, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text(tip, NamedTextColor.GRAY)));
    }

    private static String prettyName(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(stack.getItemMeta().displayName());
        }
        String raw = stack.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String name(UUID id) {
        if (id == null) {
            return "nobody";
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            return p.getName();
        }
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : "Player";
    }

    private static void msg(Player p, String text) {
        p.sendMessage(text);
    }
}
