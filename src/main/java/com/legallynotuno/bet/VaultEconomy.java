package com.legallynotuno.bet;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Server currency, through Vault, reached reflectively.
 *
 * <p>Same reasoning as {@link com.legallynotuno.table.CustomBlocks}: Vault is not on any Maven
 * repository this build pulls from, so compiling against it would make the plugin unbuildable
 * for anyone without its jar, and a server that doesn't run it must not pay for its absence
 * with a {@code NoClassDefFoundError} the first time somebody types {@code /gamble 500}.
 * With no Vault, or nothing registered with it, {@link #available()} is false and the wagering
 * layer offers items only.
 *
 * <p>Every call is wrapped. An economy plugin that refuses an {@link OfflinePlayer} — a few
 * do — makes the call return false rather than throw, which the callers already treat as
 * "the transaction didn't happen": a stake is refused, and a payout stays in escrow for the
 * player's next login instead of evaporating.
 *
 * <p><strong>Detection retries.</strong> Vault holds no money itself: the provider comes from a
 * THIRD plugin (EssentialsX and friends) registering with it, and that happens in that plugin's
 * own enable. {@code softdepend: Vault} only orders us after Vault, not after whoever fills it,
 * so a one-shot lookup in the constructor is a coin flip on load order — and losing it means
 * money is silently off until the next restart. The startup lookup is for the log line; the
 * real answer is worked out again on first use.
 */
public final class VaultEconomy {

    private final Logger log;

    private Object economy;
    private Method has;
    private Method withdraw;
    private Method deposit;
    private Method balance;
    private Method format;
    private Method success;

    /** Set once a call throws, so a broken API logs one line rather than one per transaction. */
    private boolean warned = false;
    /** True once detection has an answer that cannot change: no Vault, or an unreadable API. */
    private boolean settled = false;
    /** Logged at most once, so a server with Vault and no economy doesn't repeat itself. */
    private boolean announcedEmpty = false;

    public VaultEconomy(Plugin plugin) {
        this.log = plugin.getLogger();
        detect();
    }

    private void detect() {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            settled = true;
            return;
        }
        try {
            Class<?> api = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(api);
            if (registration == null || registration.getProvider() == null) {
                if (!announcedEmpty) {
                    announcedEmpty = true;
                    log.info("Vault is installed but nothing has registered an economy with"
                            + " it yet — checking again the first time somebody wagers money.");
                }
                return; // deliberately NOT settled: the provider may arrive after us
            }
            Class<?> response = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            has = api.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = api.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = api.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            balance = api.getMethod("getBalance", OfflinePlayer.class);
            format = api.getMethod("format", double.class);
            success = response.getMethod("transactionSuccess");
            economy = registration.getProvider();
            log.info("Money wagers: hooked Vault economy '" + name() + "'.");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ex) {
            log.warning("Vault is installed but its economy API could not be read ("
                    + ex.getClass().getSimpleName() + ") — money wagers are off,"
                    + " items still work.");
            economy = null;
            settled = true;
        }
    }

    /**
     * True if money can actually change hands here. Everything else no-ops without it.
     *
     * <p>Re-runs detection while the answer could still change — see the class note on load
     * order. Once it settles (no Vault, or an API we can't read) this is a field read.
     */
    public boolean available() {
        if (economy == null && !settled) {
            detect();
        }
        return economy != null;
    }

    /** The economy plugin's own name, for the startup line. */
    private String name() {
        try {
            return String.valueOf(economy.getClass().getMethod("getName").invoke(economy));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return economy.getClass().getSimpleName();
        }
    }

    public boolean has(Player player, double amount) {
        available(); // may still be waiting on the economy plugin's own enable
        Boolean ok = call(has, Boolean.class, player, amount);
        return Boolean.TRUE.equals(ok);
    }

    public double balanceOf(Player player) {
        Double value = call(balance, Double.class, player);
        return value == null ? 0.0 : value;
    }

    /**
     * Take {@code amount} out of a player's balance. True only if it really left.
     *
     * <p>The caller records the stake in escrow immediately afterwards, in that order on
     * purpose: the reverse — writing escrow first — would have a crash in between hand back
     * money the player never actually paid, and minting currency is a worse failure than the
     * vanishingly rare loss of one stake.
     */
    public boolean withdraw(Player player, double amount) {
        return amount > 0 && transacted(withdraw, player, amount);
    }

    /** Pay {@code amount} into an account, online or not. False means it did not land. */
    public boolean deposit(UUID owner, double amount) {
        if (amount <= 0 || owner == null || !available()) {
            return false;
        }
        return transacted(deposit, Bukkit.getOfflinePlayer(owner), amount);
    }

    /** The amount as the economy plugin writes it ("$1,200.00"), for player-facing text. */
    public String format(double amount) {
        String out = call(format, String.class, amount);
        // Not a fallback worth dressing up: without an economy there is no money to format,
        // and this only shows up in a message that the callers already suppress.
        return out == null ? String.valueOf(amount) : out;
    }

    // ----------------------------------------------------------------- plumbing

    /** Run a method returning an EconomyResponse and report whether it succeeded. */
    private boolean transacted(Method method, OfflinePlayer who, double amount) {
        Object response = call(method, Object.class, who, amount);
        if (response == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(success.invoke(response));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            complain(ex);
            return false;
        }
    }

    private <T> T call(Method method, Class<T> type, Object... args) {
        if (economy == null || method == null) {
            return null;
        }
        try {
            Object out = method.invoke(economy, args);
            return type.isInstance(out) ? type.cast(out) : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            complain(ex);
            return null;
        }
    }

    private void complain(Exception ex) {
        if (warned) {
            return;
        }
        warned = true;
        log.warning("The Vault economy threw on a transaction ("
                + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                + "). Money wagers will keep being refused; items are unaffected."
                + " This is logged once.");
    }
}
