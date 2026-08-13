package com.unoplugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private int casinoMinPlayers;
    private int casinoMaxPlayers;
    private boolean dealerEnabled;

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
        casinoMinPlayers = clamp(c.getInt("tables.casino.min-players", 4), 2, 10);
        casinoMaxPlayers = clamp(c.getInt("tables.casino.max-players", 6), casinoMinPlayers, 10);
        dealerEnabled = c.getBoolean("dealer.enabled", true);

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

    public int casinoMinPlayers() {
        return casinoMinPlayers;
    }

    public int casinoMaxPlayers() {
        return casinoMaxPlayers;
    }

    public boolean dealerEnabled() {
        return dealerEnabled;
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
