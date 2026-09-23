package com.legallynotuno.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells admins whether a newer Legally Not Uno is out, and where to get it.
 *
 * <p>The answer comes from Modrinth's public API rather than from the GitHub repo, which is
 * private (a server can't read it, and no token can ship inside a jar). It is also the right
 * source: Modrinth is where the download is, so "an update is available" can only ever mean
 * "there is one to download at the link". Releasing is just publishing a version there whose
 * version number is the pom's {@code <version>}.
 *
 * <p>It asks once at startup (so the console says) and again as each {@code legallynotuno.admin} player
 * joins or runs {@code /uno version}, reusing an answer less than {@link #FRESH_FOR} old so a
 * restart that brings every admin back at once is still one request. It never blocks the main
 * thread, never messages anyone about a check that failed (the console hears about a failure
 * once), and after a failure keeps telling admins the last answer it did get.
 */
public final class UpdateChecker implements Listener {

    /** Where the notice sends people. */
    public static final String PAGE = "https://modrinth.com/project/legallynotuno";

    static final URI VERSIONS =
            URI.create("https://api.modrinth.com/v2/project/legallynotuno/version?include_changelog=false");

    /** The Modrinth loaders a build of this plugin can be published under. */
    private static final Set<String> LOADERS = Set.of("paper", "purpur", "folia", "spigot", "bukkit");

    private static final Duration FRESH_FOR = Duration.ofMinutes(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Let the join spam scroll past first, or the notice is buried under it. */
    private static final long JOIN_DELAY_TICKS = 40L;
    private static final String ADMIN = "legallynotuno.admin";

    enum Status { CURRENT, OUTDATED }

    record Result(Status status, String latest) {}

    private record Outcome(Result result, String failure) {}

    private final Plugin plugin;
    private final Settings settings;
    private final Messages messages;
    private final String current;

    // Everything below is touched on the main thread only; the HTTP thread hands its answer
    // back through the scheduler.
    private HttpClient http;
    private Result last;
    private Instant lastAttempt;
    private boolean fetching;
    private final List<Consumer<Result>> waiting = new ArrayList<>();
    private Result logged;
    private String lastFailure;

    public UpdateChecker(Plugin plugin, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.current = plugin.getPluginMeta().getVersion();
    }

    /** The startup check: the console gets the answer as soon as it arrives. */
    public void start() {
        withResult(result -> { });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!settings.updateChecks() || !player.hasPermission(ADMIN)) {
            return;
        }
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player still = Bukkit.getPlayer(id);
            if (still != null) {
                tell(still);
            }
        }, JOIN_DELAY_TICKS);
    }

    /**
     * Tell {@code who} whether they are up to date, checking first if the last answer is
     * stale. Says nothing if checks are off or no check has ever succeeded.
     */
    public void tell(CommandSender who) {
        withResult(result -> {
            if (who instanceof Player player && !player.isOnline()) {
                return;
            }
            if (result.status() == Status.OUTDATED) {
                messages.send(who, "plugin.update-available", "latest", result.latest(),
                        "current", current,
                        "download", messages.link("plugin.update-download-button", PAGE));
            } else {
                messages.send(who, "plugin.update-current", "current", current);
            }
        });
    }

    public void shutdown() {
        if (http != null) {
            http.shutdownNow();
            http = null;
        }
        waiting.clear();
    }

    // ---------------------------------------------------------------- fetching

    private void withResult(Consumer<Result> then) {
        if (!settings.updateChecks()) {
            return;
        }
        if (fetching) {
            waiting.add(then);
            return;
        }
        Instant now = Instant.now();
        if (lastAttempt != null && Duration.between(lastAttempt, now).compareTo(FRESH_FOR) < 0) {
            if (last != null) {
                then.accept(last);
            }
            return;
        }
        waiting.add(then);
        fetching = true;
        lastAttempt = now;
        HttpRequest request = HttpRequest.newBuilder(VERSIONS)
                .timeout(REQUEST_TIMEOUT)
                // Modrinth asks every client to identify itself.
                .header("User-Agent", "hqzzztvc/legallynotuno/" + current
                        + " (modrinth.com/project/legallynotuno)")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            client().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    // The request timeout stops at the headers; this covers a body that stalls.
                    .orTimeout(REQUEST_TIMEOUT.multipliedBy(2).toSeconds(), TimeUnit.SECONDS)
                    .handle(this::interpret)
                    .thenAccept(outcome -> onMainThread(() -> finish(outcome)));
        } catch (RuntimeException e) {
            finish(new Outcome(null, describe(e)));
        }
    }

    private HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return http;
    }

    /** Runs on the HTTP thread: pure, touches nothing shared. */
    private Outcome interpret(HttpResponse<String> response, Throwable error) {
        if (error != null) {
            return new Outcome(null, describe(error));
        }
        int code = response.statusCode();
        if (code == 404) {
            return new Outcome(null, "Modrinth has no public project at " + PAGE + " (HTTP 404)");
        }
        if (code != 200) {
            return new Outcome(null, "Modrinth answered HTTP " + code);
        }
        try {
            String latest = newestRelease(response.body());
            if (latest == null) {
                return new Outcome(null, "Modrinth doesn't list a release for this platform yet");
            }
            Status status = compareVersions(latest, current) > 0 ? Status.OUTDATED : Status.CURRENT;
            return new Outcome(new Result(status, latest), null);
        } catch (RuntimeException e) {
            return new Outcome(null, "couldn't read Modrinth's answer (" + describe(e) + ")");
        }
    }

    private void finish(Outcome outcome) {
        fetching = false;
        if (outcome.result() != null) {
            last = outcome.result();
            lastFailure = null;
            logToConsole(last);
        } else if (!outcome.failure().equals(lastFailure)) {
            lastFailure = outcome.failure();
            plugin.getLogger().warning("Couldn't check for updates: " + outcome.failure() + ".");
        }
        List<Consumer<Result>> ready = new ArrayList<>(waiting);
        waiting.clear();
        if (last != null) {
            for (Consumer<Result> then : ready) {
                then.accept(last);
            }
        }
    }

    /** Once per distinct answer, so a server up for a month doesn't repeat itself. */
    private void logToConsole(Result result) {
        if (result.equals(logged)) {
            return;
        }
        logged = result;
        if (result.status() == Status.OUTDATED) {
            plugin.getLogger().warning("Legally Not Uno " + result.latest() + " is out (this server "
                    + "runs " + current + "). Download it from " + PAGE);
        } else {
            plugin.getLogger().info("Legally Not Uno is up to date (" + current + ").");
        }
    }

    private void onMainThread(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            // Disabled between the check above and here: nobody is left to tell.
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    // ------------------------------------------------------ reading the answer

    /**
     * The highest release version number in Modrinth's version list that runs on this
     * platform, or null if there is none. Betas, alphas, unlisted versions and builds for other
     * loaders never count: an admin should only ever be pointed at something they can install.
     */
    static String newestRelease(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("expected a list of versions");
        }
        String best = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            String status = string(version, "status");
            String number = string(version, "version_number");
            if (!"release".equals(string(version, "version_type"))
                    || (status != null && !status.equals("listed"))
                    || !forThisPlatform(version)
                    || number == null || !VERSION.matcher(number).find()) {
                continue;
            }
            if (best == null || compareVersions(number, best) > 0) {
                best = number;
            }
        }
        return best;
    }

    private static boolean forThisPlatform(JsonObject version) {
        JsonElement loaders = version.get("loaders");
        if (loaders == null || !loaders.isJsonArray() || loaders.getAsJsonArray().isEmpty()) {
            return true;
        }
        JsonArray list = loaders.getAsJsonArray();
        for (JsonElement loader : list) {
            if (loader.isJsonPrimitive()
                    && LOADERS.contains(loader.getAsString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * The first run of dot-separated numbers in the string, and the pre-release tag after a
     * hyphen if there is one. A leading "v" and trailing "+build" metadata are ignored.
     */
    private static final Pattern VERSION =
            Pattern.compile("(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?");

    /**
     * Semantic-version order: numbers compared as numbers (so 1.10 beats 1.9), missing parts
     * count as zero (1.0 equals 1.0.0), and a pre-release sorts below its release
     * (1.1-beta.2 is older than 1.1).
     *
     * @throws IllegalArgumentException if either has no version number in it at all
     */
    static int compareVersions(String a, String b) {
        Matcher ma = match(a);
        Matcher mb = match(b);
        String[] na = ma.group(1).split("\\.");
        String[] nb = mb.group(1).split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            int c = compareNumbers(i < na.length ? na[i] : "0", i < nb.length ? nb[i] : "0");
            if (c != 0) {
                return c;
            }
        }
        String qa = ma.group(2);
        String qb = mb.group(2);
        if (qa == null || qb == null) {
            return qa == null ? (qb == null ? 0 : 1) : -1;
        }
        String[] pa = qa.split("\\.");
        String[] pb = qb.split("\\.");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            boolean numA = pa[i].chars().allMatch(Character::isDigit);
            boolean numB = pb[i].chars().allMatch(Character::isDigit);
            int c = numA && numB ? compareNumbers(pa[i], pb[i])
                    : numA != numB ? (numA ? -1 : 1)
                    : pa[i].compareToIgnoreCase(pb[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static Matcher match(String version) {
        Matcher m = VERSION.matcher(version == null ? "" : version);
        if (!m.find()) {
            throw new IllegalArgumentException("no version number in '" + version + "'");
        }
        return m;
    }

    /** Compared as numbers of any length, without parsing them into something that overflows. */
    private static int compareNumbers(String a, String b) {
        String x = a.replaceFirst("^0+(?=.)", "");
        String y = b.replaceFirst("^0+(?=.)", "");
        return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
    }
}
