package com.unoplugin.table;

import com.unoplugin.util.Settings;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table variants, checked without a server.
 *
 * <p>{@link UnoTable.Type} is a plain enum over {@link Material} constants, so everything it
 * promises can be asserted here rather than found by placing one and looking at it.
 */
class TablePaletteTest {

    /**
     * A variant's four materials must collapse into a set.
     *
     * <p>Every variant deliberately uses the same wood for its frame as for its top, and
     * {@code TableManager.clearBlocks} gathers the palette into a set to decide which blocks
     * belong to the table. Built with {@code Set.of} that threw
     * {@code IllegalArgumentException: duplicate element} on the way into removing <em>any</em>
     * table. This is that bug.
     */
    @Test
    void everyPaletteCollapsesIntoASet() {
        for (UnoTable.Type type : UnoTable.Type.values()) {
            // Through the production helper, not a local EnumSet — testing the latter would
            // pass no matter what clearBlocks actually does.
            Settings.TableBlocks blocks = new Settings.TableBlocks(
                    type.topPrimary(), type.topSecondary(), type.frame(), type.seat());
            Set<Material> palette = blocks.materials();
            assertFalse(palette.isEmpty(), type + " has an empty palette");
            assertTrue(palette.contains(type.seat()), type + " palette lost its seat");
            assertTrue(palette.contains(type.topSecondary()), type + " palette lost its edges");
        }
    }

    /** The duplicate that broke it: frame and top are the same wood on every variant. */
    @Test
    void frameSharesTheTopMaterial() {
        for (UnoTable.Type type : UnoTable.Type.values()) {
            assertEquals(type.topPrimary(), type.frame(),
                    type + " no longer shares frame with top — check clearBlocks still holds");
        }
    }

    /** The two top colours have to differ, or the chequer isn't a chequer. */
    @Test
    void topColoursDiffer() {
        for (UnoTable.Type type : UnoTable.Type.values()) {
            assertNotEquals(type.topPrimary(), type.topSecondary(),
                    type + " would render as a plain slab");
        }
    }

    /**
     * The three top materials have to be logs.
     *
     * <p>Asserted on the name rather than with {@link Material#isBlock()}, which reaches into
     * Paper's registry and needs a running server. The top is laid ring-face up by setting
     * {@code Orientable.axis}, so a material without that property would silently come out
     * facing the wrong way instead of failing.
     */
    @Test
    void topMaterialsAreLogs() {
        for (UnoTable.Type type : UnoTable.Type.values()) {
            for (Material m : new Material[]{
                    type.topPrimary(), type.topSecondary(), type.frame()}) {
                assertTrue(m.name().endsWith("_LOG") || m.name().endsWith("_STEM"),
                        type + " top material " + m + " is not a log");
            }
        }
    }

    /** Seats are stairs: the sitter's backrest is the stair's full-height half. */
    @Test
    void seatsAreStairs() {
        for (UnoTable.Type type : UnoTable.Type.values()) {
            assertTrue(type.seat().name().endsWith("_STAIRS"),
                    type + " seat " + type.seat() + " is not a stairs block");
        }
    }

    /** Aliases are what players type, so they must be unique and lower case. */
    @Test
    void aliasesAreUniqueAndTypable() {
        Set<String> seen = new HashSet<>();
        for (UnoTable.Type type : UnoTable.Type.values()) {
            String alias = type.alias();
            assertTrue(alias.equals(alias.toLowerCase(java.util.Locale.ROOT)),
                    alias + " is not lower case");
            assertTrue(seen.add(alias), "duplicate alias " + alias);
            assertEquals(type, UnoTable.Type.byAlias(alias), "byAlias lost " + alias);
            assertEquals(type, UnoTable.Type.byAlias(alias.toUpperCase(java.util.Locale.ROOT)),
                    "byAlias is case sensitive for " + alias);
        }
        assertNull(UnoTable.Type.byAlias("nosuchtable"));
        assertNull(UnoTable.Type.byAlias(null));
    }

    private static void assertNull(Object o) {
        assertTrue(o == null, "expected null, got " + o);
    }

    /** Display names are shown to players, so none may be blank. */
    @Test
    void displayNamesArePresent() {
        for (UnoTable.Type type : UnoTable.Type.values()) {
            assertNotNull(type.displayName());
            assertFalse(type.displayName().isBlank(), type + " has a blank display name");
        }
    }
}
