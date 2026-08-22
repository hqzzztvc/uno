package com.unoplugin.bet;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What one player (or a whole pot) has on the table: items, money, or both.
 *
 * <p>Items and currency are the same thing to every rule the wagering layer has — a stake is
 * refunded, forfeited, re-attributed and paid out as a unit — so they travel as one value
 * rather than as two parallel maps that some code path eventually forgets to keep in step.
 * That was the whole risk in adding money: a refund that hands back the diamonds and quietly
 * keeps the cash is indistinguishable from theft.
 *
 * <p>Immutable, and every operation returns a new one. {@link #items()} is an unmodifiable
 * view of live {@link ItemStack}s — whoever hands them to Bukkit clones them
 * ({@link EscrowStore#give}), the same as before.
 */
public record Stake(List<ItemStack> items, double money) {

    /** Nothing staked. */
    public static final Stake NONE = new Stake(List.of(), 0.0);

    public Stake {
        items = List.copyOf(items);
        money = Math.max(0.0, money);
    }

    public static Stake ofItem(ItemStack stack) {
        return new Stake(List.of(stack.clone()), 0.0);
    }

    public static Stake ofMoney(double amount) {
        return new Stake(List.of(), amount);
    }

    /** Total item count across stacks (8 diamonds + 3 emeralds = 11). Money is not counted. */
    public int itemCount() {
        return EscrowStore.count(items);
    }

    public boolean isEmpty() {
        return items.isEmpty() && money <= 0.0;
    }

    public boolean hasMoney() {
        return money > 0.0;
    }

    /** This stake plus another — how a pot is totted up, and how a raise is recorded. */
    public Stake plus(Stake other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        List<ItemStack> merged = new ArrayList<>(items.size() + other.items.size());
        merged.addAll(items);
        merged.addAll(other.items);
        return new Stake(merged, money + other.money);
    }
}
