package com.unoplugin.table;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Casino Table — with the Fish Dealer at the head (+forward). Six player seats are
 * arranged around the three non-dealer sides; the player limits come from config.yml
 * ({@code tables.casino.min-players} / {@code max-players}).
 */
public class CasinoTable extends UnoTable {

    private final int minPlayers;
    private final int maxPlayers;

    public CasinoTable(UUID id, Location anchor, float yaw, int minPlayers, int maxPlayers) {
        super(id, anchor, yaw);
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
    }

    @Override
    public Type type() {
        return Type.CASINO;
    }

    @Override
    public int minPlayers() {
        return Math.min(minPlayers, seatCount());
    }

    @Override
    public int maxPlayers() {
        // However high the config goes, you can't seat more players than there are stools.
        return Math.min(maxPlayers, seatCount());
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

    /** Where the Fish Dealer stands, facing the players. */
    public Location dealerSpot() {
        Location spot = seatAt(2.4, 0.0);
        spot.setYaw(yaw + 180f);
        return spot;
    }
}
