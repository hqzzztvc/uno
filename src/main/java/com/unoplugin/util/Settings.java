package com.unoplugin.util;

import com.unoplugin.table.UnoTable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed view over config.yml.
 *
 * <p>One place that reads the file, so "is this key actually wired up?" is answerable by
 * reading a single class. Everything here is re-read by {@link #reload()} on /uno reload.
 */
public final class Settings {

    private final Plugin plugin;

    // gameplay
    private int startingHandSize;
    private int turnTimeoutSeconds;
    private int turnWarningSeconds;

    // tables
    private int maxTablesPerWorld;
    private double joinRadius;
    private final Map<UnoTable.Type, Integer> tableMin = new EnumMap<>(UnoTable.Type.class);
    private final Map<UnoTable.Type, Integer> tableMax = new EnumMap<>(UnoTable.Type.class);
    private final Map<UnoTable.Type, TableBlocks> tableBlocks = new EnumMap<>(UnoTable.Type.class);

    /**
     * A variant's resolved block palette: the enum defaults with any config override applied.
     *
     * <p>Kept as its own type so {@link com.unoplugin.table.TableManager} builds a table from
     * one object rather than four parallel lookups that could drift out of step.
     */
    public record TableBlocks(Material topPrimary, Material topSecondary,
                              Material frame, Material seat) {}

    // gambling
    private boolean gamblingEnabled;
    private int anteSeconds;
    private boolean auditLog;
    private int maxPotItems;
    private int minAnteItems;
    private boolean blockContainers;
    private List<String> blacklist = List.of();
    private boolean rideEnabled;
    private int rideWindowSeconds;
    private int rideChallengeSeconds;

    // effects
    private boolean sounds;
    private boolean particles;
    private float effectVolume;

    // misc
    private boolean debug;
    private String packLink;
    private String packSha1;
    private boolean packRequired;
    private String packPrompt;

    public Settings(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        startingHandSize = clamp(c.getInt("game.starting-hand-size", 7), 1, 20);
        turnTimeoutSeconds = Math.max(0, c.getInt("game.turn-timeout-seconds", 60));
        turnWarningSeconds = Math.max(0, c.getInt("game.turn-warning-seconds", 10));
        if (turnWarningSeconds >= turnTimeoutSeconds) {
            turnWarningSeconds = 0; // a warning at or after the deadline is no warning at all
        }

        maxTablesPerWorld = Math.max(0, c.getInt("tables.max-per-world", 0));
        joinRadius = Math.max(1.0, c.getDouble("tables.join-radius", 4.0));
        tableMin.clear();
        tableMax.clear();
        tableBlocks.clear();
        for (UnoTable.Type t : UnoTable.Type.values()) {
            String base = "tables." + t.alias() + ".";
            int min = clamp(c.getInt(base + "min-players", 2), 2, 4);
            int max = clamp(c.getInt(base + "max-players", 4), min, 4);
            tableMin.put(t, min);
            tableMax.put(t, max);
            tableBlocks.put(t, new TableBlocks(
                    material(c, base + "blocks.top-primary", t.topPrimary()),
                    material(c, base + "blocks.top-secondary", t.topSecondary()),
                    material(c, base + "blocks.frame", t.frame()),
                    material(c, base + "blocks.seat", t.seat())));
        }

        gamblingEnabled = c.getBoolean("gambling.enabled", true);
        anteSeconds = Math.max(0, c.getInt("gambling.ante-seconds", 300));
        auditLog = c.getBoolean("gambling.audit-log", true);
        maxPotItems = Math.max(0, c.getInt("gambling.limits.max-pot-items", 0));
        minAnteItems = Math.max(0, c.getInt("gambling.limits.min-ante-items", 1));
        blockContainers = c.getBoolean("gambling.limits.block-containers", true);
        List<String> raw = c.getStringList("gambling.limits.blacklist");
        List<String> upper = new ArrayList<>(raw.size());
        for (String s : raw) {
            upper.add(s.trim().toUpperCase(Locale.ROOT));
        }
        blacklist = List.copyOf(upper);
        rideEnabled = c.getBoolean("gambling.ride.enabled", true);
        rideWindowSeconds = Math.max(1, c.getInt("gambling.ride.window-seconds", 20));
        rideChallengeSeconds = Math.max(1, c.getInt("gambling.ride.challenge-seconds", 90));

        sounds = c.getBoolean("effects.sounds", true);
        particles = c.getBoolean("effects.particles", true);
        // Above 2 is just distortion, and 0 is what `sounds: false` is for.
        effectVolume = (float) Math.max(0.0, Math.min(2.0, c.getDouble("effects.volume", 1.0)));

        debug = c.getBoolean("debug", false);
        packLink = c.getString("resource-pack.url.link", "");
        packSha1 = c.getString("resource-pack.url.sha1", "");
        packRequired = c.getBoolean("resource-pack.require", false);
        packPrompt = c.getString("resource-pack.prompt", "");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Read a block material by name, falling back to the variant's default.
     *
     * <p>A typo in a retheme should cost you that one block, not the table: an unknown or
     * non-block name is logged and the default stands, because returning null here would
     * surface as a NPE inside the chunk-load handler that rebuilds every table in range.
     */
    private Material material(FileConfiguration c, String path, Material fallback) {
        String raw = c.getString(path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(raw.trim());
        if (m == null || !m.isBlock()) {
            plugin.getLogger().warning(
                    "config.yml " + path + ": '" + raw + "' is not a block — using " + fallback + ".");
            return fallback;
        }
        return m;
    }

    // ---------------------------------------------------------------- gameplay

    public int startingHandSize() {
        return startingHandSize;
    }

    public int turnTimeoutSeconds() {
        return turnTimeoutSeconds;
    }

    public int turnWarningSeconds() {
        return turnWarningSeconds;
    }

    // ------------------------------------------------------------------ tables

    public int maxTablesPerWorld() {
        return maxTablesPerWorld;
    }

    /** How close a player must stand to a table for {@code /uno join} to seat them. */
    public double joinRadius() {
        return joinRadius;
    }

    public int tableMinPlayers(UnoTable.Type type) {
        return tableMin.getOrDefault(type, 2);
    }

    public int tableMaxPlayers(UnoTable.Type type) {
        return tableMax.getOrDefault(type, 4);
    }

    /** The variant's block palette, with any {@code tables.<alias>.blocks.*} override applied. */
    public TableBlocks tableBlocks(UnoTable.Type type) {
        return tableBlocks.computeIfAbsent(type, t ->
                new TableBlocks(t.topPrimary(), t.topSecondary(), t.frame(), t.seat()));
    }

    // --------------------------------------------------------------- gambling

    public boolean gamblingEnabled() {
        return gamblingEnabled;
    }

    public int anteSeconds() {
        return anteSeconds;
    }

    public boolean auditLog() {
        return auditLog;
    }

    public int maxPotItems() {
        return maxPotItems;
    }

    public int minAnteItems() {
        return minAnteItems;
    }

    public boolean rideEnabled() {
        return rideEnabled;
    }

    public int rideWindowSeconds() {
        return rideWindowSeconds;
    }

    public int rideChallengeSeconds() {
        return rideChallengeSeconds;
    }

    /**
     * Why this stack may not be staked, or {@code null} if it may.
     *
     * <p>Returns a message key so the caller can show the admin's own wording.
     */
    public String stakeRefusal(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        if (blockContainers && hidesItems(stack)) {
            return "bet.container-blocked";
        }
        String material = stack.getType().name();
        for (String banned : blacklist) {
            if (banned.isEmpty()) {
                continue;
            }
            // Whole words only. A bare endsWith would make blacklisting STONE also ban
            // REDSTONE, BLACKSTONE and END_STONE — silent refusals on items the admin never
            // listed. The underscore form still catches the intended DIAMOND -> *_DIAMOND.
            if (material.equals(banned) || material.endsWith("_" + banned)) {
                return "bet.blacklisted";
            }
        }
        return null;
    }

    /** True if the stack can carry other items inside it (shulker box, bundle). */
    private static boolean hidesItems(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            return bundle.hasItems();
        }
        if (meta instanceof BlockStateMeta state && state.hasBlockState()) {
            return state.getBlockState() instanceof org.bukkit.block.Container;
        }
        return false;
    }

    // ----------------------------------------------------------------- effects

    public boolean sounds() {
        return sounds;
    }

    public boolean particles() {
        return particles;
    }

    /** Multiplier applied to every sound the plugin plays (0 = silent, 1 = as tuned). */
    public float effectVolume() {
        return effectVolume;
    }

    // -------------------------------------------------------------------- misc

    public boolean debug() {
        return debug;
    }

    public String packLink() {
        return packLink == null ? "" : packLink;
    }

    public String packSha1() {
        return packSha1 == null ? "" : packSha1;
    }

    public boolean packRequired() {
        return packRequired;
    }

    /** The download prompt, rendered from its MiniMessage source. */
    public Component packPrompt() {
        return MiniMessage.miniMessage().deserialize(packPrompt == null ? "" : packPrompt);
    }
}
