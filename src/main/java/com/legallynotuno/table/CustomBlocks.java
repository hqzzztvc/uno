package com.legallynotuno.table;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Places and removes blocks belonging to the custom-block plugins a server might be running.
 *
 * <p>Everything here is reflective and every call is wrapped, on purpose. ItemsAdder, Oraxen
 * and Nexo are not on any Maven repository this build pulls from, so compiling against them
 * would make the plugin unbuildable for anyone who doesn't already have their jars; and a
 * server that runs none of them — which is most of them — must not pay for their absence with
 * a {@code NoClassDefFoundError} at table-building time.
 *
 * <p>The contract every hook honours: if the plugin isn't installed, or its API has moved, or
 * the id doesn't exist, {@link #place} returns false and the caller falls back to the spec's
 * declared vanilla block. A theme that names a custom block is therefore always placeable —
 * it just looks plainer on a server without the plugin that defines it.
 */
public final class CustomBlocks {

    /** One custom-block plugin we know how to drive. */
    private record Hook(String prefix, String pluginName, Method place, Method remove,
                        Method isCustom, boolean placeTakesLocationFirst) {}

    private final Logger log;
    private final List<Hook> hooks = new ArrayList<>();
    /** Set once a hook throws, so a broken API logs one line instead of one per block. */
    private final List<String> warned = new ArrayList<>();

    public CustomBlocks(Logger log) {
        this.log = log;
        detect();
    }

    /**
     * Find whichever of the three is actually running.
     *
     * <p>Resolved once at startup rather than per block: {@code getPlugin} plus three class
     * lookups on every one of a table's thirteen positions, for every table on every chunk
     * load, is a lot of work to conclude "no, still not installed".
     */
    private void detect() {
        // ItemsAdder: CustomBlock.getInstance(id).place(location)
        tryHook("itemsadder", "ItemsAdder", "dev.lone.itemsadder.api.CustomBlock",
                "place", "remove", "byAlreadyPlaced", false);
        // Oraxen: OraxenBlocks.place(id, location) / .remove(location, player)
        tryHook("oraxen", "Oraxen", "io.th0rgal.oraxen.api.OraxenBlocks",
                "place", "remove", "isOraxenBlock", true);
        // Nexo, the maintained fork of Oraxen, with the same shape of API.
        tryHook("nexo", "Nexo", "com.nexomc.nexo.api.NexoBlocks",
                "place", "remove", "isCustomBlock", true);
    }

    private void tryHook(String prefix, String pluginName, String className,
                         String placeName, String removeName, String isCustomName,
                         boolean placeTakesLocationFirst) {
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        if (p == null || !p.isEnabled()) {
            return;
        }
        try {
            Class<?> api = Class.forName(className);
            Method place = findMethod(api, placeName);
            Method remove = findMethod(api, removeName);
            Method isCustom = findMethod(api, isCustomName);
            if (place == null) {
                log.warning("" + pluginName + " is installed but its place() API has moved — "
                        + "themes naming " + prefix + ": blocks will use their fallback block.");
                return;
            }
            hooks.add(new Hook(prefix, pluginName, place, remove, isCustom, placeTakesLocationFirst));
            log.info("Custom blocks: hooked " + pluginName + " (themes may use "
                    + prefix + ":<id>).");
        } catch (ClassNotFoundException | LinkageError ex) {
            log.warning("" + pluginName + " is installed but its API could not be read ("
                    + ex.getClass().getSimpleName() + ") — " + prefix
                    + ": blocks will use their fallback block.");
        }
    }

    /** First public static method with this name, whatever its exact signature. */
    private static Method findMethod(Class<?> api, String name) {
        for (Method m : api.getMethods()) {
            if (m.getName().equals(name) && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                return m;
            }
        }
        // ItemsAdder's place() is an instance method on the CustomBlock it hands back.
        for (Method m : api.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** True if any custom-block plugin is hooked, so callers can skip the work entirely. */
    public boolean any() {
        return !hooks.isEmpty();
    }

    /** The hook that owns this namespace prefix, or null. */
    private Hook hookFor(String namespace) {
        for (Hook h : hooks) {
            if (h.prefix().equals(namespace)) {
                return h;
            }
        }
        return null;
    }

    /** True if this namespace is one a hooked plugin claims — used to route a spec. */
    public boolean handles(String namespace) {
        return hookFor(namespace.toLowerCase(Locale.ROOT)) != null;
    }

    /**
     * Put a custom block into the world.
     *
     * @return true if it was placed; false if nothing here can place it, in which case the
     *         caller must fall back to a vanilla block rather than leave a hole in the table.
     */
    public boolean place(String namespace, String id, Location at) {
        Hook h = hookFor(namespace.toLowerCase(Locale.ROOT));
        if (h == null) {
            return false;
        }
        try {
            if (h.placeTakesLocationFirst()) {
                // Oraxen / Nexo: static place(String id, Location location)
                h.place().invoke(null, id, at);
                return true;
            }
            // ItemsAdder: static CustomBlock.getInstance(id) -> instance.place(location)
            Class<?> api = h.place().getDeclaringClass();
            Method getInstance = null;
            for (Method m : api.getMethods()) {
                if (m.getName().equals("getInstance") && m.getParameterCount() == 1) {
                    getInstance = m;
                    break;
                }
            }
            if (getInstance == null) {
                return false;
            }
            Object block = getInstance.invoke(null, namespace + ":" + id);
            if (block == null) {
                return false; // no such custom block on this server
            }
            h.place().invoke(block, at);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warnOnce(h.pluginName(), "place", ex);
            return false;
        }
    }

    /**
     * Take a custom block back out, if this position holds one.
     *
     * @return true if a custom block was removed, so the caller knows not to also clear the
     *         vanilla block underneath it (which on these plugins is the block itself).
     */
    public boolean removeAt(Block block) {
        for (Hook h : hooks) {
            try {
                if (h.isCustom() != null) {
                    Object owned = h.isCustom().getParameterCount() == 1
                            ? h.isCustom().invoke(null, block) : null;
                    if (!(owned instanceof Boolean b) || !b) {
                        // ItemsAdder's byAlreadyPlaced returns the CustomBlock or null.
                        if (owned == null) {
                            continue;
                        }
                    }
                }
                if (h.remove() != null) {
                    invokeRemove(h, block);
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                warnOnce(h.pluginName(), "remove", ex);
            }
        }
        return false;
    }

    /** remove() takes (Location) or (Location, Player) depending on the plugin and version. */
    private void invokeRemove(Hook h, Block block) throws ReflectiveOperationException {
        Method remove = h.remove();
        int params = remove.getParameterCount();
        if (params == 1) {
            remove.invoke(null, remove.getParameterTypes()[0] == Block.class
                    ? block : block.getLocation());
        } else if (params == 2) {
            remove.invoke(null, remove.getParameterTypes()[0] == Block.class
                    ? block : block.getLocation(), null);
        }
    }

    private void warnOnce(String plugin, String what, Throwable ex) {
        String key = plugin + "#" + what;
        if (warned.contains(key)) {
            return;
        }
        warned.add(key);
        log.warning("" + plugin + " rejected a " + what + " call ("
                + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                + ") — falling back to plain blocks for its ids. This is logged once.");
    }
}
