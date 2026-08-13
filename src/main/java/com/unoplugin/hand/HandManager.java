package com.unoplugin.hand;

import com.unoplugin.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Input;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
 * {@code custom_model_data}. A/D scroll the selection — polled every tick so inputs are
 * never dropped. Held viewmodel -> no lag, no clipping.
 *
 * <p><strong>The fan never costs a player an item.</strong> It claims an empty hotbar slot
 * where it can; only when the whole inventory is full does it move something aside, and
 * that stack is given back when the hand ends, on death, or on quit.
 */
public final class HandManager implements Listener {

    /** Must match generate_held_fan.py (which reads these two constants out of this file). */
    private static final int MAX_SLOTS = 21;
    /** Per-card angular step for each density tier (must match DENSITIES in the generator). */
    private static final double[] DENSITIES = {19.0, 10.0, 5.0};
    /** Total fan spread allowed on screen; the tier is the widest one that fits this. */
    private static final double MAX_SPAN = 135.0;
    private static final NamespacedKey HELD_MODEL = new NamespacedKey("uno", "held");
    /** Left-click and interact events double-fire; ignore the echo. */
    private static final long INPUT_DEBOUNCE_MS = 150;
    /** Ticks A/D must be held before the selection starts auto-repeating. */
    private static final int REPEAT_DELAY_TICKS = 5;
    /** Ticks between steps once auto-repeat has started. */
    private static final int REPEAT_EVERY_TICKS = 2;

    /** Routes play/draw input into a game; returns true if a game handled it. */
    public interface CardActions {
        boolean play(Player player, int selectedIndex);

        boolean draw(Player player);
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Map<UUID, Hand> hands = new HashMap<>();
    private final BukkitTask pollTask;
    private CardActions cardActions;

    public HandManager(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
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
        /** What used to be in that slot, when the inventory was too full to move it aside. */
        ItemStack displaced;
        boolean lastLeft;
        boolean lastRight;
        int leftHeldTicks;
        int rightHeldTicks;
        long lastPlayMs;
        long lastDrawMs;

        Hand(Player player, List<String> cards) {
            this.player = player;
            this.cards = cards;
            this.selected = 0;
        }
    }

    /** Show (or refresh) a player's hand as the held fan (any size). */
    public void show(Player player, List<String> cards) {
        Hand hand = hands.get(player.getUniqueId());
        boolean fresh = hand == null;
        if (fresh) {
            hand = new Hand(player, new ArrayList<>());
            hands.put(player.getUniqueId(), hand);
            claimSlot(hand);
        }
        // Only sweep the inventory when the cards themselves changed — not on every A/D press.
        boolean contentsChanged = fresh || !hand.cards.equals(cards);
        hand.cards = new ArrayList<>(cards);
        if (hand.selected >= hand.cards.size()) {
            hand.selected = Math.max(0, hand.cards.size() - 1);
        }
        updateItem(hand, contentsChanged);
    }

    /** Hide a player's hand: clear the fan and give back anything it displaced. */
    public void hide(Player player) {
        Hand hand = hands.remove(player.getUniqueId());
        if (hand == null) {
            return;
        }
        clearStrayFans(player, -1);
        restoreDisplaced(player, hand);
    }

