package com.legallynotuno.bet;

import com.legallynotuno.game.GameManager;
import com.legallynotuno.table.TableManager;
import com.legallynotuno.table.UnoTable;
import com.legallynotuno.util.Fx;
import com.legallynotuno.util.Messages;
import com.legallynotuno.util.NameCache;
import com.legallynotuno.util.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
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
 * "Let It Ride" — the wagering layer on top of a normal hand.
 *
 * <p>The loop: <b>/gamble</b> opens an ante at your table, players
 * put something up — items tossed onto the felt, or currency with <b>/gamble &lt;amount&gt;</b>
 * — <b>/gamble ready</b> locks a stake in, and when everyone's ready the table plays one hand
 * for the whole pot. The winner then chooses: cash out, or let the pot ride into the
 * next hand and make the table match it.
 *
 * <p>Items and money are one thing to every rule here — see {@link Stake}. Money reaches the
 * server's economy through {@link VaultEconomy} and is withdrawn from the staker's balance
 * the instant it is staked, so it lives in escrow exactly as items do and a crash refunds
 * both.
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

    /**
     * How far the ante heap spreads from the middle of the table, in blocks. There are no
     * cards down while people are anteing, so it gets the room; it stays clear of the edges
     * of the 3×3 top even with a full-size item on its outermost slot.
     */
    private static final double ANTE_SPREAD = 0.8;
    /** How far toward the winner's seat their winnings are pushed from the middle. */
    private static final double WINNINGS_REACH = 0.75;
    /**
     * The winnings' shape: wide and shallow, so a heap that close to the winner's edge of the
     * table still doesn't hang over it.
     */
    private static final double WINNINGS_ACROSS = 0.85;
    private static final double WINNINGS_DEPTH = 0.4;
    /** How long a paid-out pot stays in front of its winner before it's gone. */
    private static final long PAYOUT_LINGER_TICKS = 40L;
    /** How many kinds of item a pot names before it says "+N more" (all of them on hover). */
    private static final int LABEL_ITEM_KINDS = 3;

    private final Plugin plugin;
    private final TableManager tables;
    private final GameManager games;
    private final Messages messages;
    private final Settings settings;
    private final NameCache names;
    private final Fx fx;
    private final VaultEconomy economy;
    private final EscrowStore escrow;
    private final BetLog log;

    private final Map<UUID, BetSession> byTable = new HashMap<>();  // tableId  -> session
    private final Map<UUID, UUID> byGame = new HashMap<>();         // gameId   -> tableId
    /**
     * tableId -> a pot that has been paid out and is sitting in front of its winner for a
     * moment. Its session is already closed, so it is tracked here to be taken down early if
     * a new ante opens at the table, and on shutdown.
     */
    private final Map<UUID, PotRenderer> settled = new HashMap<>();

    public BetManager(Plugin plugin, TableManager tables, GameManager games,
                      Messages messages, Settings settings, NameCache names, Fx fx) {
        this.plugin = plugin;
        this.tables = tables;
        this.games = games;
        this.messages = messages;
        this.settings = settings;
        this.names = names;
        this.fx = fx;
        this.economy = new VaultEconomy(plugin);
        this.escrow = new EscrowStore(plugin, messages, economy);
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
            default -> {
                // A bare number IS the command: `/gamble 500` opens the ante if it needs to
                // and puts 500 in. Anything that isn't a number falls through to help, so a
                // typo still tells you what the verbs are.
                Double amount = parseAmount(sub);
                if (amount == null) {
                    help(player);
                } else {
                    stakeMoney(player, amount);
                }
            }
        }
    }

    /**
     * A wager amount, or null if this isn't one.
     *
     * <p>Rounded to whole cents up front, because every later comparison — the minimum, the
     * pot cap, "did the challenger match it?" — is against a number the player was shown, and
     * a third of a penny hiding behind the formatting makes those read as lies.
     */
    private static Double parseAmount(String raw) {
        try {
            double value = Double.parseDouble(raw.replace(",", ""));
            if (!Double.isFinite(value) || value <= 0) {
                return null;
            }
            return Math.round(value * 100.0) / 100.0;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Subcommands, for tab completion. */
    public static List<String> subcommands() {
        return List.of("ready", "out", "pot", "go", "cancel", "ride", "cash", "help");
    }

    private void help(Player p) {
        messages.send(p, "bet.help-header");
        messages.send(p, "bet.help-open");
        messages.send(p, "bet.help-stake");
        if (moneyAllowed()) {
            messages.send(p, "bet.help-money");
        }
        for (String key : new String[]{"bet.help-ready", "bet.help-pot", "bet.help-cancel"}) {
            messages.send(p, key);
        }
    }

    /** True if currency can be wagered here at all: switched on, and an economy to do it. */
    private boolean moneyAllowed() {
        return settings.moneyEnabled() && economy.available();
    }

    /**
     * The money-aware wording of a hint, where one exists.
     *
     * <p>Every "here's how you buy in" line has two versions, because telling a player about
     * {@code /gamble <amount>} on a server with no economy sends them at a door that isn't
     * there — and leaving cash out of the wording on a server that HAS one is how you end up
     * unable to work out that items and money go in the same pot.
     */
    private String hint(String base) {
        return moneyAllowed() ? base + "-money" : base;
    }

    /**
     * After every stake: what this player has in, and the one click that locks it.
     *
     * <p>Says "you have X in" rather than "you staked X", and names the other currency, because
     * <strong>items and money add up and nothing was telling anyone that.</strong> The old
     * flow put a bare [ I'M IN ] under a cash stake, which reads as the next step — and taking
     * it slams the door, since a locked-in player's dropped items are refused. Raising has to
     * look at least as available as locking in.
     */
    private void stakeHint(Player p, BetSession s) {
        messages.send(p, hint("bet.your-stake"),
                "stake", stakeLabel(s.stakeOf(p.getUniqueId())),
                "lock", messages.button("bet.lock-in-button", "/gamble ready"));
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
        if (s == null && games.hasGameAtTable(table.id())) {
            // Not the same as the check above: a player who sat down in a free seat after a
            // hand was dealt isn't in it, but the table is still busy. Opening here would put
            // the ante heap in the middle of the felt, on top of the card piles.
            messages.send(p, "bet.table-busy");
            return;
        }
        if (s == null) {
            s = new BetSession(table.id(), p.getUniqueId());
            clearSettled(table.id()); // the last pot's winnings, still on their way out
            s.setRenderer(new PotRenderer(plugin, surfaceCentre(table)));
            byTable.put(table.id(), s);
            // Anyone who had said they were up for a friendly hand has to say so again now
            // that there is money on it. Consent to a casual game is not consent to a wager.
            games.clearReady(table.id());
            redraw(s);
            broadcast(s, messages.get("bet.opened", "player", p.getName()));
            // The how-to goes to the host; everyone else gets the button that buys them in.
            messages.send(p, hint("bet.opened-hint"));
            invite(table, p.getUniqueId());
            log.note("OPEN", table.id(), "host=" + p.getName() + " (" + p.getUniqueId() + ")");
            armAnteTimeout(s);
            return;
        }
        if (s.state() != BetSession.State.ANTE) {
            messages.send(p, "bet.hand-in-play");
            return;
        }
        if (s.isLive(p.getUniqueId())) {
            messages.send(p, hint("bet.youre-in"));
            return;
        }
        // Not live yet — you're only in the bet once something of yours is in the pot.
        messages.send(p, hint("bet.bet-open"));
        showPot(p);
    }

    /**
     * Tell the rest of the table a bet just opened, with the button that buys them in.
     *
     * <p>Everyone but the host, who is already looking at the pot they opened.
     */
    private void invite(UnoTable table, UUID host) {
        Component button = messages.button("bet.join-button", "/gamble");
        for (UUID id : tables.seatedPlayersAt(table.id())) {
            Player other = id.equals(host) ? null : Bukkit.getPlayer(id);
            if (other != null) {
                messages.send(other, "bet.invited", "player", name(host), "join", button);
            }
        }
    }

    /**
     * Stake currency: {@code /gamble <amount>}, which also opens the ante if there isn't one.
     *
     * <p><strong>The money leaves the balance before the stake is recorded, in that order.</strong>
     * The reverse would have a crash in the gap hand back money that was never actually paid,
     * and minting currency out of a power cut is a far worse failure than the vanishing chance
     * of losing one stake to it. Once recorded, escrow is written synchronously — from that
     * instant the wager survives a {@code kill -9}.
     */
    private void stakeMoney(Player p, double amount) {
        if (!settings.moneyEnabled()) {
            messages.send(p, "bet.money-disabled");
            return;
        }
        if (!economy.available()) {
            messages.send(p, "bet.no-economy");
            return;
        }
        if (sessionAt(p) == null) {
            open(p);  // `/gamble 500` at a table with no bet on it opens one and joins it
            if (sessionAt(p) == null) {
                return; // open() already said why — don't follow it with "no bet running"
            }
        }
        BetSession s = anteSessionFor(p);
        if (s == null) {
            return;
        }
        UUID id = p.getUniqueId();
        if (s.isReady(id)) {
            messages.send(p, "bet.locked-in");
            return;
        }
        double cap = settings.maxPotMoney();
        if (cap > 0 && s.potMoney() + amount > cap) {
            messages.send(p, "bet.money-limit", "limit", economy.format(cap));
            return;
        }
        if (!economy.has(p, amount)) {
            messages.send(p, "bet.money-short",
                    "amount", economy.format(amount),
                    "balance", economy.format(economy.balanceOf(p)));
            return;
        }
        if (!economy.withdraw(p, amount)) {
            messages.send(p, "bet.money-failed");
            return;
        }
        s.stakeMoney(id, amount);
        escrow.hold(id, s.stakeOf(id));
        log.record("STAKE", s.tableId(), id, p.getName(), Stake.ofMoney(amount),
                "pot=" + potDescription(s));

        fx.staked(surfaceCentre(tableOf(s)));
        broadcast(s, messages.get("bet.staked-money",
                "player", p.getName(),
                "money", economy.format(amount),
                "pot", potLabel(s)));
        redraw(s);
        stakeHint(p, s);
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
        Stake staked = s.stakeOf(id);
        if (!isRider && !meetsMinimum(staked)) {
            // Two wordings, because quoting a money minimum on a server with no economy is
            // telling the player about a door that isn't there.
            messages.send(p, moneyAllowed() ? "bet.min-ante-money" : "bet.min-ante",
                    "min", settings.minAnteItems(),
                    "staked", staked.itemCount(),
                    "money", economy.format(settings.minAnteMoney()));
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

    /**
     * Is this stake big enough to lock in?
     *
     * <p>Either minimum will do. They are separate figures because they are separate
     * currencies — requiring both would mean a player who wanted to bet cash had to find an
     * item to go with it, and holding a pure-money stake to the item minimum would refuse it
     * outright.
     */
    private boolean meetsMinimum(Stake stake) {
        if (stake.isEmpty()) {
            return false;
        }
        return stake.itemCount() >= settings.minAnteItems()
                || (stake.hasMoney() && stake.money() >= settings.minAnteMoney());
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
        messages.send(p, "bet.pot-header", "pot", potLabel(s), "stakers", s.stakers().size());
        for (UUID staker : s.stakers()) {
            Component status = messages.get(s.isLive(staker)
                    ? (s.isReady(staker) ? "bet.pot-status-ready" : "bet.pot-status-anteing")
                    : "bet.pot-status-out");
            messages.send(p, "bet.pot-entry",
                    "player", name(staker),
                    "stake", stakeLabel(s.stakeOf(staker)),
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
        if (!p.getUniqueId().equals(s.hostId()) && !p.hasPermission("legallynotuno.admin")) {
            messages.send(p, "bet.host-only", "player", name(s.hostId()));
            return;
        }
        broadcast(s, messages.get("bet.cancelled", "player", p.getName()));
        log.note("CANCEL", s.tableId(), "by=" + p.getName());
        refundAll(s);
        close(s);
    }

    private void setBots(Player p, String[] args) {
        if (!p.hasPermission("legallynotuno.admin")) {
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
        log.record("STAKE", s.tableId(), id, p.getName(), Stake.ofItem(stack),
                "pot=" + potDescription(s));

        fx.staked(surfaceCentre(tableOf(s)));
        broadcast(s, messages.get("bet.staked",
                "player", p.getName(),
                "amount", stack.getAmount(),
                "item", prettyName(stack),
                "pot", potLabel(s)));
        redraw(s);
        stakeHint(p, s);
    }

    /** The game's own cards and table items must never end up in a pot. */
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
        if (firstOnline(humans) == null) {
            // Everyone who staked has gone offline — deal to nobody and hand it all back.
            refundAll(s);
            close(s);
            return;
        }
        UUID gameId = games.startWager(humans, s.botCount(), table);
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
        broadcast(s, messages.get("bet.on-the-line", "pot", potLabel(s)));
        log.note("DEAL", s.tableId(), "hand=" + s.handNumber() + " pot=" + potDescription(s)
                + " players=" + humans.size() + " bots=" + s.botCount());
        redraw(s);
    }

    /** Does this table have a live pot? Used to refuse {@code /uno stop} on a wagered hand. */
    @Override
    public boolean hasPotAtTable(UUID tableId) {
        return hasSessionAtTable(tableId);
    }

    /** A player left mid-hand (disconnect or /uno quit): out, stake stays in the pot. */
    @Override
    public void onForfeit(UUID gameId, UUID player) {
        BetSession s = sessionForGame(gameId);
        if (s == null || !s.isLive(player)) {
            return;
        }
        Stake lost = s.stakeOf(player);
        s.forfeit(player);
        broadcast(s, messages.get("bet.forfeit", "player", name(player),
                "stake", stakeLabel(lost)));
        log.record("FORFEIT", s.tableId(), player, name(player), lost,
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
        if (!settings.rideEnabled() || s.pot().isEmpty()) {
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
        Component pot = potLabel(s);
        broadcast(s, messages.get("bet.ride-won", "player", name(winner), "pot", pot));
        redraw(s);

        Player w = Bukkit.getPlayer(winner);
        if (w != null) {
            w.sendMessage(messages.get("bet.ride-offer", "pot", pot)
                    .append(button(messages.get("bet.ride-cash-button"), NamedTextColor.GREEN,
                            "/gamble cash", messages.get("bet.ride-cash-tip", "pot", pot)))
                    .append(Component.text("  "))
                    .append(button(messages.get("bet.ride-ride-button"), NamedTextColor.RED,
                            "/gamble ride", messages.get("bet.ride-ride-tip", "pot", pot))));
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
        escrow.hold(p.getUniqueId(), s.pot());

        s.live().clear();
        s.live().add(p.getUniqueId());
        s.clearReady();
        s.setReady(p.getUniqueId());
        s.setState(BetSession.State.ANTE);

        int challenge = settings.rideChallengeSeconds();
        broadcast(s, messages.get("bet.rides", "player", p.getName(), "pot", potLabel(s)));
        broadcast(s, messages.get("bet.match-it", "seconds", challenge));
        fx.letItRide(surfaceCentre(tableOf(s)));
        log.note("RIDE", s.tableId(), "rider=" + p.getName() + " pot=" + potDescription(s));
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

    /**
     * Settle: the whole pot changes hands and the session closes.
     *
     * <p>The heap is shown in front of the winner for a moment on the way out, whichever way
     * the pot got here — cashed out, unanswered, or dealt straight to them with riding
     * switched off. The last case matters most: there the heap goes straight from hidden
     * under the cards to paid, and without the pause nobody would ever see who took it.
     */
    private void payout(BetSession s, UUID winner) {
        Stake pot = s.pot();
        Component label = potLabel(s);
        for (UUID staker : s.stakers()) {
            escrow.release(staker);
        }
        escrow.payTo(winner, pot);
        log.record("PAYOUT", s.tableId(), winner, name(winner), pot, "hand=" + s.handNumber());
        broadcast(s, messages.get("bet.collects", "player", name(winner), "pot", label));
        UnoTable table = tableOf(s);
        PotRenderer.Spot winnings = table == null ? null : winnerSpot(table, winner);
        fx.jackpot(winnings == null ? null : winnings.centre(), Bukkit.getPlayer(winner));
        PotRenderer renderer = s.renderer();
        if (renderer != null && winnings != null) {
            renderer.update(pot, messages.get("bet.pot-label-won",
                    "player", name(winner), "pot", label), winnings);
            // Detached from the session, so close() leaves it up; it takes itself down.
            s.setRenderer(null);
            linger(s.tableId(), renderer);
        }
        close(s);
    }

    /** Leave a paid-out pot on the table for a moment, then take it down. */
    private void linger(UUID tableId, PotRenderer renderer) {
        clearSettled(tableId);
        settled.put(tableId, renderer);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (settled.remove(tableId, renderer)) {
                renderer.remove();
            }
        }, PAYOUT_LINGER_TICKS);
    }

    private void clearSettled(UUID tableId) {
        PotRenderer old = settled.remove(tableId);
        if (old != null) {
            old.remove();
        }
    }

    // =====================================================================  refunds

    private void refund(BetSession s, UUID player, String action) {
        Stake back = s.withdraw(player);
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
        close(s, true);
    }

    /**
     * Settle a session and take the table back to normal.
     *
     * <p>{@code prompt} is false only on shutdown: offering "play again" to a table the
     * server is in the middle of stopping is noise at best, and the click would land on a
     * plugin that has already torn its commands down.
     */
    private void close(BetSession s, boolean prompt) {
        s.cancelTimer();
        if (s.renderer() != null) {
            s.renderer().remove();
        }
        if (s.gameId() != null) {
            byGame.remove(s.gameId());
        }
        byTable.remove(s.tableId());
        if (prompt) {
            tables.promptModeAt(s.tableId(), "table.again");
        }
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
            if (s.isLive(id) && leaveAnte(s, id, event.getPlayer().getName())) {
                return;
            }
        }
    }

    /**
     * A player got up from the table: {@code /uno leave}, shift, or a disconnect.
     *
     * <p>Wired as half of the table layer's stand-up hook, alongside the game layer's forfeit.
     * <strong>Standing up during an ante has to settle the ante</strong>, and the game layer
     * cannot do it: no hand has been dealt yet, so {@code GameManager.forfeit} finds nothing to
     * forfeit and returns quietly. Without this the player walks off and their stake, the pot
     * display and the mat stay on a table nobody is sitting at until the ante times out —
     * while doing the exact same thing by pulling the plug refunded them, because
     * {@link #onQuit} always handled it. Two ways out of the same chair must cost the same.
     *
     * <p>Mid-hand there is nothing to do here: the forfeit rule applies and the stake stays in
     * the pot, which the hand's own {@link #onForfeit} has already recorded.
     */
    public void onStandUp(Player player, UUID tableId) {
        BetSession s = byTable.get(tableId);
        if (s == null || s.state() != BetSession.State.ANTE) {
            return;
        }
        if (s.isLive(player.getUniqueId())) {
            leaveAnte(s, player.getUniqueId(), player.getName());
            return;
        }
        // They had nothing in the pot — but they may have been the one who opened it, and the
        // last person at the table. An ante with no stake in it and nobody sitting at it is
        // just a mat and a timer, so take it down rather than making the table wait one out.
        if (s.live().isEmpty() && tables.seatedPlayersAt(tableId).isEmpty()) {
            broadcast(s, messages.get("bet.abandoned"));
            log.note("ABANDON", tableId, "everyone left the table before anything was staked");
            close(s);
        }
    }

    /**
     * Pull one player out of an open ante and hand their stake back.
     *
     * <p>Shared by disconnecting and by standing up, deliberately — see {@link #onStandUp}.
     *
     * @return true if this session was the one they were in, so the caller can stop looking.
     */
    private boolean leaveAnte(BetSession s, UUID id, String name) {
        if (s.state() != BetSession.State.ANTE || !s.isLive(id)) {
            return false;
        }
        if (id.equals(s.rideWinner())) {
            // The rider left mid-challenge. The pot they won is already theirs on paper, but
            // anything a challenger staked to match them is NOT — no hand was played for it.
            // Hand that back before settling, or leaving at the right moment is a way to take
            // other people's items.
            s.cancelTimer();
            for (UUID staker : new ArrayList<>(s.stakers())) {
                if (!staker.equals(id)) {
                    refund(s, staker, "REFUND");
                }
            }
            payout(s, id);
            return true;
        }
        refund(s, id, "REFUND");
        broadcast(s, messages.get("bet.left-before-deal", "player", name));
        redraw(s);
        closeIfEmpty(s);
        return true;
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
            log.note("SHUTDOWN", s.tableId(), "pot=" + potDescription(s)
                    + " held in escrow, returned on next join");
            close(s, false);
        }
        for (PotRenderer renderer : settled.values()) {
            renderer.remove();
        }
        settled.clear();
        log.shutdown();
    }

    // =====================================================================  helpers

    private void redraw(BetSession s) {
        if (s.renderer() == null) {
            return;
        }
        Component pot = potLabel(s);
        Component label = switch (s.state()) {
            case ANTE -> messages.get("bet.pot-label-ante", "pot", pot,
                    "ready", s.readyCount(), "live", s.live().size());
            case PLAYING -> messages.get("bet.pot-label-playing", "pot", pot,
                    "hand", s.handNumber());
            case RIDE -> messages.get("bet.pot-label-ride", "pot", pot,
                    "player", name(s.rideWinner()));
        };
        UnoTable table = tableOf(s);
        // Where the heap is depends on what the table is doing: in the middle while people
        // ante, off the felt while the cards are on it, and in front of the winner while they
        // decide whether to let it ride. Riding sends it back to the middle for the challenge.
        PotRenderer.Spot spot = table == null ? null : switch (s.state()) {
            case ANTE -> anteSpot(table);
            case PLAYING -> null;
            case RIDE -> winnerSpot(table, s.rideWinner());
        };
        s.renderer().update(s.pot(), label, spot);
    }

    /**
     * What is on the table, as one phrase: "32× Diamond, 16× Gold Ingot and $500".
     *
     * <p>Every message that quotes a pot goes through this rather than through an
     * {@code <items>} count of its own. Half the wagering messages predate money, and the way
     * they would have gone wrong is by continuing to say "8 items" over a pot that is mostly
     * cash — technically true, and a lie about what the player is playing for. It names the
     * items for the same reason: "8 items" doesn't say whether that's dirt or diamonds.
     */
    private Component potLabel(BetSession s) {
        return stakeLabel(s.pot());
    }

    private Component stakeLabel(Stake stake) {
        Component items = itemList(stake.items());
        if (items != null && stake.hasMoney()) {
            return messages.get("bet.stake-list-both", "items", items,
                    "money", economy.format(stake.money()));
        }
        if (stake.hasMoney()) {
            return messages.get("bet.stake-list-money", "money", economy.format(stake.money()));
        }
        if (items != null) {
            return messages.get("bet.stake-list-items", "items", items);
        }
        return messages.get("bet.stake-list-nothing");
    }

    /**
     * The items themselves, grouped by kind: "32× Diamond, 16× Gold Ingot, +2 more", with
     * every kind listed on hover. Null if there are none.
     *
     * <p>Two drops of 16 diamonds are one line of 32, not two lines of 16 — the list is what
     * is on the table, not the order it arrived in. Names are the client's own translated
     * item names (or the item's custom name), so they read the way the item does in hand.
     */
    private Component itemList(List<ItemStack> stacks) {
        List<ItemStack> kinds = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        next:
        for (ItemStack stack : stacks) {
            for (int i = 0; i < kinds.size(); i++) {
                if (kinds.get(i).isSimilar(stack)) {
                    amounts.set(i, amounts.get(i) + stack.getAmount());
                    continue next;
                }
            }
            kinds.add(stack);
            amounts.add(stack.getAmount());
        }
        if (kinds.isEmpty()) {
            return null;
        }
        Component separator = messages.get("bet.stake-list-separator");
        TextComponent.Builder shown = Component.text();
        TextComponent.Builder all = Component.text();
        for (int i = 0; i < kinds.size(); i++) {
            Component entry = messages.get("bet.stake-list-entry",
                    "amount", amounts.get(i), "item", kinds.get(i).effectiveName());
            if (i > 0) {
                all.append(Component.newline());
                if (i < LABEL_ITEM_KINDS) {
                    shown.append(separator);
                }
            }
            if (i < LABEL_ITEM_KINDS) {
                shown.append(entry);
            }
            all.append(entry);
        }
        if (kinds.size() > LABEL_ITEM_KINDS) {
            shown.append(separator).append(messages.get("bet.stake-list-more",
                    "count", kinds.size() - LABEL_ITEM_KINDS));
        }
        return shown.build().hoverEvent(HoverEvent.showText(all.build()));
    }

    /** The same thing for the audit log — plain, greppable, and never the economy's format. */
    private static String potDescription(BetSession s) {
        Stake pot = s.pot();
        return pot.itemCount() + " item(s)"
                + (pot.hasMoney() ? String.format(java.util.Locale.ROOT, " + %.2f", pot.money()) : "");
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

    /** The middle of the table's top surface, or null if there's no table. */
    private static Location surfaceCentre(UnoTable table) {
        if (table == null) {
            return null;
        }
        Location at = table.anchor();
        at.setY(at.getY() + UnoTable.SURFACE_Y);
        at.setYaw(table.yaw());
        at.setPitch(0f);
        return at;
    }

    /** The ante heap: the middle of the table, where everyone buying in can see it grow. */
    private static PotRenderer.Spot anteSpot(UnoTable table) {
        return new PotRenderer.Spot(surfaceCentre(table), table.yaw(), ANTE_SPREAD, ANTE_SPREAD);
    }

    /**
     * The winnings: on the table in front of the winner's seat, pushed out from the middle
     * toward them. Falls back to the middle for a winner who isn't sitting here any more.
     */
    private static PotRenderer.Spot winnerSpot(UnoTable table, UUID winner) {
        int seat = winner == null ? -1 : table.seatOf(winner);
        if (seat < 0) {
            return anteSpot(table);
        }
        Location centre = surfaceCentre(table);
        Location chair = table.seats().get(seat);
        Vector toward = new Vector(chair.getX() - centre.getX(), 0, chair.getZ() - centre.getZ());
        if (toward.lengthSquared() < 1.0e-6) {
            return anteSpot(table);
        }
        toward.normalize();
        Location at = centre.clone().add(toward.clone().multiply(WINNINGS_REACH));
        // The yaw whose forward vector (-sin, cos) points at the seat.
        float facing = (float) Math.toDegrees(Math.atan2(-toward.getX(), toward.getZ()));
        return new PotRenderer.Spot(at, facing, WINNINGS_ACROSS, WINNINGS_DEPTH);
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
