package com.unoplugin.table;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.Locale;

/**
 * One entry in a theme: which block goes in this cell of the table.
 *
 * <p>The written form is a block id, optionally with a vanilla fallback after a pipe:
 *
 * <pre>
 *   minecraft:cherry_log[axis=y]      a vanilla block, with its block state
 *   CHERRY_LOG                        a bare material name (what older configs wrote)
 *   itemsadder:marble_tile|minecraft:quartz_block
 *                                     a custom block, and what to lay if it isn't available
 * </pre>
 *
 * <p>The fallback is the whole reason a theme built around a custom-block plugin still works
 * on a server that doesn't run one. Without it a missing id leaves a hole in the middle of a
 * table, which is worse than a plainer table: the surface is what the card piles sit on and
 * what {@code /uno remove} matches against.
 */
public record BlockSpec(String namespace, String id, String state, BlockSpec fallback, String raw) {

    /** What a cell falls back to when nothing else resolves — never a hole in the table. */
    private static final Material LAST_RESORT = Material.OAK_PLANKS;

    /**
     * Parse a written spec. Never returns null and never throws: a theme file is written by
     * hand, so a typo has to degrade to something placeable rather than take a table down.
     */
    public static BlockSpec parse(String written) {
        if (written == null || written.isBlank()) {
            return new BlockSpec("minecraft", LAST_RESORT.name().toLowerCase(Locale.ROOT),
                    null, null, "");
        }
        String text = written.trim();
        BlockSpec fall = null;
        int pipe = text.indexOf('|');
        if (pipe >= 0) {
            fall = parse(text.substring(pipe + 1));
            text = text.substring(0, pipe).trim();
        }

        String state = null;
        int bracket = text.indexOf('[');
        if (bracket >= 0 && text.endsWith("]")) {
            state = text.substring(bracket);
            text = text.substring(0, bracket).trim();
        }

        String namespace = "minecraft";
        String id = text;
        int colon = text.indexOf(':');
        if (colon > 0) {
            namespace = text.substring(0, colon).toLowerCase(Locale.ROOT);
            id = text.substring(colon + 1);
        }
        return new BlockSpec(namespace, id.toLowerCase(Locale.ROOT), state, fall, written.trim());
    }

    /** Build a spec from a material, the way the GUI does when a player drops a block in. */
    public static BlockSpec of(Material material) {
        return new BlockSpec("minecraft", material.name().toLowerCase(Locale.ROOT), null, null,
                "minecraft:" + material.name().toLowerCase(Locale.ROOT));
    }

    /** True if this spec names a plugin's custom block rather than a registry block. */
    public boolean isCustom(CustomBlocks custom) {
        return !"minecraft".equals(namespace) && custom.handles(namespace);
    }

    /** The written form, as it should appear in themes.yml. */
    public String written() {
        return raw.isEmpty() ? "minecraft:" + id : raw;
    }

    /**
     * The vanilla block data this spec resolves to, or null if it names nothing this server
     * knows. Custom-block ids resolve through their fallback, since the plugin that owns them
     * places them itself.
     */
    public BlockData vanillaData(CustomBlocks custom) {
        if (isCustom(custom)) {
            return fallback == null ? null : fallback.vanillaData(custom);
        }
        String full = namespace + ":" + id + (state == null ? "" : state);
        try {
            return Bukkit.createBlockData(full);
        } catch (IllegalArgumentException ignored) {
            // Not a registry id — try it as a bare material name, which is what the old
            // tables.<alias>.blocks.* keys held (CHERRY_LOG rather than minecraft:cherry_log).
        }
        Material m = Material.matchMaterial(id);
        if (m != null && m.isBlock()) {
            return m.createBlockData();
        }
        return fallback == null ? null : fallback.vanillaData(custom);
    }

    /**
     * Put this spec's block at {@code at}.
     *
     * <p>Tries the custom-block plugin first when the spec names one, and falls through to the
     * vanilla data on any failure, so a table always ends up complete.
     *
     * @return the Material actually placed, for the removal matcher to remember.
     */
    public Material place(Location at, CustomBlocks custom, java.util.function.UnaryOperator<BlockData> shape) {
        if (isCustom(custom) && custom.place(namespace, id, at)) {
            Block b = at.getBlock();
            return b.getType();
        }
        BlockData data = vanillaData(custom);
        if (data == null) {
            data = LAST_RESORT.createBlockData();
        }
        data = shape.apply(data);
        at.getBlock().setBlockData(data, false);
        return data.getMaterial();
    }

    /** The material this cell holds once placed — what {@code /uno remove} matches on. */
    public Material material(CustomBlocks custom) {
        BlockData data = vanillaData(custom);
        return data == null ? LAST_RESORT : data.getMaterial();
    }
}
