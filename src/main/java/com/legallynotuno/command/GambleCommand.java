package com.legallynotuno.command;

import com.legallynotuno.bet.BetManager;
import com.legallynotuno.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /gamble} — a thin front door onto {@link BetManager#command}. */
public final class GambleCommand implements CommandExecutor, TabCompleter {

    private final BetManager bets;
    private final Messages messages;

    public GambleCommand(BetManager bets, Messages messages) {
        this.bets = bets;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "common.players-only");
            return true;
        }
        bets.command(player, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String sub : BetManager.subcommands()) {
            if (sub.startsWith(typed)) {
                out.add(sub);
            }
        }
        return out;
    }
}
