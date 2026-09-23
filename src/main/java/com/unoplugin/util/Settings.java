package com.unoplugin.util;

import com.unoplugin.game.RuleSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Typed view over config.yml.
 *
 * <p>One place that reads the file, so "is this key actually wired up?" is answerable by
 * reading a single class. Everything here is re-read by {@link #reload()} on /uno reload.
 *
 * <p>The file itself is loaded through {@link ShippedYaml}, which adds the keys a new version
 * ships to a server's existing config.yml and never changes a value already in it.
 */
public final class Settings {

    private final ShippedYaml file;

    // gameplay
    private int startingHandSize;
    private int turnTimeoutSeconds;
    private int turnWarningSeconds;
    private RuleSet rules = RuleSet.VANILLA;

    // tables
    private int maxTablesPerWorld;
    private double joinRadius;
    private int tableMinPlayers;
    private int tableMaxPlayers;

    // gambling
    private boolean gamblingEnabled;
    private int anteSeconds;
    private boolean auditLog;
    private int maxPotItems;
    private int minAnteItems;
    private boolean blockContainers;
    private List<String> blacklist = List.of();
    private boolean moneyEnabled;
    private double minAnteMoney;
    private double maxPotMoney;
    private boolean rideEnabled;
    private int rideWindowSeconds;
    private int rideChallengeSeconds;

    // effects
    private boolean sounds;
    private boolean particles;
    private float effectVolume;

    // updates
    private boolean updateChecks;

    // misc
    private boolean debug;
    private String packLink;
    private String packSha1;
    private boolean packRequired;
    private String packPrompt;

    /**
     * Every rename or change of meaning config.yml has had, oldest first — see
     * {@link ShippedYaml.Migration}. Adding a key needs nothing here (it is merged in on its
     * own); renaming one, or changing what its value means, needs a step here AND
     * {@code config-version} in config.yml bumped to match, or servers keep the old name with
     * nothing reading it and their setting silently reverts to the default.
     */
    static final List<ShippedYaml.Migration> MIGRATIONS = List.of();

    public Settings(Plugin plugin) {
        this.file = new ShippedYaml(plugin, "config.yml", "config-version", false, MIGRATIONS);
        reload();
    }

    /** For tests: read an already-loaded config, with no file behind it. */
    Settings(ConfigurationSection config) {
        this.file = null;
        apply(config);
    }

    /**
     * Re-read config.yml, upgrading it first if this jar ships settings it doesn't have yet.
     *
     * @return null, or why config.yml can't be used — in which case the settings it last loaded
     *         with stay in force, rather than the server quietly falling back to defaults.
     */
    public String reload() {
        ShippedYaml.Loaded loaded = file.load();
        apply(loaded.config());
        return loaded.error();
    }

    /**
     * Every read here passes no default of its own: the jar's config.yml is registered as the
     * defaults, so a missing or mistyped value falls back to what that file documents, and
     * there is no second copy of every default to drift away from it. {@code SettingsTest}
     * holds this method and config.yml to each other in both directions.
     */
    void apply(ConfigurationSection c) {
        startingHandSize = clamp(c.getInt("game.starting-hand-size"), 1, 20);
        turnTimeoutSeconds = Math.max(0, c.getInt("game.turn-timeout-seconds"));
        turnWarningSeconds = Math.max(0, c.getInt("game.turn-warning-seconds"));
        if (turnWarningSeconds >= turnTimeoutSeconds) {
            turnWarningSeconds = 0; // a warning at or after the deadline is no warning at all
        }

        // Every house rule is off unless a server turns it on: an upgrade must not silently
        // change the game people are already playing.
        rules = new RuleSet(
                c.getBoolean("rules.stacking.enabled"),
                c.getBoolean("rules.stacking.draw4-on-draw2"),
                c.getBoolean("rules.stacking.draw2-on-draw4"),
                c.getBoolean("rules.multi-play.enabled"),
                Math.max(0, c.getInt("rules.multi-play.max-cards")),
                c.getBoolean("rules.jump-in"),
                c.getBoolean("rules.seven-o"),
                c.getBoolean("rules.draw-to-match"),
                c.getBoolean("rules.challenge-draw4"),
                c.getBoolean("rules.uno-callout.enabled"),
                Math.max(1, c.getInt("rules.uno-callout.window-seconds")),
                Math.max(0, c.getInt("rules.uno-callout.penalty")),
                Math.max(0, c.getInt("rules.uno-callout.false-callout-penalty")));

        maxTablesPerWorld = Math.max(0, c.getInt("tables.max-per-world"));
        joinRadius = Math.max(1.0, c.getDouble("tables.join-radius"));
        // One pair for every table. The per-variant keys these replaced only existed because
        // the four variants were Java constants; a theme is data now and there can be any
        // number of them, so a seat count keyed by theme would be a config section nobody
        // could keep in step with themes.yml.
        tableMinPlayers = clamp(c.getInt("tables.min-players"), 2, 4);
        tableMaxPlayers = clamp(c.getInt("tables.max-players"), tableMinPlayers, 4);

        gamblingEnabled = c.getBoolean("gambling.enabled");
        anteSeconds = Math.max(0, c.getInt("gambling.ante-seconds"));
        auditLog = c.getBoolean("gambling.audit-log");
        maxPotItems = Math.max(0, c.getInt("gambling.limits.max-pot-items"));
        minAnteItems = Math.max(0, c.getInt("gambling.limits.min-ante-items"));
        blockContainers = c.getBoolean("gambling.limits.block-containers");
        List<String> raw = c.getStringList("gambling.limits.blacklist");
        List<String> upper = new ArrayList<>(raw.size());
        for (String s : raw) {
            upper.add(s.trim().toUpperCase(Locale.ROOT));
        }
        blacklist = List.copyOf(upper);
        moneyEnabled = c.getBoolean("gambling.money.enabled");
        minAnteMoney = Math.max(0.0, c.getDouble("gambling.money.min-ante"));
        maxPotMoney = Math.max(0.0, c.getDouble("gambling.money.max-pot"));

        rideEnabled = c.getBoolean("gambling.ride.enabled");
        rideWindowSeconds = Math.max(1, c.getInt("gambling.ride.window-seconds"));
        rideChallengeSeconds = Math.max(1, c.getInt("gambling.ride.challenge-seconds"));

        sounds = c.getBoolean("effects.sounds");
        particles = c.getBoolean("effects.particles");
        // Above 2 is just distortion, and 0 is what `sounds: false` is for.
        effectVolume = (float) Math.max(0.0, Math.min(2.0, c.getDouble("effects.volume")));

        updateChecks = c.getBoolean("update-checker.enabled");

        debug = c.getBoolean("debug");
        packLink = c.getString("resource-pack.url.link");
        packSha1 = c.getString("resource-pack.url.sha1");
        packRequired = c.getBoolean("resource-pack.require");
        packPrompt = c.getString("resource-pack.prompt");
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

    /** The house rules every new hand is dealt under. */
    public RuleSet rules() {
        return rules;
    }

    // ------------------------------------------------------------------ tables

    public int maxTablesPerWorld() {
        return maxTablesPerWorld;
    }

    /** How close a player must stand to a table for {@code /uno join} to seat them. */
    public double joinRadius() {
        return joinRadius;
    }

    public int tableMinPlayers() {
        return tableMinPlayers;
    }

    public int tableMaxPlayers() {
        return tableMaxPlayers;
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

    /**
     * Whether currency may be wagered at all.
     *
     * <p>Only half the answer — {@code VaultEconomy.available()} is the other half, and both
     * have to be true. This one is the admin saying "not on my server" even where an economy
     * plugin is installed.
     */
    public boolean moneyEnabled() {
        return moneyEnabled;
    }

    /** Smallest money stake that counts as an ante on its own. */
    public double minAnteMoney() {
        return minAnteMoney;
    }

    /** Largest money pot the table will take. 0 = unlimited. */
    public double maxPotMoney() {
        return maxPotMoney;
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

    // ----------------------------------------------------------------- updates

    /** Whether to ask Modrinth for a newer release and tell admins about it. */
    public boolean updateChecks() {
        return updateChecks;
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
