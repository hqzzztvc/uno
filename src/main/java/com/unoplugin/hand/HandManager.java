package com.unoplugin.hand;

import com.unoplugin.util.Fx;
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
import org.bukkit.event.player.PlayerInputEvent;
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
 * {@code custom_model_data}. Held viewmodel -&gt; no lag, no clipping.
 *
 * <p><strong>The fan never costs a player an item.</strong> It claims an empty hotbar slot
 * where it can; only when the whole inventory is full does it move something aside, and that
 * stack is handed back — into the very slot it came out of — when the hand ends, on death, or
 * on quit. Nothing here ever writes the fan over a stack it did not put there: see
 * {@link #ensureSlot}.
 *
 * <p><strong>Input is edge-driven, not tick-driven.</strong> A/D arrive as
 * {@link PlayerInputEvent}s, which the client sends the moment a key changes state — so a tap
 * lands even when the server is running at 5 TPS and the per-tick poll would have sampled the
 * key already released. The poll that remains does three cheap things the event can't:
 * key-repeat while a key is held, a fallback edge check, and {@link #resync} — which puts the
 * fan back if a lag spike, a respawn or another plugin left the player holding something else.
 */
public final class HandManager implements Listener {

    /** Must match generate_held_fan.py (which reads these two constants out of this file). */
    private static final int MAX_SLOTS = 21;
    /** Per-card angular step for each density tier (must match DENSITIES in the generator). */
    private static final double[] DENSITIES = {16.0, 8.6, 5.0};
    /**
     * Total fan spread allowed on screen; the tier is the widest one that fits this.
     *
     * <p>Sized so the outermost card of the widest hand still lands inside a 4:3 viewport: the
     * fan is held to the lower right, so a card leaning far enough right leaves the screen —
     * and the card that gets scrolled to the end of the fan is precisely the one being looked
     * at. Keep spread * scale inside the frame if these are ever retuned.
     */
    private static final double MAX_SPAN = 112.0;
    private static final NamespacedKey HELD_MODEL = new NamespacedKey("uno", "held");
    /**
     * Play/draw guard. Left-click fires as both an animation and an interact, and holding the
     * button down repeats the swing — neither should read as a second move. A turn never comes
     * back around this fast, so nothing legitimate is swallowed.
     */
    private static final long INPUT_DEBOUNCE_MS = 250;
    /** Hold A/D to run along the fan: first repeat after this, then one every REPEAT_MS. */
    private static final long REPEAT_DELAY_MS = 300;
    private static final long REPEAT_MS = 110;
    /** How often a hand re-checks that its player is really still holding the fan. */
    private static final int RESYNC_TICKS = 20;

    /** Routes play/draw input into a game; returns true if a game handled it. */
    public interface CardActions {
        boolean play(Player player, int selectedIndex);

        boolean draw(Player player);
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Fx fx;
    private final Map<UUID, Hand> hands = new HashMap<>();
    private final BukkitTask pollTask;
    private CardActions cardActions;
    private int tick;

    public HandManager(Plugin plugin, Messages messages, Fx fx) {
        this.plugin = plugin;
        this.messages = messages;
        this.fx = fx;
        this.pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 1L, 1L);
    }

    public void setCardActions(CardActions cardActions) {
        this.cardActions = cardActions;
    }

    /** One player's hand state. */
    private static final class Hand {
        final Player player;
        /** Spreads the resync check across ticks so a full table doesn't all check at once. */
        final int stagger;
        List<String> cards;
        int selected;
        int fanSlot;   // the hotbar slot the fan item lives in (so the wheel can't move it)
        /** What used to be in that slot, when the inventory was too full to move it aside. */
        ItemStack displaced;
        /** The model strings currently on the item — skip the packet when nothing changed. */
        List<String> shown;
        boolean lastLeft;
        boolean lastRight;
        long repeatAt;
        long lastPlayMs;
        long lastDrawMs;

        Hand(Player player, List<String> cards) {
            this.player = player;
            this.cards = cards;
            this.selected = 0;
            this.fanSlot = -1;
            this.stagger = Math.floorMod(player.getUniqueId().hashCode(), RESYNC_TICKS);
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

    // ------------------------------------------------------------------ the slot

    /**
     * Pick the hotbar slot the fan will live in, without destroying anything.
     *
     * <p>Order of preference: the slot they're already holding if it's free, then any free
     * hotbar slot, then move the held stack into free inventory space. Only a completely full
     * inventory forces us to hold the stack aside, and that is handed back in
     * {@link #restoreDisplaced}.
     */
    private void claimSlot(Hand hand) {
        Player player = hand.player;
        PlayerInventory inv = player.getInventory();
        restoreDisplaced(player, hand); // never stack two displacements
        hand.shown = null;              // whatever slot we end up in starts empty

        int current = inv.getHeldItemSlot();
        if (isFree(inv.getItem(current))) {
            hand.fanSlot = current;
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isFree(inv.getItem(slot))) {
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
        // Nowhere to put it: keep it safe until the hand is over. It goes straight back into
        // this slot the moment the fan leaves it, so a full inventory costs nothing.
        hand.displaced = occupant == null ? null : occupant.clone();
        inv.setItem(current, null);
        messages.send(player, "hand.slot-held", "item", describe(occupant));
    }

    /**
     * The fan must be in {@code fanSlot} and nothing else may be, so re-claim if a real stack
     * has appeared there.
     *
     * <p>{@link #updateItem} writes the fan into that slot on every refresh — several times a
     * second while a player scrolls. Writing blind means anything that landed in the slot
     * meanwhile (a respawn, an inventory restore, another plugin) is silently overwritten, and
     * an item destroyed that way is gone for good.
     */
    private void ensureSlot(Hand hand) {
        PlayerInventory inv = hand.player.getInventory();
        if (hand.fanSlot < 0 || hand.fanSlot > 8 || !isFree(inv.getItem(hand.fanSlot))) {
            claimSlot(hand);
        }
    }

    /**
     * Give back the stack the fan pushed out of its slot, if there was one.
     *
     * <p>It came out of the fan slot, so once the fan is gone that slot is exactly the room it
     * needs — no {@code addItem} search that can come up short, and no drop on the ground for a
     * passer-by to collect. Only a slot that has since been taken falls back to that.
     */
    private void restoreDisplaced(Player player, Hand hand) {
        ItemStack back = hand.displaced;
        if (back == null) {
            return;
        }
        hand.displaced = null;
        PlayerInventory inv = player.getInventory();
        if (hand.fanSlot >= 0 && hand.fanSlot < 9 && isFree(inv.getItem(hand.fanSlot))) {
            inv.setItem(hand.fanSlot, back);
            return;
        }
        Map<Integer, ItemStack> leftover = inv.addItem(back);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            messages.send(player, "hand.dropped", "item", describe(overflow));
        }
    }

    /** True if the fan may take this slot: empty, or holding nothing but an old fan of ours. */
    private boolean isFree(ItemStack stack) {
        return isEmpty(stack) || isHeldItem(stack);
    }

    // --------------------------------------------------------------------- input

    /**
     * A/D, straight off the client's input packet.
     *
     * <p>This is what makes the fan feel instant on a busy server: the packet arrives when the
     * key changes, not when the server next gets round to a tick, so a quick tap during a lag
     * spike still scrolls exactly one card.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        Hand hand = hands.get(event.getPlayer().getUniqueId());
        if (hand != null) {
            applyInput(hand, event.getInput(), System.currentTimeMillis());
        }
    }

    /**
     * Per-tick upkeep: key-repeat while A/D is held, a fallback edge check in case an input
     * packet was never delivered, and a staggered {@link #resync}.
     */
    private void poll() {
        tick++;
        long now = System.currentTimeMillis();
        for (Hand hand : hands.values()) {
            if (!hand.player.isOnline()) {
                continue;
            }
            applyInput(hand, hand.player.getCurrentInput(), now);
            if (hand.repeatAt > 0 && now >= hand.repeatAt && hand.lastLeft != hand.lastRight) {
                // Running into the end of the fan stops the repeat: one blocked tick, not one
                // every 110 ms for as long as the key is down.
                hand.repeatAt = move(hand, hand.lastLeft ? 1 : -1) ? now + REPEAT_MS : 0;
            }
            if ((tick + hand.stagger) % RESYNC_TICKS == 0) {
                resync(hand);
            }
        }
    }

    /**
     * Act on a key going down. Idempotent: the event and the poll both call this with the same
     * state, and only a change from what we last saw counts as a press.
     */
    private void applyInput(Hand hand, Input in, long now) {
        boolean left = in.isLeft();
        boolean right = in.isRight();
        // Fan order runs right-to-left vs index, so A (left) increments to move the highlight
        // visually left, D (right) decrements to move it right.
        if (left != hand.lastLeft) {
            hand.lastLeft = left;
            if (left) {
                move(hand, 1);
                hand.repeatAt = now + REPEAT_DELAY_MS;
            }
        }
        if (right != hand.lastRight) {
            hand.lastRight = right;
            if (right) {
                move(hand, -1);
                hand.repeatAt = now + REPEAT_DELAY_MS;
            }
        }
        if (!left && !right) {
            hand.repeatAt = 0;
        }
    }

    /**
     * Put the fan back if the player isn't holding it any more.
     *
     * <p>A respawn, a lag spike that desynced the held slot, or another plugin touching the
     * inventory all leave a player who cannot play their hand at all. This costs a slot read
     * per player per second and fixes every one of them.
     */
    private void resync(Hand hand) {
        PlayerInventory inv = hand.player.getInventory();
        if (hand.fanSlot < 0 || !isHeldItem(inv.getItem(hand.fanSlot))) {
            hand.shown = null;
            updateItem(hand, true);
        }
        if (inv.getHeldItemSlot() != hand.fanSlot) {
            inv.setHeldItemSlot(hand.fanSlot);
        }
    }

    /** True if the player currently has an active hand fan. */
    public boolean isActive(Player player) {
        return hands.containsKey(player.getUniqueId());
    }

    /** Move the selection one step. False if it was already at that end of the fan. */
    private boolean move(Hand hand, int delta) {
        if (hand.cards.isEmpty()) {
            return false;
        }
        int next = Math.max(0, Math.min(hand.cards.size() - 1, hand.selected + delta));
        if (next == hand.selected) {
            fx.selectBlocked(hand.player);
            return false;
        }
        hand.selected = next;
        updateItem(hand, false);
        fx.select(hand.player, hand.selected, hand.cards.size());
        return true;
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

    // ------------------------------------------------------------------ the item

    /** Widest density tier whose total spread fits MAX_SPAN for n cards. */
    static int chooseTier(int n) {
        double step = (n <= 1) ? DENSITIES[0] : Math.min(DENSITIES[0], MAX_SPAN / (n - 1));
        for (int i = 0; i < DENSITIES.length; i++) {
            if (DENSITIES[i] <= step + 0.001) {
                return i;
            }
        }
        return DENSITIES.length - 1;
    }

    /**
     * How wide on screen a hand of {@code n} cards actually fans out, in degrees.
     *
     * <p>The fan is drawn to one side of the view, so a spread over {@link #MAX_SPAN} puts its
     * outermost card — often the selected one — off the edge of the screen. HandFanTest holds
     * this to the limit for every hand size a game can produce.
     */
    static double spanOf(int n) {
        int shown = Math.min(n, MAX_SLOTS);
        return shown <= 1 ? 0.0 : (shown - 1) * DENSITIES[chooseTier(shown)];
    }

    /** The custom_model_data string for each of the fan's slots. */
    private List<String> slotStrings(Hand hand) {
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
        return strings;
    }

    /**
     * Rebuild the custom_model_data strings and put the fan in the player's hand.
     *
     * <p>An unchanged fan is not rewritten: a hand refresh that didn't actually move anything
     * (and there are a lot of those — every game event re-renders every hand) would otherwise
     * cost every player at the table a slot packet for no visible change.
     *
     * @param sweep clear duplicate fan items from the rest of the inventory. Only worth doing
     *              when the hand contents changed — scanning 41 slots on every A/D press is
     *              pure waste.
     */
    private void updateItem(Hand hand, boolean sweep) {
        ensureSlot(hand); // never write over a stack that isn't ours
        PlayerInventory inv = hand.player.getInventory();
        List<String> strings = slotStrings(hand);
        if (!strings.equals(hand.shown) || !isHeldItem(inv.getItem(hand.fanSlot))) {
            inv.setItem(hand.fanSlot, fanItem(strings));
            hand.shown = strings;
        }
        if (sweep) {
            clearStrayFans(hand.player, hand.fanSlot);
        }
        if (!hand.cards.isEmpty()) {
            messages.actionBar(hand.player, "hand.selected",
                    "card", hand.cards.get(hand.selected),
                    "index", hand.selected + 1,
                    "count", hand.cards.size());
        }
    }

    private static ItemStack fanItem(List<String> strings) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(HELD_MODEL);
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(strings);
        meta.setCustomModelDataComponent(cmd);
        meta.displayName(Component.text("UNO Hand"));
        item.setItemMeta(meta);
        return item;
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

    /**
     * Mouse wheel = scroll the selection (kept on the fan item by cancelling the slot change).
     *
     * <p>A number key is a slot change too, and one that jumps several slots at once — that
     * pins the fan in place without flinging the selection halfway across the hand.
     */
    @EventHandler
    public void onScrollWheel(PlayerItemHeldEvent event) {
        Hand hand = hands.get(event.getPlayer().getUniqueId());
        if (hand == null) {
            return;
        }
        event.setCancelled(true);   // stay on the held fan item
        int delta = event.getNewSlot() - event.getPreviousSlot();
        if (delta == 8) {
            delta = -1; // wrapped 0 -> 8 (scrolled up)
        } else if (delta == -8) {
            delta = 1;  // wrapped 8 -> 0 (scrolled down)
        }
        if (delta == 1 || delta == -1) {
            move(hand, -delta); // scroll up -> highlight moves left
        }
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

    /**
     * Q (drop key) also plays — without actually dropping the item.
     *
     * <p>The play runs <strong>next tick</strong>, never inside this event. While a drop event
     * is being handled the fan has already been lifted out of the hand, and cancelling only
     * puts it back into the main hand if that slot is still empty — otherwise Paper
     * {@code addItem}s it into the first free slot. Playing a card here re-renders the fan
     * into that empty slot, so the server's put-back became a second fan in the hotbar, which
     * lingered until the player's hand next changed (most visibly in the colour picker).
     * A tick is not something anyone can feel — and the client swings its arm after a drop,
     * so the left-click handler usually plays the card first and the debounce drops this.
     */
    @EventHandler
    public void onDropPlay(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (isActive(player)) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    playSelected(player);
                }
            });
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
