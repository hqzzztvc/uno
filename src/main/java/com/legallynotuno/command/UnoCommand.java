package com.legallynotuno.command;

import com.legallynotuno.LegallyNotUno;
import com.legallynotuno.bet.BetManager;
import com.legallynotuno.bet.BetSession;
import com.legallynotuno.debug.CardTester;
import com.legallynotuno.game.GameManager;
import com.legallynotuno.hand.HandManager;
import com.legallynotuno.table.TableManager;
import com.legallynotuno.table.TableTheme;
import com.legallynotuno.table.ThemeEditor;
import com.legallynotuno.table.UnoTable;
import com.legallynotuno.util.Messages;
import com.legallynotuno.util.Settings;
import com.legallynotuno.util.UpdateChecker;
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
 * without {@code legallynotuno.admin}, and the debug ones only appear when {@code debug: true}.
 */
public final class UnoCommand implements CommandExecutor, TabCompleter {

    /** Demo hand for /uno fan (pass more args to test the even-split / compression). */
    private static final List<String> DEMO_HAND = List.of(
            "red_1", "yellow_5", "green_skip", "blue_9", "red_draw2", "wild", "green_3");

    private static final List<String> PUBLIC_SUBS = List.of(
            "help", "version", "join", "leave", "ready", "bet", "start", "quit", "stop",
            "gamble", "uno", "callout");
    private static final List<String> ADMIN_SUBS = List.of(
            "give", "theme", "remove", "list", "info", "tp", "end", "refund", "reload", "play");
    private static final List<String> DEBUG_SUBS = List.of(
            "fan", "fanclear", "testcards", "hand", "cleartest");

    /** {@code /uno theme ...} verbs. */
    private static final List<String> THEME_SUBS = List.of("list", "create", "edit", "delete");

    private final LegallyNotUno plugin;
    private final Messages messages;
    private final Settings settings;
    private final TableManager tables;
    private final GameManager games;
    private final BetManager bets;
    private final HandManager hands;
    private final CardTester tester;
    private final ThemeEditor themeEditor;
    private final UpdateChecker updates;

    public UnoCommand(LegallyNotUno plugin, Messages messages, Settings settings, TableManager tables,
                      GameManager games, BetManager bets, HandManager hands, CardTester tester,
                      ThemeEditor themeEditor, UpdateChecker updates) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.tables = tables;
        this.games = games;
        this.bets = bets;
        this.hands = hands;
        this.tester = tester;
        this.themeEditor = themeEditor;
        this.updates = updates;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Bare /uno is the help listing. A one-line banner told a player the plugin was
            // there and nothing about how to use it, which is the one thing they needed.
            messages.send(sender, "plugin.header", "version", plugin.getPluginMeta().getVersion());
            help(sender);
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "version" -> version(sender);
            case "join", "sit" -> join(sender);
            case "leave", "stand" -> leave(sender);
            case "ready" -> ready(sender);
            case "start" -> start(sender, rest);
            case "quit", "forfeit" -> quit(sender);
            case "stop" -> stop(sender);
            case "gamble", "bet", "letitride" -> gamble(sender, rest);
            case "uno" -> callUno(sender);
            case "callout" -> callOut(sender, rest);
            case "challenge" -> respondToDraw4(sender, true);
            case "takeit" -> respondToDraw4(sender, false);
            case "colour", "color" -> chooseColour(sender, rest);

            case "reload" -> reload(sender);
            case "give" -> give(sender, rest);
            case "theme" -> theme(sender, rest);
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

    /** The version, and for an admin whether it is the newest one on Modrinth. */
    private void version(CommandSender sender) {
        messages.send(sender, "plugin.version", "version", plugin.getPluginMeta().getVersion());
        if (sender.hasPermission("legallynotuno.admin")) {
            updates.tell(sender);
        }
    }