    /**
     * Pick the hotbar slot the fan will live in, without destroying anything.
     *
     * <p>Order of preference: the slot they're already holding if it's empty, then any empty
     * hotbar slot, then move the held stack into free inventory space. Only a completely full
     * inventory forces us to hold the stack aside, and that is handed back in
     * {@link #restoreDisplaced}.
     */
    private void claimSlot(Hand hand) {
        Player player = hand.player;
        PlayerInventory inv = player.getInventory();
        restoreDisplaced(player, hand); // never stack two displacements

        int current = inv.getHeldItemSlot();
        if (isEmpty(inv.getItem(current))) {
            hand.fanSlot = current;
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isEmpty(inv.getItem(slot))) {
                hand.fanSlot = slot;
                inv.setHeldItemSlot(slot);
                return;
            }
        }
        // Hotbar full — shift what's in hand into the backpack.
        ItemStack occupant = inv.getItem(current);
        hand.fanSlot = current;
        int free = inv.firstEmpty();
        if (free >= 0) {
            inv.setItem(free, occupant);
            inv.setItem(current, null);
            messages.send(player, "hand.slot-freed", "item", describe(occupant));
            return;
        }
        // Nowhere to put it: keep it safe until the hand is over.
        hand.displaced = occupant == null ? null : occupant.clone();
        inv.setItem(current, null);
        messages.send(player, "hand.slot-freed", "item", describe(occupant));
    }

    /** Give back the stack the fan pushed out of its slot, if there was one. */
    private void restoreDisplaced(Player player, Hand hand) {
        ItemStack back = hand.displaced;
        if (back == null) {
            return;
        }
        hand.displaced = null;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(back);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    /**
     * Per-tick: read each hand-holder's A/D input and scroll the selection.
     *
     * <p>Tap = one card. HOLD = keep going: after {@link #REPEAT_DELAY_TICKS} the key starts
     * auto-repeating every {@link #REPEAT_EVERY_TICKS}, so crossing a 15-card hand is one
     * held key rather than fifteen taps. The delay is what keeps a deliberate single tap from
     * ever becoming two.
     */
    private void poll() {
        for (Hand hand : hands.values()) {
            if (!hand.player.isOnline() || hand.cards.isEmpty()) {
                continue;
            }
            Input in = hand.player.getCurrentInput();
            // Fan order runs right-to-left vs index, so A (left) increments to move the
            // highlight visually left, D (right) decrements to move it right.
            int step = 0;
            if (repeats(in.isLeft(), hand.lastLeft, hand.leftHeldTicks)) {
                step += 1;
            }
            if (repeats(in.isRight(), hand.lastRight, hand.rightHeldTicks)) {
                step -= 1;
            }
            hand.leftHeldTicks = in.isLeft() ? hand.leftHeldTicks + 1 : 0;
            hand.rightHeldTicks = in.isRight() ? hand.rightHeldTicks + 1 : 0;
            hand.lastLeft = in.isLeft();
            hand.lastRight = in.isRight();

            if (step == 0) {
                continue;
            }
            int target = Math.max(0, Math.min(hand.cards.size() - 1, hand.selected + step));
            if (target != hand.selected) {
                hand.selected = target;
                updateItem(hand, false);
            }
        }
    }

    /** True on the tick a key goes down, and again on each auto-repeat while it is held. */
    private static boolean repeats(boolean down, boolean wasDown, int heldTicks) {
        if (!down) {
            return false;
        }
        if (!wasDown) {
            return true; // key-down edge: always one step
        }
        int sinceDelay = heldTicks - REPEAT_DELAY_TICKS;
        return sinceDelay >= 0 && sinceDelay % REPEAT_EVERY_TICKS == 0;
    }

    /** True if the player currently has an active hand fan. */
    public boolean isActive(Player player) {
        return hands.containsKey(player.getUniqueId());
    }

    /** Move the selection by dir and refresh. */
    private void scroll(Hand hand, int dir) {
        if (hand.cards.isEmpty()) {
            return;
        }
        hand.selected = Math.max(0, Math.min(hand.cards.size() - 1, hand.selected + dir));
        updateItem(hand, false);
    }

    /** Play the selected card. Does nothing unless a game picks it up. */
    public void playSelected(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null || hand.cards.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - hand.lastPlayMs < INPUT_DEBOUNCE_MS) {
            return;
        }
        hand.lastPlayMs = now;
        if (cardActions != null) {
            cardActions.play(player, hand.selected);
        }
    }

