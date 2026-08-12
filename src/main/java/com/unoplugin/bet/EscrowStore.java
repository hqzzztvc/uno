package com.unoplugin.bet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crash-safe custody for wagered items.
 *
 * <p>The invariant: <strong>at every instant {@code escrow.yml} records who gets what back
 * if the server dies right now.</strong> A staked item leaves the player's inventory, so it
 * must never live only in RAM — a crash, a {@code kill -9} or a power cut would eat it.
 * Every stake, refund and payout writes the file synchronously; items are precious and
 * betting is nowhere near hot enough for that to matter.
 *
 * <p>Anything owed to an offline player waits here and is handed over on their next join.
 */
public final class EscrowStore {

    private final Plugin plugin;
    private final File file;

    /** owner -> the items we are holding for them right now. */
    private final Map<UUID, List<ItemStack>> held = new HashMap<>();

    public EscrowStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "escrow.yml");
        load();
    }

    /**
     * Record (or replace) everything we hold for one owner. An empty list means we hold
     * nothing for them, which is how a refund is recorded once the items are back in hand.
     */
    public void hold(UUID owner, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            held.remove(owner);
        } else {
            held.put(owner, copy(items));
        }
        save();
    }

    /** Forget what we hold for an owner <em>without</em> giving it back (payout moved it). */
    public void release(UUID owner) {
        if (held.remove(owner) != null) {
            save();
        }
    }

    /**
     * Hand {@code items} to a player: straight into their inventory if they're online,
     * otherwise into escrow to wait for their next login.
     */
    public void payTo(UUID owner, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            release(owner);
            return;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            release(owner);
            give(online, items);
        } else {
            hold(owner, items);
        }
    }

    /** Called on join: give the player anything we've been holding for them. */
    public void deliverPending(Player player) {
        List<ItemStack> items = held.get(player.getUniqueId());
        if (items == null || items.isEmpty()) {
            return;
        }
        release(player.getUniqueId());
        give(player, items);
        player.sendMessage("§6§lUNO §r§7— returned §e" + count(items)
                + " §7item(s) held from an unfinished bet.");
    }

    /** True if the last shutdown left stakes on the books (they're refunded on load). */
    public boolean hasPending() {
        return !held.isEmpty();
    }

    /** Put items in a player's hands now; overflow lands on the floor rather than vanishing. */
    public static void give(Player player, List<ItemStack> items) {
        ItemStack[] arr = copy(items).toArray(new ItemStack[0]);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(arr);
        if (leftover.isEmpty()) {
            return;
        }
        Location at = player.getLocation();
        for (ItemStack overflow : leftover.values()) {
            at.getWorld().dropItemNaturally(at, overflow);
        }
        player.sendMessage("§7Your inventory was full — the rest dropped at your feet.");
    }

    /** Total item count across stacks (8 diamonds + 3 emeralds = 11). */
    public static int count(List<ItemStack> items) {
        int n = 0;
        for (ItemStack s : items) {
            if (s != null) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private static List<ItemStack> copy(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>(items.size());
        for (ItemStack s : items) {
            if (s != null && !s.getType().isAir()) {
                out.add(s.clone());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("held");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("escrow.yml: skipping bad owner id '" + key + "'");
                continue;
            }
            List<ItemStack> items = new ArrayList<>();
            List<?> raw = sec.getList(key);
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof ItemStack stack) {
                        items.add(stack);
                    }
                }
            }
            if (!items.isEmpty()) {
                held.put(owner, items);
            }
        }
        if (!held.isEmpty()) {
            plugin.getLogger().info("Escrow: holding items for " + held.size()
                    + " player(s) from a previous session — returning them on join.");
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> e : held.entrySet()) {
            cfg.set("held." + e.getKey(), e.getValue());
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().severe("Could not create the plugin data folder — escrow NOT saved!");
                return;
            }
            cfg.save(file);
        } catch (IOException ex) {
            // Loud on purpose: a silent failure here is how players lose diamonds.
            plugin.getLogger().severe("FAILED to write escrow.yml — staked items are at risk: " + ex.getMessage());
        }
    }
}
