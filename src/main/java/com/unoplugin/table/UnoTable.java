package com.unoplugin.table;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base class for a placed UNO table.
 *
 * <p>Holds the logical/persisted state (id, anchor, facing) and computes seat
 * positions from simple table-space math. Subclasses define player limits and
 * seat layout. Visual Display Entities are owned by {@link TableManager}.
 */
public abstract class UnoTable {

    public enum Type { CASINO }

    /**
     * Height of the felt surface above {@link #anchor}. Anything that sits ON the table —
     * the card piles, the pot — measures from here, so it lives in one place rather than
     * being re-typed as a magic 0.757 in each renderer.
     */
    public static final double SURFACE_Y = 0.757;

    protected final UUID id;
    /** Table centre: clicked-block top, +0.5 on X/Z. Surfaces and seats derive from this. */
    protected final Location anchor;
    /** Facing, snapped to the nearest 90 degrees. Casino dealer sits at +forward. */
    protected final float yaw;
    protected final List<Location> seats = new ArrayList<>();
    /** Per-seat occupant (null = empty). Sized once seats are computed. */
    private final UUID[] occupants;

    // --- runtime visual state (never persisted) ---
    private final List<UUID> entityIds = new ArrayList<>();
    private boolean spawned = false;

    protected UnoTable(UUID id, Location anchor, float yaw) {
        this.id = id;
        this.anchor = anchor;
        this.yaw = yaw;
        computeSeats();
        this.occupants = new UUID[seats.size()];
    }

    public abstract Type type();

    public abstract int minPlayers();

    public abstract int maxPlayers();

    /** Populate {@link #seats} from {@link #anchor} and {@link #yaw}. */
    protected abstract void computeSeats();

    // --- accessors ---

    public UUID id() { return id; }

    public Location anchor() { return anchor.clone(); }

    public float yaw() { return yaw; }

    public List<Location> seats() { return seats; }

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
     * Build a seat location offset by {@code fwd} (toward dealer) and {@code rgt}
     * in table space, standing on the table's ground plane and facing the centre.
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

    /**
     * Same position as {@link #seatAt}, but with a fixed facing of {@code yaw + yawOffset}
     * (so a row of seats all face straight at the table, not angled toward the centre).
     */
    protected Location seatFacing(double fwd, double rgt, float yawOffset) {
        Location seat = seatAt(fwd, rgt);
        seat.setYaw(yaw + yawOffset);
        return seat;
    }
}
