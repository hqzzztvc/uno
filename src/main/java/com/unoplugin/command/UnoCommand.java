package com.unoplugin.command;

import com.unoplugin.UnoPlugin;
import com.unoplugin.bet.BetManager;
import com.unoplugin.bet.BetSession;
import com.unoplugin.debug.CardTester;
import com.unoplugin.game.GameManager;
import com.unoplugin.hand.HandManager;
import com.unoplugin.table.TableManager;
import com.unoplugin.table.UnoTable;
import com.unoplugin.util.Messages;
import com.unoplugin.util.Settings;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /uno} — routing, permissions and tab completion, one method per subcommand.
 *
 * <p>Admin subcommands are hidden from tab completion (and from the help listing) for players
 * without {@code uno.admin}, and the debug ones only appear when {@code debug: true}.
 */
public final class UnoCommand implements CommandExecutor, TabCompleter {

    /** Demo hand for /uno fan (pass more args to test the even-split / compression). */
    private static final List<String> DEMO_HAND = List.of(
            "red_1", "yellow_5", "green_skip", "blue_9", "red_draw2", "wild", "green_3");

    private static final List<String> PUBLIC_SUBS = List.of("help", "version", "start", "gamble");
    private static final List<String> ADMIN_SUBS = List.of(
            "give", "remove", "list", "info", "tp", "end", "refund", "reload", "play");
    private static final List<String> DEBUG_SUBS = List.of(
            "fan", "fanclear", "testcards", "hand", "cleartest");

    private final UnoPlugin plugin;
    private final Messages messages;
    private final Settings settings;
    private final TableManager tables;
    private final GameManager games;
    private final BetManager bets;
    private final HandManager hands;
    private final CardTester tester;

