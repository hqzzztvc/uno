package com.legallynotuno.bet;

import com.legallynotuno.util.Messages;
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
 * Crash-safe custody for wagered items <em>and</em> money.
 *
 * <p>The invariant: <strong>at every instant the escrow files record who gets what back if
 * the server dies right now.</strong> A staked item leaves the player's inventory and staked
 * money leaves their balance, so neither may live only in RAM — a crash, a {@code kill -9} or
 * a power cut would eat it. Every stake, refund and payout is written to disk synchronously,
 * before anything else happens.
 *
 * <p>One file per owner ({@code plugins/LegallyNotUno/escrow/<uuid>.yml}) rather than one big file: a
 * single stake then costs one small write instead of re-serialising every item the server is
 * holding for everybody. The durability guarantee is unchanged — it is the write volume that
 * shrinks. A legacy {@code escrow.yml} is imported automatically on first run.
 *
 * <p>Anything owed to an offline player waits here and is handed over on their next join.
 * Money is the one thing that usually doesn't have to wait: Vault can pay an offline account,
 * so a payout only falls back to escrow when the deposit is actually refused.
 */
public final class EscrowStore {

    private final Plugin plugin;
    private final Messages messages;
    private final VaultEconomy economy;
    private final File dir;
    private final File legacyFile;

    /** owner -> what we are holding for them right now. */
    private final Map<UUID, Stake> held = new HashMap<>();

    public EscrowStore(Plugin plugin, Messages messages, VaultEconomy economy) {
        this.plugin = plugin;
        this.messages = messages;
        this.economy = economy;
        this.dir = new File(plugin.getDataFolder(), "escrow");
        this.legacyFile = new File(plugin.getDataFolder(), "escrow.yml");
        load();
    }

    /**
     * Record (or replace) everything we hold for one owner. An empty stake means we hold
     * nothing for them, which is how a refund is recorded once it is back in their hands.
     */
    public void hold(UUID owner, Stake stake) {
        if (stake == null || stake.isEmpty()) {
            held.remove(owner);
            deleteFile(owner);
        } else {
            held.put(owner, stake);
            writeFile(owner, stake);
        }
    }

    /** Forget what we hold for an owner <em>without</em> giving it back (payout moved it). */
    public void release(UUID owner) {
        if (held.remove(owner) != null) {
            deleteFile(owner);
        }
    }

    /**
     * Hand {@code stake} to a player: into their inventory and their balance if it can go
     * there now, and into escrow to wait for their next login if it can't.
     *
     * <p>The two halves are settled independently and whatever could not be delivered is
     * what stays on file. Handing over the diamonds and silently dropping the cash — or the
     * reverse — is the failure this shape exists to prevent.
     */
    public void payTo(UUID owner, Stake stake) {
        if (stake == null || stake.isEmpty()) {
            release(owner);
            return;
        }
        Player online = Bukkit.getPlayer(owner);
        List<ItemStack> undelivered = stake.items();
        if (online != null) {
            give(online, stake.items());
            undelivered = List.of();
        }
        double unpaid = stake.money();
        if (unpaid > 0 && economy.deposit(owner, unpaid)) {
            unpaid = 0;
        }
        hold(owner, new Stake(undelivered, unpaid));
    }

    /** Called on join: give the player anything we've been holding for them. */
    public void deliverPending(Player player) {
        UUID id = player.getUniqueId();
        Stake stake = held.get(id);
        if (stake == null || stake.isEmpty()) {
            return;
        }
        release(id);
        give(player, stake.items());
        // Money can be refused (no economy plugin now, or one that threw). Put exactly that
        // part back on file rather than dropping it — they get it on the join after next.
        boolean paid = !stake.hasMoney() || economy.deposit(id, stake.money());
        if (!paid) {
            hold(id, Stake.ofMoney(stake.money()));
        }
        if (stake.itemCount() > 0) {
            messages.send(player, "escrow.returned", "items", stake.itemCount());
        }
        if (stake.hasMoney() && paid) {
            messages.send(player, "escrow.returned-money", "money", economy.format(stake.money()));
        }
    }

    /** Put items in a player's hands now; overflow lands on the floor rather than vanishing. */
    public void give(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
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
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            Stake stake = new Stake(readItems(cfg, "items"), cfg.getDouble("money", 0.0));
            if (!stake.isEmpty()) {
                held.put(owner, stake);
            }
        }
        if (!held.isEmpty()) {
            plugin.getLogger().info("Escrow: holding stakes for " + held.size()
                    + " player(s) from a previous session — returning them on join.");
        }
    }

    /** One-time import of the old single-file format (items only — it predates money). */
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
                    writeFile(owner, new Stake(items, 0.0));
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

    private void writeFile(UUID owner, Stake stake) {
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().severe("Could not create " + dir + " — staked items NOT saved!");
            return;
        }
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("items", stake.items());
        if (stake.hasMoney()) {
            cfg.set("money", stake.money());
        }
        try {
            cfg.save(fileFor(owner));
        } catch (IOException ex) {
            // Loud on purpose: a silent failure here is how players lose diamonds.
            plugin.getLogger().severe("FAILED to write escrow for " + owner
                    + " — a staked wager is at risk: " + ex.getMessage());
        }
    }

    private void deleteFile(UUID owner) {
        File f = fileFor(owner);
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("Could not delete " + f + " — it may be handed back twice.");
        }
    }
}
