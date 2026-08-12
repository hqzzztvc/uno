package com.unoplugin.hand;

import net.kyori.adventure.text.Component;
import org.bukkit.Input;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns each player's hand and renders it as a DYNAMIC 3D fan held in the main hand.
 *
 * <p>The fan shows ALL the cards the player holds; as the hand grows it COMPRESSES so
 * everything fits (density tiers, picked from the card count). The held item
 * ({@code uno:held}) is a composite model whose slots are switched live via
 * {@code custom_model_data} (card + tier + selected). A/D scroll the selection — polled
 * every tick so inputs are never dropped. Held viewmodel -> no lag, no clipping.
 */
public final class HandManager implements Listener {

    /** Must match generate_held_fan.py. */
    private static final int MAX_SLOTS = 21;
    /** Per-card angular step for each density tier (must match DENSITIES in the generator). */
    private static final double[] DENSITIES = {19.0, 10.0, 5.0};
    /** Total fan spread allowed on screen; the tier is the widest one that fits this. */
    private static final double MAX_SPAN = 135.0;
    private static final NamespacedKey HELD_MODEL = new NamespacedKey("uno", "held");

    /** Full 54-card deck, used for test draws (Step 6 replaces this with the real deck). */
    private static final List<String> DECK = buildDeck();

    private static List<String> buildDeck() {
        List<String> deck = new ArrayList<>();
        for (String colour : new String[]{"red", "green", "blue", "yellow"}) {
            for (int i = 0; i <= 9; i++) {
                deck.add(colour + "_" + i);
            }
            deck.add(colour + "_skip");
            deck.add(colour + "_reverse");
            deck.add(colour + "_draw2");
        }
        deck.add("wild");
        deck.add("wild_draw4");
        return deck;
    }

    /** Routes play/draw input into a game; returns true if a game handled it. */
    public interface CardActions {
        boolean play(Player player, int selectedIndex);

        boolean draw(Player player);
    }

    private final Plugin plugin;
    private final Map<UUID, Hand> hands = new HashMap<>();
    private final BukkitTask pollTask;
    private CardActions cardActions;