    public UnoCommand(UnoPlugin plugin, Messages messages, Settings settings, TableManager tables,
                      GameManager games, BetManager bets, HandManager hands, CardTester tester) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.tables = tables;
        this.games = games;
        this.bets = bets;
        this.hands = hands;
        this.tester = tester;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "plugin.header", "version", plugin.getPluginMeta().getVersion());
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "version" -> messages.send(sender, "plugin.version",
                    "version", plugin.getPluginMeta().getVersion());
            case "start" -> start(sender, rest);
            case "gamble", "bet", "letitride" -> gamble(sender, rest);

            case "reload" -> reload(sender);
            case "give" -> give(sender, rest);
            case "remove" -> remove(sender);
            case "list" -> list(sender);
            case "info" -> info(sender);
            case "tp" -> teleport(sender, rest);
            case "end" -> end(sender, rest);
            case "refund" -> refund(sender, rest);
            case "play" -> play(sender, rest);

            case "fan" -> fan(sender, rest);
            case "fanclear" -> fanClear(sender);
            case "testcards" -> testCards(sender, rest);
            case "hand" -> handItem(sender);
            case "cleartest" -> clearTest(sender);

            default -> messages.send(sender, "common.unknown-subcommand", "command", label);
        }
        return true;
    }

    // ------------------------------------------------------------------ public

    private void help(CommandSender sender) {
        messages.send(sender, "plugin.help-header");
        messages.send(sender, "plugin.help-play");
        messages.send(sender, "plugin.help-gamble");
        messages.send(sender, "plugin.help-version");
        if (!sender.hasPermission("uno.admin")) {
            return;
        }
        messages.send(sender, "plugin.help-admin-header");
        for (String key : new String[]{"plugin.help-give", "plugin.help-remove", "plugin.help-list",
                "plugin.help-info", "plugin.help-tp", "plugin.help-end", "plugin.help-refund",
                "plugin.help-reload"}) {
            messages.send(sender, key);
        }
    }

    /** Anyone may deal a hand to everyone seated at their table. */
    private void start(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Integer bots = optionalInt(sender, args, 0);
        if (bots == null && args.length > 0) {
            return;
        }
        games.startSeated(player, bots == null ? 0 : bots);
    }

    private void gamble(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player != null) {
            bets.command(player, args);
        }
    }

    // ------------------------------------------------------------------- admin

    private void reload(CommandSender sender) {
        if (notAdmin(sender)) {
            return;
        }
        settings.reload();
        messages.reload();
        messages.send(sender, "plugin.reloaded");
    }

    private void give(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        UnoTable.Type type = args.length >= 1 ? parseType(args[0]) : UnoTable.Type.CASINO;
        if (type == null) {
            messages.send(player, "table.unknown-type");
            return;
        }
        tables.giveTableItem(player, type);
        messages.send(player, "table.given");
    }

    /** Removing a table with a hand or a pot running on it would strand both. */
    private void remove(CommandSender sender) {
        if (notAdmin(sender)) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        UnoTable nearest = tables.nearest(player, 5.0);
        if (nearest == null) {
            messages.send(player, "table.none-near", "radius", 5);
            return;
        }
        if (tables.isBusy(nearest.id())) {
            messages.send(player, "table.busy");
            return;
        }
        tables.remove(nearest);
        messages.send(player, "table.removed");
    }

    private void list(CommandSender sender) {
        if (notAdmin(sender)) {
            return;
        }
        var all = tables.tables();
        var waiting = tables.pendingSummaries();
        if (all.isEmpty() && waiting.isEmpty()) {
            messages.send(sender, "table.list-empty");
            return;
        }
        messages.send(sender, "table.list-header", "count", all.size() + waiting.size());
        for (UnoTable t : all) {
            var a = t.anchor();
            messages.send(sender, "table.list-entry",
                    "id", shortId(t.id()),
                    "world", a.getWorld() == null ? "?" : a.getWorld().getName(),
                    "x", Math.round(a.getX()),
                    "y", Math.round(a.getY()),
                    "z", Math.round(a.getZ()),
                    "seated", t.occupiedCount(),
                    "seats", t.seatCount());
        }
        for (String[] p : waiting) {
            messages.send(sender, "table.pending-world",
                    "id", p[0].substring(0, Math.min(8, p[0].length())), "world", p[1]);
        }
    }

    private void info(CommandSender sender) {
        if (notAdmin(sender)) {
            return;
        }
        var gameIds = games.gameIds();
        var potTables = bets.sessionTables();
        messages.send(sender, "game.info-header", "games", gameIds.size(), "bets", potTables.size());
        if (gameIds.isEmpty() && potTables.isEmpty()) {
            messages.send(sender, "game.info-none");
            return;
        }
        for (UUID gid : gameIds) {
            UUID tableId = games.tableOfGame(gid);
            UUID turn = games.currentTurnOf(gid);
            messages.send(sender, "game.info-game",
                    "table", tableId == null ? "no table" : shortId(tableId),
                    "players", names(games.playersOf(gid)),
                    "turn", turn == null ? "-" : games.displayName(turn));
        }
        for (UUID tableId : potTables) {
            BetSession s = bets.session(tableId);
            if (s == null) {
                continue;
            }
            messages.send(sender, "game.info-bet",
                    "table", shortId(tableId),
                    "state", s.state().name().toLowerCase(Locale.ROOT),
                    "items", s.potSize(),
                    "stakers", s.stakers().size());
        }
    }

    private void teleport(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            messages.send(sender, "plugin.help-tp");
            return;
        }
        UnoTable table = tables.findByPrefix(args[0]);
        if (table == null) {
            messages.send(sender, "table.not-found", "id", args[0]);
            return;
        }
        player.teleport(table.anchor().add(0, 1, 0));
        messages.send(sender, "table.teleported", "id", shortId(table.id()));
    }

    /** Force a stuck hand to finish. It ends with no winner, so any pot is refunded. */
    private void end(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return;
        }
        if (args.length == 0) {
            messages.send(sender, "plugin.help-end");
            return;
        }
        if (args[0].equalsIgnoreCase("all")) {
            int n = games.forceEndAll();
            messages.send(sender, n > 0 ? "game.ended" : "game.end-none", "count", n);
            return;
        }
        UUID target = resolvePlayer(args[0]);
        UUID gameId = target == null ? null : games.gameIdOf(target);
        if (gameId == null || !games.forceEnd(gameId)) {
            messages.send(sender, "game.end-none");
            return;
        }
        messages.send(sender, "game.ended", "count", 1);
    }

    /** Hand a stuck pot back to whoever staked it. */
    private void refund(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return;
        }
        if (args.length == 0) {
            messages.send(sender, "plugin.help-refund");
            return;
        }
        if (args[0].equalsIgnoreCase("all")) {
            int n = bets.forceRefundAll();
            messages.send(sender, n > 0 ? "bet.refunded-admin" : "bet.refund-none", "count", n);
            return;
        }
        UUID target = resolvePlayer(args[0]);
        UUID tableId = target == null ? null : bets.tableOfPlayer(target);
        if (tableId == null || !bets.forceRefund(tableId)) {
            messages.send(sender, "bet.refund-none");
            return;
        }
        messages.send(sender, "bet.refunded-admin", "count", 1);
    }

    /** Solo game against bots — useful on a test server, harmless on a live one. */
    private void play(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Integer bots = optionalInt(sender, args, 0);
        if (bots == null && args.length > 0) {
            return;
        }
        games.startTest(player, bots == null ? 3 : bots);
    }

    // ------------------------------------------------------------------- debug

    private void fan(CommandSender sender, String[] args) {
        Player player = debugPlayer(sender);
        if (player == null) {
            return;
        }
        List<String> cards = args.length > 0 ? Arrays.asList(args) : DEMO_HAND;
        hands.show(player, cards);
    }

    private void fanClear(CommandSender sender) {
        Player player = debugPlayer(sender);
        if (player != null) {
            hands.hide(player);
        }
    }

    private void testCards(CommandSender sender, String[] args) {
        Player player = debugPlayer(sender);
        if (player != null) {
            tester.spawn(player, args.length > 0 ? Arrays.asList(args) : null);
        }
    }

    private void handItem(CommandSender sender) {
        Player player = debugPlayer(sender);
        if (player != null) {
            tester.giveHand(player);
        }
    }

    private void clearTest(CommandSender sender) {
        Player player = debugPlayer(sender);
        if (player != null) {
            tester.clear(player);
        }
    }

    // ----------------------------------------------------------------- helpers

    private boolean notAdmin(CommandSender sender) {
        if (!sender.hasPermission("uno.admin")) {
            messages.send(sender, "common.no-permission");
            return true;
        }
        return false;
    }

    private Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "common.players-only");
        return null;
    }

    /** Admin + the debug flag, or nothing happens. */
    private Player debugPlayer(CommandSender sender) {
        if (notAdmin(sender)) {
            return null;
        }
        if (!settings.debug()) {
            messages.send(sender, "common.debug-disabled");
            return null;
        }
        return asPlayer(sender);
    }

    /** Parses args[index] as an int, or null. Complains only if the argument was present. */
    private Integer optionalInt(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            return null;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException e) {
            messages.send(sender, "common.number-expected", "value", args[index]);
            return null;
        }
    }

    private UnoTable.Type parseType(String raw) {
        if (raw.equalsIgnoreCase("table") || raw.equalsIgnoreCase("casino")) {
            return UnoTable.Type.CASINO;
        }
        return null;
    }

    /** Online player first; otherwise anyone the server has already seen. */
    private UUID resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        var offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline == null ? null : offline.getUniqueId();
    }

    private String names(List<UUID> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            out.add(games.displayName(id));
        }
        return String.join(", ", out);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(PUBLIC_SUBS);
            if (sender.hasPermission("uno.admin")) {
                options.addAll(ADMIN_SUBS);
                if (settings.debug()) {
                    options.addAll(DEBUG_SUBS);
                }
            }
            return prefixed(options, args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> {
                    return prefixed(List.of("table"), args[1]);
                }
                case "gamble", "bet", "letitride" -> {
                    return prefixed(BetManager.subcommands(), args[1]);
                }
                case "tp" -> {
                    if (!sender.hasPermission("uno.admin")) {
                        return List.of();
                    }
                    List<String> ids = new ArrayList<>();
                    for (UnoTable t : tables.tables()) {
                        ids.add(shortId(t.id()));
                    }
                    return prefixed(ids, args[1]);
                }
                case "end", "refund" -> {
                    if (!sender.hasPermission("uno.admin")) {
                        return List.of();
                    }
                    List<String> options = new ArrayList<>();
                    options.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        options.add(p.getName());
                    }
                    return prefixed(options, args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        Collections.sort(out);
        return out;
    }
}
