package com.unoplugin.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

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
                loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
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
