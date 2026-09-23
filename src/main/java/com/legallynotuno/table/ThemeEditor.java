package com.legallynotuno.table;

import com.legallynotuno.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The in-game theme editor: a chest window laid out like the table you are designing.
 *
 * <p>Nine slots in the middle are the 3×3 top, seen from above with the FAR side of the table
 * at the top of the window, and one slot centred on each edge is that side's seat. Drop a
 * block in each, hit save, and the theme is written to {@code themes.yml}.
 *
 * <p>The window is a real inventory the player drops real items into, rather than a
 * click-to-cycle picker, for one reason: it is the only way to support whatever blocks the
 * server actually has. A picker has to enumerate its options, so it can only ever offer what
 * the plugin was compiled knowing about; an inventory takes anything a player can hold,
 * including a custom block from ItemsAdder, Oraxen or Nexo.
 *
 * <p>Items are BORROWED, never taken. Everything placed is handed straight back when the
 * window closes, so designing a theme costs nothing — see {@link #returnContents}.
 */
public final class ThemeEditor implements Listener {

    /** Where each piece of the table lives in a 54-slot (6-row) window. */
    private static final int[] GRID_SLOTS = {
            12, 13, 14,   // far row
            21, 22, 23,   // middle row
            30, 31, 32};  // near row
    private static final int SEAT_FAR = 4;
    private static final int SEAT_LEFT = 20;
    private static final int SEAT_RIGHT = 24;
    private static final int SEAT_NEAR = 40;
    private static final int SAVE_SLOT = 49;
    private static final int CANCEL_SLOT = 45;

    private static final int SIZE = 54;

    private final Plugin plugin;
    private final Messages messages;
    private final ThemeStore themes;
    private final CustomBlocks customBlocks;
    private final Map<UUID, Session> open = new HashMap<>();

    /** One player's editing session. */
    private static final class Session {
        final String themeId;
        final String displayName;
        final Inventory inventory;
        /** Set once the theme is saved, so closing doesn't read it as a cancel. */
        boolean saved;

        Session(String themeId, String displayName, Inventory inventory) {
            this.themeId = themeId;
            this.displayName = displayName;
            this.inventory = inventory;
        }
    }

    /**
     * Marks our inventory as ours, so a click in any other chest is none of our business.
     *
     * <p>Holds the inventory rather than throwing from {@code getInventory()}: Bukkit and other
     * plugins are entitled to call it on any holder they are handed, and a marker that throws
     * turns an unrelated plugin's inventory sweep into a stack trace.
     */
    private static final class EditorHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public ThemeEditor(Plugin plugin, Messages messages, ThemeStore themes,
                       CustomBlocks customBlocks) {
        this.plugin = plugin;
        this.messages = messages;
        this.themes = themes;
        this.customBlocks = customBlocks;
    }

    /** Open the editor for a theme id, pre-filled if that theme already exists. */
    public void open(Player player, String rawId) {
        String id = TableTheme.normaliseId(rawId);
        if (id.isEmpty()) {
            messages.send(player, "theme.bad-id");
            return;
        }
        TableTheme existing = themes.get(id);
        if (existing != null && existing.builtIn()) {
            // Editing a built-in in place would leave the server with no way back to the
            // look it shipped with. Copying it under a new name costs the player one word.
            messages.send(player, "theme.built-in-locked", "theme", id);
            return;
        }
        EditorHolder holder = new EditorHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Component.text("Table theme: " + id, NamedTextColor.DARK_GRAY));
        holder.inventory = inv;
        decorate(inv);
        if (existing != null) {
            prefill(inv, existing);
        }
        Session session = new Session(id,
                existing == null ? id : existing.displayName(), inv);
        open.put(player.getUniqueId(), session);
        player.openInventory(inv);
        messages.send(player, "theme.editor-opened");
    }

    /** Fill the background and drop the save/cancel buttons in. */
    private void decorate(Inventory inv) {
        ItemStack filler = labelled(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler);
        }
        for (int slot : GRID_SLOTS) {
            inv.setItem(slot, null);
        }
        for (int slot : new int[]{SEAT_FAR, SEAT_LEFT, SEAT_RIGHT, SEAT_NEAR}) {
            inv.setItem(slot, null);
        }
        inv.setItem(SAVE_SLOT, labelled(Material.LIME_CONCRETE, "Save theme",
                "Write this table to themes.yml"));
        inv.setItem(CANCEL_SLOT, labelled(Material.RED_CONCRETE, "Cancel",
                "Close without saving"));
        // Signposts, so it is obvious which slot is which side of the table.
        inv.setItem(3, labelled(Material.GRAY_STAINED_GLASS_PANE, "Far seat →", null));
        inv.setItem(41, labelled(Material.GRAY_STAINED_GLASS_PANE, "← Near seat", null));
        inv.setItem(19, labelled(Material.GRAY_STAINED_GLASS_PANE, "Left seat →", null));
        inv.setItem(25, labelled(Material.GRAY_STAINED_GLASS_PANE, "← Right seat", null));
    }

    private void prefill(Inventory inv, TableTheme theme) {
        for (int row = 0; row < TableTheme.SIZE; row++) {
            for (int col = 0; col < TableTheme.SIZE; col++) {
                Material m = theme.cell(row, col).material(customBlocks);
                inv.setItem(GRID_SLOTS[row * TableTheme.SIZE + col], new ItemStack(m));
            }
        }
        inv.setItem(SEAT_FAR, new ItemStack(theme.seat(TableTheme.Seat.FAR).material(customBlocks)));
        inv.setItem(SEAT_NEAR, new ItemStack(theme.seat(TableTheme.Seat.NEAR).material(customBlocks)));
        inv.setItem(SEAT_LEFT, new ItemStack(theme.seat(TableTheme.Seat.LEFT).material(customBlocks)));
        inv.setItem(SEAT_RIGHT, new ItemStack(theme.seat(TableTheme.Seat.RIGHT).material(customBlocks)));
    }

    private static ItemStack labelled(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        if (lore != null) {
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        }
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = open.get(player.getUniqueId());
        if (session == null) {
            event.setCancelled(true);
            return;
        }
        // Clicks in the player's own inventory are theirs to make; only the top half is ours.
        if (event.getClickedInventory() != session.inventory) {
            // Shift-clicking from below would land in whichever slot came first, including a
            // button. Placing is done by picking the item up and clicking a slot.
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        int slot = event.getSlot();
        if (slot == SAVE_SLOT) {
            event.setCancelled(true);
            save(player, session);
            return;
        }
        if (slot == CANCEL_SLOT) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (!isEditable(slot)) {
            event.setCancelled(true);
        }
    }

    private static boolean isEditable(int slot) {
        for (int s : GRID_SLOTS) {
            if (s == slot) {
                return true;
            }
        }
        return slot == SEAT_FAR || slot == SEAT_NEAR || slot == SEAT_LEFT || slot == SEAT_RIGHT;
    }

    private void save(Player player, Session session) {
        BlockSpec[][] grid = new BlockSpec[TableTheme.SIZE][TableTheme.SIZE];
        for (int row = 0; row < TableTheme.SIZE; row++) {
            for (int col = 0; col < TableTheme.SIZE; col++) {
                BlockSpec spec = specOf(session.inventory.getItem(
                        GRID_SLOTS[row * TableTheme.SIZE + col]));
                if (spec == null) {
                    messages.send(player, "theme.incomplete");
                    return;
                }
                grid[row][col] = spec;
            }
        }
        BlockSpec[] seats = new BlockSpec[TableTheme.Seat.values().length];
        int[] seatSlots = {SEAT_NEAR, SEAT_FAR, SEAT_LEFT, SEAT_RIGHT}; // Seat enum order
        for (int i = 0; i < seatSlots.length; i++) {
            BlockSpec spec = specOf(session.inventory.getItem(seatSlots[i]));
            if (spec == null) {
                messages.send(player, "theme.incomplete");
                return;
            }
            seats[i] = spec;
        }

        if (!themes.put(new TableTheme(session.themeId, session.displayName, grid, seats, false))) {
            messages.send(player, "theme.file-broken");
            return;
        }
        session.saved = true;
        messages.send(player, "theme.saved", "theme", session.themeId);
        player.closeInventory();
    }

    /**
     * What block a placed item stands for, or null if it isn't one.
     *
     * <p>A stack whose material isn't a placeable block (a sword, a potion) is refused rather
     * than silently swapped for something else — a table cell has to be a block.
     */
    private BlockSpec specOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        Material m = stack.getType();
        if (!m.isBlock()) {
            return null;
        }
        return BlockSpec.of(m);
    }

    /**
     * Hand back everything the player put in the window.
     *
     * <p>The editor borrows blocks to show a design; it must never cost a player the stack
     * they built it with. Anything that no longer fits is dropped at their feet rather than
     * deleted.
     */
    private void returnContents(Player player, Session session) {
        List<ItemStack> giveBack = new ArrayList<>();
        for (int slot : GRID_SLOTS) {
            collect(session.inventory.getItem(slot), giveBack);
        }
        for (int slot : new int[]{SEAT_FAR, SEAT_NEAR, SEAT_LEFT, SEAT_RIGHT}) {
            collect(session.inventory.getItem(slot), giveBack);
        }
        for (ItemStack stack : giveBack) {
            for (ItemStack overflow : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
    }

    private static void collect(ItemStack stack, List<ItemStack> out) {
        if (stack != null && !stack.getType().isAir()) {
            out.add(stack.clone());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = open.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        returnContents(player, session);
        if (!session.saved) {
            messages.send(player, "theme.editor-cancelled");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Closing happens on quit anyway, but the close handler runs against a player who is
        // already leaving; putting the blocks back here means they are in the inventory that
        // gets saved rather than dropped into a world they are no longer in.
        Session session = open.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            returnContents(event.getPlayer(), session);
        }
    }

    /** Close every open editor — the plugin is going down and the windows must not outlive it. */
    public void shutdown() {
        for (Map.Entry<UUID, Session> e : new HashMap<>(open).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                returnContents(p, e.getValue());
                p.closeInventory();
            }
        }
        open.clear();
    }
}
