package com.unoplugin;

import com.unoplugin.bet.BetManager;
import com.unoplugin.command.GambleCommand;
import com.unoplugin.command.UnoCommand;
import com.unoplugin.debug.CardTester;
import com.unoplugin.game.GameManager;
import com.unoplugin.hand.HandManager;
import com.unoplugin.table.TableManager;
import com.unoplugin.util.Fx;
import com.unoplugin.util.Messages;
import com.unoplugin.util.NameCache;
import com.unoplugin.util.ResourcePackSender;
import com.unoplugin.util.Settings;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * UNO — main plugin entry point.
 *
 * <p>Builds the manager subsystems and wires them together with setter injection, because
 * the dependencies are circular: tables → games → bets, and bets listens back to games while
 * tables ask both whether they are busy.
 */
public final class UnoPlugin extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private Fx fx;
    private NameCache names;
    private TableManager tableManager;
    private CardTester cardTester;
    private HandManager handManager;
    private GameManager gameManager;
    private BetManager betManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(this);
        messages = new Messages(this);
        fx = new Fx(settings);

        PluginManager events = getServer().getPluginManager();

        names = new NameCache(this);
        events.registerEvents(names, this);

        tableManager = new TableManager(this, messages, settings, fx);
        events.registerEvents(tableManager, this);
        tableManager.load();

        cardTester = new CardTester(this);

        handManager = new HandManager(this, messages, fx);
        events.registerEvents(handManager, this);

        gameManager = new GameManager(this, handManager, messages, settings, names, fx);
        events.registerEvents(gameManager, this);
        handManager.setCardActions(gameManager);
        gameManager.setTableManager(tableManager);

        betManager = new BetManager(this, tableManager, gameManager, messages, settings, names, fx);
        events.registerEvents(betManager, this);
        gameManager.setGameListener(betManager);

        // A table with a hand or a pot on it must not be removable out from under them.
        tableManager.setBusyCheck(id ->
                gameManager.hasGameAtTable(id) || betManager.hasSessionAtTable(id));

        events.registerEvents(new ResourcePackSender(this, settings), this);

        registerCommand("uno", new UnoCommand(this, messages, settings, tableManager,
                gameManager, betManager, handManager, cardTester));
        registerCommand("gamble", new GambleCommand(betManager, messages));

        getLogger().info("UNO v" + getPluginMeta().getVersion() + " enabled.");
    }

    /** Bind one command to a handler that is both its executor and its tab completer. */
    private <T extends CommandExecutor & TabCompleter> void registerCommand(String name, T handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml.");
            return;
        }
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    @Override
    public void onDisable() {
        // Order matters: the bet layer settles (and logs) before the games and tables it
        // refers to are torn down.
        if (betManager != null) {
            betManager.shutdown();
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
}