    /** Draw a card. Does nothing unless a game picks it up. */
    public void drawCard(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - hand.lastDrawMs < INPUT_DEBOUNCE_MS) {
            return;
        }
        hand.lastDrawMs = now;
        if (cardActions != null) {
            cardActions.draw(player);
        }
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

    /**
     * Rebuild the custom_model_data strings and put the fan in the player's hand.
     *
     * @param sweep clear duplicate fan items from the rest of the inventory. Only worth doing
     *              when the hand contents changed — scanning 41 slots on every A/D press is
     *              pure waste.
     */
    private void updateItem(Hand hand, boolean sweep) {
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

        hand.player.getInventory().setItem(hand.fanSlot, item); // always the fixed fan slot
        if (sweep) {
            clearStrayFans(hand.player, hand.fanSlot);
        }
        if (n > 0) {
            messages.actionBar(hand.player, "hand.selected",
                    "card", hand.cards.get(hand.selected),
                    "index", hand.selected + 1,
                    "count", n);
        }
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** True if this stack is a UNO hand fan (wherever it turned up). */
    public boolean isHeldItem(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PAPER || !stack.hasItemMeta()) {
            return false;
        }
        return HELD_MODEL.equals(stack.getItemMeta().getItemModel());
    }

    /** Remove any UNO-hand items anywhere except {@code keepSlot} ({@code -1} = all of them). */
    private void clearStrayFans(Player player, int keepSlot) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (i != keepSlot && isHeldItem(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
        if (isHeldItem(inv.getItemInOffHand())) {
            inv.setItemInOffHand(null);
        }
    }

    private static String describe(ItemStack stack) {
        if (stack == null) {
            return "item";
        }
        String raw = stack.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Hand hand = hands.remove(event.getPlayer().getUniqueId());
        if (hand != null) {
            clearStrayFans(event.getPlayer(), -1);
            restoreDisplaced(event.getPlayer(), hand);
        }
    }

    /**
     * Dying must not scatter a 21-card fan on the ground for anyone to pick up — and must not
     * quietly keep a stack the fan had moved aside, which vanilla would have dropped.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent event) {
        Hand hand = hands.get(event.getEntity().getUniqueId());
        if (hand == null) {
            return;
        }
        event.getDrops().removeIf(this::isHeldItem);
        if (hand.displaced != null && !event.getKeepInventory()) {
            event.getDrops().add(hand.displaced); // exactly as if it had still been in the slot
            hand.displaced = null;
        }
    }

    /** Respawning wipes the inventory; put the fan back where the player can see it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isActive(player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Hand hand = hands.get(player.getUniqueId());
            if (hand == null || !player.isOnline()) {
                return;
            }
            clearStrayFans(player, -1);
            claimSlot(hand);
            updateItem(hand, true);
        });
    }

    // ---------------------------------------------------------------------- input

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

    /**
     * That same left-click is also a punch. Playing a card must not deal damage to whoever
     * happens to be standing in front of you.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttackWhileHolding(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && isActive(attacker)) {
            event.setCancelled(true);
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

    // ------------------------------------------------------------- fan protection

    /**
     * The fan is a real item in a real inventory, so without this a player can shift-click
     * their hand into a chest. Nothing may move a fan item, ever — including a stray one that
     * outlived its game.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isHeldItem(event.getCurrentItem()) || isHeldItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Number-key and offhand swaps move a slot the click itself never touches.
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && isHeldItem(player.getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND
                && isHeldItem(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isHeldItem(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        for (ItemStack dragged : event.getNewItems().values()) {
            if (isHeldItem(dragged)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Tear down all hands (plugin disable), returning anything the fans displaced. */
    public void shutdown() {
        if (pollTask != null) {
            pollTask.cancel();
        }
        for (Hand hand : new ArrayList<>(hands.values())) {
            if (hand.player.isOnline()) {
                clearStrayFans(hand.player, -1);
                restoreDisplaced(hand.player, hand);
            }
        }
        hands.clear();
    }
}
