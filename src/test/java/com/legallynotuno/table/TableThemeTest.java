package com.legallynotuno.table;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
        // Read the real list rather than a copy of it: a set written out here goes stale the
        // moment a shipped theme is renamed, which is exactly the change this test guards.
        Set<String> builtIn = ThemeStore.builtIns().stream()
                .map(TableTheme::id)
                .collect(Collectors.toSet());
        String[] legacy = {
                "CASINO", "BLOSSOM", "CHERRY",
                "MIDNIGHT", "DARK_CHERRY", "DARKCHERRY",
                "TAVERN", "OAK", "SPRUCE",
                "HOMESTEAD", "BIRCH", "STRIPPED_OAK", "STRIPPEDOAK"};
        for (String name : legacy) {
            String id = ThemeStore.migrateLegacyId(name);
            assertTrue(builtIn.contains(id),
                    name + " migrated to '" + id + "', which is not a built-in theme");
        }
        assertTrue(builtIn.contains(ThemeStore.migrateLegacyId(null)),
                "the null default must name a theme that ships");
    }

    /**
     * The light/dark rename crosses over, so migrating on the name would swap the wood.
     *
     * <p>The palette that used to be called {@code cherry} ships as {@code lightcherry} and the
     * one called {@code spruce} ships as {@code darkspruce} — while both of those names are
     * still in use, for different woods. A legacy table has to follow its palette.
     */
    @Test
    void theLightDarkRenameFollowsThePalette() {
        assertEquals("lightcherry", ThemeStore.migrateLegacyId("CASINO"));
        assertEquals("lightcherry", ThemeStore.migrateLegacyId("CHERRY"));
        assertEquals("cherry", ThemeStore.migrateLegacyId("MIDNIGHT"));
        assertEquals("cherry", ThemeStore.migrateLegacyId("DARKCHERRY"));
        assertEquals("darkspruce", ThemeStore.migrateLegacyId("TAVERN"));
        assertEquals("darkspruce", ThemeStore.migrateLegacyId("OAK"));
        assertEquals("darkspruce", ThemeStore.migrateLegacyId("SPRUCE"));
        assertEquals("spruce", ThemeStore.migrateLegacyId("HOMESTEAD"));
        assertEquals("spruce", ThemeStore.migrateLegacyId("BIRCH"));
        assertEquals("spruce", ThemeStore.migrateLegacyId("STRIPPEDOAK"));
    }

    /** Every shipped theme is complete and uniquely named — the file is seeded straight from it. */
    @Test
    void builtInThemesAreWellFormed() {
        Set<String> seen = new HashSet<>();
        for (TableTheme theme : ThemeStore.builtIns()) {
            assertTrue(seen.add(theme.id()), "duplicate built-in theme id: " + theme.id());
            assertEquals(theme.id(), TableTheme.normaliseId(theme.id()),
                    theme.id() + " is not a normalised id, so /uno give could not name it");
            assertTrue(theme.builtIn(), theme.id() + " must be marked built-in");
            for (int r = 0; r < TableTheme.SIZE; r++) {
                for (int c = 0; c < TableTheme.SIZE; c++) {
                    assertNotNull(theme.cell(r, c), theme.id() + " has a hole at " + r + "," + c);
                }
            }
            for (TableTheme.Seat seat : TableTheme.Seat.values()) {
                assertNotNull(theme.seat(seat), theme.id() + " has no " + seat.key() + " seat");
            }
        }
    }

    /** A name written by a build we've never seen passes through rather than being dropped. */
    @Test
    void unknownNamesPassThroughNormalised() {
        assertEquals("someones_theme", ThemeStore.migrateLegacyId("Someones_Theme"));
        assertEquals("cherry", ThemeStore.migrateLegacyId(null), "null must still be placeable");
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
