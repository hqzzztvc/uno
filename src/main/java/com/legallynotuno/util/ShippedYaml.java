package com.legallynotuno.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A YAML file the jar ships a copy of and the admin edits in the data folder: config.yml and
 * messages.yml.
 *
 * <p>The admin's copy is written once, on first run, and from then on it is theirs — which is
 * exactly why a plugin update can't simply rely on it. Left alone, an update that adds a
 * setting never shows it to anyone, and one that rewords a message never reaches a server that
 * installed the old wording, because every line in that file counts as the admin's choice
 * whether they ever touched it or not. {@link #load} closes both gaps without ever overriding
 * something the admin actually did:
 *
 * <ol>
 *   <li><b>Install</b> the jar's copy byte for byte when there is no file.</li>
 *   <li><b>Never write over a file that doesn't parse.</b> The server runs on the last copy that
 *       did ({@code .state/<name>.last-good}) and the broken file is left exactly as it is for
 *       its author to fix. Writing defaults over it would destroy every setting in it over one
 *       stray tab.</li>
 *   <li><b>Migrate</b> a file written at an older {@code versionKey} through each registered
 *       {@link Migration} — the only way a key is ever renamed or its meaning changed.</li>
 *   <li><b>Merge in the jar's copy.</b> New keys arrive with their comments, in the place the
 *       jar has them. Existing values are kept. With {@code updateUntouched} on (messages.yml),
 *       a line still reading exactly what the previous jar shipped is moved on to the new
 *       wording; a line the admin changed is not. What the previous jar shipped is known because
 *       every load keeps a copy of it in {@code .state/<name>.shipped}.</li>
 * </ol>
 *
 * <p>The file is only rewritten when that changes something, the version it replaces is kept in
 * {@code backups/}, and the write goes through a temp file so a crash can't leave half a config.
 */
public final class ShippedYaml {

    /**
     * One step in a file's history: turns a file written at version {@code from} into the shape
     * of {@code from + 1}. Bump the version key in the jar's file along with it.
     *
     * <p>Migrations run on the admin's file <em>and</em> on the recorded copy of what the old jar
     * shipped, so a renamed message the admin never touched is still recognised as untouched
     * under its new name, and still gets new wording.
     */
    public record Migration(int from, Consumer<ConfigurationSection> apply) {

        /**
         * Move a value (or a whole section) to a new path, with its comments. The admin's own
         * value is what moves: a rename must never quietly reset a setting to its default.
         */
        public static Migration rename(int from, String oldPath, String newPath) {
            return new Migration(from, yml -> {
                if (!yml.isSet(oldPath)) {
                    return;
                }
                if (!yml.isSet(newPath)) {
                    copy(yml, oldPath, yml, newPath);
                }
                yml.set(oldPath, null);
            });
        }

        /** Drop keys that no longer mean anything. */
        public static Migration remove(int from, String... paths) {
            return new Migration(from, yml -> {
                for (String path : paths) {
                    yml.set(path, null);
                }
            });
        }
    }

    /**
     * What {@link #load} produced. {@code error} is null when the file on disk was read; when it
     * isn't, {@code config} is the last copy that parsed (or the jar's, if none ever did) and
     * {@code error} says what is wrong with the file, including where.
     */
    public record Loaded(YamlConfiguration config, String error) {}

    /** Where the merge left things, for the log and for tests. */
    record Merge(YamlConfiguration result, boolean changed, boolean newerThanJar, int fromVersion,
                 List<String> added, List<String> updated, List<String> removed,
                 List<String> unused, List<String> problems) {}

    static final String STATE_DIR = ".state";
    static final String BACKUP_DIR = "backups";

    /** No line folding: a long MiniMessage string split across lines is valid but unreadable. */
    private static final int WIDTH = 1 << 16;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final File dataFolder;
    private final Logger log;
    private final String name;
    private final String versionKey;
    private final boolean updateUntouched;
    private final List<Migration> migrations;
    private final String shippedText;

    /**
     * @param name            the file name, both in the jar and in the data folder
     * @param versionKey      the top-level key holding the file's format version
     * @param updateUntouched move lines the admin never edited on to the jar's current text
     *                        (right for wording; wrong for settings, where an update must never
     *                        change how a running server behaves)
     * @param migrations      every rename/rewrite this file has had, oldest first
     */
    public ShippedYaml(Plugin plugin, String name, String versionKey, boolean updateUntouched,
                       List<Migration> migrations) {
        this(plugin.getDataFolder(), plugin.getLogger(), name, versionKey, updateUntouched,
                migrations, readResource(plugin, name));
    }

    ShippedYaml(File dataFolder, Logger log, String name, String versionKey,
                boolean updateUntouched, List<Migration> migrations, String shippedText) {
        this.dataFolder = dataFolder;
        this.log = log;
        this.name = name;
        this.versionKey = versionKey;
        this.updateUntouched = updateUntouched;
        this.migrations = List.copyOf(migrations);
        this.shippedText = shippedText;
    }

    /** The jar's copy, parsed. A fresh object every call, so callers may change it. */
    public YamlConfiguration shipped() {
        try {
            return parse(shippedText);
        } catch (InvalidConfigurationException e) {
            // Tests parse every bundled file, so this is a build that should never have shipped.
            throw new IllegalStateException("The bundled " + name + " is not valid YAML", e);
        }
    }

    /**
     * Read the file, installing or upgrading it first if it needs it. Never throws: whatever
     * happens, the caller gets something to run on, with the jar's copy as its defaults.
     */
    public Loaded load() {
        YamlConfiguration shipped = shipped();
        File file = new File(dataFolder, name);

        if (!file.exists()) {
            try {
                writeAtomically(file.toPath(), shippedText);
                remember("shipped", shippedText);
                remember("last-good", shippedText);
            } catch (IOException e) {
                log.warning("Could not write " + name + ": " + e.getMessage()
                        + " — running on the built-in copy.");
            }
            return new Loaded(withDefaults(shipped(), shipped), null);
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            String why = "it could not be read (" + e.getMessage() + ")";
            return fallBack(shipped, why);
        }
        String text = decode(bytes);
        // A file that isn't UTF-8 can be read (the odd character comes out garbled), but
        // writing it back would make that permanent. Upgrade it in memory only.
        boolean writable = text != null;
        if (text == null) {
            text = new String(bytes, StandardCharsets.UTF_8);
            log.warning(name + " isn't saved as UTF-8, so Legally Not Uno won't rewrite it — new "
                    + (updateUntouched ? "messages" : "settings") + " from updates are used but "
                    + "not added to the file. Re-save it as UTF-8 to fix that.");
        }

        YamlConfiguration admin;
        try {
            admin = parse(text);
        } catch (InvalidConfigurationException e) {
            return fallBack(shipped, describe(e));
        }

        Merge merge = merge(admin, shippedText, readState("shipped"), versionKey, updateUntouched,
                migrations);
        report(merge);
        if (merge.newerThanJar()) {
            // Nothing here knows the newer shape, so nothing here may rewrite it.
            return new Loaded(withDefaults(merge.result(), shipped), null);
        }

        String onDisk = text;
        if (merge.changed() && writable) {
            String out = render(merge.result());
            try {
                String backup = backUp(bytes);
                writeAtomically(file.toPath(), out);
                onDisk = out;
                log.info(summary(merge) + " The previous copy is in " + backup + ".");
            } catch (IOException e) {
                // The upgraded settings are still used; the next load simply tries again. The
                // record of what the jar shipped must NOT move on, or lines still carrying the
                // old wording would look edited next time.
                log.warning("Could not update " + name + " (" + e.getMessage() + "). The new "
                        + (updateUntouched ? "messages" : "settings") + " are in use anyway; "
                        + "Legally Not Uno will try to write them again on the next restart or /uno reload.");
                return new Loaded(withDefaults(merge.result(), shipped), null);
            }
        }
        if (writable) {
            remember("shipped", shippedText);
            remember("last-good", onDisk);
        }
        return new Loaded(withDefaults(merge.result(), shipped), null);
    }

    /** The file can't be used: run on the last copy that parsed, or on the jar's. */
    private Loaded fallBack(YamlConfiguration shipped, String why) {
        YamlConfiguration lastGood = readState("last-good");
        YamlConfiguration use;
        String running;
        if (lastGood != null) {
            Merge merge = merge(lastGood, shippedText, readState("shipped"), versionKey,
                    updateUntouched, migrations);
            use = merge.result();
            running = "the " + (updateUntouched ? "messages" : "settings")
                    + " it had the last time it loaded";
        } else {
            use = shipped();
            running = "the built-in defaults";
        }
        log.severe(name + " can't be used: " + why + ". Nothing in it has been changed. Until "
                + "it's fixed, Legally Not Uno is running on " + running + ".");
        return new Loaded(withDefaults(use, shipped), why);
    }

    private static YamlConfiguration withDefaults(YamlConfiguration config, YamlConfiguration shipped) {
        config.setDefaults(shipped);
        return config;
    }

    // ------------------------------------------------------------------ merging

    /**
     * The heart of it, with no file access so it can be tested directly.
     *
     * <p>{@code base} is what the previous jar shipped (null when that isn't known: the file
     * predates this class, or the record was deleted). Without it nothing can be called
     * untouched, so every value in the admin's file is kept.
     */
    static Merge merge(YamlConfiguration admin, String shippedText, YamlConfiguration base,
                       String versionKey, boolean updateUntouched, List<Migration> migrations) {
        YamlConfiguration shipped;
        YamlConfiguration result;
        try {
            shipped = parse(shippedText);
            result = parse(shippedText);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException("The bundled file is not valid YAML", e);
        }
        result.options().width(WIDTH);

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> unused = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        int current = version(shipped, versionKey);
        int fromVersion = version(admin, versionKey);
        if (fromVersion > current) {
            problems.add("it was written by a newer version of Legally Not Uno (" + versionKey + " "
                    + fromVersion + "; this one understands up to " + current + "), so it is "
                    + "used as it is and not changed");
            return new Merge(admin, false, true, fromVersion, added, updated, removed, unused,
                    problems);
        }

        Map<String, Object> before = fingerprint(admin);
        migrate(admin, fromVersion, current, versionKey, migrations);
        if (base != null) {
            int baseVersion = version(base, versionKey);
            if (baseVersion > current) {
                base = null; // a newer jar's copy says nothing about this one's
            } else {
                migrate(base, baseVersion, current, versionKey, migrations);
            }
        }

        // Values. Every leaf the admin has lands in the result unless it is an untouched copy
        // of something the jar has since changed or dropped.
        for (String path : admin.getKeys(true)) {
            if (admin.isConfigurationSection(path) || path.equals(versionKey)) {
                continue;
            }
            Object mine = admin.get(path);
            boolean untouched = base != null && base.isSet(path)
                    && !base.isConfigurationSection(path) && Objects.equals(mine, base.get(path));

            if (shipped.isConfigurationSection(path)) {
                problems.add("'" + path + "' should be a section of settings, not a single value "
                        + "— the defaults for it are used");
                continue;
            }
            if (shipped.isSet(path)) {
                Object theirs = shipped.get(path);
                String misfit = misfit(theirs, mine);
                if (misfit != null) {
                    problems.add("'" + path + "' " + misfit + ", but is " + show(mine)
                            + " — the default (" + show(theirs) + ") is used until it's fixed");
                }
                if (updateUntouched && untouched) {
                    if (!Objects.equals(mine, theirs)) {
                        updated.add(path);
                    }
                } else {
                    result.set(path, mine);
                }
                continue;
            }
            // Not in the jar any more (or never was).
            if (untouched) {
                removed.add(path); // ours, unedited, and dead
                continue;
            }
            String blocker = leafAbove(result, path);
            if (blocker != null) {
                problems.add("'" + path + "' can't be kept: '" + blocker + "' is a single value in "
                        + "this version, not a section");
                continue;
            }
            result.set(path, mine);
            unused.add(path);
        }
        for (String path : shipped.getKeys(true)) {
            if (!shipped.isConfigurationSection(path) && !path.equals(versionKey)
                    && !admin.isSet(path)) {
                added.add(path);
            }
        }

        // Comments follow the same rule as values: the admin's own words are kept, text they
        // never touched is brought up to date.
        for (String path : result.getKeys(true)) {
            if (!admin.isSet(path)) {
                continue; // new in this version: the jar's comments are already there
            }
            if (!shipped.isSet(path)) {
                result.setComments(path, admin.getComments(path));
                result.setInlineComments(path, admin.getInlineComments(path));
                continue;
            }
            boolean known = base != null && base.isSet(path);
            result.setComments(path, pick(admin.getComments(path),
                    known ? base.getComments(path) : null, shipped.getComments(path)));
            result.setInlineComments(path, pick(admin.getInlineComments(path),
                    known ? base.getInlineComments(path) : null, shipped.getInlineComments(path)));
        }
        result.options().setHeader(pick(admin.options().getHeader(),
                base == null ? null : base.options().getHeader(), shipped.options().getHeader()));
        result.options().setFooter(pick(admin.options().getFooter(),
                base == null ? null : base.options().getFooter(), shipped.options().getFooter()));

        boolean changed = !before.equals(fingerprint(result));
        return new Merge(result, changed, false, fromVersion, added, updated, removed, unused,
                problems);
    }

    private static void migrate(YamlConfiguration yml, int from, int to, String versionKey,
                                List<Migration> migrations) {
        for (int v = from; v < to; v++) {
            for (Migration m : migrations) {
                if (m.from() == v) {
                    m.apply().accept(yml);
                }
            }
        }
        yml.set(versionKey, to);
    }

    /**
     * A file's format version. One when it has none: that is the shape every file had before
     * the key existed.
     */
    static int version(ConfigurationSection yml, String versionKey) {
        return yml.isInt(versionKey) ? Math.max(1, yml.getInt(versionKey)) : 1;
    }

    /**
     * Comments the admin still has exactly as the previous jar shipped them are replaced with
     * the current jar's. Without a record of the previous jar there is no telling, and comments
     * are documentation rather than settings, so the current jar's win.
     */
    private static List<String> pick(List<String> mine, List<String> base, List<String> shipped) {
        if (base == null) {
            return shipped;
        }
        return mine.equals(base) ? shipped : mine;
    }

    /** Why {@code value} can't stand in for a setting whose default is {@code shipped}, or null. */
    private static String misfit(Object shipped, Object value) {
        if (shipped instanceof Boolean && !(value instanceof Boolean)) {
            return "should be true or false";
        }
        if (shipped instanceof Number && !(value instanceof Number)) {
            return "should be a number";
        }
        if (shipped instanceof List && !(value instanceof List)) {
            return "should be a list";
        }
        if (shipped instanceof String && (value instanceof List || value instanceof Map)) {
            return "should be a single line of text";
        }
        return null;
    }

    private static String show(Object value) {
        return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }

    /** The nearest ancestor of {@code path} that is a plain value in {@code yml}, if any. */
    private static String leafAbove(YamlConfiguration yml, String path) {
        char sep = yml.options().pathSeparator();
        for (int i = path.indexOf(sep); i >= 0; i = path.indexOf(sep, i + 1)) {
            String parent = path.substring(0, i);
            if (yml.isSet(parent) && !yml.isConfigurationSection(parent)) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Everything a reader of the file would see: values, where sections are, comments, header
     * and footer. Two configs with equal fingerprints write out the same file, so this is
     * what decides whether a load needs to touch the disk at all. Key order is deliberately
     * not part of it — a file is never rewritten just to reorder it.
     */
    static Map<String, Object> fingerprint(YamlConfiguration yml) {
        Map<String, Object> out = new HashMap<>();
        for (String path : yml.getKeys(true)) {
            out.put(path, yml.isConfigurationSection(path) ? "<section>" : yml.get(path));
            out.put(path + "\0comments", yml.getComments(path));
            out.put(path + "\0inline", yml.getInlineComments(path));
        }
        out.put("\0header", yml.options().getHeader());
        out.put("\0footer", yml.options().getFooter());
        return out;
    }

    static void copy(ConfigurationSection from, String fromPath, ConfigurationSection to,
                     String toPath) {
        if (from.isConfigurationSection(fromPath)) {
            ConfigurationSection source = from.getConfigurationSection(fromPath);
            to.createSection(toPath);
            for (String key : source.getKeys(false)) {
                copy(source, key, to, toPath + to.getRoot().options().pathSeparator() + key);
            }
        } else {
            to.set(toPath, from.get(fromPath));
        }
        to.setComments(toPath, from.getComments(fromPath));
        to.setInlineComments(toPath, from.getInlineComments(fromPath));
    }

    // ---------------------------------------------------------------- reporting

    private void report(Merge merge) {
        for (String problem : merge.problems()) {
            log.warning(name + ": " + problem + ".");
        }
        if (!merge.unused().isEmpty()) {
            log.warning(name + " has " + (updateUntouched ? "messages" : "settings")
                    + " this version of Legally Not Uno doesn't use, so they do nothing: "
                    + String.join(", ", merge.unused()) + ". Delete them.");
        }
    }

    private String summary(Merge merge) {
        List<String> parts = new ArrayList<>();
        if (!merge.added().isEmpty()) {
            parts.add("added " + list(merge.added()));
        }
        if (!merge.updated().isEmpty()) {
            parts.add("brought " + merge.updated().size() + " line(s) you hadn't edited up to date "
                    + "(" + list(merge.updated()) + ")");
        }
        if (!merge.removed().isEmpty()) {
            parts.add("removed " + list(merge.removed()) + ", which this version no longer uses");
        }
        String what = parts.isEmpty() ? "refreshed its comments and format version"
                : String.join("; ", parts);
        return "Updated " + name + " for this version of Legally Not Uno: " + what + ". Everything you had "
                + "changed is kept.";
    }

    private static String list(List<String> keys) {
        if (keys.size() <= 8) {
            return String.join(", ", keys);
        }
        return String.join(", ", keys.subList(0, 8)) + " and " + (keys.size() - 8) + " more";
    }

    /** SnakeYAML's own complaint, with the line and column people need to find it. */
    public static String describe(InvalidConfigurationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MarkedYAMLException marked) {
                Mark mark = marked.getProblemMark();
                String problem = marked.getProblem() == null ? "invalid YAML" : marked.getProblem();
                return mark == null ? problem
                        : problem + " (line " + (mark.getLine() + 1) + ", column "
                        + (mark.getColumn() + 1) + ")";
            }
        }
        String message = e.getMessage() == null ? "invalid YAML" : e.getMessage();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    // ------------------------------------------------------------------ disk

    static YamlConfiguration parse(String text) throws InvalidConfigurationException {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().width(WIDTH);
        yml.loadFromString(text);
        return yml;
    }

    /** What gets written: Bukkit's output, less the indentation it leaves on blank lines. */
    static String render(YamlConfiguration yml) {
        yml.options().width(WIDTH);
        return yml.saveToString().replaceAll("(?m)^[ \\t]+$", "");
    }

    /** Strict UTF-8, BOM dropped; null if the bytes aren't UTF-8 at all. */
    static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private YamlConfiguration readState(String kind) {
        Path path = statePath(kind);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            String text = decode(Files.readAllBytes(path));
            return text == null ? null : parse(text);
        } catch (IOException | InvalidConfigurationException e) {
            return null; // a damaged record is the same as none: nothing gets called untouched
        }
    }

    private void remember(String kind, String text) {
        Path path = statePath(kind);
        try {
            if (Files.isRegularFile(path)
                    && text.equals(decode(Files.readAllBytes(path)))) {
                return;
            }
            Files.createDirectories(path.getParent());
            Path readme = path.getParent().resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, STATE_README, StandardCharsets.UTF_8);
            }
            writeAtomically(path, text);
        } catch (IOException e) {
            log.warning("Could not record " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private Path statePath(String kind) {
        return dataFolder.toPath().resolve(STATE_DIR).resolve(name + "." + kind);
    }

    private String backUp(byte[] original) throws IOException {
        Path dir = dataFolder.toPath().resolve(BACKUP_DIR);
        Files.createDirectories(dir);
        String base = name.replaceFirst("\\.yml$", "");
        String stamp = LocalDateTime.now().format(STAMP);
        Path target = dir.resolve(base + "-" + stamp + ".yml");
        for (int n = 2; Files.exists(target); n++) {
            target = dir.resolve(base + "-" + stamp + "-" + n + ".yml");
        }
        Files.write(target, original);
        return BACKUP_DIR + "/" + target.getFileName();
    }

    static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String readResource(Plugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing from the jar");
            }
            String text = decode(in.readAllBytes());
            if (text == null) {
                throw new IllegalStateException("The bundled " + name + " isn't UTF-8");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled " + name, e);
        }
    }

    private static final String STATE_README = """
            Written by Legally Not Uno. Nothing in here is a setting -- edit
            ../config.yml and ../messages.yml, not these.

              <file>.shipped    the copy of the file this version of the plugin shipped
                                with. When an update arrives, a line in your file that
                                still matches it is one you never edited, so it can safely
                                be brought up to date. Lines you did edit are always kept.
              <file>.last-good  your file as it was the last time it loaded cleanly. If
                                your file ever stops parsing (a stray tab, a missing
                                quote), the plugin runs on this until you fix it rather
                                than on the defaults.

            Deleting them does no harm: the plugin just treats every line as edited on the
            next update, and falls back to the defaults if your file breaks before it next
            loads.
            """;
}