    public HandManager(Plugin plugin) {
        this.plugin = plugin;
        this.pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 1L, 1L);
    }

    public void setCardActions(CardActions cardActions) {
        this.cardActions = cardActions;
    }

    /** One player's hand state. */
    private static final class Hand {
        final Player player;
        List<String> cards;
        int selected;
        int fanSlot;   // the hotbar slot the fan item lives in (so the wheel can't move it)
        boolean lastLeft;
        boolean lastRight;
        long lastPlayMs;   // debounce double-fired left-click swing events
        long lastDrawMs;

        Hand(Player player, List<String> cards) {
            this.player = player;
            this.cards = cards;
            this.selected = 0;
        }
    }

    /** Show (or refresh) a player's hand as the held fan (any size). */
    public void show(Player player, List<String> cards) {
        Hand hand = hands.computeIfAbsent(player.getUniqueId(), k -> new Hand(player, new ArrayList<>()));
        hand.cards = new ArrayList<>(cards);
        hand.fanSlot = player.getInventory().getHeldItemSlot(); // anchor to the current hotbar slot
        if (hand.selected >= hand.cards.size()) {
            hand.selected = Math.max(0, hand.cards.size() - 1);
        }
        updateItem(hand);
    }

    /** Hide a player's hand (clears the held item). */
    public void hide(Player player) {
        Hand hand = hands.remove(player.getUniqueId());
        if (hand != null && isHeldItem(player.getInventory().getItem(hand.fanSlot))) {
            player.getInventory().setItem(hand.fanSlot, null);
        }
    }

    /** Per-tick: read each hand-holder's A/D input and scroll the selection on key-down. */
    private void poll() {
        for (Hand hand : hands.values()) {
            if (!hand.player.isOnline() || hand.cards.isEmpty()) {
                continue;
            }
            Input in = hand.player.getCurrentInput();
            boolean left = in.isLeft();
            boolean right = in.isRight();
            boolean changed = false;
            // Fan order runs right-to-left vs index, so A (left) increments to move the
            // highlight visually left, D (right) decrements to move it right.
            if (left && !hand.lastLeft) {
                hand.selected = Math.min(hand.cards.size() - 1, hand.selected + 1);
                changed = true;
            }
            if (right && !hand.lastRight) {
                hand.selected = Math.max(0, hand.selected - 1);
                changed = true;
            }
            hand.lastLeft = left;
            hand.lastRight = right;
            if (changed) {
                updateItem(hand);
            }
        }
    }

    /** True if the player currently has an active hand fan (in a game / test). */
    public boolean isActive(Player player) {
        return hands.containsKey(player.getUniqueId());
    }

    /** Move the selection by dir and refresh. */
    private void scroll(Hand hand, int dir) {
        if (hand.cards.isEmpty()) {
            return;
        }
        hand.selected = Math.max(0, Math.min(hand.cards.size() - 1, hand.selected + dir));
        updateItem(hand);
    }

    /**
     * Play the selected card. Step-5 stub: removes it from the hand and re-renders.
     * Step 6 will validate the move, animate the card to the discard pile, advance the turn.
     */
    public void playSelected(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null || hand.cards.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - hand.lastPlayMs < 150) {
            return; // left-click swing fires twice — ignore the duplicate
        }
        hand.lastPlayMs = now;
        if (cardActions != null && cardActions.play(player, hand.selected)) {
            return; // a game handled the play (and re-rendered)
        }
        // Fallback test stub (no active game): just remove the selected card.
        String played = hand.cards.remove(hand.selected);
        if (hand.selected >= hand.cards.size()) {
            hand.selected = Math.max(0, hand.cards.size() - 1);
        }
        player.sendMessage("§a▶ Played §f" + played);
        if (hand.cards.isEmpty()) {
            player.sendMessage("§6§lUNO! §rYour hand is empty.");
            hide(player);
        } else {
            updateItem(hand);
        }
    }

    /** Draw a card. Step-5 stub: adds a random card and re-renders. */
    public void drawCard(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - hand.lastDrawMs < 150) {
            return; // ignore duplicate interact events
        }
        hand.lastDrawMs = now;
        if (cardActions != null && cardActions.draw(player)) {
            return; // a game handled the draw
        }
        // Fallback test stub (no active game): add a random card.
        String card = DECK.get((int) (Math.random() * DECK.size()));
        hand.cards.add(card);
        player.sendMessage("§e+ Drew §f" + card);
        updateItem(hand);
    }

    /** Widest density tier whose total spread fits MAX_SPAN for n cards. */
    private int chooseTier(int n) {
        double step = (n <= 1) ? DENSITIES[0] : Math.min(DENSITIES[0], MAX_SPAN / (n - 1));
        for (int i = 0; i < DENSITIES.length; i++) {
            if (DENSITIES[i] <= step + 0.001) {
                return i;
            }
        }
        return DENSITIES.length - 1;
    }

    /** Rebuild the custom_model_data strings and put the fan in the player's hand. */
    private void updateItem(Hand hand) {
        int n = hand.cards.size();
        int tier = chooseTier(Math.min(n, MAX_SLOTS));
        List<String> strings = new ArrayList<>(Collections.nCopies(MAX_SLOTS, ""));

        int count = Math.min(n, MAX_SLOTS);
        int windowStart = 0;
        int slotOffset = (MAX_SLOTS - count) / 2; // centre the fan
        if (n > MAX_SLOTS) {
            // Beyond the fan's capacity (very rare) — slide a full-width window.
            windowStart = Math.max(0, Math.min(hand.selected - MAX_SLOTS / 2, n - MAX_SLOTS));
            slotOffset = 0;
        }
        for (int j = 0; j < count; j++) {
            int cardIndex = windowStart + j;
            String value = hand.cards.get(cardIndex) + "_" + tier;
            if (cardIndex == hand.selected) {
                value = value + "_sel";
            }
            strings.set(slotOffset + j, value);
        }

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(HELD_MODEL);
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(strings);
        meta.setCustomModelDataComponent(cmd);
        meta.displayName(Component.text("UNO Hand"));
        item.setItemMeta(meta);

        hand.player.getInventory().setItem(hand.fanSlot, item); // always write to the fixed fan slot
        clearStrayFans(hand.player, hand.fanSlot);              // never leave duplicate fan items around
        if (n > 0) {
            hand.player.sendActionBar(Component.text("§7Selected: §f" + hand.cards.get(hand.selected)
                    + " §8(" + (hand.selected + 1) + "/" + n + ")"));
        }
    }

    private boolean isHeldItem(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PAPER || !stack.hasItemMeta()) {
            return false;
        }
        return HELD_MODEL.equals(stack.getItemMeta().getItemModel());
    }

    /** Remove any UNO-hand items anywhere except the one slot the fan lives in. */
    private void clearStrayFans(Player player, int keepSlot) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (i != keepSlot && isHeldItem(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hands.remove(event.getPlayer().getUniqueId());
    }

    // --- Step 5 input: scroll wheel + play (left-click / Q) + draw (right-click / F) ----

    /** Mouse wheel = scroll the selection (kept on the fan item by cancelling the slot change). */
    @EventHandler
    public void onScrollWheel(PlayerItemHeldEvent event) {
        Hand hand = hands.get(event.getPlayer().getUniqueId());
        if (hand == null) {
            return;
        }
        int delta = event.getNewSlot() - event.getPreviousSlot();
        if (delta == 8) {
            delta = -1; // wrapped 0 -> 8 (scrolled up)
        } else if (delta == -8) {
            delta = 1;  // wrapped 8 -> 0 (scrolled down)
        }
        event.setCancelled(true);   // stay on the held fan item
        scroll(hand, -delta);       // scroll up -> highlight moves left
    }

    /** Left-click (arm swing) plays the selected card. */
    @EventHandler
    public void onLeftClickPlay(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING && isActive(event.getPlayer())) {
            playSelected(event.getPlayer());
        }
    }

    /** Q (drop key) also plays — without actually dropping the item. */
    @EventHandler
    public void onDropPlay(PlayerDropItemEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
            playSelected(event.getPlayer());
        }
    }

    /** Right-click draws a card. */
    @EventHandler
    public void onRightClickDraw(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isActive(event.getPlayer())) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            drawCard(event.getPlayer());
        }
    }

    /** F (swap-hands key) also draws — without actually swapping. */
    @EventHandler
    public void onSwapDraw(PlayerSwapHandItemsEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
            drawCard(event.getPlayer());
        }
    }

    /** No world editing while holding the hand. */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Tear down all hands (plugin disable). */
    public void shutdown() {
        if (pollTask != null) {
            pollTask.cancel();
        }
        hands.clear();
    }
}
