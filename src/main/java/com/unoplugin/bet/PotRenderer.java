package com.unoplugin.bet;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The pot as it looks on the felt: staked items lying flat in a loose heap, with a floating
 * tally over the middle of the table.
 *
 * <p>The items you see are {@link ItemDisplay}s, not real dropped {@link org.bukkit.entity.Item}
 * entities — the real stacks are held in {@link EscrowStore}. That's deliberate: dropped items
 * despawn after 5 minutes, get sucked up by hoppers, and can be grabbed by anyone walking past.
 * Displays can't be picked up, so the pot is exactly as safe as the escrow file behind it.
 *
 * <p><strong>Where</strong> the heap sits is the caller's decision, passed as a {@link Spot}
 * on every update — the middle of the table while people ante up, nowhere while the cards
 * have the felt, in front of the winner once there is one. Moving it slides the displays
 * that are already out rather than respawning them.
 *
 * <p>Layout is deterministic per slot index (fixed seed), so a rebuild after someone adds to
 * the pot leaves every existing item exactly where it was — no shuffling heap.
 *
 * <p>Money in the pot is drawn as a single {@link #COIN}. Without it a pot that is pure
 * currency renders as nothing at all on the felt, which reads as a broken table rather than
 * as a wager — and the whole point of drawing the pot is that you can see what is at stake
 * from across the room. The exact figure is in the tally above it.
 */
public final class PotRenderer {

    /**
     * Where the heap goes and the shape it spreads into.
     *
     * @param centre the middle of the heap, ON the table surface
     * @param facing yaw of the heap's depth axis; {@code across} is measured at right angles
     * @param across how far the heap spreads sideways, in blocks
     * @param depth  how far it spreads along {@code facing}, in blocks
     */
    public record Spot(Location centre, float facing, double across, double depth) { }

    /**
     * Width of a flat item — an ingot, a sword — in blocks.
     *
     * <p>Drawn with no display transform, so this IS its size on the table. It is twice what
     * the heap drew when it borrowed the dropped-item look, whose own half-scale made a pot of
     * diamonds hard to make out from the far seat.
     */
    private static final float ITEM_SIZE = 0.45f;
    /** A block item is a cube this wide: half a flat item, the proportion vanilla draws them in. */
    private static final float BLOCK_SIZE = ITEM_SIZE / 2f;
    /** A flat item is one model unit thick — a sixteenth of its width — centred on its origin. */
    private static final double ITEM_HALF_THICKNESS = ITEM_SIZE / 32.0;
    /** Flat items are drawn standing up; this lays them face-up on the felt. */
    private static final Quaternionf LIE_FLAT = new Quaternionf().rotationX((float) Math.toRadians(-90));

    private static final int MAX_SHOWN = 12;       // beyond this the tally does the talking
    /**
     * Each item sits this much above the one before it, so overlapping ones don't z-fight.
     * Kept tiny: it is the only thing lifting an item off the felt, and a heap that visibly
     * hovers is what this layout replaced.
     */
    private static final double STACK_STEP = 0.002;
    /**
     * Height of the tally over the table surface. Above seated eye level on purpose: it floats
     * over the middle of the table, and any lower it sits between every pair of players.
     */
    private static final double TALLY_HEIGHT = 1.6;
    /** Ticks a display takes to slide when the heap moves or re-lays. */
    private static final int SLIDE_TICKS = 6;
    private static final long LAYOUT_SEED = 0x150D5L;
    /** Stands in for the money half of a pot — one coin, however large the sum. */
    private static final Material COIN = Material.GOLD_NUGGET;

    private final NamespacedKey tag;
    private final Location tallyAt;

    private final List<ItemDisplay> shown = new ArrayList<>();
    private TextDisplay tally;

    /** @param tableCentre the middle of the table surface — the tally floats over it. */
    public PotRenderer(Plugin plugin, Location tableCentre) {
        this.tag = new NamespacedKey(plugin, "uno_pot");
        this.tallyAt = tableCentre.clone().add(0, TALLY_HEIGHT, 0);
        this.tallyAt.setPitch(0f);
    }

    /**
     * Set the floating label, and lay the heap for {@code stake} at {@code spot} — or take
     * the items off the table if {@code spot} is null.
     */
    public void update(Stake stake, Component label, Spot spot) {
        setTally(label);
        if (spot == null || spot.centre().getWorld() == null) {
            clearItems();
            return;
        }
        List<ItemStack> items = drawn(stake);
        int target = Math.min(MAX_SHOWN, items.size());

        // Trim first so a shrinking pot (refund / cash-out) drops its extra displays.
        while (shown.size() > target) {
            ItemDisplay d = shown.remove(shown.size() - 1);
            if (d.isValid()) {
                d.remove();
            }
        }
        for (int i = 0; i < target; i++) {
            ItemStack stack = items.get(i);
            Location where = slot(spot, i, stack);
            ItemDisplay d = i < shown.size() ? shown.get(i) : null;
            if (d != null && d.isValid()) {
                place(d, stack, where);
            } else if (d != null) {
                shown.set(i, spawnItem(where, stack));
            } else {
                shown.add(spawnItem(where, stack));
            }
        }
    }

    /** Change only the floating text (turn counter, ride prompt, …). */
    public void setTally(Component label) {
        World w = tallyAt.getWorld();
        if (w == null) {
            return;
        }
        if (tally == null || !tally.isValid()) {
            tally = w.spawn(tallyAt, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setSeeThrough(false);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);
                d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            });
        }
        tally.text(label);
    }

    public void remove() {
        clearItems();
        if (tally != null && tally.isValid()) {
            tally.remove();
        }
        tally = null;
    }

    // ----------------------------------------------------------------- helpers

    private void clearItems() {
        for (ItemDisplay d : shown) {
            if (d.isValid()) {
                d.remove();
            }
        }
        shown.clear();
    }

    /**
     * What the heap actually shows: the staked stacks, with the coin first when there is
     * money in the pot.
     *
     * <p>First rather than last so the coin keeps slot 0 as items are added around it. The
     * one reshuffle is when money first enters a pot that already had items in it, which is
     * a single redraw and beats having the coin hop about as the pot grows.
     */
    private static List<ItemStack> drawn(Stake stake) {
        if (!stake.hasMoney()) {
            return stake.items();
        }
        List<ItemStack> out = new ArrayList<>(stake.items().size() + 1);
        out.add(new ItemStack(COIN));
        out.addAll(stake.items());
        return out;
    }

    /**
     * Deterministic spot for the nth item — a loose spiral, stretched to the spot's shape.
     *
     * <p>Resting ON the felt: the item's underside is at the surface, not its middle. A flat
     * item is lifted by half its own thickness and a block by half its height; an ItemDisplay
     * draws its model centred on the entity, so anything less sinks into the table and
     * anything more hovers over it.
     */
    private static Location slot(Spot spot, int index, ItemStack stack) {
        Random rng = new Random(LAYOUT_SEED + index * 31L);
        double angle = index * 2.399963; // golden angle — spreads without clumping
        double reach = Math.sqrt((index + 0.6) / MAX_SHOWN);
        double a = Math.cos(angle) * reach * spot.across();
        double b = Math.sin(angle) * reach * spot.depth();
        // Table-space axes, the same ones UnoTable uses: forward (-sin, cos), right (cos, sin).
        double r = Math.toRadians(spot.facing());
        Location centre = spot.centre();
        Location l = new Location(centre.getWorld(),
                centre.getX() + Math.cos(r) * a - Math.sin(r) * b,
                centre.getY() + lift(stack) + index * STACK_STEP,
                centre.getZ() + Math.sin(r) * a + Math.cos(r) * b);
        l.setYaw(Location.normalizeYaw(spot.facing() + rng.nextFloat() * 360f));
        l.setPitch(0f);
        return l;
    }

    /**
     * Drawn as a cube rather than laid flat?
     *
     * <p>Solid blocks have a 3D item model; everything else — ingots, tools, and the flat
     * sprites of doors and flowers — is a picture that has to be laid down to look like
     * it's lying on the table.
     */
    private static boolean isCube(ItemStack stack) {
        Material type = stack.getType();
        return type.isBlock() && type.isSolid();
    }

    private static double lift(ItemStack stack) {
        return isCube(stack) ? BLOCK_SIZE / 2.0 : ITEM_HALF_THICKNESS;
    }

    private static Transformation transformFor(ItemStack stack) {
        boolean cube = isCube(stack);
        float size = cube ? BLOCK_SIZE : ITEM_SIZE;
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                cube ? new Quaternionf() : new Quaternionf(LIE_FLAT),
                new Vector3f(size, size, size),
                new Quaternionf());
    }

    /** Point an existing display at this slot: swap the item if it changed, slide if it moved. */
    private static void place(ItemDisplay d, ItemStack stack, Location where) {
        if (!stack.isSimilar(d.getItemStack())) {
            d.setItemStack(stack.clone());
            d.setTransformation(transformFor(stack));
        }
        Location now = d.getLocation();
        if (now.distanceSquared(where) > 1.0e-6 || Math.abs(now.getYaw() - where.getYaw()) > 0.01f) {
            d.teleport(where);
        }
    }

    private ItemDisplay spawnItem(Location loc, ItemStack stack) {
        World w = loc.getWorld();
        ItemStack visual = stack.clone();
        return w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(visual);
            // No display transform: the model at the size given, centred on the entity, which
            // is what lets slot() put its underside exactly on the felt.
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(transformFor(visual));
            d.setTeleportDuration(SLIDE_TICKS);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
    }
}
