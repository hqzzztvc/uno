package com.unoplugin.util;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Offers the client-side resource pack to players as they join, when
 * {@code resource-pack.url.link} is set.
 *
 * <p>The plugin has never hosted the pack itself and doesn't start now — this just points
 * clients at a zip the admin hosts. With no link configured nothing is sent at all, which is
 * the "hand the players the zip yourself" workflow.
 */
public final class ResourcePackSender implements Listener {

    private final Plugin plugin;
    private final Settings settings;
    private boolean warnedAboutHash;

    public ResourcePackSender(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        send(event.getPlayer());
    }

    public void send(Player player) {
        String link = settings.packLink();
        if (link.isEmpty()) {
            return;
        }
        String sha1 = settings.packSha1();
        try {
            if (sha1.isEmpty()) {
                if (!warnedAboutHash) {
                    warnedAboutHash = true;
                    plugin.getLogger().warning("resource-pack.url.sha1 is empty — clients will"
                            + " re-download the pack on every join. Set it to the zip's SHA-1.");
                }
                player.setResourcePack(link);
                return;
            }
            player.setResourcePack(link, sha1, settings.packRequired(), settings.packPrompt());
        } catch (IllegalArgumentException | IllegalStateException e) {
            plugin.getLogger().warning("Could not send the resource pack to "
                    + player.getName() + ": " + e.getMessage());
        }
    }
}
