package com.unoplugin.debug;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Throwaway debug helper (Route B): spawns the given cards as a real-card-sized
 * fan held in the player's lower-right ("right hand"), anchored to the camera
 * and refreshed every tick so it follows the player's view. Lets us judge the
 * in-hand look/readability at true scale before building the real Step-4 hand.
 * Not part of game logic.
 */
public final class CardTester {

    /** A representative 7-card starting hand (mixed types so fronts are visible). */
    private static final List<String> DEFAULT_HAND = List.of(
            "red_1", "yellow_5", "green_skip", "blue_9", "red_draw2", "wild", "green_3"
    );

    // --- Look & feel (tune from screenshots) -------------------------------
    private static final float CARD_SCALE = 0.42f;   // real-card size in hand
    private static final double FWD = 0.48;          // forward from eye (blocks)
    private static final double RIGHT = 0.30;        // right of centre (blocks)
    private static final double DOWN = 0.52;         // below eye line (blocks) -> down at the hand
    private static final float FAN_STEP_DEG = 10f;   // angle between adjacent cards
    private static final float LAYER_EPS = 0.0015f;  // depth offset so cards stack cleanly
    // -----------------------------------------------------------------------

    private final Plugin plugin;
    private final NamespacedKey testKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public CardTester(Plugin plugin) {
        this.plugin = plugin;
        this.testKey = new NamespacedKey(plugin, "uno_test_card");
    }

    /** A player's active fan: its display entities and the per-tick follow task. */
    private static final class Session {
        final List<ItemDisplay> cards;
        final BukkitTask task;

        Session(List<ItemDisplay> cards, BukkitTask task) {
            this.cards = cards;
            this.task = task;
        }
    }

    /** Spawn (or respawn) a fanned hand in front of the player. Returns card count. */
    public int spawn(Player player, List<String> names) {
        clear(player); // drop any existing fan first

        List<String> hand = (names == null || names.isEmpty()) ? DEFAULT_HAND : names;

        List<ItemDisplay> displays = new ArrayList<>(hand.size());
        Location eye = player.getEyeLocation();
        for (String name : hand) {
            ItemDisplay d = eye.getWorld().spawn(eye, ItemDisplay.class, disp -> {
                disp.setItemStack(cardItem(name));
                disp.setBillboard(Display.Billboard.FIXED);          // orientation set by us each tick
                disp.setBrightness(new Display.Brightness(15, 15));   // full-bright
                disp.setTeleportDuration(0);
                disp.setPersistent(false);
                disp.getPersistentDataContainer().set(testKey, PersistentDataType.BYTE, (byte) 1);
            });
            displays.add(d);
        }

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    sessions.remove(player.getUniqueId());
                    displays.forEach(ItemDisplay::remove);
                    return;
                }
                updateFan(player, displays);
            }
        }.runTaskTimer(plugin, 0L, 1L);

        sessions.put(player.getUniqueId(), new Session(displays, task));
        updateFan(player, displays); // place immediately
        return displays.size();
    }

    /** Re-anchor and re-orient the whole fan to the player's current camera. */
    private void updateFan(Player player, List<ItemDisplay> displays) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();

        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) {
            right = new Vector(1, 0, 0); // looking straight up/down: pick a stable right
        }
        right.normalize();
        Vector camUp = right.clone().crossProduct(forward).normalize();

        // Anchor (fan pivot) at lower-right of the view.
        Location anchor = eye.clone()
                .add(forward.clone().multiply(FWD))
                .add(right.clone().multiply(RIGHT))
                .add(camUp.clone().multiply(-DOWN));
        anchor.setYaw(0f);
        anchor.setPitch(0f);

        // Camera orientation: card local X->right, Y->up, Z->camera axis.
        Matrix3f basis = new Matrix3f();
        basis.m00 = (float) right.getX();   basis.m01 = (float) right.getY();   basis.m02 = (float) right.getZ();
        basis.m10 = (float) camUp.getX();   basis.m11 = (float) camUp.getY();   basis.m12 = (float) camUp.getZ();
        basis.m20 = (float) -forward.getX();basis.m21 = (float) -forward.getY();basis.m22 = (float) -forward.getZ();
        Quaternionf camQuat = basis.getNormalizedRotation(new Quaternionf());
        // Spin 180° about the card's own up axis so the FRONT faces the player
        // (you see your numbers; opponents see the backs — proper UNO).
        camQuat.rotateY((float) Math.PI);

        int n = displays.size();
        float mid = (n - 1) / 2.0f;
        for (int i = 0; i < n; i++) {
            ItemDisplay d = displays.get(i);
            d.teleport(anchor);

            float theta = (mid - i) * FAN_STEP_DEG;                 // leftmost tilts one way, rightmost the other
            Quaternionf roll = new Quaternionf().rotateZ((float) Math.toRadians(theta));

            // Slight depth offset toward the camera so later cards layer in front.
            Vector layer = forward.clone().multiply(-LAYER_EPS * i);

            d.setTransformation(new Transformation(
                    new Vector3f((float) layer.getX(), (float) layer.getY(), (float) layer.getZ()),
                    new Quaternionf(camQuat),
                    new Vector3f(CARD_SCALE, CARD_SCALE, CARD_SCALE),
                    roll));
        }
    }

    /** Give the player a real HELD hand-of-cards item (uno:hand) to hold in their main hand. */
    public void giveHand(Player player) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey("uno", "hand"));
        meta.displayName(net.kyori.adventure.text.Component.text("UNO Hand"));
        stack.setItemMeta(meta);
        player.getInventory().addItem(stack);
    }

    private ItemStack cardItem(String cardName) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey("uno", cardName));
        meta.displayName(net.kyori.adventure.text.Component.text(cardName));
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Drop every debug fan (plugin disable).
     *
     * <p>These are per-tick display entities. The scheduler stops driving them when the plugin
     * disables, but nothing removes them — so without this a {@code /reload} leaves a frozen
     * fan hanging in the air until its chunk unloads.
     */
    public void shutdown() {
        for (Session session : new ArrayList<>(sessions.values())) {
            session.task.cancel();
            for (ItemDisplay d : session.cards) {
                if (d.isValid()) {
                    d.remove();
                }
            }
        }
        sessions.clear();
    }

    /** Remove the player's fan (and any stray tagged cards in their world). Returns count removed. */
    public int clear(Player player) {
        int removed = 0;
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.task.cancel();
            for (ItemDisplay d : session.cards) {
                if (d.isValid()) {
                    d.remove();
                    removed++;
                }
            }
        }
        for (ItemDisplay d : player.getWorld().getEntitiesByClass(ItemDisplay.class)) {
            if (d.getPersistentDataContainer().has(testKey, PersistentDataType.BYTE)) {
                d.remove();
                removed++;
            }
        }
        return removed;
    }
}
