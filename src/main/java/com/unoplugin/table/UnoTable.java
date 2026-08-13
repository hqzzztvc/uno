package com.unoplugin.table;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A placed UNO table.
 *
 * <p>Holds the logical/persisted state (id, variant, anchor, facing) and computes seat
 * positions from simple table-space math. The blocks it is built from are set into the
 * world by {@link TableManager}.
 *
 * <p>All four variants are the same shape — a 3×3 block top with a stair pulled up to the
 * middle of each side — and differ only in their block palette, so there is one concrete
 * class and the palette lives in {@link Type}. Adding a fifth variant is one enum constant.
 */
public class UnoTable {

    /**
     * The four table variants. Each is a block palette over the shared 3×3-plus-four-stairs
     * shape; {@code alias} is what players type after {@code /uno createtable}.
     *
     * <p>The materials here are only the DEFAULTS — {@code tables.<alias>.blocks.*} in
     * config.yml overrides any of them, so a server can retheme a table without a rebuild
     * (see {@link com.unoplugin.util.Settings#tableBlocks}).
     */
    public enum Type {
        /** All-pink: stripped cherry keeps its colour on the sides as well as the rings. */
        CHERRY("cherry", "Cherry Table",
                Material.STRIPPED_CHERRY_LOG, Material.STRIPPED_PALE_OAK_LOG,
                Material.STRIPPED_CHERRY_LOG, Material.CHERRY_STAIRS),
        /** Pink rings, dark sides — unstripped cherry bark is the dark frame. */
        DARK_CHERRY("darkcherry", "Dark Cherry Table",
                Material.CHERRY_LOG, Material.PALE_OAK_LOG,
                Material.CHERRY_LOG, Material.PALE_OAK_STAIRS),
        /** The dark pair: unstripped spruce and oak, dark oak seats. */
        SPRUCE("spruce", "Spruce Table",
                Material.SPRUCE_LOG, Material.OAK_LOG,
                Material.SPRUCE_LOG, Material.DARK_OAK_STAIRS),
        /** The same two woods stripped — spruce on the corners and centre, oak on the edges. */
        STRIPPED_OAK("strippedoak", "Stripped Oak Table",
                Material.STRIPPED_SPRUCE_LOG, Material.STRIPPED_OAK_LOG,
                Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_STAIRS);

        private final String alias;
        private final String displayName;
        private final Material topPrimary;
        private final Material topSecondary;
        private final Material frame;
        private final Material seat;

        Type(String alias, String displayName,
             Material topPrimary, Material topSecondary, Material frame, Material seat) {
            this.alias = alias;
            this.displayName = displayName;
            this.topPrimary = topPrimary;
            this.topSecondary = topSecondary;
            this.frame = frame;
            this.seat = seat;
        }

        public String alias() { return alias; }

        public String displayName() { return displayName; }

        /** Chequer colour on the corners and the centre of the 3×3 top. */
        public Material topPrimary() { return topPrimary; }

        /** Chequer colour on the four edge tiles of the 3×3 top. */
        public Material topSecondary() { return topSecondary; }

        /** Legacy body colour; unused by the single-layer top, kept so removal still knows it. */
        public Material frame() { return frame; }

        /** The stair block players sit on. */
        public Material seat() { return seat; }

