package com.unoplugin.game;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The in-world card piles, sitting flat ON the table for everyone to see.
 *
 * <p>The cards use dedicated <em>flat</em> models (`uno:flat_*`) authored lying down with
 * the number on the top face and white edges on the sides, rendered with NO rotation. This
 * is the key fix: a render-time {@code rotateX} swings the card off its spawn point and
 * drops it half a block <em>under</em> the table; an un-rotated flat model stays exactly
 * where it is spawned. Stacked with a small vertical gap, the white side-edges read as a
 * real deck / discard pile.
 *
 *  - DRAW deck: a neat stack of face-down backs whose height tracks the remaining count.
 *  - DISCARD: played cards land face-up, lightly tossed, stacking on each other.
 */
public final class PileRenderer {

    private static final float SCALE = 0.85f;
    // Card is ~0.016 blocks thick; gap is a touch LESS so cards overlap slightly — a solid
    // stack with no see-through air gap between them (which read as "floating" before).
    private static final float STACK_GAP = 0.013f;

    private static final int DECK_MAX = 10;
    private static final int DECK_PER_CARD = 6;          // 1 visible card per ~6 deck cards
    private static final float DECK_STAGGER = 0.006f;    // tiny offset — neat stack, cards just peek
    /** Real cards shown face-down in the draw deck (logo up, number down). */
    private static final String[] DECK_FACES = {
            "red_5", "blue_8", "green_2", "yellow_9", "red_1",
            "blue_4", "green_7", "yellow_3", "red_6", "blue_0"};

    private static final int DISCARD_MAX = 10;
    private static final double DISC_STAGGER = 0.013;
    private static final double DISC_TILT = 8.0;

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final Location discardLoc;
    private final Location drawLoc;
    private final float yaw;

    private final List<ItemDisplay> deck = new ArrayList<>();      // face-down backs
    private final List<ItemDisplay> discard = new ArrayList<>();   // face-up plays

    public PileRenderer(Plugin plugin, Location discardLoc, Location drawLoc, float yaw) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "uno_pile");
        this.discardLoc = base(discardLoc);
        this.drawLoc = base(drawLoc);
        this.yaw = yaw;
    }

    public void spawn(Card firstTop, int drawCount) {
        setDrawCount(drawCount);
        addToDiscard(firstTop);
    }

    /** Resize the draw deck (a neat stack of card-backs) to the remaining count. */
    public void setDrawCount(int count) {
        World w = drawLoc.getWorld();
        if (w == null) {
            return;
        }
        int target = count <= 0 ? 0
                : Math.min(DECK_MAX, Math.max(2, Math.round(count / (float) DECK_PER_CARD)));
        while (deck.size() < target) {
            int i = deck.size();
            Location l = drawLoc.clone();
            l.add((i % 2 == 0 ? 1 : -1) * DECK_STAGGER, i * STACK_GAP, (i % 3 == 0 ? 1 : -1) * DECK_STAGGER);
            l.setYaw(yaw + (i % 2 == 0 ? 1.5f : -1.5f));
            deck.add(spawn(l, "down_" + DECK_FACES[i % DECK_FACES.length]));
        }
        while (deck.size() > target) {
            ItemDisplay d = deck.remove(deck.size() - 1);
            if (d.isValid()) {
                d.remove();
            }
        }
    }

    /** Drop a card FACE-UP onto the discard stack. */
    public void addToDiscard(Card card) {
        Location l = discardLoc.clone();
        l.add(jitter(DISC_STAGGER), discard.size() * STACK_GAP, jitter(DISC_STAGGER));
        l.setYaw(yaw + (float) jitter(DISC_TILT));
        discard.add(spawn(l, "flat_" + card.name()));
        if (discard.size() > DISCARD_MAX) {
            ItemDisplay old = discard.remove(0);
            if (old.isValid()) {
                old.remove();
            }
            // Re-base the whole pile down one card so it never creeps up out of readable range.
            for (ItemDisplay d : discard) {
                if (d.isValid()) {
                    d.teleport(d.getLocation().subtract(0, STACK_GAP, 0));
                }
            }
        }
    }

    public void remove() {
        for (ItemDisplay d : deck) {
            if (d.isValid()) {
                d.remove();
            }
        }
        for (ItemDisplay d : discard) {
            if (d.isValid()) {
                d.remove();
            }
        }
        deck.clear();
        discard.clear();
    }

    // ----------------------------------------------------------------- helpers

    /** Spawn one flat card lying exactly where placed (no rotation → no drop). */
    private ItemDisplay spawn(Location loc, String model) {
        World w = loc.getWorld();
        return w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(cardItem(model));
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f), new Quaternionf(),
                    new Vector3f(SCALE, SCALE, SCALE), new Quaternionf()));
            d.setPersistent(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private static Location base(Location loc) {
        Location l = loc.clone();
        l.setPitch(0f);
        return l;
    }

    private static double jitter(double range) {
        return ThreadLocalRandom.current().nextDouble(-range, range);
    }

    private ItemStack cardItem(String model) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey("uno", model));
        stack.setItemMeta(meta);
        return stack;
    }
}