    private void help(CommandSender sender) {
        messages.send(sender, "plugin.help-header");
        messages.send(sender, "plugin.help-join");
        messages.send(sender, "plugin.help-ready");
        messages.send(sender, "plugin.help-bet");
        messages.send(sender, "plugin.help-leave");
        messages.send(sender, "plugin.help-play");
        messages.send(sender, "plugin.help-quit");
        messages.send(sender, "plugin.help-stop");
        messages.send(sender, "plugin.help-uno");
        messages.send(sender, "plugin.help-callout");
        messages.send(sender, "plugin.help-gamble");
        messages.send(sender, "plugin.help-version");
        if (!sender.hasPermission("legallynotuno.admin")) {
            return;
        }
        messages.send(sender, "plugin.help-admin-header");
        for (String key : new String[]{"plugin.help-give", "plugin.help-theme", "plugin.help-remove",
                "plugin.help-list", "plugin.help-info", "plugin.help-tp", "plugin.help-end",
                "plugin.help-refund", "plugin.help-reload"}) {
            messages.send(sender, key);
        }
    }

    /** Stand next to a table and take the free seat nearest you. */
    private void join(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player != null) {
            tables.joinNearest(player);
        }
    }

    /** Get up. Dismounting the seat by hand (shift) does the same thing. */
    private void leave(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player != null) {
            tables.leaveNearest(player);
        }
    }

    /**
     * {@code /uno ready} — one word that means "I'm in", whichever kind of hand this is.
     *
     * <p>The routing lives here rather than in either manager because the command layer is
     * the one place that already holds both, and because it is genuinely a routing question:
     * at a table with a pot open, being ready means your stake is locked in and there is no
     * such thing as being casually ready alongside it. One button on the prompt, one command
     * to learn, and the table decides what it means.
     */
    private void ready(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        UnoTable table = tables.seatedTable(player.getUniqueId());
        if (table != null && bets.hasSessionAtTable(table.id())) {
            bets.command(player, new String[]{"ready"});
            return;
        }
        games.ready(player);
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

    /**
     * Drop out of the hand you're in; everyone else plays on.
     *
     * <p>Costs exactly what disconnecting costs — including leaving a wagered stake in the pot.
     */
    private void quit(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!games.forfeit(player)) {
            messages.send(player, "game.not-in-game");
        }
    }

    /** End the hand at your table for everyone. Refused while a pot is riding on it. */
    private void stop(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        switch (games.stopGameOf(player)) {
            case OK -> messages.send(player, "game.stopped");
            case NOT_IN_GAME -> messages.send(player, "game.not-in-game");
            case WAGERED -> messages.send(player, "game.stop-wagered");
        }
    }

    private void gamble(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player != null) {
            bets.command(player, args);
        }
    }

    /** Call UNO on yourself. Usually reached by clicking the prompt rather than typing. */
    private void callUno(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player != null) {
            games.callUno(player);
        }
    }

    /** Catch somebody sitting on one card who never called it. */
    private void callOut(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            messages.send(player, "plugin.help-callout");
            return;
        }
        games.callOut(player, args[0]);
    }

    /** Answer a Wild +4 aimed at you: challenge the bluff, or just take the four. */
    private void respondToDraw4(CommandSender sender, boolean challenge) {
        Player player = asPlayer(sender);
        if (player != null) {
            games.respondToDraw4(player, challenge);
        }
    }

    /** The colour buttons a wild puts in chat run this. */
    private void chooseColour(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player != null) {
            games.chooseColor(player, args.length == 0 ? null : args[0]);
        }
    }

    // ------------------------------------------------------------------- admin

    private void reload(CommandSender sender) {
        if (notAdmin(sender)) {
            return;
        }
        // A file with a mistake in it is reported and left alone; the server keeps running on
        // what that file said the last time it loaded, never on a silent reset to defaults.
        String configError = settings.reload();
        String messagesError = messages.reload();
        String themesError = tables.reloadThemes();
        boolean clean = true;
        for (String[] failed : new String[][]{
                {"config.yml", configError}, {"messages.yml", messagesError},
                {"themes.yml", themesError}}) {
            if (failed[1] != null) {
                clean = false;
                messages.send(sender, "plugin.reload-failed", "file", failed[0], "error", failed[1]);
            }
        }
        if (clean) {
            messages.send(sender, "plugin.reloaded");
        }
    }

    /**
     * {@code /uno give <theme>} — hand over a placeable table item.
     *
     * <p>The kind argument is gone along with the casual/casino split. Every table plays
     * both, so the only question left about a table you are about to place is what it looks
     * like.
     */
    private void give(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        String wanted = args.length > 0 ? args[0] : null;
        if (wanted == null) {
            messages.send(player, "table.pick-variant", "variants", tables.themes().idList());
            return;
        }
        TableTheme theme = tables.themes().get(wanted);
        if (theme == null) {
            messages.send(player, "table.unknown-type", "variants", tables.themes().idList());
            return;
        }
        tables.giveTableItem(player, theme);
        messages.send(player, "table.given", "variant", theme.displayName());
    }

    /** {@code /uno theme <list|create|edit|delete>} — manage the themes tables are built from. */
    private void theme(CommandSender sender, String[] args) {
        if (notAdmin(sender)) {
            return;
        }
        if (args.length == 0) {
            messages.send(sender, "plugin.help-theme");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                messages.send(sender, "theme.list-header", "count", tables.themes().all().size());
                for (TableTheme t : tables.themes().all()) {
                    messages.send(sender, "theme.list-entry", "id", t.id(), "name", t.displayName(),
                            "kind", t.builtIn() ? "built-in" : "custom");
                }
            }
            case "create", "edit" -> {
                Player player = asPlayer(sender);
                if (player == null) {
                    return;
                }
                if (args.length < 2) {
                    messages.send(sender, "theme.need-id");
                    return;
                }
                themeEditor.open(player, args[1]);
            }
            case "delete" -> {
                if (args.length < 2) {
                    messages.send(sender, "theme.need-id");
                    return;
                }
                if (tables.themes().delete(args[1])) {
                    messages.send(sender, "theme.deleted", "theme", args[1]);
                } else {
                    messages.send(sender, "theme.undeletable", "theme", args[1]);
                }
            }
            default -> messages.send(sender, "plugin.help-theme");
        }
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
        if (!sender.hasPermission("legallynotuno.admin")) {
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
            if (sender.hasPermission("legallynotuno.admin")) {
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
                    if (!sender.hasPermission("legallynotuno.admin")) {
                        return List.of();
                    }
                    return prefixed(tables.themes().ids(), args[1]);
                }
                case "theme" -> {
                    if (!sender.hasPermission("legallynotuno.admin")) {
                        return List.of();
                    }
                    return prefixed(THEME_SUBS, args[1]);
                }
                case "gamble", "bet", "letitride" -> {
                    return prefixed(BetManager.subcommands(), args[1]);
                }
                case "callout" -> {
                    // Only the people actually in your hand can be called out, so offering
                    // the whole server would be noise that never completes to anything valid.
                    if (!(sender instanceof Player p)) {
                        return List.of();
                    }
                    return prefixed(games.opponentNames(p.getUniqueId()), args[1]);
                }
                case "tp" -> {
                    if (!sender.hasPermission("legallynotuno.admin")) {
                        return List.of();
                    }
                    List<String> ids = new ArrayList<>();
                    for (UnoTable t : tables.tables()) {
                        ids.add(shortId(t.id()));
                    }
                    return prefixed(ids, args[1]);
                }
                case "end", "refund" -> {
                    if (!sender.hasPermission("legallynotuno.admin")) {
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
        if (args.length == 3 && sender.hasPermission("legallynotuno.admin")) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("theme") && (args[1].equalsIgnoreCase("edit")
                    || args[1].equalsIgnoreCase("delete"))) {
                return prefixed(tables.themes().ids(), args[2]);
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
