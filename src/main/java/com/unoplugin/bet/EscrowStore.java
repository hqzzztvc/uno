package com.unoplugin.bet;

import com.unoplugin.util.Messages;
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
 * <p>The invariant: <strong>at every instant the escrow files record who gets what back if
 * the server dies right now.</strong> A staked item leaves the player's inventory, so it must
 * never live only in RAM — a crash, a {@code kill -9} or a power cut would eat it. Every
 * stake, refund and payout is written to disk synchronously, before anything else happens.
 *
 * <p>One file per owner ({@code plugins/UNO/escrow/<uuid>.yml}) rather than one big file: a
 * single stake then costs one small write instead of re-serialising every item the server is
 * holding for everybody. The durability guarantee is unchanged — it is the write volume that
 * shrinks. A legacy {@code escrow.yml} is imported automatically on first run.
 *
 * <p>Anything owed to an offline player waits here and is handed over on their next join.
 */
public final class EscrowStore {

    private final Plugin plugin;
    private final Messages messages;
    private final File dir;
    private final File legacyFile;

    /** owner -> the items we are holding for them right now. */
    private final Map<UUID, List<ItemStack>> held = new HashMap<>();

    public EscrowStore(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.dir = new File(plugin.getDataFolder(), "escrow");
        this.legacyFile = new File(plugin.getDataFolder(), "escrow.yml");
        load();
    }

    /**
     * Record (or replace) everything we hold for one owner. An empty list means we hold
     * nothing for them, which is how a refund is recorded once the items are back in hand.
     */
    public void hold(UUID owner, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            held.remove(owner);
            deleteFile(owner);
        } else {
            held.put(owner, copy(items));
            writeFile(owner, held.get(owner));
        }
    }

    /** Forget what we hold for an owner <em>without</em> giving it back (payout moved it). */
    public void release(UUID owner) {
        if (held.remove(owner) != null) {
            deleteFile(owner);
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
        messages.send(player, "escrow.returned", "items", count(items));
    }

    /** Put items in a player's hands now; overflow lands on the floor rather than vanishing. */
    public void give(Player player, List<ItemStack> items) {
        ItemStack[] arr = copy(items).toArray(new ItemStack[0]);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(arr);
        if (leftover.isEmpty()) {
            return;
        }
        Location at = player.getLocation();
        for (ItemStack overflow : leftover.values()) {
            at.getWorld().dropItemNaturally(at, overflow);
        }
        messages.send(player, "escrow.inventory-full");
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

    private File fileFor(UUID owner) {
        return new File(dir, owner + ".yml");
    }

    private void load() {
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().severe("Could not create " + dir + " — escrow is NOT crash-safe!");
        }
        importLegacyFile();

        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String key = f.getName().substring(0, f.getName().length() - 4);
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("escrow: skipping file with a bad owner id '" + f.getName() + "'");
                continue;
            }
            List<ItemStack> items = readItems(YamlConfiguration.loadConfiguration(f), "items");
            if (!items.isEmpty()) {
                held.put(owner, items);
            }
        }
        if (!held.isEmpty()) {
            plugin.getLogger().info("Escrow: holding items for " + held.size()
                    + " player(s) from a previous session — returning them on join.");
        }
    }

    /** One-time import of the old single-file format. */
    private void importLegacyFile() {
        if (!legacyFile.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacyFile);
        ConfigurationSection sec = cfg.getConfigurationSection("held");
        int moved = 0;
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                UUID owner;
                try {
                    owner = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("escrow.yml: skipping bad owner id '" + key + "'");
                    continue;
                }
                List<ItemStack> items = readItems(sec, key);
                if (!items.isEmpty()) {
                    writeFile(owner, items);
                    moved++;
                }
            }
        }
        File done = new File(plugin.getDataFolder(), "escrow.yml.imported");
        if (legacyFile.renameTo(done)) {
            plugin.getLogger().info("Escrow: imported " + moved + " holding(s) from escrow.yml"
                    + " into escrow/ (old file kept as escrow.yml.imported).");
        } else {
            plugin.getLogger().warning("Escrow: imported " + moved + " holding(s) from escrow.yml but"
                    + " could not rename it — delete it by hand or it will be imported again.");
        }
    }

    private static List<ItemStack> readItems(ConfigurationSection section, String path) {
        List<ItemStack> items = new ArrayList<>();
        List<?> raw = section.getList(path);
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ItemStack stack) {
                    items.add(stack);
                }
            }
        }
        return items;
    }

    private void writeFile(UUID owner, List<ItemStack> items) {
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().severe("Could not create " + dir + " — staked items NOT saved!");
            return;
        }
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("items", items);
        try {
            cfg.save(fileFor(owner));
        } catch (IOException ex) {
            // Loud on purpose: a silent failure here is how players lose diamonds.
            plugin.getLogger().severe("FAILED to write escrow for " + owner
                    + " — staked items are at risk: " + ex.getMessage());
        }
    }

    private void deleteFile(UUID owner) {
        File f = fileFor(owner);
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("Could not delete " + f + " — it may be handed back twice.");
        }
    }
}
