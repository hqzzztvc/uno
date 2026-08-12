package com.unoplugin.bet;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
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
 * The pot as it looks on the felt: staked items lying in a loose heap at the dealer's end
 * of the table, with a floating tally above them.
 *
 * <p>The items you see are {@link ItemDisplay}s, not real dropped {@link org.bukkit.entity.Item}
 * entities — the real stacks are held in {@link EscrowStore}. That's deliberate: dropped items
 * despawn after 5 minutes, get sucked up by hoppers, and can be grabbed by anyone walking past.
 * Displays can't be picked up, so the pot is exactly as safe as the escrow file behind it.
 *
 * <p>Layout is deterministic per slot index (fixed seed), so a rebuild after someone adds to
 * the pot leaves every existing item exactly where it was — no shuffling heap.
 */
public final class PotRenderer {

    private static final float SCALE = 0.45f;
    private static final int MAX_SHOWN = 12;       // beyond this the tally does the talking
    private static final double RING_RADIUS = 0.34;
    private static final double LIFT = 0.02;       // clear of the felt, no z-fighting
    private static final long LAYOUT_SEED = 0x150D5L;

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final Location centre;
    private final float yaw;

    private final List<ItemDisplay> shown = new ArrayList<>();
    private TextDisplay tally;

    public PotRenderer(Plugin plugin, Location centre, float yaw) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "uno_pot");
        this.centre = centre.clone();
        this.centre.setPitch(0f);
        this.yaw = yaw;
    }

    /** Redraw the heap for {@code items} and set the floating label. */
    public void update(List<ItemStack> items, String label) {
        World w = centre.getWorld();
        if (w == null) {
            return;
        }
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
            if (i < shown.size()) {
                ItemDisplay d = shown.get(i);
                if (d.isValid()) {
                    d.setItemStack(stack.clone());
                    continue;
                }
                shown.remove(i);
            }
            shown.add(i, spawnItem(slot(i), stack));
        }
        setTally(label);
    }

    /** Change only the floating text (turn counter, ride prompt, …). */
    public void setTally(String label) {
        World w = centre.getWorld();
        if (w == null) {
            return;
        }
        if (tally == null || !tally.isValid()) {
            Location at = centre.clone().add(0, 1.05, 0);
            tally = w.spawn(at, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setSeeThrough(false);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);
                d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            });
        }
        tally.text(Component.text(label));
    }

    public void remove() {
        for (ItemDisplay d : shown) {
            if (d.isValid()) {
                d.remove();
            }
        }
        shown.clear();
        if (tally != null && tally.isValid()) {
            tally.remove();
        }
        tally = null;
    }

    // ----------------------------------------------------------------- helpers

    /** Deterministic spot for the nth item — a loose ring that spirals inward as it fills. */
    private Location slot(int index) {
        Random rng = new Random(LAYOUT_SEED + index * 31L);
        double angle = index * 2.399963; // golden angle — spreads without clumping
        double radius = RING_RADIUS * Math.sqrt((index + 0.6) / MAX_SHOWN);
        Location l = centre.clone().add(
                Math.cos(angle) * radius,
                LIFT + index * 0.004,
                Math.sin(angle) * radius);
        l.setYaw(yaw + rng.nextFloat() * 360f);
        return l;
    }

    private ItemDisplay spawnItem(Location loc, ItemStack stack) {
        World w = loc.getWorld();
        ItemStack visual = stack.clone();
        return w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(visual);
            // GROUND renders the item lying flat like a dropped stack — no rotateX needed,
            // which is what keeps it on the felt instead of sinking under the tabletop.
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f), new Quaternionf(),
                    new Vector3f(SCALE, SCALE, SCALE), new Quaternionf()));
            d.setPersistent(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
    }
}
