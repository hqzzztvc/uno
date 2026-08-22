package com.unoplugin.table;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The "Let It Ride" playing mat: the felt decal that goes down on a table the moment real
 * stakes are on it, and comes up again when the pot settles.
 *
 * <p>It is what tells you across the room that a table is playing for keeps. Tables
 * themselves are no longer casual-or-casino — every table is both, and this is the only
 * thing that marks which one it is playing right now.
 *
 * <p>One {@link ItemDisplay} showing {@code uno:letitride_mat}, which comes out of
 * {@code generate_table_mat.py} <em>already lying flat</em>, centred on (8, 8, 8) in X/Z with
 * its underside at y=8 — so it spawns with no rotation (a render-time rotation would swing a
 * 2.9-block quad clean off the table) and lands flush on the felt.
 */
public final class TableMat {

    /**
     * How wide the mat is laid, in blocks.
     *
     * <p>The table top is exactly 3×3, so this is inset a tenth of a block: the mat has to
     * read as something lying <em>on</em> the table rather than as a retexture of it, and an
     * edge flush with the block grid overhangs thin air the moment you look from the side.
     */
    private static final double SPAN_BLOCKS = 2.9;

    /** The model is 32 units across, and 16 model units is one block at scale 1. */
    private static final double MODEL_UNITS = 32.0;

    /** Model thickness, in units — the 0.5 the Blockbench source was drawn at. */
    private static final double MODEL_THICKNESS = 0.5;

    private static final float SCALE = (float) (SPAN_BLOCKS / (MODEL_UNITS / 16.0));

    /**
     * How high the mat's top face sits above the table surface, in blocks.
     *
     * <p>Everything that goes on the felt during a wagered hand — both card piles and the pot
     * — is lifted by exactly this, so it rests on the mat instead of sinking through it. It
     * lives here rather than being re-typed at each renderer for the same reason
     * {@link UnoTable#SURFACE_Y} does: there is one right answer and it is derived from the
     * model, not guessed.
     */
    public static final double THICKNESS = MODEL_THICKNESS * SCALE / 16.0;

    private final NamespacedKey tag;
    private final Location at;
    private final float yaw;

    private ItemDisplay display;

    public TableMat(Plugin plugin, UnoTable table) {
        this.tag = new NamespacedKey(plugin, "uno_table_mat");
        Location surface = table.anchor();
        surface.setY(surface.getY() + UnoTable.SURFACE_Y);
        surface.setYaw(table.yaw());
        surface.setPitch(0f);
        this.at = surface;
        this.yaw = table.yaw();
    }

    /**
     * Put the mat down, or put it back if its display died with its chunk.
     *
     * <p>Idempotent: calling it on a mat that is already showing does nothing, which is what
     * lets the chunk-load repair call it blind.
     */
    public void spawn() {
        World w = at.getWorld();
        if (w == null || (display != null && display.isValid())) {
            return;
        }
        Location where = at.clone();
        where.setYaw(yaw); // squared up with the table, so the logo faces the near seat
        display = w.spawn(where, ItemDisplay.class, d -> {
            d.setItemStack(matItem());
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f), new Quaternionf(),
                    new Vector3f(SCALE, SCALE, SCALE), new Quaternionf()));
            d.setPersistent(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public void remove() {
        if (display != null && display.isValid()) {
            display.remove();
        }
        display = null;
    }

    private static ItemStack matItem() {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey("uno", "letitride_mat"));
        stack.setItemMeta(meta);
        return stack;
    }
}
