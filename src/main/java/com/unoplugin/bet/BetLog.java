package com.unoplugin.bet;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Append-only record of everything that happens to players' items:
 * {@code plugins/UNO/bets.log}.
 *
 * <p>A gambling feature without one cannot answer "he took my diamonds" — escrow.yml only
 * says what is held <em>now</em>, never who put it there or where it went.
 *
 * <p>Lines are written on a single background thread: the log is evidence, not state
 * (escrow.yml is the source of truth), so it must never cost the server a disk write mid-tick.
 * The queue is drained on plugin disable.
 */
public final class BetLog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Plugin plugin;
    private final Path file;
    private final boolean enabled;
    private final ExecutorService writer;

    public BetLog(Plugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.file = plugin.getDataFolder().toPath().resolve("bets.log");
        this.writer = enabled
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "UNO-bet-log");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    /**
     * Record one event.
     *
     * @param action   STAKE / PAYOUT / REFUND / FORFEIT / OPEN / CANCEL / RIDE
     * @param tableId  the table it happened at
     * @param actor    whose items moved
     * @param actorName their name at the time (UUIDs alone are useless in a dispute)
     * @param items    the stacks involved (may be empty)
     * @param note     free-text detail, e.g. the pot size
     */
    public void record(String action, UUID tableId, UUID actor, String actorName,
                       List<ItemStack> items, String note) {
        if (!enabled) {
            return;
        }
        String line = STAMP.format(Instant.now())
                + " | " + pad(action)
                + " | table=" + shortId(tableId)
                + " | player=" + actorName + " (" + actor + ")"
                + " | items=" + describe(items)
                + (note == null || note.isEmpty() ? "" : " | " + note)
                + System.lineSeparator();
        append(line);
    }

    /** Record something that isn't tied to one player's items. */
    public void note(String action, UUID tableId, String note) {
        if (!enabled) {
            return;
        }
        append(STAMP.format(Instant.now())
                + " | " + pad(action)
                + " | table=" + shortId(tableId)
                + " | " + note
                + System.lineSeparator());
    }

    private void append(String line) {
        writer.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | UncheckedIOException e) {
                plugin.getLogger().warning("Could not append to bets.log: " + e.getMessage());
            }
        });
    }

    /** Drain the queue on plugin disable so nothing in flight is lost. */
    public void shutdown() {
        if (writer == null) {
            return;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("bets.log writer didn't finish in time — some lines may be missing.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String pad(String action) {
        return action.length() >= 7 ? action : (action + "       ").substring(0, 7);
    }

    private static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }

    private static String describe(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(EscrowStore.count(items)).append(" [");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (i == 12) {
                sb.append("+").append(items.size() - 12).append(" more");
                break;
            }
            ItemStack s = items.get(i);
            sb.append(s.getAmount()).append("x ").append(s.getType().name());
        }
        return sb.append(']').toString();
    }
}
