package com.unoplugin.table;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A placed UNO table.
 *
 * <p>Holds the logical/persisted state (id, theme, anchor, facing) and computes seat
 * positions from simple table-space math. The blocks it is built from are set into the
 * world by {@link TableManager}, from the {@link TableTheme} named here.
 *
 * <p><strong>There is no such thing as a casino table.</strong> There used to be a {@code Kind}
 * here, and a table was built as one thing or the other for good. That put the decision in
 * the wrong place and at the wrong time: whether this hand is being played for stakes is
 * something the four people sitting down decide, in the ten seconds before it is dealt, not
 * something an admin fixes when they place the furniture. Every table now plays both, and
 * the {@link TableMat} is what says which one is happening right now.
 *
 * <p>Every table is the same shape — a 3×3 block top with a stair pulled up to the middle of
 * each side — and differs only in the blocks it is made of. Those used to be four materials
 * on an enum constant, which meant a new look needed a new build; they are now a theme id
 * resolved against {@link ThemeStore}, so a server writes its own.
 */
public class UnoTable {

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
    /**
     * Which theme this table wears, by id.
     *
     * <p>An id rather than the resolved {@link TableTheme}, because themes.yml is re-read on
     * {@code /uno reload} and an edited theme has to reach the tables already standing. Held
     * by value, a retheme would only show on tables placed afterwards.
     */
    private final String themeId;
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

    public UnoTable(UUID id, String themeId, Location anchor, float yaw,
                    int minPlayers, int maxPlayers) {
        this.id = id;
        this.themeId = themeId;
        this.anchor = anchor;
        this.yaw = yaw;
        this.worldName = anchor.getWorld() == null ? null : anchor.getWorld().getName();
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        computeSeats();
        this.occupants = new UUID[seats.size()];
    }

    /** The id of the theme this table wears — resolved against {@link ThemeStore} on demand. */
    public String themeId() { return themeId; }

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
     * placer was standing on. That order is the same one {@link TableTheme.Seat} declares,
     * and the two are indexed against each other when a table is built — keep them in step.
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