        /** Resolve a player-typed name ({@code blossom}, {@code MIDNIGHT}) to a variant. */
        public static Type byAlias(String raw) {
            if (raw == null) {
                return null;
            }
            String needle = raw.toLowerCase(Locale.ROOT);
            for (Type t : values()) {
                if (t.alias.equals(needle) || t.name().toLowerCase(Locale.ROOT).equals(needle)) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * Height of the table surface above {@link #anchor}. Anything that sits ON the table —
     * the card piles, the pot — measures from here, so it lives in one place rather than
     * being re-typed as a magic number in each renderer.
     *
     * <p>The table is one layer of blocks, so the playable surface is exactly one block
     * above the ground it was built on.
     */
    public static final double SURFACE_Y = 1.0;

    /** Distance from the table centre to a seat, in blocks — one clear of the 3×3 top. */
    private static final double SEAT_REACH = 2.0;

    private final UUID id;
    private final Type variant;
    /** Table centre: clicked-block top, +0.5 on X/Z. Surfaces and seats derive from this. */
    protected final Location anchor;
    /** Facing, snapped to the nearest 90 degrees. */
    protected final float yaw;
    /**
     * The anchor's world, captured by NAME at construction.
     *
     * <p>{@code anchor.getWorld()} goes null once that world is unloaded at runtime, so
     * persistence must not depend on it: a table is only ever built while its world is up,
     * and the name is what {@code tables.yml} stores anyway.
     */
    private final String worldName;
    protected final List<Location> seats = new ArrayList<>();
    /** Per-seat occupant (null = empty). Sized once seats are computed. */
    private final UUID[] occupants;

    private final int minPlayers;
    private final int maxPlayers;

    // --- runtime visual state (never persisted) ---
    private final List<UUID> entityIds = new ArrayList<>();
    private boolean spawned = false;

    public UnoTable(UUID id, Type variant, Location anchor, float yaw, int minPlayers, int maxPlayers) {
        this.id = id;
        this.variant = variant;
        this.anchor = anchor;
        this.yaw = yaw;
        this.worldName = anchor.getWorld() == null ? null : anchor.getWorld().getName();
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        computeSeats();
        this.occupants = new UUID[seats.size()];
    }

    public Type type() { return variant; }

    public int minPlayers() {
        return Math.min(minPlayers, seatCount());
    }

    /** However high the config goes, you can't seat more players than there are stairs. */
    public int maxPlayers() {
        return Math.min(maxPlayers, seatCount());
    }

    /**
     * One stair pulled up to the middle of each of the four sides, each facing the centre.
     *
     * <p>Seat order is near, far, left, right in table space, so seat 0 is the side the
     * placer was standing on.
     */
    private void computeSeats() {
        seats.add(seatAt(-SEAT_REACH, 0.0));
        seats.add(seatAt(SEAT_REACH, 0.0));
        seats.add(seatAt(0.0, -SEAT_REACH));
        seats.add(seatAt(0.0, SEAT_REACH));
    }

    // --- accessors ---

    public UUID id() { return id; }

    public Location anchor() { return anchor.clone(); }

    /** Name of the world this table was built in — survives that world being unloaded. */
    public String worldName() { return worldName; }

    public float yaw() { return yaw; }

    /**
     * The four seat positions, as copies.
     *
     * <p>Copies because a {@link Location} is mutable and {@code add()} returns the same object
     * it just changed: one caller doing {@code table.seats().get(0).add(0, 1, 0)} would move
     * the seat itself, permanently and invisibly, for every later build and repair.
     */
    public List<Location> seats() {
        List<Location> copies = new ArrayList<>(seats.size());
        for (Location seat : seats) {
            copies.add(seat.clone());
        }
        return copies;
    }

    public int seatCount() { return seats.size(); }

    public List<UUID> entityIds() { return entityIds; }

    public boolean isSpawned() { return spawned; }

    public void setSpawned(boolean spawned) { this.spawned = spawned; }

    // --- seat occupancy ---

    public int firstFreeSeat() {
        for (int i = 0; i < occupants.length; i++) {
            if (occupants[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public boolean isSeatFree(int index) {
        return index >= 0 && index < occupants.length && occupants[index] == null;
    }

    public void setOccupant(int index, UUID player) {
        if (index >= 0 && index < occupants.length) {
            occupants[index] = player;
        }
    }

    public void clearOccupant(int index) {
        if (index >= 0 && index < occupants.length) {
            occupants[index] = null;
        }
    }

    public int seatOf(UUID player) {
        for (int i = 0; i < occupants.length; i++) {
            if (player.equals(occupants[i])) {
                return i;
            }
        }
        return -1;
    }

    public int occupiedCount() {
        int n = 0;
        for (UUID u : occupants) {
            if (u != null) {
                n++;
            }
        }
        return n;
    }

    // --- shared seat geometry ---

    /** Table-facing unit vector (x, z) derived from yaw. */
    private double[] forward() {
        double r = Math.toRadians(yaw);
        return new double[]{ -Math.sin(r), Math.cos(r) };
    }

    /** Right-hand unit vector (x, z) derived from yaw. */
    private double[] right() {
        double r = Math.toRadians(yaw);
        return new double[]{ Math.cos(r), Math.sin(r) };
    }

    /**
     * Build a seat location offset by {@code fwd} and {@code rgt} in table space, standing
     * on the table's ground plane and facing the centre.
     */
    protected Location seatAt(double fwd, double rgt) {
        double[] f = forward();
        double[] g = right();
        double x = anchor.getX() + f[0] * fwd + g[0] * rgt;
        double z = anchor.getZ() + f[1] * fwd + g[1] * rgt;
        Location seat = new Location(anchor.getWorld(), x, anchor.getY(), z);
        double dx = anchor.getX() - x;
        double dz = anchor.getZ() - z;
        seat.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        seat.setPitch(0f);
        return seat;
    }
}
