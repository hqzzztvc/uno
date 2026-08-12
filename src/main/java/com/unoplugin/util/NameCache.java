package com.unoplugin.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UUID → display name, without ever blocking the server thread.
 *
 * <p>{@code Bukkit.getOfflinePlayer(uuid).getName()} can fire an HTTP request to Mojang for a
 * UUID that isn't in the local cache. Called from a render or broadcast path — the bossbar
 * updates on <em>every move</em> — that stalls the whole server for the length of a web
 * request.
 *
 * <p>So: {@link #name} answers instantly from the cache, and schedules a one-shot async
 * lookup for anything it doesn't know yet. The first mention of a long-gone player may read
 * as "Player"; every one after that has the real name.
 */
public final class NameCache implements Listener {

    /** Shown while an unknown player's name is still being resolved. */
    private static final String UNKNOWN = "Player";

    private final Plugin plugin;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Set<UUID> resolving = ConcurrentHashMap.newKeySet();

    public NameCache(Plugin plugin) {
        this.plugin = plugin;
        for (Player online : Bukkit.getOnlinePlayers()) {
            remember(online);
        }
    }

    public void remember(Player player) {
        if (player != null) {
            names.put(player.getUniqueId(), player.getName());
        }
    }

    /** Register a name we already know — bot names, or a lookup done elsewhere. */
    public void remember(UUID id, String name) {
        if (id != null && name != null) {
            names.put(id, name);
        }
    }

    /** Never blocks. Falls back to a placeholder while an async lookup fills the cache. */
    public String name(UUID id) {
        if (id == null) {
            return "nobody";
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            names.put(id, online.getName());
            return online.getName();
        }
        String cached = names.get(id);
        if (cached != null) {
            return cached;
        }
        resolveLater(id);
        return UNKNOWN;
    }

    /** Off-thread name lookup, at most one in flight per player. */
    private void resolveLater(UUID id) {
        if (!plugin.isEnabled() || !resolving.add(id)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String resolved = Bukkit.getOfflinePlayer(id).getName();
                if (resolved != null) {
                    names.put(id, resolved);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Name lookup failed for " + id + ": " + e.getMessage());
            } finally {
                resolving.remove(id);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
    }
}
