package com.unoplugin.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every player-facing string, loaded from {@code messages.yml} and rendered with MiniMessage.
 *
 * <p>The file shipped inside the jar is installed on first run and also registered as the
 * <em>defaults</em>, so an admin's copy only needs the keys they actually want to change and
 * a plugin upgrade that adds new keys can't leave holes.
 *
 * <p>Placeholder values are inserted <strong>unparsed</strong> unless they are already a
 * {@link Component}: a player named {@code <red>oops} cannot smuggle formatting (or a click
 * event) into a broadcast.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final File file;
    private final Set<String> warned = new HashSet<>();

    private YamlConfiguration cfg = new YamlConfiguration();
    private String prefix = "";

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    /** Re-read messages.yml from disk (and re-apply the jar's copy as defaults). */
    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
                loaded.setDefaults(shipped);
                // In memory only: the admin's file is theirs to fix, and it says which lines.
                List<String> stale = staleKeys(loaded, shipped);
                for (String key : stale) {
                    loaded.set(key, shipped.getString(key));
                }
                if (!stale.isEmpty()) {
                    plugin.getLogger().warning("messages.yml uses placeholders this version no "
                            + "longer fills in, so the built-in text is shown for: "
                            + String.join(", ", stale) + ". Update or delete those lines.");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled messages.yml: " + e.getMessage());
        }
        this.cfg = loaded;
        this.prefix = loaded.getString("prefix", "");
        warned.clear();
    }

    /**
     * Render a message.
     *
     * <p>{@code placeholders} are alternating name/value pairs:
     * {@code get("game.win", "player", name)}. A value that is already a {@link Component}
     * is inserted as-is; a {@link TagResolver} is used directly; anything else is inserted
     * as literal text.
     */
    public Component get(String key, Object... placeholders) {
        String raw = cfg.getString(key);
        if (raw == null) {
            if (warned.add(key)) {
                plugin.getLogger().warning("messages.yml has no key '" + key + "' (and neither does"
                        + " the bundled copy) — showing the key instead.");
            }
            return Component.text("<" + key + ">");
        }
        return MM.deserialize(raw, resolvers(placeholders));
    }

    /**
     * A click-to-run chat button: a label from messages.yml, wired to a command built here.
     *
     * <p>Pass the result back in as a placeholder value. That is the whole point of it being
     * a {@link Component}: {@link #get} inserts plain values <em>unparsed</em>, so a player
     * called {@code <red>oops} can't smuggle a click event into a broadcast — but a Component
     * placeholder goes in as-is, which is what a button needs. The command string is always
     * assembled in Java and never from anything a player typed.
     */
    public Component button(String labelKey, String command, Object... placeholders) {
        return get(labelKey, placeholders).clickEvent(ClickEvent.runCommand(command));
    }

    /** True if the message resolves to something worth sending (not blank). */
    public boolean isBlank(String key) {
        String raw = cfg.getString(key);
        return raw == null || raw.isEmpty();
    }

    /** Render and send in one step. Silently skips a blank message. */
    public void send(Audience to, String key, Object... placeholders) {
        if (to == null || isBlank(key)) {
            return;
        }
        to.sendMessage(get(key, placeholders));
    }

    /** Render and send to the action bar. */
    public void actionBar(Audience to, String key, Object... placeholders) {
        if (to == null || isBlank(key)) {
            return;
        }
        to.sendActionBar(get(key, placeholders));
    }

    /**
     * Render and show as a title with a subtitle, on the timings every title in the plugin
     * uses. Either half may be blank in messages.yml; blanking both silences the title.
     */
    public void title(Audience to, String mainKey, String subKey, Object... placeholders) {
        title(to, mainKey, subKey, DEFAULT_TITLE_TIMES, placeholders);
    }

    /** The same, on timings of the caller's choosing. */
    public void title(Audience to, String mainKey, String subKey, Title.Times times,
                      Object... placeholders) {
        if (to == null || (isBlank(mainKey) && isBlank(subKey))) {
            return;
        }
        Component main = isBlank(mainKey) ? Component.empty() : get(mainKey, placeholders);
        Component sub = isBlank(subKey) ? Component.empty() : get(subKey, placeholders);
        to.showTitle(Title.title(main, sub, times));
    }

    private static final Title.Times DEFAULT_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400));

    /**
     * Keys in an admin's file that use a placeholder the shipped text for that key doesn't.
     *
     * <p>That is what a messages.yml copied out of an older jar looks like after the code
     * behind a message changed what it fills in: {@code bet.ride-won} used to be given
     * {@code <items>} and is now given {@code <pot>}, so the old line printed
     * "&lt;items&gt; item(s) won." to the whole table. The admin's copy wins over the
     * defaults by design, which is exactly why it has to be caught here rather than hoping
     * every server regenerates its file on upgrade.
     *
     * <p>MiniMessage's own tags ({@code <gold>}, {@code <bold>}, {@code <click:…>}) are never
     * counted, so recolouring or restyling a message is still entirely the admin's business.
     */
    static List<String> staleKeys(ConfigurationSection admin, ConfigurationSection shipped) {
        List<String> stale = new ArrayList<>();
        for (String key : admin.getKeys(true)) {
            if (!admin.isString(key) || !shipped.isString(key)) {
                continue;
            }
            Set<String> known = tagNames(shipped.getString(key));
            for (String tag : tagNames(admin.getString(key))) {
                if (!known.contains(tag) && !"prefix".equals(tag) && !STANDARD_TAGS.has(tag)) {
                    stale.add(key);
                    break;
                }
            }
        }
        return stale;
    }

    private static final TagResolver STANDARD_TAGS = TagResolver.standard();
    private static final Pattern TAG = Pattern.compile("<(?!/)([a-zA-Z0-9_-]+)");

    private static Set<String> tagNames(String raw) {
        Set<String> names = new HashSet<>();
        Matcher m = TAG.matcher(raw);
        while (m.find()) {
            names.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private TagResolver resolvers(Object... placeholders) {
        TagResolver.Builder builder = TagResolver.builder();
        // The prefix is admin-authored, so its own tags are parsed.
        builder.resolver(Placeholder.parsed("prefix", prefix));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            Object value = placeholders[i + 1];
            if (value instanceof TagResolver resolver) {
                builder.resolver(resolver);
            } else if (value instanceof Component component) {
                builder.resolver(Placeholder.component(name, component));
            } else {
                builder.resolver(Placeholder.unparsed(name, String.valueOf(value)));
            }
        }
        return builder.build();
    }
}
