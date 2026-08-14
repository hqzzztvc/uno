package com.unoplugin.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the written block form, checked without a server.
 *
 * <p>Only the PARSE half is testable here: resolving a spec to real block data goes through
 * {@code Bukkit.createBlockData}, which needs a running server. Parsing is where the format a
 * server admin types by hand is interpreted, so it is also where a mistake is silent — a spec
 * that splits wrong doesn't throw, it quietly builds the wrong table.
 */
class BlockSpecTest {

    @Test
    void bareMaterialGetsTheDefaultNamespace() {
        BlockSpec spec = BlockSpec.parse("CHERRY_LOG");
        assertEquals("minecraft", spec.namespace());
        assertEquals("cherry_log", spec.id());
        assertNull(spec.state());
        assertNull(spec.fallback());
    }

    @Test
    void namespaceAndIdSplitOnTheFirstColon() {
        BlockSpec spec = BlockSpec.parse("itemsadder:marble_pillar");
        assertEquals("itemsadder", spec.namespace());
        assertEquals("marble_pillar", spec.id());
    }

    /** The block state has to survive parsing, or every log comes out lying on its side. */
    @Test
    void blockStateIsKeptWholeAndOffTheId() {
        BlockSpec spec = BlockSpec.parse("minecraft:cherry_log[axis=y]");
        assertEquals("cherry_log", spec.id(), "the state leaked into the id");
        assertEquals("[axis=y]", spec.state());
    }

    @Test
    void multiPropertyStateIsKeptWhole() {
        BlockSpec spec = BlockSpec.parse("minecraft:oak_stairs[facing=north,half=bottom]");
        assertEquals("oak_stairs", spec.id());
        assertEquals("[facing=north,half=bottom]", spec.state());
    }

    /**
     * The fallback is what keeps a custom-block theme placeable on a server without that
     * plugin. If the pipe stopped splitting, those themes would resolve to nothing and leave
     * holes in the table top.
     */
    @Test
    void pipeSplitsOffTheFallback() {
        BlockSpec spec = BlockSpec.parse("nexo:casino_felt|minecraft:green_wool");
        assertEquals("nexo", spec.namespace());
        assertEquals("casino_felt", spec.id());
        assertNotNull(spec.fallback(), "lost the fallback");
        assertEquals("minecraft", spec.fallback().namespace());
        assertEquals("green_wool", spec.fallback().id());
    }

    @Test
    void fallbackKeepsItsOwnBlockState() {
        BlockSpec spec = BlockSpec.parse("oraxen:felt | minecraft:cherry_log[axis=y]");
        assertEquals("felt", spec.id());
        assertEquals("[axis=y]", spec.fallback().state());
    }

    /** Whitespace around a hand-written entry must not become part of the id. */
    @Test
    void surroundingSpaceIsTrimmed() {
        BlockSpec spec = BlockSpec.parse("  minecraft:stone  ");
        assertEquals("stone", spec.id());
    }

    /** Ids are matched case-insensitively, because config files are typed by people. */
    @Test
    void idAndNamespaceAreLowercased() {
        BlockSpec spec = BlockSpec.parse("ItemsAdder:Marble_Pillar");
        assertEquals("itemsadder", spec.namespace());
        assertEquals("marble_pillar", spec.id());
    }

    /**
     * A blank or null entry must still parse.
     *
     * <p>It resolves to the last-resort block rather than null, because a null here surfaces
     * as an NPE inside the chunk-load handler that rebuilds every table in range.
     */
    @Test
    void blankParsesToSomethingPlaceable() {
        for (String raw : new String[]{null, "", "   "}) {
            BlockSpec spec = BlockSpec.parse(raw);
            assertNotNull(spec, "null spec for input " + raw);
            assertNotNull(spec.id());
            assertTrue(!spec.id().isBlank(), "blank id for input " + raw);
        }
    }

    /** What goes back into themes.yml has to round-trip through the parser unchanged. */
    @Test
    void writtenFormRoundTrips() {
        for (String raw : new String[]{
                "minecraft:cherry_log[axis=y]",
                "itemsadder:marble|minecraft:quartz_block",
                "minecraft:stone"}) {
            assertEquals(raw, BlockSpec.parse(raw).written(),
                    "themes.yml would be rewritten differently from how it was read");
        }
    }
}
