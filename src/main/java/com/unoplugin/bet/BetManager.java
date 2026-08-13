package com.unoplugin.bet;

import com.unoplugin.game.GameManager;
import com.unoplugin.table.TableManager;
import com.unoplugin.table.UnoTable;
import com.unoplugin.util.Fx;
import com.unoplugin.util.Messages;
import com.unoplugin.util.NameCache;
import com.unoplugin.util.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
import java.util.Collection;
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

    /** Pot sits at the dealer's end, clear of the draw/discard piles at ±0.38 sideways. */
    private static final double POT_FORWARD = 0.78;

    private final Plugin plugin;
    private final TableManager tables;
    private final GameManager games;
    private final Messages messages;
    private final Settings settings;
    private final NameCache names;
    private final Fx fx;
    private final EscrowStore escrow;
    private final BetLog log;

    private final Map<UUID, BetSession> byTable = new HashMap<>();  // tableId  -> session
    private final Map<UUID, UUID> byGame = new HashMap<>();         // gameId   -> tableId

    public BetManager(Plugin plugin, TableManager tables, GameManager games,
                      Messages messages, Settings settings, NameCache names, Fx fx) {
        this.plugin = plugin;
        this.tables = tables;
        this.games = games;
        this.messages = messages;
        this.settings = settings;
        this.names = names;
        this.fx = fx;
        this.escrow = new EscrowStore(plugin, messages);
        this.log = new BetLog(plugin, settings.auditLog());
    }

    // =====================================================================  commands

    /** Entry point for {@code /gamble …} (and {@code /uno gamble …}). */
    public void command(Player player, String[] args) {
        if (!settings.gamblingEnabled()) {
            messages.send(player, "bet.disabled");
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

    /** Subcommands, for tab completion. */
    public static List<String> subcommands() {
        return List.of("ready", "out", "pot", "go", "cancel", "ride", "cash", "help");
    }

    private void help(Player p) {
        for (String key : new String[]{"bet.help-header", "bet.help-open", "bet.help-stake",
                "bet.help-ready", "bet.help-pot", "bet.help-cancel"}) {
            messages.send(p, key);
        }
    }

    /** Open a new ante at the player's table, or join the one already running. */
    private void open(Player p) {
        UnoTable table = tables.seatedTable(p.getUniqueId());
        if (table == null) {
            messages.send(p, "bet.sit-first");
            return;
        }
        if (games.isInGame(p.getUniqueId())) {
            messages.send(p, "bet.in-hand-already");
            return;
        }
        BetSession s = byTable.get(table.id());
        if (s == null) {
            s = new BetSession(table.id(), p.getUniqueId());
            s.setRenderer(new PotRenderer(plugin, potLocation(table), table.yaw()));
            byTable.put(table.id(), s);
            redraw(s);
            broadcast(s, messages.get("bet.opened", "player", p.getName()));
            broadcast(s, messages.get("bet.opened-hint"));
            log.note("OPEN", table.id(), "host=" + p.getName() + " (" + p.getUniqueId() + ")");
            armAnteTimeout(s);
            return;
        }
        if (s.state() != BetSession.State.ANTE) {
            messages.send(p, "bet.hand-in-play");
            return;
        }
        if (s.isLive(p.getUniqueId())) {
            messages.send(p, "bet.youre-in");
            return;
        }
        // Not live yet — you're only in the bet once something of yours is in the pot.
        messages.send(p, "bet.bet-open");
        showPot(p);
    }

    private void ready(Player p) {
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        UUID id = p.getUniqueId();
        boolean isRider = id.equals(s.rideWinner());
        if (!s.hasStaked(id) && !isRider) {
            messages.send(p, "bet.stake-first");
            return;
        }
        int staked = EscrowStore.count(s.stakeOf(id));
        if (!isRider && staked < settings.minAnteItems()) {
            messages.send(p, "bet.min-ante",
                    "min", settings.minAnteItems(), "staked", staked);
            return;
        }
        if (s.isReady(id)) {
            messages.send(p, "bet.already-ready");
            return;
        }
        s.setReady(id);
        broadcast(s, messages.get("bet.ready",
                "player", p.getName(), "ready", s.readyCount(), "live", s.live().size()));
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
            messages.send(p, "bet.not-in-bet");
            return;
        }
        if (id.equals(s.rideWinner())) {
            // The rider owns the whole pot — walking away is just cashing out.
            cash(p);
            return;
        }
        refund(s, id, "WITHDRAW");
        broadcast(s, messages.get("bet.pulled-out", "player", p.getName()));
        redraw(s);
        closeIfEmpty(s);
    }

    private void showPot(Player p) {
        BetSession s = sessionAt(p);
        if (s == null) {
            messages.send(p, "bet.no-bet");
            return;
        }
        messages.send(p, "bet.pot-header", "items", s.potSize(), "stakers", s.stakers().size());
        for (UUID staker : s.stakers()) {
            Component status = messages.get(s.isLive(staker)
                    ? (s.isReady(staker) ? "bet.pot-status-ready" : "bet.pot-status-anteing")
                    : "bet.pot-status-out");
            messages.send(p, "bet.pot-entry",
                    "player", name(staker),
                    "items", EscrowStore.count(s.stakeOf(staker)),
                    "status", status);
        }
        if (s.state() == BetSession.State.PLAYING) {
            messages.send(p, "bet.pot-in-play");
        }
    }

    /** Host escape hatch: start with whoever is ready, refunding the ones still deciding. */
    private void forceStart(Player p) {
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        if (!p.getUniqueId().equals(s.hostId())) {
            messages.send(p, "bet.host-only", "player", name(s.hostId()));
            return;
        }
        for (UUID id : new ArrayList<>(s.live())) {
            if (!s.isReady(id)) {
                refund(s, id, "REFUND");
                Player laggard = Bukkit.getPlayer(id);
                if (laggard != null) {
                    messages.send(laggard, "bet.started-without-you");
                }
            }
        }
        if (s.live().size() + s.botCount() < 2) {
            messages.send(p, "bet.not-enough");
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
            messages.send(p, "bet.host-only", "player", name(s.hostId()));
            return;
        }
        broadcast(s, messages.get("bet.cancelled", "player", p.getName()));
        log.note("CANCEL", s.tableId(), "by=" + p.getName());
        refundAll(s);
        close(s);
    }

    private void setBots(Player p, String[] args) {
        if (!p.hasPermission("uno.admin")) {
            messages.send(p, "common.no-permission");
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
                messages.send(p, "common.number-expected", "value", args[1]);
                return;
            }
        }
        s.setBotCount(Math.max(0, Math.min(4, n)));
        messages.send(p, "bet.bots-set", "count", s.botCount());
    }

    // =====================================================================  staking

    /**
     * Tossing an item at the table stakes it. Runs at HIGH so the hand fan's "Q = play card"
     * cancel (registered at NORMAL) is always seen first — you can never stake your own cards.
     *
     * <p>Every refusal cancels the event: bouncing the message but letting the item really
     * drop on the floor is how a player loses their diamonds to the next passer-by.
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
            event.setCancelled(true);
            messages.send(p, "bet.plugin-item");
            return;
        }
        UUID id = p.getUniqueId();
        if (s.isReady(id)) {
            event.setCancelled(true);
            messages.send(p, "bet.locked-in");
            return;
        }
        String refusal = settings.stakeRefusal(stack);
        if (refusal != null) {
            event.setCancelled(true);
            messages.send(p, refusal, "item", prettyName(stack));
            return;
        }
        int cap = settings.maxPotItems();
        if (cap > 0 && s.potSize() + stack.getAmount() > cap) {
            event.setCancelled(true);
            messages.send(p, "bet.pot-limit", "limit", cap);
            return;
        }
        // Kill the real entity immediately: dropped items despawn, get hoovered by hoppers and
        // can be grabbed by anyone. The stack lives in escrow and is drawn as a display instead.
        event.getItemDrop().remove();
        s.stake(id, stack);
        escrow.hold(id, s.stakeOf(id));
        log.record("STAKE", s.tableId(), id, p.getName(), List.of(stack), "pot=" + s.potSize());

        fx.staked(potLocation(tableOf(s)));
        broadcast(s, messages.get("bet.staked",
                "player", p.getName(),
                "amount", stack.getAmount(),
                "item", prettyName(stack),
                "pot", s.potSize()));
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
            broadcast(s, messages.get("bet.table-vanished"));
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
            broadcast(s, messages.get("bet.deal-failed"));
            refundAll(s);
            close(s);
            return;
        }
        s.cancelTimer();
        s.setGameId(gameId);
        s.setState(BetSession.State.PLAYING);
        s.nextHand();
        byGame.put(gameId, s.tableId());
        broadcast(s, messages.get("bet.on-the-line", "items", s.potSize()));
        log.note("DEAL", s.tableId(), "hand=" + s.handNumber() + " pot=" + s.potSize()
                + " players=" + humans.size() + " bots=" + s.botCount());
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
        broadcast(s, messages.get("bet.forfeit", "player", name(player), "items", lost));
        log.record("FORFEIT", s.tableId(), player, name(player), s.stakeOf(player),
                "stake stays in the pot");
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
            broadcast(s, messages.get("bet.no-winner"));
            refundAll(s);
            close(s);
            return;
        }
        if (games.isBotId(winner)) {
            // Bots don't collect. A bot win is a push, not a house edge.
            broadcast(s, messages.get("bet.bot-push", "player", games.displayName(winner)));
            refundAll(s);
            close(s);
            return;
        }
        if (!settings.rideEnabled() || s.potSize() == 0) {
            payout(s, winner);
            return;
        }
        s.setState(BetSession.State.RIDE);
        s.setRideWinner(winner);
        offerRide(s, winner);
    }

    /** Winner's choice: take the pot, or leave it all in for another hand. */
    private void offerRide(BetSession s, UUID winner) {
        int seconds = settings.rideWindowSeconds();
        int pot = s.potSize();
        broadcast(s, messages.get("bet.ride-won", "player", name(winner), "items", pot));
        redraw(s);

        Player w = Bukkit.getPlayer(winner);
        if (w != null) {
            w.sendMessage(messages.get("bet.ride-offer", "items", pot)
                    .append(button(messages.get("bet.ride-cash-button"), NamedTextColor.GREEN,
                            "/gamble cash", messages.get("bet.ride-cash-tip", "items", pot)))
                    .append(Component.text("  "))
                    .append(button(messages.get("bet.ride-ride-button"), NamedTextColor.RED,
                            "/gamble ride", messages.get("bet.ride-ride-tip", "items", pot))));
            messages.send(w, "bet.ride-window", "seconds", seconds);
        }
        s.setTimer(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (s.state() == BetSession.State.RIDE) {
                broadcast(s, messages.get("bet.time-up"));
                payout(s, winner);
            }
        }, Math.max(1, seconds) * 20L));
    }

    private void ride(Player p) {
        BetSession s = sessionAt(p);
        if (s == null || s.state() != BetSession.State.RIDE
                || !p.getUniqueId().equals(s.rideWinner())) {
            messages.send(p, "bet.nothing-to-ride");
            return;
        }
        s.cancelTimer();
        // The pot is his now — re-file every item under the winner so a crash pays him, and
        // so anyone who wants a piece has to put fresh items in.
        //
        // Read the old stakers BEFORE re-filing: reattributeTo collapses `stakes` down to the
        // rider alone, so asking afterwards returns only him and the losers' escrow files are
        // never released. They would keep holding items that are now ALSO held under the
        // rider — and the next relog hands those back, minting them out of nothing.
        List<UUID> previous = new ArrayList<>(s.stakers());
        s.reattributeTo(p.getUniqueId());
        for (UUID staker : previous) {
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

        int challenge = settings.rideChallengeSeconds();
        broadcast(s, messages.get("bet.rides", "player", p.getName(), "items", s.potSize()));
        broadcast(s, messages.get("bet.match-it", "seconds", challenge));
        fx.letItRide(potLocation(tableOf(s)));
        log.note("RIDE", s.tableId(), "rider=" + p.getName() + " pot=" + s.potSize());
        redraw(s);
        UUID rider = p.getUniqueId();
        s.setTimer(plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> resolveChallenge(s, rider), Math.max(1, challenge) * 20L));
    }

    /**
     * The challenge window closed. This must always leave the session resolved.
     *
     * <p>The old version only paid out when fewer than two players were live, so a challenger
     * who staked but never ran {@code /gamble ready} left the session parked in ANTE with
     * everyone's items in escrow and no timer to ever revisit it — the table was dead until
     * the server restarted.
     */
    private void resolveChallenge(BetSession s, UUID rider) {
        if (s.state() != BetSession.State.ANTE || !rider.equals(s.rideWinner())) {
            return;
        }
        boolean refundedSomeone = false;
        for (UUID id : new ArrayList<>(s.live())) {
            if (!id.equals(rider) && !s.isReady(id)) {
                refund(s, id, "REFUND");
                refundedSomeone = true;
                Player laggard = Bukkit.getPlayer(id);
                if (laggard != null) {
                    messages.send(laggard, "bet.started-without-you");
                }
            }
        }
        if (refundedSomeone) {
            broadcast(s, messages.get("bet.challenge-lapsed"));
        }
        if (s.live().size() >= 2) {
            // Someone did match the pot and lock in — play the challenge hand.
            startHand(s);
            return;
        }
        broadcast(s, messages.get("bet.no-takers", "player", name(rider)));
        payout(s, rider);
    }

    /**
     * Take the pot instead of riding it.
     *
     * <p>Only valid while the offer is actually open. {@code rideWinner} stays set through the
     * whole challenge window — and {@code ride()} puts the state back to ANTE — so checking it
     * alone let the rider cash out <em>after</em> a challenger had matched, walking off with
     * their fresh stake without a card being played.
     */
    private void cash(Player p) {
        BetSession s = sessionAt(p);
        if (s == null || s.state() != BetSession.State.RIDE
                || !p.getUniqueId().equals(s.rideWinner())) {
            messages.send(p, "bet.nothing-to-cash");
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
        log.record("PAYOUT", s.tableId(), winner, name(winner), pot, "hand=" + s.handNumber());
        broadcast(s, messages.get("bet.collects",
                "player", name(winner), "items", EscrowStore.count(pot)));
        fx.jackpot(potLocation(tableOf(s)), Bukkit.getPlayer(winner));
        close(s);
    }

    // =====================================================================  refunds

    private void refund(BetSession s, UUID player, String action) {
        List<ItemStack> back = s.withdraw(player);
        if (!back.isEmpty()) {
            log.record(action, s.tableId(), player, name(player), back, "returned");
        }
        escrow.payTo(player, back);
    }

    private void refundAll(BetSession s) {
        for (UUID staker : new ArrayList<>(s.stakers())) {
            refund(s, staker, "REFUND");
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
        int seconds = settings.anteSeconds();
        if (seconds <= 0) {
            return;
        }
        s.setTimer(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (s.state() == BetSession.State.ANTE) {
                broadcast(s, messages.get("bet.expired"));
                refundAll(s);
                close(s);
            }
        }, seconds * 20L));
    }

    // =====================================================================  admin

    /** True if a pot is open at this table — it must not be removed underneath one. */
    public boolean hasSessionAtTable(UUID tableId) {
        return tableId != null && byTable.containsKey(tableId);
    }

    /** Tables with an open pot. */
    public Collection<UUID> sessionTables() {
        return List.copyOf(byTable.keySet());
    }

    public BetSession session(UUID tableId) {
        return byTable.get(tableId);
    }

    /** The pot a player is mixed up in, whatever state it's in. */
    public UUID tableOfPlayer(UUID playerId) {
        for (BetSession s : byTable.values()) {
            if (s.stakers().contains(playerId) || s.isLive(playerId)) {
                return s.tableId();
            }
        }
        return null;
    }

    /** Force a stuck pot back to its stakers (the admin escape hatch). */
    public boolean forceRefund(UUID tableId) {
        BetSession s = byTable.get(tableId);
        if (s == null) {
            return false;
        }
        broadcast(s, messages.get("bet.no-winner"));
        log.note("ADMIN", tableId, "forced refund");
        refundAll(s);
        close(s);
        return true;
    }

    /** Force every open pot back to its stakers. Returns how many were settled. */
    public int forceRefundAll() {
        int n = 0;
        for (UUID tableId : List.copyOf(byTable.keySet())) {
            if (forceRefund(tableId)) {
                n++;
            }
        }
        return n;
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
                    // The rider left mid-challenge. The pot they won is already theirs on
                    // paper, but anything a challenger staked to match them is NOT — no hand
                    // was played for it. Hand that back before settling, or quitting at the
                    // right moment is a way to take other people's items.
                    s.cancelTimer();
                    for (UUID staker : new ArrayList<>(s.stakers())) {
                        if (!staker.equals(id)) {
                            refund(s, staker, "REFUND");
                        }
                    }
                    payout(s, id);
                    return;
                }
                refund(s, id, "REFUND");
                broadcast(s, messages.get("bet.left-before-deal", "player", event.getPlayer().getName()));
                redraw(s);
                closeIfEmpty(s);
                return;
            }
        }
    }

    /**
     * Server stopping. Deliberately does <strong>not</strong> hand items back.
     *
     * <p>Every stake is already recorded in escrow under its owner, and mutating player
     * inventories while the server is shutting down is a race with the save that persists
     * them — whether the items survive depends on timing. Leaving them in escrow uses the
     * path a hard kill would take anyway: they are returned on the player's next join.
     */
    public void shutdown() {
        for (BetSession s : new ArrayList<>(byTable.values())) {
            log.note("SHUTDOWN", s.tableId(), "pot=" + s.potSize()
                    + " held in escrow, returned on next join");
            close(s);
        }
        log.shutdown();
    }

    // =====================================================================  helpers

    private void redraw(BetSession s) {
        if (s.renderer() == null) {
            return;
        }
        Component label = switch (s.state()) {
            case ANTE -> messages.get("bet.pot-label-ante", "items", s.potSize(),
                    "ready", s.readyCount(), "live", s.live().size());
            case PLAYING -> messages.get("bet.pot-label-playing", "items", s.potSize(),
                    "hand", s.handNumber());
            case RIDE -> messages.get("bet.pot-label-ride", "items", s.potSize(),
                    "player", name(s.rideWinner()));
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
            messages.send(p, "bet.no-bet");
            return null;
        }
        if (s.state() != BetSession.State.ANTE) {
            messages.send(p, "bet.already-playing");
            return null;
        }
        return s;
    }

    private BetSession sessionForGame(UUID gameId) {
        UUID tableId = byGame.get(gameId);
        return tableId == null ? null : byTable.get(tableId);
    }

    private UnoTable tableOf(BetSession s) {
        return tables.table(s.tableId());
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
        at.setY(centre.getY() + UnoTable.SURFACE_Y);
        at.setYaw(table.yaw());
        at.setPitch(0f);
        return at;
    }

    /** Everyone with a stake in this pot, plus anyone sitting at the table watching. */
    private void broadcast(BetSession s, Component message) {
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

    private static Component button(Component label, NamedTextColor color, String cmd, Component tip) {
        return label.colorIfAbsent(color).decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(tip));
    }

    private static String prettyName(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName());
        }
        String raw = stack.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    /** Never blocks the server thread — see {@link NameCache}. */
    private String name(UUID id) {
        return id == null ? "nobody" : games.displayName(id);
    }
}
