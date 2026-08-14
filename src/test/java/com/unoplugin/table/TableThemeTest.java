package com.unoplugin.table;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The theme layer's pure logic: seat ordering, id normalisation and the legacy migration.
 *
 * <p>None of this needs a server, and all of it is load-bearing in a way that fails quietly.
 * A seat order that drifts doesn't throw — it builds every table with its chairs made of the
 * wrong blocks.
 */
class TableThemeTest {

    /**
     * {@link TableTheme.Seat} is indexed against {@code UnoTable.computeSeats()} when a table
     * is built, so its order IS the seat order. Reordering the enum silently swaps which block
     * each chair is made from, on every table, with nothing to notice it.
     */
    @Test
    void seatOrderMatchesTheTableSeatOrder() {
        TableTheme.Seat[] seats = TableTheme.Seat.values();
        assertEquals(4, seats.length, "a table has exactly four stairs");
        assertEquals(TableTheme.Seat.NEAR, seats[0], "seat 0 is the near side");
        assertEquals(TableTheme.Seat.FAR, seats[1]);
        assertEquals(TableTheme.Seat.LEFT, seats[2]);
        assertEquals(TableTheme.Seat.RIGHT, seats[3]);
    }

    @Test
    void seatKeysAreUniqueAndResolvable() {
        Set<String> seen = new HashSet<>();
        for (TableTheme.Seat seat : TableTheme.Seat.values()) {
            String key = seat.key();
            assertEquals(key.toLowerCase(java.util.Locale.ROOT), key, key + " is not lower case");
            assertTrue(seen.add(key), "duplicate seat key " + key);
            assertEquals(seat, TableTheme.Seat.byKey(key));
            assertEquals(seat, TableTheme.Seat.byKey(key.toUpperCase(java.util.Locale.ROOT)),
                    "byKey is case sensitive for " + key);
        }
        assertNull(TableTheme.Seat.byKey("nosuchseat"));
        assertNull(TableTheme.Seat.byKey(null));
    }

    /** Theme ids are typed into commands, so they must normalise to something typable. */
    @Test
    void idsNormaliseToLowercaseWithoutSpaces() {
        assertEquals("dark_cherry", TableTheme.normaliseId("Dark Cherry"));
        assertEquals("marble", TableTheme.normaliseId("  MARBLE  "));
        assertEquals("", TableTheme.normaliseId(null));
    }

    /**
     * Every name any earlier build wrote into tables.yml has to land on a real theme.
     *
     * <p>A row that migrates to an id no theme has renders as the fallback theme, so a missed
     * mapping doesn't crash — it silently retextures somebody's table.
     */
    @Test
    void legacyTypeNamesMapOntoBuiltInThemes() {
        Set<String> builtIn = Set.of("cherry", "darkcherry", "spruce", "strippedoak");
        String[] legacy = {
                "CASINO", "BLOSSOM", "CHERRY",
                "MIDNIGHT", "DARK_CHERRY",
                "TAVERN", "OAK", "SPRUCE",
                "HOMESTEAD", "BIRCH", "STRIPPED_OAK"};
        for (String name : legacy) {
            String id = ThemeStore.migrateLegacyId(name);
            assertTrue(builtIn.contains(id),
                    name + " migrated to '" + id + "', which is not a built-in theme");
        }
    }

    /** The oak/spruce pair was mis-built once; both old names must land on the woods meant. */
    @Test
    void theOakSpruceRenameIsPreserved() {
        assertEquals("spruce", ThemeStore.migrateLegacyId("TAVERN"));
        assertEquals("spruce", ThemeStore.migrateLegacyId("OAK"));
        assertEquals("strippedoak", ThemeStore.migrateLegacyId("HOMESTEAD"));
        assertEquals("strippedoak", ThemeStore.migrateLegacyId("BIRCH"));
    }

    /** A name written by a build we've never seen passes through rather than being dropped. */
    @Test
    void unknownNamesPassThroughNormalised() {
        assertEquals("someones_theme", ThemeStore.migrateLegacyId("Someones_Theme"));
        assertEquals("cherry", ThemeStore.migrateLegacyId(null), "null must still be placeable");
    }

    /** Table kinds are typed into /uno give, so they resolve the same way seat keys do. */
    @Test
    void tableKindsResolveCaseInsensitively() {
        assertEquals(UnoTable.Kind.CASUAL, UnoTable.Kind.byKey("casual"));
        assertEquals(UnoTable.Kind.CASUAL, UnoTable.Kind.byKey("CASUAL"));
        assertEquals(UnoTable.Kind.CASINO, UnoTable.Kind.byKey("Casino"));
        assertNull(UnoTable.Kind.byKey("roulette"));
        assertNull(UnoTable.Kind.byKey(null));
    }

    /** A theme's grid is addressed by row/column all through the builder and the editor. */
    @Test
    void gridIsAddressableAndTheRightSize() {
        BlockSpec[][] grid = new BlockSpec[TableTheme.SIZE][TableTheme.SIZE];
        for (int r = 0; r < TableTheme.SIZE; r++) {
            for (int c = 0; c < TableTheme.SIZE; c++) {
                grid[r][c] = BlockSpec.parse("minecraft:stone_" + r + c);
            }
        }
        BlockSpec[] seats = new BlockSpec[TableTheme.Seat.values().length];
        for (TableTheme.Seat s : TableTheme.Seat.values()) {
            seats[s.ordinal()] = BlockSpec.parse("minecraft:oak_stairs");
        }
        TableTheme theme = new TableTheme("t", "T", grid, seats, false);

        assertEquals(3, TableTheme.SIZE, "the editor and themes.yml both assume 3x3");
        assertEquals("stone_00", theme.cell(0, 0).id());
        assertEquals("stone_22", theme.cell(2, 2).id());
        assertEquals("stone_02", theme.cell(0, 2).id());
        for (TableTheme.Seat s : TableTheme.Seat.values()) {
            assertNotNull(theme.seat(s), s + " has no block");
        }
    }
}
