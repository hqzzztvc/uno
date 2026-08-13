package com.unoplugin.table;

import com.unoplugin.UnoPlugin;
import com.unoplugin.util.Messages;
import com.unoplugin.util.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.Axis;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every placed table: the registry, persistence, the placeable item,
 * right-click placement, removal, and chunk-aware visual spawning.
 */
public class TableManager implements Listener {

    /** Outcome of trying to place a table. */
    public enum PlaceResult { OK, TOO_CLOSE, WORLD_LIMIT, NO_ROOM }

    /** Lets the game/bet layers veto removing a table they're using. */
    public interface BusyCheck {
        boolean isBusy(UUID tableId);
    }

    private final UnoPlugin plugin;
    private final Messages messages;
    private final Settings settings;
    private final Map<UUID, UnoTable> tables = new HashMap<>();
    /**
     * Tables whose world wasn't loaded when we read tables.yml. They are NOT dropped:
     * they are written back verbatim on save and materialised if their world turns up.
     */
    private final List<PendingTable> pending = new ArrayList<>();
    private final NamespacedKey idKey;       // tags spawned entities with their table id
    private final NamespacedKey vehicleKey;  // tags the invisible seat mount
    private final File dataFile;

    private BusyCheck busyCheck = id -> false;

    /** A table we know about but can't build yet, because its world isn't loaded. */
    private record PendingTable(String key, String type, String world,
                                double x, double y, double z, double yaw) {}

    /**
     * How far in front of the player {@code /uno createtable} builds, in blocks.
     *
     * <p>The table is 3×3 with seats 2 out, so it reaches 2 blocks from its centre. At 4 the
     * near seat lands just past arm's reach — clear of the player, close enough to walk to.
     */
    private static final double BUILD_DISTANCE = 4.0;

    /** How far up or down to look for ground under the build spot before giving up. */
    private static final int GROUND_SEARCH = 4;

    /** Players currently seated, keyed by player id. */
    private final Map<UUID, Seated> seated = new HashMap<>();

    private record Seated(UUID tableId, int index, UUID vehicleId) {}

    public TableManager(UnoPlugin plugin, Messages messages, Settings settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.idKey = new NamespacedKey(plugin, "uno_table_id");
        this.vehicleKey = new NamespacedKey(plugin, "uno_seat_vehicle");
        this.dataFile = new File(plugin.getDataFolder(), "tables.yml");
    }

    /** Wire in "is anything using this table right now?" (games and pots). */
    public void setBusyCheck(BusyCheck busyCheck) {
        this.busyCheck = busyCheck == null ? id -> false : busyCheck;
    }

    // ---------------------------------------------------------------- lifecycle

