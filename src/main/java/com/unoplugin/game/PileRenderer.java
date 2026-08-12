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
 * <p>Everything here uses dedicated <em>flat</em> models (`uno:flat_*`, `uno:deck_*`) generated
 * lying down with the number on the top face and white edges on the sides, rendered with NO
 * rotation. This is the key fix: a render-time {@code rotateX} swings the card off its spawn
 * point and drops it half a block <em>under</em> the table; an un-rotated flat model stays
 * exactly where it is spawned.
 *
 *  - DRAW deck: ONE model carved as a solid block of cards, swapped for a thinner one as the
 *    count falls. Its white side-edges are what read as a stack.
 *  - DISCARD: played cards land face-up, lightly tossed, overlapping into a real heap that
 *    rolls — the bottom card leaves as a new one lands, so the pile keeps a steady height.
 */
public final class PileRenderer {

    /** 24 model units long at this scale is the same 12.75 blocks the old 15-unit card was. */
    private static final float SCALE = 0.53125f;
    // A discard card is half a model unit thick -> ~0.0166 blocks; the gap is a good bit LESS
    // so cards sink into each other — a solid heap rather than a tower, and no see-through air
    // gap (which read as "floating").
    private static final float STACK_GAP = 0.010f;

    /**
     * The draw pile is a single model carved as a solid block of cards, not a stack of
     * separate card entities. Drawing does not disturb it: the deck order is settled once
     * when the hand is dealt, so a draw only ever shrinks the count, and the pile just
     * steps down to the next model when it crosses a threshold. One display, three swaps
     * a game — instead of an entity removal every time somebody draws.
     */
    private static final int DECK_LARGE = 50;    // above this, the full 100-card block
    private static final int DECK_SMALL = 10;    // at or below this, the thin one

    /** How many plays the discard heap shows at once, oldest rolling off the bottom. */
    private static final int DISCARD_MAX = 5;
    private static final double DISC_STAGGER = 0.013;
    private static final double DISC_TILT = 8.0;

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final Location discardLoc;
    private final Location drawLoc;
    private final float yaw;

    private final List<ItemDisplay> discard = new ArrayList<>();   // face-up plays
    private ItemDisplay deck;                                      // the one draw-pile block
    private String deckModel;                                      // which one it is showing

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

    /** Step the draw pile down to the deck model that matches the remaining count. */
    public void setDrawCount(int count) {
        if (drawLoc.getWorld() == null) {
            return;
        }
        String model = deckModelFor(count);
        if (model == null) {
            if (deck != null) {
                if (deck.isValid()) {
                    deck.remove();
                }
                deck = null;
                deckModel = null;
            }
            return;
        }
        if (deck != null && !deck.isValid()) {
            deck = null; // chunk unloaded out from under us
        }
        if (deck == null) {
            Location l = drawLoc.clone();
            l.setYaw(yaw); // squared up with the table — a deck is not tossed, it is placed
            deck = spawn(l, model);
        } else if (!model.equals(deckModel)) {
            deck.setItemStack(cardItem(model)); // same entity, thicker or thinner block
        }
        deckModel = model;
    }

    private static String deckModelFor(int count) {
        if (count <= 0) {
            return null;
        }
        if (count > DECK_LARGE) {
            return "deck_100";
        }
        return count > DECK_SMALL ? "deck_50" : "deck_10";
    }

    /**
     * Drop a card FACE-UP onto the discard stack.
     *
     * <p>The pile is a rolling window over the last {@link #DISCARD_MAX} plays. Once it is
     * full the bottom card leaves, everything above it drops one step, and the new card lands
     * on top — so the heap holds its height and never blinks out to start again.
     *
     * <p>Nothing is spawned or destroyed to do that. The display freed from the bottom is the
     * one that becomes the new top card, so a play costs {@code DISCARD_MAX} teleports and one
     * item swap instead of a removal plus a respawn — and a teleport is a smaller packet to
     * every nearby player than a despawn/spawn pair. Each card carries its own scatter and
     * tilt down with it, so the heap stays as untidy as it was dealt.
     */
    public void addToDiscard(Card card) {
        if (discardLoc.getWorld() == null) {
            return; // world unloaded mid-hand — spawning here would NPE and freeze the hand
        }
        discard.removeIf(d -> !d.isValid()); // culled with their chunk — don't hold their slots
        ItemDisplay recycled = null;
        if (discard.size() >= DISCARD_MAX) {
            recycled = discard.remove(0);
            for (ItemDisplay d : discard) {
                slideDown(d);
            }
        }
        Location l = slot(discard.size());
        if (recycled != null) {
            recycled.setItemStack(cardItem("flat_" + card.name()));
            recycled.teleport(l);
            discard.add(recycled);
        } else {
            discard.add(spawn(l, "flat_" + card.name()));
        }
    }

    /** Where the card at {@code index} up the stack sits, scattered so the heap looks tossed. */
    private Location slot(int index) {
        Location l = discardLoc.clone();
        l.add(jitter(DISC_STAGGER), index * STACK_GAP, jitter(DISC_STAGGER));
        l.setYaw(yaw + (float) jitter(DISC_TILT));
        return l;
    }

    /** Drop one card a step, keeping the scatter and tilt it was dealt with. */
    private void slideDown(ItemDisplay d) {
        Location l = d.getLocation();
        l.setY(l.getY() - STACK_GAP);
        d.teleport(l);
    }

    public void remove() {
        if (deck != null && deck.isValid()) {
            deck.remove();
        }
        for (ItemDisplay d : discard) {
            if (d.isValid()) {
                d.remove();
            }
        }
        deck = null;
        deckModel = null;
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
