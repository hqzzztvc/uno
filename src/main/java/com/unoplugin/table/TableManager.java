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
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every placed table: the registry, persistence, the placeable item,
 * right-click placement, removal, and chunk-aware visual spawning.
 */
public class TableManager implements Listener {

    /** Outcome of trying to place a table. */
    public enum PlaceResult { OK, TOO_CLOSE, WORLD_LIMIT }

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
    /** Chunk key -> tables in it, so chunk load/unload isn't a scan of every table. */
    private final Map<Long, List<UnoTable>> byChunk = new HashMap<>();

    private final NamespacedKey itemKey;    // marks the placeable item with its type
    private final NamespacedKey idKey;       // tags spawned entities with their table id
    private final NamespacedKey seatKey;     // tags a seat interaction with its seat index
    private final NamespacedKey vehicleKey;  // tags the invisible seat mount
    private final File dataFile;

    private BusyCheck busyCheck = id -> false;

    /** A table we know about but can't build yet, because its world isn't loaded. */
    private record PendingTable(String key, String type, String world,
                                double x, double y, double z, double yaw) {}

    /**
     * Stool-cushion block. TODO(26.3): swap to {@code Material.CUSHION} — the new
     * sittable cushion (crafted from 3 wool slabs) that ships in the 26.3 drop; it
     * didn't exist in the API this was built against, so wool stands in for the look.
     */
    private static final Material SEAT_CUSHION = Material.RED_WOOL;

    /** Multiplier on the dealer's 1×2-block model. 1.0 → he stands 2 blocks tall. */
    private static final float DEALER_SCALE = 1.0f;

    /** Transparent gap under his tail: 3px of a 128px-tall sprite spanning 2 blocks. */
    private static final double DEALER_FOOT_GAP = 0.047;

    /** Players currently seated, keyed by player id. */
    private final Map<UUID, Seated> seated = new HashMap<>();

    private record Seated(UUID tableId, int index, UUID vehicleId) {}

    public TableManager(UnoPlugin plugin, Messages messages, Settings settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.itemKey = new NamespacedKey(plugin, "uno_table_item");
        this.idKey = new NamespacedKey(plugin, "uno_table_id");
        this.seatKey = new NamespacedKey(plugin, "uno_seat_index");
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
            String type = s.getString("type", "CASINO");
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
                + (pending.isEmpty() ? "" : " " + pending.size()
                + " waiting for their world to load (kept on file)."));
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

