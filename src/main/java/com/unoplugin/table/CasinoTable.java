package com.unoplugin.table;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Casino Table — 4 to 6 players, with the Fish Dealer at the head (+forward).
 * Six player seats are arranged around the three non-dealer sides.
 */
public class CasinoTable extends UnoTable {

    public CasinoTable(UUID id, Location anchor, float yaw) {
        super(id, anchor, yaw);
    }

    @Override
    public Type type() {
        return Type.CASINO;
    }

    @Override
    public int minPlayers() {
        return 4;
    }

    @Override
    public int maxPlayers() {
        return 6;
    }

    @Override
    protected void computeSeats() {
        // Chairs sit straight against three sides (dealer side stays open), facing the table head-on.
        // Bottom row (near side) — face forward toward the table.
        seats.add(seatFacing(-2.0, -0.7, 0f));
        seats.add(seatFacing(-2.0, 0.7, 0f));
        // Left side — face right.
        seats.add(seatFacing(-0.7, -2.0, -90f));
        seats.add(seatFacing(0.7, -2.0, -90f));
        // Right side — face left.
        seats.add(seatFacing(-0.7, 2.0, 90f));
        seats.add(seatFacing(0.7, 2.0, 90f));
    }

    /** Where the Fish Dealer stands (used from build step 7), facing the players. */
    public Location dealerSpot() {
        Location spot = seatAt(2.4, 0.0);
        spot.setYaw(yaw + 180f);
        return spot;
    }
}
