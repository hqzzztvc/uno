package com.unoplugin.table;

import com.unoplugin.UnoPlugin;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every placed table: the registry, persistence, the placeable item,
 * right-click placement, removal, and chunk-aware visual spawning.
 */
public class TableManager implements Listener {

    private final UnoPlugin plugin;
    private final Map<UUID, UnoTable> tables = new HashMap<>();
    private final NamespacedKey itemKey;    // marks the placeable item with its type
    private final NamespacedKey idKey;       // tags spawned entities with their table id
    private final NamespacedKey seatKey;     // tags a seat interaction with its seat index
    private final NamespacedKey vehicleKey;  // tags the invisible seat mount
    private final File dataFile;

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

    public TableManager(UnoPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "uno_table_item");
        this.idKey = new NamespacedKey(plugin, "uno_table_id");
        this.seatKey = new NamespacedKey(plugin, "uno_seat_index");
        this.vehicleKey = new NamespacedKey(plugin, "uno_seat_vehicle");
        this.dataFile = new File(plugin.getDataFolder(), "tables.yml");
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
            try {
                UUID id = UUID.fromString(key);
                UnoTable.Type type = UnoTable.Type.valueOf(s.getString("type", "CASINO"));
                World world = Bukkit.getWorld(s.getString("world", ""));
                if (world == null) {
                    plugin.getLogger().warning("Skipping table " + key
                            + " (world '" + s.getString("world") + "' not loaded).");
                    continue;
                }
                Location anchor = new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
                float yaw = (float) s.getDouble("yaw");
                UnoTable table = create(type, id, anchor, yaw);
                tables.put(id, table);
                if (world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
                    spawnVisuals(table);
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Bad table entry '" + key + "': " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + tables.size() + " UNO table(s).");
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
            case CASINO -> new CasinoTable(id, anchor, yaw);
        };
    }

    // ------------------------------------------------------------ place / remove

    /** Place a table on top of the clicked block, oriented to the placer's facing. */
    public boolean placeTable(UnoTable.Type type, Block clicked, Player placer) {
        Location anchor = clicked.getLocation().add(0.5, 1.0, 0.5);
        for (UnoTable other : tables.values()) {
            if (other.anchor().getWorld().equals(anchor.getWorld())
                    && other.anchor().distanceSquared(anchor) < 16) {
                return false; // too close to an existing table
            }
        }
        float yaw = snap(placer.getLocation().getYaw());
        UnoTable table = create(type, UUID.randomUUID(), anchor, yaw);
        tables.put(table.id(), table);
        spawnVisuals(table);
        save();
        return true;
    }

    /** Remove the nearest table within {@code radius} of the player. */
    public UnoTable removeNearest(Player player, double radius) {
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
        if (nearest != null) {
            despawnVisuals(nearest);
            tables.remove(nearest.id());
            save();
        }
        return nearest;
    }

    private float snap(float yaw) {
        float snapped = Math.round(yaw / 90f) * 90f;
        return (snapped % 360f + 360f) % 360f;
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
        spawnDealer(table, w);
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
                Component.text("4-6 players · has the Fish Dealer",
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

        if (placeTable(type, clicked, player)) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                item.setAmount(item.getAmount() - 1);
            }
            player.sendMessage("§aCasino Table placed. §7(Sitting comes in the next build step.)");
        } else {
            player.sendMessage("§cToo close to another table — give it more room.");
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (UnoTable t : tables.values()) {
            if (t.isSpawned()) {
                continue;
            }
            Location a = t.anchor();
            if (a.getWorld().equals(event.getWorld())
                    && (a.getBlockX() >> 4) == event.getChunk().getX()
                    && (a.getBlockZ() >> 4) == event.getChunk().getZ()) {
                spawnVisuals(t);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (UnoTable t : tables.values()) {
            if (!t.isSpawned()) {
                continue;
            }
            Location a = t.anchor();
            if (a.getWorld().equals(event.getWorld())
                    && (a.getBlockX() >> 4) == event.getChunk().getX()
                    && (a.getBlockZ() >> 4) == event.getChunk().getZ()) {
                // Non-persistent entities unload with the chunk; reset so they respawn on reload.
                t.entityIds().clear();
                t.setSpawned(false);
            }
        }
    }

    public Collection<UnoTable> tables() {
        return tables.values();
    }

    // -------------------------------------------------------------------- seats

    private void sit(Player player, UnoTable table, int index) {
        if (seated.containsKey(player.getUniqueId())) {
            player.sendMessage("§eYou're already seated. Press §6Shift §eto leave first.");
            return;
        }
        if (!table.isSeatFree(index)) {
            player.sendMessage("§cThat seat is taken.");
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
        });
        player.teleport(seat); // align facing toward the table centre
        mount.addPassenger(player);
        table.setOccupant(index, player.getUniqueId());
        seated.put(player.getUniqueId(), new Seated(table.id(), index, mount.getUniqueId()));

        player.sendMessage("§aYou sit down at the Casino Table. §7Press §6Shift §7to leave.");
    }

    private void leaveSeat(Player player) {
        Seated s = seated.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        UnoTable table = tables.get(s.tableId());
        if (table != null) {
            table.clearOccupant(s.index());
        }
        UUID vehicleId = s.vehicleId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Entity vehicle = Bukkit.getEntity(vehicleId);
            if (vehicle != null) {
                vehicle.remove();
            }
        });
        player.sendMessage("§7You leave the table.");
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