    /** Add to the registry, index it by chunk, and build it if its chunk is loaded. */
    private void register(UnoTable table) {
        tables.put(table.id(), table);
        indexChunk(table);
        Location a = table.anchor();
        World world = a.getWorld();
        if (world != null && world.isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) {
            spawnVisuals(table);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (UnoTable t : tables.values()) {
            Location a = t.anchor();
            String base = "tables." + t.id();
            yml.set(base + ".type", t.type().name());
            yml.set(base + ".world", a.getWorld().getName());
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
        for (Seated s : new ArrayList<>(seated.values())) {
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
        seated.clear();
        for (UnoTable t : tables.values()) {
            despawnVisuals(t);
        }
    }

    private UnoTable create(UnoTable.Type type, UUID id, Location anchor, float yaw) {
        return switch (type) {
            case CASINO -> new CasinoTable(id, anchor, yaw,
                    settings.casinoMinPlayers(), settings.casinoMaxPlayers());
        };
    }

    // ------------------------------------------------------------ place / remove

    /** Place a table on top of the clicked block, oriented to the placer's facing. */
    public PlaceResult placeTable(UnoTable.Type type, Block clicked, Player placer) {
        Location anchor = clicked.getLocation().add(0.5, 1.0, 0.5);
        int limit = settings.maxTablesPerWorld();
        if (limit > 0 && countIn(anchor.getWorld()) >= limit) {
            return PlaceResult.WORLD_LIMIT;
        }
        for (UnoTable other : tables.values()) {
            if (other.anchor().getWorld().equals(anchor.getWorld())
                    && other.anchor().distanceSquared(anchor) < 16) {
                return PlaceResult.TOO_CLOSE; // too close to an existing table
            }
        }
        float yaw = snap(placer.getLocation().getYaw());
        UnoTable table = create(type, UUID.randomUUID(), anchor, yaw);
        tables.put(table.id(), table);
        indexChunk(table);
        spawnVisuals(table);
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
            if (!t.anchor().getWorld().equals(player.getWorld())) {
                continue;
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
        despawnVisuals(table);
        unindexChunk(table);
        tables.remove(table.id());
        save();
    }

    private float snap(float yaw) {
        float snapped = Math.round(yaw / 90f) * 90f;
        return (snapped % 360f + 360f) % 360f;
    }

    // ------------------------------------------------------------- chunk index

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    private static long chunkKey(Location loc) {
        return chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private void indexChunk(UnoTable table) {
        byChunk.computeIfAbsent(chunkKey(table.anchor()), k -> new ArrayList<>()).add(table);
    }

    private void unindexChunk(UnoTable table) {
        long key = chunkKey(table.anchor());
        List<UnoTable> here = byChunk.get(key);
        if (here != null) {
            here.remove(table);
            if (here.isEmpty()) {
                byChunk.remove(key);
            }
        }
    }

    // ----------------------------------------------------------------- visuals

    private void spawnVisuals(UnoTable table) {
        if (table.isSpawned()) {
            return;
        }
        World w = table.anchor().getWorld();
        if (w == null) {
            return;
        }
        Location a = table.anchor();
        // Textured felt top (top surface ~0.75), held up by 4 legs.
        spawnTexturedSlab(table, w, a, "casino_table", 0.61);
        for (float lx : new float[]{-1.35f, 1.15f}) {
            for (float lz : new float[]{-1.35f, 1.15f}) {
                spawnBlock(table, w, a, Material.SPRUCE_LOG,
                        new Vector3f(0.2f, 0.53f, 0.2f), new Vector3f(lx, 0f, lz));
            }
        }
        for (int i = 0; i < table.seats().size(); i++) {
            Location seat = table.seats().get(i);
            // A stool: a cushion at sitting height plus a centre leg to the floor.
            spawnSeatCushion(table, w, seat);
            spawnBlock(table, w, seat, Material.SPRUCE_LOG,
                    new Vector3f(0.26f, 0.42f, 0.26f), new Vector3f(-0.13f, 0f, -0.13f));
            spawnSeatInteraction(table, w, seat, i);
        }
        if (settings.dealerEnabled()) {
            spawnDealer(table, w);
        }
        table.setSpawned(true);
    }

    /**
     * The Fish Dealer at the head of the table, facing the players.
     *
     * <p>His model is a flat 1×2-block panel carrying the sprite on both faces. An
     * ItemDisplay anchors the model's (8,8,8) point, so the panel's bottom edge lands
     * half a scaled block below the entity; the lift cancels that out, less the
     * transparent gap under his tail.
     */
    private void spawnDealer(UnoTable table, World w) {
        if (!(table instanceof CasinoTable casino)) {
            return;
        }
        Location loc = casino.dealerSpot().add(0, (0.5 - DEALER_FOOT_GAP) * DEALER_SCALE, 0);
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, id -> {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(new NamespacedKey("uno", "dealer"));
            item.setItemMeta(meta);
            id.setItemStack(item);
            id.setBillboard(Display.Billboard.FIXED);
            id.setBrightness(new Display.Brightness(15, 15));
            id.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f), new Quaternionf(),
                    new Vector3f(DEALER_SCALE, DEALER_SCALE, DEALER_SCALE), new Quaternionf()));
            id.setPersistent(false);
            id.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, table.id().toString());
        });
        table.entityIds().add(d.getUniqueId());
    }

    /**
     * The soft pad on top of a stool. A wool placeholder for now; TODO(26.3):
     * swap {@link #SEAT_CUSHION} to {@code Material.CUSHION} — the sittable cushion
     * (crafted from 3 wool slabs) that ships in the 26.3 drop — once it is stable.
     */
    private void spawnSeatCushion(UnoTable table, World w, Location seat) {
        spawnBlock(table, w, seat, SEAT_CUSHION,
                new Vector3f(0.8f, 0.16f, 0.8f), new Vector3f(-0.4f, 0.38f, -0.4f));
    }

