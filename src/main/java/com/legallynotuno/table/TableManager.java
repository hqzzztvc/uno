package com.legallynotuno.table;

import com.legallynotuno.LegallyNotUno;
import com.legallynotuno.util.Fx;
import com.legallynotuno.util.Messages;
import com.legallynotuno.util.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Axis;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every placed table: the registry, persistence, building and removing the blocks a
 * table is made of, and seating players at it.
 *
 * <p>A table is REAL BLOCKS — nine logs and four stairs — not display entities, so nothing
 * here spawns or despawns visuals: blocks are saved with their chunk and come back on their
 * own. The one chunk hook left ({@link #onChunkLoad}) is a repair, not a respawn — it re-lays
 * a table whose blocks went missing while nobody was looking, after a retheme or a griefing.
 */
public class TableManager implements Listener {

    /** Outcome of trying to place a table. */
    public enum PlaceResult { OK, TOO_CLOSE, WORLD_LIMIT, NO_ROOM }

    /** Lets the game/bet layers veto removing a table they're using. */
    public interface BusyCheck {
        boolean isBusy(UUID tableId);
    }

    /**
     * Lets the game layer drop a player out of their hand when they stand up.
     *
     * <p>A hook rather than a direct call, for the same reason {@link BusyCheck} is one: tables
     * are built below games in the wiring, so this class must not know what a game is.
     */
    public interface StandUpHook {
        /**
         * @param tableId the table they just got up from — passed explicitly because the seat
         *                bookkeeping is already undone by the time this fires, so the layers
         *                below can no longer ask which table it was.
         * @return true if the player was in a hand and has just been dropped from it.
         */
        boolean onStandUp(Player player, UUID tableId);
    }

    private final LegallyNotUno plugin;
    private final Messages messages;
    private final Settings settings;
    private final Fx fx;
    private final Map<UUID, UnoTable> tables = new HashMap<>();
    /**
     * Tables whose world wasn't loaded when we read tables.yml. They are NOT dropped:
     * they are written back verbatim on save and materialised if their world turns up.
     */
    private final List<PendingTable> pending = new ArrayList<>();
    /**
     * Anchor chunk -> tables in it, so ChunkLoadEvent isn't a scan of every table.
     *
     * <p>Chunk load is one of the hottest events on a busy server. This only exists so a
     * table whose blocks are missing gets them back the moment its chunk comes up, rather
     * than waiting for someone to sit at it.
     */
    private final Map<Long, List<UnoTable>> byChunk = new HashMap<>();
    private final NamespacedKey idKey;       // tags spawned entities with their table id
    private final NamespacedKey vehicleKey;  // tags the invisible seat mount
    private final NamespacedKey itemKey;     // stamps a table item with the theme it builds
    private final File dataFile;
    private final ThemeStore themes;
    private final CustomBlocks customBlocks;

    private BusyCheck busyCheck = id -> false;
    private StandUpHook standUpHook = (p, t) -> false;

    /** A table we know about but can't build yet, because its world isn't loaded. */
    private record PendingTable(String key, String theme, String world,
                                double x, double y, double z, double yaw) {}

    /** Players currently seated, keyed by player id. */
    private final Map<UUID, Seated> seated = new HashMap<>();

    private record Seated(UUID tableId, int index, UUID vehicleId) {}

    public TableManager(LegallyNotUno plugin, Messages messages, Settings settings, Fx fx) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.fx = fx;
        this.idKey = new NamespacedKey(plugin, "table_id");
        this.vehicleKey = new NamespacedKey(plugin, "seat_vehicle");
        this.itemKey = new NamespacedKey(plugin, "table_item");
        this.dataFile = new File(plugin.getDataFolder(), "tables.yml");
        this.themes = new ThemeStore(plugin);
        this.customBlocks = new CustomBlocks(plugin.getLogger());
    }

    /** The theme registry, for the commands and the editor. */
    public ThemeStore themes() {
        return themes;
    }

    /** The custom-block bridge, so the editor can preview a theme's blocks the same way. */
    public CustomBlocks customBlocks() {
        return customBlocks;
    }

    /**
     * Re-read themes.yml — {@code /uno reload} does this alongside config and messages.
     *
     * @return null, or why themes.yml can't be used
     */
    public String reloadThemes() {
        return themes.load();
    }

    /** Wire in "is anything using this table right now?" (games and pots). */
    public void setBusyCheck(BusyCheck busyCheck) {
        this.busyCheck = busyCheck == null ? id -> false : busyCheck;
    }

    /** Wire in "standing up leaves the hand" — the game layer does the forfeit itself. */
    public void setStandUpHook(StandUpHook standUpHook) {
        this.standUpHook = standUpHook == null ? (p, t) -> false : standUpHook;
    }

    // ---------------------------------------------------------------- lifecycle

    public void load() {
        themes.load();
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
            // "theme" is what this build writes; "type" is the old enum name, which maps onto
            // whichever theme replaced it. Reading both means an older tables.yml still loads.
            String theme = s.getString("theme");
            if (theme == null) {
                theme = migrateType(s.getString("type", "CHERRY"));
            }
            double x = s.getDouble("x");
            double y = s.getDouble("y");
            double z = s.getDouble("z");
            double yaw = s.getDouble("yaw");
            try {
                UUID id = UUID.fromString(key);
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    // Multiverse and friends load worlds AFTER plugins enable. Dropping the
                    // table here would have it erased from disk by the next save().
                    pending.add(new PendingTable(key, theme, worldName, x, y, z, yaw));
                    continue;
                }
                register(create(theme, id, new Location(world, x, y, z), (float) yaw));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Bad table entry '" + key + "': " + ex.getMessage()
                        + " — keeping it on file untouched.");
                pending.add(new PendingTable(key, theme, worldName, x, y, z, yaw));
            }
        }
        plugin.getLogger().info("Loaded " + tables.size() + " table(s)."
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
    /** Set once per load so the migration logs a single line, not one per table. */
    private boolean loggedMigration = false;

    private String migrateType(String stored) {
        String themeId = ThemeStore.migrateLegacyId(stored);
        if (!loggedMigration) {
            plugin.getLogger().info("tables.yml holds tables under their old variant names — "
                    + "loading them under the themes that replaced those. They are rewritten "
                    + "with a theme id on the next save.");
            loggedMigration = true;
        }
        return themeId;
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
                UnoTable table = create(p.theme(), UUID.fromString(p.key()),
                        new Location(event.getWorld(), p.x(), p.y(), p.z()), (float) p.yaw());
                register(table);
                it.remove();
                built++;
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Table '" + p.key() + "' is unreadable: " + ex.getMessage());
            }
        }
        if (built > 0) {
            plugin.getLogger().info("World '" + name + "' loaded — restored " + built + " table(s).");
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
        byChunk.computeIfAbsent(chunkKey(table.anchor()), k -> new ArrayList<>()).add(table);
        table.setSpawned(true);
        // ONLY if the chunk is already up. getBlockAt() loads a chunk synchronously, so
        // probing every table here dragged the whole table list off disk on the main thread
        // during onEnable. Anything still unloaded is repaired by repairIfNeeded() when a
        // player actually walks up to it, by which point its chunk is loaded anyway.
        if (isAnchorChunkLoaded(table) && repairIfNeeded(table)) {
            rebuilt++;
        }
    }

    private static long chunkKey(Location loc) {
        return chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    /** The one place the packed chunk key is defined — index and lookup can't drift apart. */
    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /**
     * A chunk came up — re-lay any table in it whose blocks aren't there.
     *
     * <p>Cheap by construction: a map lookup that misses on virtually every chunk load, and
     * on a hit, one block read per table. Only a table that has actually lost its blocks
     * (retheme, griefing while the server was down, the old entity build) does any work.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        List<UnoTable> here = byChunk.get(
                chunkKey(event.getChunk().getX(), event.getChunk().getZ()));
        if (here == null) {
            return;
        }
        for (UnoTable t : List.copyOf(here)) {
            if (event.getWorld().equals(t.anchor().getWorld())) {
                repairIfNeeded(t);
            }
        }
    }

    private boolean isAnchorChunkLoaded(UnoTable table) {
        World w = table.anchor().getWorld();
        return w != null && w.isChunkLoaded(
                table.anchor().getBlockX() >> 4, table.anchor().getBlockZ() >> 4);
    }

    /**
     * Re-lay a table's blocks if they aren't standing, and say whether that was needed.
     *
     * <p>Normally a no-op: the blocks are real, so they were saved with their chunk. It earns
     * its keep for a table written by the old display-entity build (whose "visuals" died with
     * the process) and for one somebody bulldozed while the server was down.
     */
    private boolean repairIfNeeded(UnoTable table) {
        World w = table.anchor().getWorld();
        if (w == null || themes.unreadable()) {
            // With themes.yml broken, every custom-themed table resolves to a stand-in, and
            // "repairing" it would lay the stand-in's blocks over the real table.
            return false;
        }
        TableTheme theme = themeOf(table);
        // Every cell, against the exact material that cell must hold. Sampling one or two let
        // a retheme through whenever the new theme happened to reuse the old material in the
        // place being sampled — and with author-written themes there is no longer a "primary
        // and secondary" pair to sample, so the whole grid is the only honest check. Thirteen
        // block reads on a chunk that actually holds a table is cheap; the map lookup in
        // onChunkLoad misses on virtually every chunk before it gets here.
        for (int row = 0; row < TableTheme.SIZE; row++) {
            for (int col = 0; col < TableTheme.SIZE; col++) {
                BlockSpec spec = theme.cell(row, col);
                if (spec.isCustom(customBlocks)) {
                    continue; // the owning plugin decides what its block looks like in-world
                }
                if (w.getBlockAt(cellLocation(table, row, col)).getType()
                        != spec.material(customBlocks)) {
                    buildBlocks(table);
                    return true;
                }
            }
        }
        List<Location> seats = table.seats();
        for (int i = 0; i < seats.size(); i++) {
            BlockSpec spec = theme.seat(TableTheme.Seat.values()[i]);
            if (spec.isCustom(customBlocks)) {
                continue;
            }
            if (w.getBlockAt(seats.get(i)).getType() != spec.material(customBlocks)) {
                buildBlocks(table);
                return true;
            }
        }
        return false;
    }

    /** This table's theme, or the fallback if the one it names has been deleted from file. */
    private TableTheme themeOf(UnoTable table) {
        TableTheme theme = themes.get(table.themeId());
        return theme != null ? theme : themes.fallback();
    }

    /** Every material a theme lays down — what {@code /uno remove} is allowed to clear. */
    private Set<Material> themeMaterials(TableTheme theme) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (int row = 0; row < TableTheme.SIZE; row++) {
            for (int col = 0; col < TableTheme.SIZE; col++) {
                out.add(theme.cell(row, col).material(customBlocks));
            }
        }
        for (TableTheme.Seat seat : TableTheme.Seat.values()) {
            out.add(theme.seat(seat).material(customBlocks));
        }
        return out;
    }

    /** Tables whose blocks had to be re-laid this load — logged once, not once per table. */
    private int rebuilt = 0;

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (UnoTable t : tables.values()) {
            Location a = t.anchor();
            String base = "tables." + t.id();
            yml.set(base + ".theme", t.themeId());
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
            yml.set(base + ".theme", p.theme());
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

    private UnoTable create(String themeId, UUID id, Location anchor, float yaw) {
        return new UnoTable(id, TableTheme.normaliseId(themeId), anchor, yaw,
                settings.tableMinPlayers(), settings.tableMaxPlayers());
    }

    // ------------------------------------------------------------ place / remove

    /** The table {@link #placeAt} last built, so the caller can light it up. */
    private UnoTable lastBuilt;

    /**
     * Build a table centred on the block ABOVE {@code clicked} — the whole of placing a table
     * item.
     *
     * <p>Where the player clicked, not where they are standing: a 3×3 with seats 2 out centred
     * on the placer buries them inside their own furniture. An item they aim is also the only
     * way to line a table up with a room they have already built.
     */
    public PlaceResult placeAt(String themeId, Block clicked, Player player) {
        World w = clicked.getWorld();
        int limit = settings.maxTablesPerWorld();
        if (limit > 0 && countIn(w) >= limit) {
            return PlaceResult.WORLD_LIMIT;
        }
        Location anchor = new Location(w, clicked.getX() + 0.5, clicked.getY() + 1.0,
                clicked.getZ() + 0.5);
        if (anchor.getBlockY() < w.getMinHeight() + 1 || anchor.getBlockY() > w.getMaxHeight() - 2) {
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
        // Snapped to the player's facing, so the theme's pattern and the seats line up with
        // the way they were standing when they placed it.
        float yaw = snap(player.getLocation().getYaw());
        UnoTable table = create(themeId, UUID.randomUUID(), anchor, yaw);
        if (!footprintClear(table)) {
            return PlaceResult.NO_ROOM;
        }
        tables.put(table.id(), table);
        byChunk.computeIfAbsent(chunkKey(anchor), k -> new ArrayList<>()).add(table);
        buildBlocks(table);
        lastBuilt = table;
        save();
        return PlaceResult.OK;
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
        List<UnoTable> here = byChunk.get(chunkKey(table.anchor()));
        if (here != null && here.remove(table) && here.isEmpty()) {
            byChunk.remove(chunkKey(table.anchor()));
        }
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
        TableTheme theme = themeOf(table);
        Location a = table.anchor();
        for (int row = 0; row < TableTheme.SIZE; row++) {
            for (int col = 0; col < TableTheme.SIZE; col++) {
                Location at = cellLocation(table, row, col);
                // A log laid flat shows bark on its top face, which is never what a table top
                // wants; the theme can still override the axis explicitly in its block state.
                theme.cell(row, col).place(at, customBlocks, TableManager::ringsUp);
            }
        }
        List<Location> seats = table.seats();
        for (int i = 0; i < seats.size(); i++) {
            Location seat = seats.get(i);
            BlockFace outward = outwardFace(a, seat);
            theme.seat(TableTheme.Seat.values()[i])
                    .place(seat, customBlocks, data -> faceOutward(data, outward));
        }
        table.setSpawned(true);
    }

    /**
     * Where a theme cell lands in the world, turned to match the way the table faces.
     *
     * <p>The old build never rotated the top, because every theme was a symmetric chequer and
     * rotating one changes nothing. Themes are author-drawn now, so an asymmetric pattern has
     * to come out the same way round however the table was placed. The 90° steps {@link #snap}
     * allows map the 3×3 onto itself exactly, so this can't put a tile off the block grid.
     */
    private static Location cellLocation(UnoTable table, int row, int col) {
        // Table space: +forward is the far side, +right is the right-hand column.
        int fwd = 1 - row;   // row 0 is the FAR row
        int rgt = col - 1;   // col 0 is the LEFT column
        double r = Math.toRadians(table.yaw());
        double fx = -Math.sin(r);
        double fz = Math.cos(r);
        double gx = Math.cos(r);
        double gz = Math.sin(r);
        int dx = (int) Math.round(fx * fwd + gx * rgt);
        int dz = (int) Math.round(fz * fwd + gz * rgt);
        return table.anchor().add(dx, 0, dz);
    }

    /** Stand a log or pillar on end so its rings face up rather than its bark. */
    private static BlockData ringsUp(BlockData data) {
        if (data instanceof Orientable orientable) {
            orientable.setAxis(Axis.Y);
        }
        return data;
    }

    /**
     * Turn a seat block to face away from the table.
     *
     * <p>Stairs carry their full-height side on the face they FACE, so facing outward puts
     * the tall half behind the sitter like a backrest and leaves the low step toward the
     * table. Anything that isn't directional is left exactly as the theme wrote it.
     */
    private static BlockData faceOutward(BlockData data, BlockFace outward) {
        if (data instanceof Directional directional
                && directional.getFaces().contains(outward)) {
            directional.setFacing(outward);
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

    /**
     * Every block position this table owns: the 3×3 top and the four seats.
     *
     * <p>Still a plain 3×3 around the anchor whatever the yaw — rotating a square by 90° gives
     * the same nine positions back, only in a different order — so this does not need the
     * theme, and callers that only ask "is this block ours?" don't pay to resolve one.
     */
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
     * <p>Only clears a position that still holds a material this theme put there, so a
     * player who built something on the spot after breaking a seat doesn't lose it, and a
     * table removed twice can't punch a hole in whatever arrived in between.
     */
    private void clearBlocks(UnoTable table) {
        World w = table.anchor().getWorld();
        if (w != null) {
            Set<Material> ours = themeMaterials(themeOf(table));
            for (Location loc : footprint(table)) {
                Block b = w.getBlockAt(loc);
                // Custom blocks first: on these plugins the world block is a note block or
                // similar, so matching on Material alone would either miss it or clear a real
                // one. The owning plugin also has its own bookkeeping to unwind.
                if (customBlocks.any() && customBlocks.removeAt(b)) {
                    continue;
                }
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

    // ------------------------------------------------------------------- items

    /**
     * The item {@code /uno give} hands out: right-click the ground with it to build the table.
     *
     * <p>What the item IS matters less than what it carries. The theme id lives in its
     * persistent data, so a stack that has been through a chest, a hopper and somebody else's
     * inventory still builds the table it was made for — and a theme renamed in themes.yml
     * since then is caught at placement time rather than building something wrong.
     */
    public ItemStack createTableItem(TableTheme theme) {
        ItemStack item = new ItemStack(Material.OAK_PRESSURE_PLATE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(theme.displayName(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click the ground to place.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Legally Not Uno table · " + settings.tableMinPlayers() + "-"
                                + settings.tableMaxPlayers() + " players · casual or stakes",
                        NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, theme.id());
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    /** {@code /uno give} — put a table item in the player's hands. */
    public void giveTableItem(Player player, TableTheme theme) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(createTableItem(theme));
        // A full inventory must not silently eat the item — drop it at their feet instead.
        for (ItemStack overflow : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    /**
     * Right-clicking the ground with a table item builds the table.
     *
     * <p>The event is cancelled up front whatever happens next: the item is a pressure plate,
     * and letting the interact through would put a real pressure plate down beside the table.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String tag = item.getItemMeta().getPersistentDataContainer()
                .get(itemKey, PersistentDataType.STRING);
        if (tag == null) {
            return;
        }
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();

        // Items made before tables stopped being casual-or-casino carry "casual/<theme>".
        // The kind is gone, so take the theme off the back of it rather than refusing an
        // item somebody has been carrying around in a chest since the last version.
        String themeId = tag.substring(tag.indexOf('/') + 1);
        TableTheme theme = themes.get(themeId);
        if (theme == null) {
            // The theme was deleted or renamed since the item was made. Say so rather than
            // quietly building something else — the player chose this look on purpose.
            messages.send(player, "table.item-stale");
            return;
        }

        switch (placeAt(theme.id(), clicked, player)) {
            case OK -> {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    item.setAmount(item.getAmount() - 1);
                }
                messages.send(player, "table.placed", "variant", theme.displayName());
                fx.tablePlaced(lastBuilt.anchor());
            }
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
        if (event.getPlayer().hasPermission("legallynotuno.admin")) {
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
        repairIfNeeded(table);
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
        fx.seat(player, true);
        promptMode(player, "table.choose");
    }

    /**
     * Ask a seated player how they want to play: a friendly hand, or one for stakes.
     *
     * <p>This is the whole front door. Sitting down used to leave a player holding two
     * commands they had to already know ({@code /uno start}, {@code /gamble}); now the two
     * choices arrive as buttons the moment they sit, and again when a hand finishes. Nothing
     * here knows what a game or a pot IS — the buttons run commands, and the command layer
     * routes them — so this stays below both in the wiring, like {@link BusyCheck}.
     */
    public void promptMode(Player player, String headerKey) {
        Component ready = messages.button("table.choose-ready-button", "/uno ready");
        if (!settings.gamblingEnabled() || !player.hasPermission("legallynotuno.gamble")) {
            // No point offering a door the player can't walk through.
            messages.send(player, headerKey + "-casual", "ready", ready);
            return;
        }
        messages.send(player, headerKey, "ready", ready,
                "bet", messages.button("table.choose-bet-button", "/uno bet"));
    }

    /** The same offer, to everyone still sitting at a table — used when a hand finishes. */
    public void promptModeAt(UUID tableId, String headerKey) {
        for (UUID id : seatedPlayersAt(tableId)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                promptMode(p, headerKey);
            }
        }
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
        fx.seat(player, false);
        // Standing up is leaving the hand — you can't play a card you've walked away from, and
        // the alternative is a seat nobody is in still holding a turn the table has to wait out.
        // It costs exactly what /uno quit costs, including a staked pot, because it IS that.
        //
        // The seat bookkeeping above is finished first on purpose: the forfeit broadcasts to
        // everyone at the table and can end the hand outright, and both walk `seated`.
        if (standUpHook.onStandUp(player, s.tableId())) {
            messages.send(player, "table.stand-forfeit");
        }
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