    public void load() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yml.getConfigurationSection("tables");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            String worldName = s.getString("world", "");
            String type = migrateType(s.getString("type", UnoTable.Type.CHERRY.name()));
            double x = s.getDouble("x");
            double y = s.getDouble("y");
            double z = s.getDouble("z");
            double yaw = s.getDouble("yaw");
            try {
                UUID id = UUID.fromString(key);
                UnoTable.Type parsedType = UnoTable.Type.valueOf(type);
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    // Multiverse and friends load worlds AFTER plugins enable. Dropping the
                    // table here would have it erased from disk by the next save().
                    pending.add(new PendingTable(key, type, worldName, x, y, z, yaw));
                    continue;
                }
                register(create(parsedType, id, new Location(world, x, y, z), (float) yaw));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Bad table entry '" + key + "': " + ex.getMessage()
                        + " — keeping it on file untouched.");
                pending.add(new PendingTable(key, type, worldName, x, y, z, yaw));
            }
        }
        plugin.getLogger().info("Loaded " + tables.size() + " UNO table(s)."
                + (rebuilt == 0 ? "" : " Re-laid the blocks of " + rebuilt + " of them.")
                + (pending.isEmpty() ? "" : " " + pending.size()
                + " waiting for their world to load (kept on file)."));
    }

    /**
     * Table type names that existed in earlier builds, mapped to what they are called now.
     *
     * <p>Purely so a {@code tables.yml} written by an older jar still loads: the row keeps its
     * position and just picks up the current palette. Rejecting an unknown name instead strands
     * the row in {@code pending} and leaves a dead entry on disk that no command can reach.
     */
    private static final Map<String, UnoTable.Type> LEGACY_TYPES = Map.of(
            "CASINO", UnoTable.Type.CHERRY,      // the single pre-variant table
            "BLOSSOM", UnoTable.Type.CHERRY,     // renamed after the blocks it is made of
            "MIDNIGHT", UnoTable.Type.DARK_CHERRY,
            "TAVERN", UnoTable.Type.OAK,
            "HOMESTEAD", UnoTable.Type.BIRCH);
    /** Set once per load so the migration logs a single line, not one per table. */
    private boolean loggedMigration = false;

    private String migrateType(String stored) {
        UnoTable.Type replacement = stored == null
                ? null : LEGACY_TYPES.get(stored.toUpperCase(Locale.ROOT));
        if (replacement == null) {
            return stored;
        }
        if (!loggedMigration) {
            plugin.getLogger().info("tables.yml holds tables under their old names — "
                    + "loading them under the current ones. Their blocks are re-laid to match.");
            loggedMigration = true;
        }
        return replacement.name();
    }

    /** A world showed up late — build any tables that were waiting for it. */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (pending.isEmpty()) {
            return;
        }
        String name = event.getWorld().getName();
        int built = 0;
        for (Iterator<PendingTable> it = pending.iterator(); it.hasNext(); ) {
            PendingTable p = it.next();
            if (!p.world().equals(name)) {
                continue;
            }
            try {
                UnoTable table = create(UnoTable.Type.valueOf(p.type()), UUID.fromString(p.key()),
                        new Location(event.getWorld(), p.x(), p.y(), p.z()), (float) p.yaw());
                register(table);
                it.remove();
                built++;
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Table '" + p.key() + "' is unreadable: " + ex.getMessage());
            }
        }
        if (built > 0) {
            plugin.getLogger().info("World '" + name + "' loaded — restored " + built + " UNO table(s).");
        }
    }

    /**
     * Add a table read from disk back to the registry, building its blocks if they're gone.
     *
     * <p>Normally there is nothing to do: the blocks are real, so they were saved with their
     * chunk and are already standing there. The check is for the two cases where they aren't
     * — a table written by the old display-entity build (whose "visuals" were entities that
     * died with the process), and a table someone bulldozed while the server was down.
     * Re-laying only what's missing makes both self-healing and costs one block read.
     */
    private void register(UnoTable table) {
        tables.put(table.id(), table);
        table.setSpawned(true);
        World w = table.anchor().getWorld();
        if (w == null) {
            return;
        }
        Settings.TableBlocks palette = settings.tableBlocks(table.type());
        Material standing = w.getBlockAt(table.anchor()).getType();
        if (standing != palette.topPrimary() && standing != palette.topSecondary()) {
            buildBlocks(table);
            rebuilt++;
        }
    }

    /** Tables whose blocks had to be re-laid this load — logged once, not once per table. */
    private int rebuilt = 0;

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (UnoTable t : tables.values()) {
            Location a = t.anchor();
            String base = "tables." + t.id();
            yml.set(base + ".type", t.type().name());
            // The captured name, NOT a.getWorld() — that goes null the moment the world is
            // unloaded at runtime, and an NPE here means tables.yml is never written at all.
            yml.set(base + ".world", t.worldName());
            yml.set(base + ".x", a.getX());
            yml.set(base + ".y", a.getY());
            yml.set(base + ".z", a.getZ());
            yml.set(base + ".yaw", (double) t.yaw());
        }
        // Write back everything we couldn't resolve, exactly as we read it. Without this,
        // placing one table erases every table in an unloaded world, permanently.
        for (PendingTable p : pending) {
            String base = "tables." + p.key();
            yml.set(base + ".type", p.type());
            yml.set(base + ".world", p.world());
            yml.set(base + ".x", p.x());
            yml.set(base + ".y", p.y());
            yml.set(base + ".z", p.z());
            yml.set(base + ".yaw", p.yaw());
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save tables.yml: " + e.getMessage());
        }
    }

    /** Eject seated players and despawn all visuals (called on plugin disable). */
    public void shutdown() {
        // Drop the bookkeeping FIRST. Ejecting fires EntityDismountEvent synchronously, and if
        // `seated` still matches, onDismount routes into leaveSeat — which schedules a task.
        // The plugin is already disabled by now, so the scheduler rejects it and Bukkit logs a
        // stack trace on every single shutdown that has someone sitting down.
        List<Seated> riders = new ArrayList<>(seated.values());
        seated.clear();
        for (Seated s : riders) {
            Entity vehicle = Bukkit.getEntity(s.vehicleId());
            if (vehicle != null) {
                vehicle.eject();
                vehicle.remove();
            }
            UnoTable t = tables.get(s.tableId());
            if (t != null) {
                t.clearOccupant(s.index());
            }
        }
        // The blocks stay: they are part of the world now, and a table that survives a
        // restart is the whole point of building it out of blocks.
    }

    private UnoTable create(UnoTable.Type type, UUID id, Location anchor, float yaw) {
        return new UnoTable(id, type, anchor, yaw,
                settings.tableMinPlayers(type), settings.tableMaxPlayers(type));
    }

    // ------------------------------------------------------------ place / remove

    /**
     * Build a table on the ground in FRONT of the player — the whole of {@code /uno createtable}.
     *
     * <p>In front, not underfoot: the table is 3×3 with seats 2 out, so centring it on the
     * player buries them inside their own furniture and leaves them standing on the felt.
     * {@link #BUILD_DISTANCE} puts the near seat about where they are looking.
     */
    public PlaceResult createTable(UnoTable.Type type, Player player) {
        World w = player.getWorld();
        int limit = settings.maxTablesPerWorld();
        if (limit > 0 && countIn(w) >= limit) {
            return PlaceResult.WORLD_LIMIT;
        }
        Location anchor = groundInFront(player);
        if (anchor == null) {
            return PlaceResult.NO_ROOM;
        }
        for (UnoTable other : tables.values()) {
            // World first: a table whose world was unloaded at runtime has a null one, and
            // calling equals ON that is an NPE that takes the whole placement down.
            if (w.equals(other.anchor().getWorld())
                    && other.anchor().distanceSquared(anchor) < 36) {
                return PlaceResult.TOO_CLOSE;
            }
        }
        float yaw = snap(player.getLocation().getYaw());
        UnoTable table = create(type, UUID.randomUUID(), anchor, yaw);
        if (!footprintClear(table)) {
            return PlaceResult.NO_ROOM;
        }
        tables.put(table.id(), table);
        buildBlocks(table);
        save();
        return PlaceResult.OK;
    }

    /**
     * The block the table's centre sits on: {@link #BUILD_DISTANCE} ahead of the player,
     * dropped onto whatever ground is there.
     *
     * <p>Returns null if there is nothing to stand the table on within a few blocks up or
     * down — over a ravine or in mid-air, refusing beats dropping a table into the void.
     */
    private Location groundInFront(Player player) {
        Location eye = player.getLocation();
        double rad = Math.toRadians(snap(eye.getYaw()));
        // Snapped yaw, so the table lands square with the world grid the player is facing.
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        int bx = eye.getBlockX() + (int) Math.round(fx * BUILD_DISTANCE);
        int bz = eye.getBlockZ() + (int) Math.round(fz * BUILD_DISTANCE);
        World w = player.getWorld();
        int startY = eye.getBlockY();
        for (int dy = 0; dy <= GROUND_SEARCH; dy++) {
            for (int sign : new int[]{1, -1}) {
                int y = startY + sign * dy;
                if (y < w.getMinHeight() + 1 || y > w.getMaxHeight() - 2) {
                    continue;
                }
                Block floor = w.getBlockAt(bx, y - 1, bz);
                Block at = w.getBlockAt(bx, y, bz);
                if (floor.getType().isSolid() && (at.getType().isAir() || at.isReplaceable())) {
                    return new Location(w, bx + 0.5, y, bz + 0.5);
                }
                if (dy == 0) {
                    break; // +0 and -0 are the same block
                }
            }
        }
        return null;
    }

    /** How many tables are already in this world (for the per-world cap). */
    public int countIn(World world) {
        int n = 0;
        for (UnoTable t : tables.values()) {
            if (world.equals(t.anchor().getWorld())) {
                n++;
            }
        }
        return n;
    }

    /** The nearest table within {@code radius}, or null. */
    public UnoTable nearest(Player player, double radius) {
        UnoTable nearest = null;
        double best = radius * radius;
        for (UnoTable t : tables.values()) {
            if (!player.getWorld().equals(t.anchor().getWorld())) {
                continue; // player's world is never null; a table in an unloaded world is
            }
            double d = t.anchor().distanceSquared(player.getLocation());
            if (d <= best) {
                best = d;
                nearest = t;
            }
        }
        return nearest;
    }

    /** True if a hand or a pot is running at this table right now. */
    public boolean isBusy(UUID tableId) {
        return busyCheck.isBusy(tableId);
    }

    /**
     * Tear a table down completely: stand everyone up, despawn every entity it owns, drop it
     * from the registry and rewrite the file.
     */
    public void remove(UnoTable table) {
        for (Map.Entry<UUID, Seated> e : new ArrayList<>(seated.entrySet())) {
            if (e.getValue().tableId().equals(table.id())) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    leaveSeat(p);
                } else {
                    seated.remove(e.getKey());
                }
            }
        }
        clearBlocks(table);
        tables.remove(table.id());
        save();
    }

    private float snap(float yaw) {
        float snapped = Math.round(yaw / 90f) * 90f;
        return (snapped % 360f + 360f) % 360f;
    }

    // ------------------------------------------------------------------ blocks

    /**
     * Build the table out of REAL blocks — nine logs and four stairs, set into the world.
     *
     * <p>Not display entities. A display is one more entity per table for every player in
     * range to track, it renders at whatever scale it was given rather than as a block, and
     * it vanishes with its chunk so the plugin has to babysit chunk load/unload to put it
     * back. Real blocks are saved with the chunk, cost nothing to render, light and occlude
     * like the blocks they are, and behave the same whether the plugin is loaded or not.
     */
    private void buildBlocks(UnoTable table) {
        World w = table.anchor().getWorld();
        if (w == null) {
            return;
        }
        Settings.TableBlocks palette = settings.tableBlocks(table.type());
        Location a = table.anchor();
        // The 3×3 top: one layer of logs laid ring-face up, chequered. World-axis aligned and
        // never rotated — a 3×3 is symmetric under the 90° steps snap() allows.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // Corners and centre take the primary colour, the four edges the secondary.
                Material log = ((Math.abs(dx) + Math.abs(dz)) % 2 == 0)
                        ? palette.topPrimary() : palette.topSecondary();
                BlockData data = log.createBlockData();
                if (data instanceof Orientable orientable) {
                    orientable.setAxis(Axis.Y); // rings up, bark on the sides
                    data = orientable;
                }
                w.getBlockAt(a.clone().add(dx, 0, dz)).setBlockData(data, false);
            }
        }
        for (Location seat : table.seats()) {
            w.getBlockAt(seat).setBlockData(seatData(table, seat, palette.seat()), false);
        }
        table.setSpawned(true);
    }

    /**
     * The stair a player sits on, facing away from the table.
     *
     * <p>Stairs carry their full-height side on the face they FACE, so facing outward puts
     * the tall half behind the sitter like a backrest and leaves the low step toward the
     * table.
     */
    private BlockData seatData(UnoTable table, Location seat, Material material) {
        BlockData data = material.createBlockData();
        if (data instanceof Directional directional) {
            BlockFace outward = outwardFace(table.anchor(), seat);
            if (directional.getFaces().contains(outward)) {
                directional.setFacing(outward);
                data = directional;
            }
        }
        return data;
    }

    /** Which way is "away from the table" for this seat, snapped to a cardinal face. */
    private static BlockFace outwardFace(Location anchor, Location seat) {
        double dx = seat.getX() - anchor.getX();
        double dz = seat.getZ() - anchor.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Every block position this table owns: the 3×3 top and the four seats. */
    private static List<Location> footprint(UnoTable table) {
        List<Location> out = new ArrayList<>(13);
        Location a = table.anchor();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out.add(a.clone().add(dx, 0, dz));
            }
        }
        out.addAll(table.seats());
        return out;
    }

    /**
     * Take the table's blocks back out of the world.
     *
     * <p>Only clears a position that still holds the material the table put there, so a
     * player who built something on the spot after breaking a seat doesn't lose it, and a
     * table removed twice can't punch a hole in whatever arrived in between.
     */
    private void clearBlocks(UnoTable table) {
        World w = table.anchor().getWorld();
        if (w != null) {
            Settings.TableBlocks palette = settings.tableBlocks(table.type());
            Set<Material> ours = Set.of(palette.topPrimary(), palette.topSecondary(),
                    palette.frame(), palette.seat());
            for (Location loc : footprint(table)) {
                Block b = w.getBlockAt(loc);
                if (ours.contains(b.getType())) {
                    b.setType(Material.AIR, false);
                }
            }
        }
        // The seat mounts are still entities; they are all this table owns now.
        for (UUID eid : new ArrayList<>(table.entityIds())) {
            Entity e = Bukkit.getEntity(eid);
            if (e != null) {
                e.eject(); // a seat mount may still be carrying someone
                e.remove();
            }
        }
        table.entityIds().clear();
        table.setSpawned(false);
    }

    /** True if every block the table needs is free to build into. */
    private boolean footprintClear(UnoTable table) {
        World w = table.anchor().getWorld();
        if (w == null) {
            return false;
        }
        for (Location loc : footprint(table)) {
            Material m = w.getBlockAt(loc).getType();
            // Grass, snow layers and the like are fine to build through — they're what the
            // ground is dressed in. Anything solid is somebody's build.
            if (!m.isAir() && !w.getBlockAt(loc).isReplaceable()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ events

    /**
     * {@code /uno createtable <theme>}: build one in front of the player and report why not.
     *
     * <p>There is no placeable item any more. Handing out a block that turns into a table on
     * right-click meant the table was a thing you could stack, drop, put in a chest and lose;
     * a command that builds it where you are standing has none of that to go wrong.
     */
    public void createTableCommand(Player player, UnoTable.Type type) {
        switch (createTable(type, player)) {
            case OK -> messages.send(player, "table.placed", "variant", type.displayName());
            case TOO_CLOSE -> messages.send(player, "table.too-close");
            case NO_ROOM -> messages.send(player, "table.no-room");
            case WORLD_LIMIT -> messages.send(player, "table.world-limit",
                    "limit", settings.maxTablesPerWorld());
        }
    }

    /**
     * Keep a live table's blocks intact.
     *
     * <p>The table is real blocks now, so anyone can mine a seat out from under it and leave
     * the plugin holding a table that is half gone. Admins are let through so a stuck table
     * can still be cleaned up by hand.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getPlayer().hasPermission("uno.admin")) {
            return;
        }
        if (tableAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "table.protected");
        }
    }

    /** The table owning this block position, or null. */
    private UnoTable tableAt(Location loc) {
        for (UnoTable t : tables.values()) {
            if (!loc.getWorld().equals(t.anchor().getWorld())) {
                continue;
            }
            // Cheap reject before walking the 13 positions.
            if (t.anchor().distanceSquared(loc) > 16) {
                continue;
            }
            for (Location owned : footprint(t)) {
                if (owned.getBlockX() == loc.getBlockX()
                        && owned.getBlockY() == loc.getBlockY()
                        && owned.getBlockZ() == loc.getBlockZ()) {
                    return t;
                }
            }
        }
        return null;
    }

    public Collection<UnoTable> tables() {
        return tables.values();
    }

    public UnoTable table(UUID id) {
        return tables.get(id);
    }

    /** Find a table by the start of its id, the way an admin types it from /uno list. */
    public UnoTable findByPrefix(String prefix) {
        String lower = prefix.toLowerCase();
        UnoTable match = null;
        for (UnoTable t : tables.values()) {
            if (t.id().toString().startsWith(lower)) {
                if (match != null) {
                    return null; // ambiguous
                }
                match = t;
            }
        }
        return match;
    }

    /** Tables we know of but can't build — reported by /uno list so they're not a mystery. */
    public List<String[]> pendingSummaries() {
        List<String[]> out = new ArrayList<>(pending.size());
        for (PendingTable p : pending) {
            out.add(new String[]{p.key(), p.world()});
        }
        return out;
    }

    // -------------------------------------------------------------------- seats

    /**
     * Seat the player at the nearest table within {@code tables.join-radius} — the whole of
     * {@code /uno join}.
     *
     * <p>Walking up and typing a command replaced clicking a seat entity, so the seat is
     * chosen for the player: the free one nearest to where they are standing, which is the
     * one they would have clicked. Every refusal reports itself, because from the player's
     * side "nothing happened" is indistinguishable from a broken command.
     */
    public void joinNearest(Player player) {
        if (seated.containsKey(player.getUniqueId())) {
            messages.send(player, "table.already-seated");
            return;
        }
        double radius = settings.joinRadius();
        UnoTable table = nearest(player, radius);
        if (table == null) {
            messages.send(player, "table.none-near", "radius", radius);
            return;
        }
        int index = nearestFreeSeat(table, player.getLocation());
        if (index < 0) {
            messages.send(player, "table.full");
            return;
        }
        sit(player, table, index);
    }

    /** {@code /uno leave} — get up, or say so if you weren't sitting down. */
    public void leaveNearest(Player player) {
        if (!seated.containsKey(player.getUniqueId())) {
            messages.send(player, "table.not-seated");
            return;
        }
        // Ejecting fires EntityDismountEvent, and onDismount routes that into leaveSeat —
        // so the bookkeeping and the "you stood up" message happen exactly once, there.
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            vehicle.eject();
        } else {
            leaveSeat(player);
        }
    }

    /** The free seat closest to {@code from}, or -1 if every seat is taken. */
    private int nearestFreeSeat(UnoTable table, Location from) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        List<Location> seats = table.seats();
        for (int i = 0; i < seats.size(); i++) {
            if (!table.isSeatFree(i)) {
                continue;
            }
            Location seat = seats.get(i);
            // Compare on X/Z only: standing on the table or in a hole beside it should not
            // change which side of it you are on.
            double dx = seat.getX() - from.getX();
            double dz = seat.getZ() - from.getZ();
            double dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private void sit(Player player, UnoTable table, int index) {
        if (seated.containsKey(player.getUniqueId())) {
            messages.send(player, "table.already-seated");
            return;
        }
        if (!table.isSeatFree(index)) {
            messages.send(player, "table.seat-taken");
            return;
        }
        Location seat = table.seats().get(index);
        World w = seat.getWorld();
        if (w == null) {
            return;
        }
        // Lift the rider up onto the stair's low step (step top = half a block).
        double sitLift = 0.5;
        Location mountLoc = seat.clone();
        mountLoc.setY(seat.getY() + sitLift);
        ArmorStand mount = w.spawn(mountLoc, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setMarker(true);
            as.setBasePlate(false);
            as.setPersistent(false);
            as.setSilent(true);
            as.setCanPickupItems(false);
            as.getPersistentDataContainer().set(vehicleKey, PersistentDataType.BYTE, (byte) 1);
            as.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, table.id().toString());
        });
        // Registered with the table so removing it tears the mount down too — otherwise the
        // rider is left sitting on an invisible entity nothing owns.
        table.entityIds().add(mount.getUniqueId());
        player.teleport(seat); // align facing toward the table centre
        mount.addPassenger(player);
        table.setOccupant(index, player.getUniqueId());
        seated.put(player.getUniqueId(), new Seated(table.id(), index, mount.getUniqueId()));

        messages.send(player, "table.sit");
    }

    private void leaveSeat(Player player) {
        Seated s = seated.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        UnoTable table = tables.get(s.tableId());
        if (table != null) {
            table.clearOccupant(s.index());
            table.entityIds().remove(s.vehicleId());
        }
        UUID vehicleId = s.vehicleId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Entity vehicle = Bukkit.getEntity(vehicleId);
            if (vehicle != null) {
                vehicle.eject();
                vehicle.remove();
            }
        });
        messages.send(player, "table.leave");
    }

    /** True if the player is currently seated at any table. */
    public boolean isSeated(UUID playerId) {
        return seated.containsKey(playerId);
    }

    /** The table the player is currently seated at, or {@code null} if not seated. */
    public UnoTable seatedTable(UUID playerId) {
        Seated s = seated.get(playerId);
        return s == null ? null : tables.get(s.tableId());
    }

    /** Everyone seated at the given table, ordered by seat index. */
    public List<UUID> seatedPlayersAt(UUID tableId) {
        List<Map.Entry<UUID, Seated>> entries = new ArrayList<>();
        for (Map.Entry<UUID, Seated> e : seated.entrySet()) {
            if (e.getValue().tableId().equals(tableId)) {
                entries.add(e);
            }
        }
        entries.sort((a, b) -> Integer.compare(a.getValue().index(), b.getValue().index()));
        List<UUID> result = new ArrayList<>();
        for (Map.Entry<UUID, Seated> e : entries) {
            result.add(e.getKey());
        }
        return result;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Seated s = seated.get(player.getUniqueId());
        if (s == null || !event.getDismounted().getUniqueId().equals(s.vehicleId())) {
            return;
        }
        leaveSeat(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        leaveSeat(event.getPlayer());
    }
}
