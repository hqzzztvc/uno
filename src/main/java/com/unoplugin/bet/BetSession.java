package com.unoplugin.bet;

import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One table's gambling session: who has staked what, who's still in, and which UNO hand
 * (if any) is currently deciding it.
 *
 * <p><strong>Attribution never gets lost.</strong> Everything ever staked — items and money
 * alike — stays filed under the player who staked it, even after they forfeit: that record is
 * what makes a crash refundable. Who <em>wins</em> the pot is a separate question, answered by
 * {@link #live} and the hand result; the pot is only re-attributed at payout time.
 */
public final class BetSession {

    /** ANTE: taking bets. PLAYING: a hand is deciding the pot. RIDE: winner choosing. */
    public enum State { ANTE, PLAYING, RIDE }

    private final UUID tableId;
    private final UUID hostId;

    /** Original staker -> everything they've put in. Survives forfeit; drives refunds. */
    private final Map<UUID, Stake> stakes = new LinkedHashMap<>();
    /** Players still contesting the pot (a forfeit removes you; your items stay in). */
    private final Set<UUID> live = new LinkedHashSet<>();
    private final Set<UUID> ready = new LinkedHashSet<>();

    private State state = State.ANTE;
    private UUID gameId;
    private UUID rideWinner;
    private int handNumber = 0;
    private int botCount = 0;
    private PotRenderer renderer;
    private BukkitTask timer;

    public BetSession(UUID tableId, UUID hostId) {
        this.tableId = tableId;
        this.hostId = hostId;
    }

    // ------------------------------------------------------------------ staking

    /** Add one dropped stack to a player's ante; they join the bet if they hadn't yet. */
    public void stake(UUID player, ItemStack stack) {
        add(player, Stake.ofItem(stack));
    }

    /** Add money to a player's ante. Already withdrawn from their balance by the caller. */
    public void stakeMoney(UUID player, double amount) {
        add(player, Stake.ofMoney(amount));
    }

    private void add(UUID player, Stake more) {
        stakes.merge(player, more, Stake::plus);
        live.add(player);
    }

    /** Everything one player has staked ({@link Stake#NONE} if nothing) — the refund. */
    public Stake stakeOf(UUID player) {
        return stakes.getOrDefault(player, Stake.NONE);
    }

    /** Pull a player out entirely and return what they get back. ANTE only. */
    public Stake withdraw(UUID player) {
        Stake back = stakes.remove(player);
        live.remove(player);
        ready.remove(player);
        return back == null ? Stake.NONE : back;
    }

    /**
     * A player is out of the running but their items stay in the pot — the rage-quit rule.
     * Attribution is intentionally left alone so a crash still refunds them.
     */
    public void forfeit(UUID player) {
        live.remove(player);
        ready.remove(player);
    }

    /** Hand the whole pot to one owner, on paper — used when a win rides into the next hand. */
    public void reattributeTo(UUID owner) {
        Stake all = pot();
        stakes.clear();
        if (!all.isEmpty()) {
            stakes.put(owner, all);
        }
    }

    /** Everything in the pot, items and money, regardless of who staked it. */
    public Stake pot() {
        Stake all = Stake.NONE;
        for (Stake s : stakes.values()) {
            all = all.plus(s);
        }
        return all;
    }

    /** Items in the pot, counted the way players count them. Money is reported separately. */
    public int potSize() {
        return pot().itemCount();
    }

    public double potMoney() {
        return pot().money();
    }

    public Set<UUID> stakers() {
        return stakes.keySet();
    }

    public boolean hasStaked(UUID player) {
        return !stakeOf(player).isEmpty();
    }

    // ------------------------------------------------------------------- players

    public Set<UUID> live() {
        return live;
    }

    public boolean isLive(UUID player) {
        return live.contains(player);
    }

    public boolean isReady(UUID player) {
        return ready.contains(player);
    }

    public void setReady(UUID player) {
        ready.add(player);
    }

    public void clearReady() {
        ready.clear();
    }

    public int readyCount() {
        return ready.size();
    }

    /**
     * Everyone still in has locked their ante in, and there are enough players to deal to.
     *
     * <p>Bots count toward the head count, the same way {@code /gamble go} counts them — one
     * human plus bots is a table. Without that this returned false forever on such a session
     * and the only way to start was the host's own force-start.
     */
    public boolean allReady() {
        return !live.isEmpty() && live.size() + botCount >= 2 && ready.containsAll(live);
    }

    // -------------------------------------------------------------------- state

    public UUID tableId() {
        return tableId;
    }

    public UUID hostId() {
        return hostId;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public UUID gameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public UUID rideWinner() {
        return rideWinner;
    }

    public void setRideWinner(UUID rideWinner) {
        this.rideWinner = rideWinner;
    }

    public int handNumber() {
        return handNumber;
    }

    public void nextHand() {
        handNumber++;
    }

    public int botCount() {
        return botCount;
    }

    public void setBotCount(int botCount) {
        this.botCount = botCount;
    }

    public PotRenderer renderer() {
        return renderer;
    }

    public void setRenderer(PotRenderer renderer) {
        this.renderer = renderer;
    }

    /** Replace the pending timeout task, cancelling whatever was scheduled before. */
    public void setTimer(BukkitTask timer) {
        cancelTimer();
        this.timer = timer;
    }

    public void cancelTimer() {
        if (this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }
    }
}
