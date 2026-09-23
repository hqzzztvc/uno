package com.legallynotuno;

import com.legallynotuno.bet.BetManager;
import com.legallynotuno.command.GambleCommand;
import com.legallynotuno.command.UnoCommand;
import com.legallynotuno.debug.CardTester;
import com.legallynotuno.game.GameManager;
import com.legallynotuno.hand.HandManager;
import com.legallynotuno.table.TableManager;
import com.legallynotuno.table.ThemeEditor;
import com.legallynotuno.util.Fx;
import com.legallynotuno.util.Messages;
import com.legallynotuno.util.NameCache;
import com.legallynotuno.util.ResourcePackSender;
import com.legallynotuno.util.Settings;
import com.legallynotuno.util.UpdateChecker;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Legally Not Uno — main plugin entry point.
 *
 * <p>Builds the manager subsystems and wires them together with setter injection, because
 * the dependencies are circular: tables → games → bets, and bets listens back to games while
 * tables ask both whether they are busy.
 */
public final class LegallyNotUno extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private Fx fx;
    private NameCache names;
    private TableManager tableManager;
    private ThemeEditor themeEditor;
    private CardTester cardTester;
    private HandManager handManager;
    private GameManager gameManager;
    private BetManager betManager;
    private UpdateChecker updates;

    @Override
    public void onEnable() {
        // Both install their file on first run and bring an older one up to date; see ShippedYaml.
        settings = new Settings(this);
        messages = new Messages(this);
        fx = new Fx(settings);

        PluginManager events = getServer().getPluginManager();

        names = new NameCache(this);
        events.registerEvents(names, this);

        tableManager = new TableManager(this, messages, settings, fx);
        events.registerEvents(tableManager, this);
        tableManager.load();

        themeEditor = new ThemeEditor(this, messages, tableManager.themes(),
                tableManager.customBlocks());
        events.registerEvents(themeEditor, this);

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
        // Getting up from the table settles BOTH layers, because "leaving the table" means
        // different things depending on how far along the table is: mid-hand it is a forfeit,
        // and during an ante — when there is no hand for the game layer to forfeit — it is a
        // withdrawal. Wiring only the game layer left a stake, a pot and a mat sitting on a
        // table nobody was at.
        tableManager.setStandUpHook((player, tableId) -> {
            boolean droppedFromHand = gameManager.onStandUp(player, tableId);
            betManager.onStandUp(player, tableId);
            return droppedFromHand;
        });

        events.registerEvents(new ResourcePackSender(this, settings), this);

        updates = new UpdateChecker(this, settings, messages);
        events.registerEvents(updates, this);
        updates.start();

        registerCommand("uno", new UnoCommand(this, messages, settings, tableManager,
                gameManager, betManager, handManager, cardTester, themeEditor, updates));
        registerCommand("gamble", new GambleCommand(betManager, messages));

        getLogger().info("Legally Not Uno v" + getPluginMeta().getVersion() + " enabled.");
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
        if (cardTester != null) {
            cardTester.shutdown();
        }
        if (themeEditor != null) {
            themeEditor.shutdown();
        }
        if (tableManager != null) {
            tableManager.save();
            tableManager.shutdown();
        }
        if (updates != null) {
            updates.shutdown();
        }
        getLogger().info("Legally Not Uno disabled. The dealer is always here.");
    }

    public TableManager getTableManager() {
        return tableManager;
    }
}