    private void spawnSeatInteraction(UnoTable table, World w, Location seat, int index) {
        Interaction inter = w.spawn(seat, Interaction.class, in -> {
            in.setInteractionWidth(0.95f);
            in.setInteractionHeight(1.0f);
            in.setResponsive(true);
            in.setPersistent(false);
            in.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, table.id().toString());
            in.getPersistentDataContainer().set(seatKey, PersistentDataType.INTEGER, index);
        });
        table.entityIds().add(inter.getUniqueId());
    }

    /** The textured 3×3 casino felt surface, drawn as a scaled item-display. */
    private void spawnTexturedSlab(UnoTable table, World w, Location anchor, String model, double yOffset) {
        Location loc = anchor.clone().add(0, yOffset, 0); // model centre of the ~0.28-thick slab
        loc.setYaw(table.yaw());
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, id -> {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(new NamespacedKey("uno", model));
            item.setItemMeta(meta);
            id.setItemStack(item);
            id.setBillboard(Display.Billboard.FIXED);
            id.setBrightness(new Display.Brightness(15, 15));
            id.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f), new Quaternionf(),
                    new Vector3f(3f, 3f, 3f), new Quaternionf())); // 1-block model → 3×3 blocks
            id.setPersistent(false);
            id.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, table.id().toString());
        });
        table.entityIds().add(d.getUniqueId());
    }

    private void spawnBlock(UnoTable table, World w, Location loc, Material mat,
                            Vector3f scale, Vector3f translation) {
        BlockDisplay d = w.spawn(loc, BlockDisplay.class, bd -> {
            bd.setBlock(mat.createBlockData());
            bd.setTransformation(new Transformation(translation, new Quaternionf(), scale, new Quaternionf()));
            bd.setPersistent(false);
            bd.setBrightness(new Display.Brightness(15, 15));
            bd.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, table.id().toString());
        });
        table.entityIds().add(d.getUniqueId());
    }

    private void despawnVisuals(UnoTable table) {
        for (UUID eid : table.entityIds()) {
            Entity e = Bukkit.getEntity(eid);
            if (e != null) {
                e.eject(); // a seat mount may still be carrying someone
                e.remove();
            }
        }
        table.entityIds().clear();
        table.setSpawned(false);
    }

    // ------------------------------------------------------------------- items

    public ItemStack createTableItem(UnoTable.Type type) {
        ItemStack item = new ItemStack(Material.GREEN_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Casino Table", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click the ground to place.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(settings.casinoMinPlayers() + "-" + settings.casinoMaxPlayers()
                                + " players · has the Fish Dealer",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type.name());
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    public void giveTableItem(Player player, UnoTable.Type type) {
        player.getInventory().addItem(createTableItem(type));
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String typeName = item.getItemMeta().getPersistentDataContainer()
                .get(itemKey, PersistentDataType.STRING);
        if (typeName == null) {
            return;
        }

        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();
        UnoTable.Type type = UnoTable.Type.valueOf(typeName);

        switch (placeTable(type, clicked, player)) {
            case OK -> {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    item.setAmount(item.getAmount() - 1);
                }
                messages.send(player, "table.placed");
            }
            case TOO_CLOSE -> messages.send(player, "table.too-close");
            case WORLD_LIMIT -> messages.send(player, "table.world-limit",
                    "limit", settings.maxTablesPerWorld());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        List<UnoTable> here = byChunk.get(chunkKey(event.getChunk().getX(), event.getChunk().getZ()));
        if (here == null) {
            return;
        }
        for (UnoTable t : here) {
            if (!t.isSpawned() && event.getWorld().equals(t.anchor().getWorld())) {
                spawnVisuals(t);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        List<UnoTable> here = byChunk.get(chunkKey(event.getChunk().getX(), event.getChunk().getZ()));
        if (here == null) {
            return;
        }
        for (UnoTable t : here) {
            if (t.isSpawned() && event.getWorld().equals(t.anchor().getWorld())) {
                // Non-persistent entities unload with the chunk; reset so they respawn on reload.
                t.entityIds().clear();
                t.setSpawned(false);
            }
        }
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
        // Lift the rider up onto the stool cushion (cushion top ~0.54).
        double sitLift = 0.55;
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
    public void onSeatInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        var pdc = event.getRightClicked().getPersistentDataContainer();
        Integer index = pdc.get(seatKey, PersistentDataType.INTEGER);
        String tableId = pdc.get(idKey, PersistentDataType.STRING);
        if (index == null || tableId == null) {
            return;
        }
        event.setCancelled(true);
        UnoTable table = tables.get(UUID.fromString(tableId));
        if (table != null) {
            sit(event.getPlayer(), table, index);
        }
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
