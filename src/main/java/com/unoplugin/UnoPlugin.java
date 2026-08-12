package com.unoplugin;

import com.unoplugin.bet.BetManager;
import com.unoplugin.debug.CardTester;
import com.unoplugin.game.GameManager;
import com.unoplugin.hand.HandManager;
import com.unoplugin.table.TableManager;
import com.unoplugin.table.UnoTable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

/**
 * UNO — main plugin entry point.
 *
 * <p>Wires up the manager subsystems and the /uno command. Game/dealer/input/pack
 * managers are added in later build steps.
 */
public final class UnoPlugin extends JavaPlugin {

    /** Demo hand for /uno fan (7 cards; pass more args to test the even-split / compression). */
    private static final java.util.List<String> DEMO_HAND = java.util.List.of(
            "red_1", "yellow_5", "green_skip", "blue_9", "red_draw2", "wild", "green_3");

    private TableManager tableManager;
    private CardTester cardTester;
    private HandManager handManager;
    private GameManager gameManager;
    private BetManager betManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        tableManager = new TableManager(this);
        getServer().getPluginManager().registerEvents(tableManager, this);
        tableManager.load();

        cardTester = new CardTester(this);

        handManager = new HandManager(this);
        getServer().getPluginManager().registerEvents(handManager, this);

        gameManager = new GameManager(this, handManager);
        getServer().getPluginManager().registerEvents(gameManager, this);
        handManager.setCardActions(gameManager);
        gameManager.setTableManager(tableManager);

        betManager = new BetManager(this, tableManager, gameManager);
        getServer().getPluginManager().registerEvents(betManager, this);
        gameManager.setGameListener(betManager);

        getLogger().info("UNO v" + getPluginMeta().getVersion() + " enabled.");
        getLogger().info("Resource pack serve-mode: "
                + getConfig().getString("resource-pack.serve-mode", "embedded"));
    }

    @Override
    public void onDisable() {
        if (betManager != null) {
            betManager.shutdown(); // hand staked items back before the games tear down
        }
        if (gameManager != null) {
            gameManager.shutdown();
        }
        if (handManager != null) {
            handManager.shutdown();
        }
        if (tableManager != null) {
            tableManager.save();
            tableManager.shutdown();
        }
        getLogger().info("UNO disabled. The dealer is always here.");
    }

    public TableManager getTableManager() {
        return tableManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("gamble")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can gamble.");
                return true;
            }
            betManager.command(player, args);
            return true;
        }

        if (!command.getName().equalsIgnoreCase("uno")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage("§6UNO §7v" + getPluginMeta().getVersion()
                    + " §8| §7/uno <start|gamble|give|remove|reload|version>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "version" -> sender.sendMessage("§6UNO §7v" + getPluginMeta().getVersion());

            case "reload" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                reloadConfig();
                sender.sendMessage("§aUNO config reloaded.");
            }

            case "give" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can receive table items.");
                    return true;
                }
                UnoTable.Type type = args.length >= 2 ? parseType(args[1]) : UnoTable.Type.CASINO;
                if (type == null) {
                    player.sendMessage("§cUnknown table type. Use §etable§c.");
                    return true;
                }
                tableManager.giveTableItem(player, type);
                player.sendMessage("§aReceived a §6Casino Table §a— right-click the ground to place it.");
            }

            case "remove" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can remove tables.");
                    return true;
                }
                UnoTable removed = tableManager.removeNearest(player, 5.0);
                player.sendMessage(removed != null
                        ? "§aRemoved the nearest table."
                        : "§cNo table within 5 blocks.");
            }

            case "testcards" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can spawn the card gallery.");
                    return true;
                }
                java.util.List<String> names = args.length > 1
                        ? Arrays.asList(Arrays.copyOfRange(args, 1, args.length))
                        : null;
                int n = cardTester.spawn(player, names);
                player.sendMessage("§aSpawned §6" + n + " §atest cards. §7Use §e/uno cleartest §7to remove.");
            }

            case "hand" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can receive the hand item.");
                    return true;
                }
                cardTester.giveHand(player);
                player.sendMessage("§aGave you a §6UNO Hand §a— hold it in your main hand to see the fan.");
            }

            case "cleartest" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can clear test cards.");
                    return true;
                }
                int n = cardTester.clear(player);
                player.sendMessage("§aRemoved §6" + n + " §atest cards.");
            }

            case "fan" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can open a hand fan.");
                    return true;
                }
                java.util.List<String> cards = args.length > 1
                        ? Arrays.asList(Arrays.copyOfRange(args, 1, args.length))
                        : DEMO_HAND;
                handManager.show(player, cards);
                player.sendMessage("§aHand fan up (§6" + cards.size()
                        + " §acards). §7Press §eA/D §7to scroll, §e/uno fanclear §7to hide.");
            }

            case "play" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can start a game.");
                    return true;
                }
                int bots = 3;
                if (args.length > 1) {
                    try {
                        bots = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§eUsage: /uno play [bots]");
                        return true;
                    }
                }
                gameManager.startTest(player, bots);
            }

            case "start" -> {
                // Anyone (not just admins) can start a game with everyone seated at their table.
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can start a game.");
                    return true;
                }
                int extraBots = 0;
                if (args.length > 1) {
                    try {
                        extraBots = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§eUsage: /uno start [bots]  §7(sit at a table first)");
                        return true;
                    }
                }
                gameManager.startSeated(player, extraBots);
            }

            case "gamble", "bet", "letitride" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can gamble.");
                    return true;
                }
                betManager.command(player, Arrays.copyOfRange(args, 1, args.length));
            }

            case "fanclear" -> {
                if (notAdmin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can clear a hand fan.");
                    return true;
                }
                handManager.hide(player);
                player.sendMessage("§aHand fan hidden.");
            }

            default -> sender.sendMessage("§7Unknown subcommand. Use §e/uno <start|play|gamble|give|remove|reload|version|hand|fan|fanclear|testcards|cleartest>");
        }
        return true;
    }

    private boolean notAdmin(CommandSender sender) {
        if (!sender.hasPermission("uno.admin")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }
        return false;
    }

    private UnoTable.Type parseType(String raw) {
        if (raw.equalsIgnoreCase("table") || raw.equalsIgnoreCase("casino")) {
            return UnoTable.Type.CASINO;
        }
        return null;
    }
}
