package com.legallynotuno.table;

import com.legallynotuno.util.ShippedYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Every table theme the server knows, loaded from {@code plugins/LegallyNotUno/themes.yml}.
 *
 * <p>The four themes that used to be enum constants are written into that file on first run
 * and then treated exactly like any theme a player makes: nothing in the plugin special-cases
 * them, so an admin can retheme "cherry" by editing it, and the editor can add a fifth without
 * a rebuild. They are marked {@code builtIn} only so {@code /uno theme delete} can refuse to
 * leave the server with no themes at all.
 *
 * <p>The file is also written at runtime by the in-game editor, so this class owns saving as
 * well as loading. It keeps insertion order, so a hand-written file stays in the order it was
 * written rather than being alphabetised out from under its author.
 *
 * <p>A built-in added by a later release reaches servers that installed an earlier one: the
 * file records which built-ins it has been given ({@value #OFFERED}), and anything in
 * {@link #builtIns()} not on that list is added once. Recording what was <em>offered</em>
 * rather than checking what is present is the point — a built-in an admin deleted by hand
 * stays deleted.
 */
public final class ThemeStore {

    static final String OFFERED = "built-ins-offered";

    private final File file;
    private final Logger log;
    private final Map<String, TableTheme> themes = new LinkedHashMap<>();
    /** Every built-in id this file has been given, whether or not it still has it. */
    private final Set<String> offered = new LinkedHashSet<>();
    /** Set while themes.yml doesn't parse: nothing may be written over it, or built from it. */
    private boolean unreadable;

    public ThemeStore(Plugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    ThemeStore(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "themes.yml");
        this.log = log;
    }

    /**
     * Read themes.yml, writing the built-in themes out first if it isn't there yet, and adding
     * any built-in a newer release ships that this file has never been given.
     *
     * @return null, or why themes.yml can't be used. The file is then left exactly as it is,
     *         {@link #unreadable()} is true until it loads, and the themes already in memory
     *         stay in use — or, at startup when there are none, the built-ins stand in.
     */
    public String load() {
        if (!file.exists()) {
            themes.clear();
            offered.clear();
            unreadable = false;
            writeDefaults();
        }
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            String why = e instanceof InvalidConfigurationException bad
                    ? ShippedYaml.describe(bad) : "it could not be read (" + e.getMessage() + ")";
            unreadable = true;
            // On /uno reload, what the file said last time is still the best answer there is.
            boolean keeping = !themes.isEmpty();
            if (!keeping) {
                for (TableTheme t : builtIns()) {
                    themes.put(t.id(), t);
                }
            }
            log.severe("themes.yml can't be used: " + why + ". Nothing in it has been changed, "
                    + "and tables won't be rebuilt or saved over it until it's fixed and "
                    + "/uno reload is run. " + (keeping
                    ? "Until then the themes it had when it last loaded stay in use."
                    : "Until then only the built-in themes can be handed out."));
            return why;
        }
        themes.clear();
        offered.clear();
        unreadable = false;
        ConfigurationSection root = yml.getConfigurationSection("themes");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                TableTheme theme = read(TableTheme.normaliseId(key), s);
                if (theme != null) {
                    themes.put(theme.id(), theme);
                }
            }
        }
        if (themes.isEmpty()) {
            // A file that parsed to nothing (emptied, or every entry malformed) would leave
            // /uno give with nothing to hand out and no way to recover in game. Nothing is
            // written back: those entries are the admin's to fix, not ours to replace.
            log.warning("themes.yml has no usable themes — falling back to the "
                    + "built-in ones. Fix the file and run /uno reload.");
            for (TableTheme t : builtIns()) {
                themes.put(t.id(), t);
            }
        } else {
            addNewBuiltIns(yml);
        }
        log.info("Loaded " + themes.size() + " table theme(s).");
        return null;
    }

    /**
     * Give this file any built-in it has never been offered.
     *
     * <p>A file from before the list existed is taken to have been offered exactly the
     * built-ins it holds, which is what every earlier release wrote on first run.
     *
     * <p>The new entries are added to the file as it was read rather than by {@link #save()}
     * rewriting it from memory, so an entry that failed to load, or a comment someone wrote,
     * comes through untouched.
     */
    private void addNewBuiltIns(YamlConfiguration yml) {
        boolean recorded = yml.isList(OFFERED);
        if (recorded) {
            for (String id : yml.getStringList(OFFERED)) {
                offered.add(TableTheme.normaliseId(id));
            }
        } else {
            for (TableTheme t : themes.values()) {
                if (t.builtIn()) {
                    offered.add(t.id());
                }
            }
        }
        List<String> added = new ArrayList<>();
        boolean grew = false;
        for (TableTheme t : builtIns()) {
            if (!offered.add(t.id())) {
                continue;
            }
            grew = true;
            // An admin's own theme that happens to share a new built-in's id is theirs, and
            // stays exactly as it is.
            if (themes.putIfAbsent(t.id(), t) == null) {
                write(yml, t);
                added.add(t.id());
            }
        }
        if (recorded && !grew) {
            return;
        }
        writeOffered(yml);
        try {
            yml.save(file);
        } catch (IOException e) {
            log.severe("Could not save themes.yml: " + e.getMessage());
        }
        if (!added.isEmpty()) {
            log.info("Added the built-in table theme(s) this version brings: "
                    + String.join(", ", added) + ".");
        }
    }

    /** True while themes.yml doesn't parse: tables must not be rebuilt from the stand-ins. */
    public boolean unreadable() {
        return unreadable;
    }

    private TableTheme read(String id, ConfigurationSection s) {
        List<?> rows = s.getList("grid");
        if (rows == null || rows.size() != TableTheme.SIZE) {
            log.warning("Theme '" + id + "' needs a grid of exactly "
                    + TableTheme.SIZE + " rows — skipped.");
            return null;
        }
        BlockSpec[][] grid = new BlockSpec[TableTheme.SIZE][TableTheme.SIZE];
        for (int r = 0; r < TableTheme.SIZE; r++) {
            if (!(rows.get(r) instanceof List<?> row) || row.size() != TableTheme.SIZE) {
                log.warning("Theme '" + id + "' row " + (r + 1) + " needs exactly "
                        + TableTheme.SIZE + " blocks — skipped.");
                return null;
            }
            for (int c = 0; c < TableTheme.SIZE; c++) {
                grid[r][c] = BlockSpec.parse(String.valueOf(row.get(c)));
            }
        }

        BlockSpec[] seats = new BlockSpec[TableTheme.Seat.values().length];
        ConfigurationSection seatSection = s.getConfigurationSection("seats");
        // A single `seats: <block>` sets all four; the section form sets them individually.
        String shared = seatSection == null ? s.getString("seats") : null;
        for (TableTheme.Seat seat : TableTheme.Seat.values()) {
            String written = seatSection != null
                    ? seatSection.getString(seat.key(), seatSection.getString("all"))
                    : shared;
            seats[seat.ordinal()] = BlockSpec.parse(
                    written == null ? "minecraft:oak_stairs" : written);
        }

        String name = s.getString("name", id);
        boolean builtIn = s.getBoolean("built-in", false);
        return new TableTheme(id, name, grid, seats, builtIn);
    }

    /**
     * Add or replace a theme and write the file. Used by the in-game editor.
     *
     * @return false, with nothing changed, while themes.yml has a mistake in it
     */
    public boolean put(TableTheme theme) {
        if (unreadable) {
            return false; // save() would refuse anyway; don't keep a theme that only lives in RAM
        }
        themes.put(theme.id(), theme);
        save();
        return true;
    }

    /**
     * Delete a theme, refusing to remove a built-in or the last one standing.
     *
     * @return true if it was deleted.
     */
    public boolean delete(String id) {
        TableTheme theme = themes.get(TableTheme.normaliseId(id));
        if (theme == null || theme.builtIn() || themes.size() <= 1) {
            return false;
        }
        themes.remove(theme.id());
        save();
        return true;
    }

    public TableTheme get(String id) {
        return themes.get(TableTheme.normaliseId(id));
    }

    /** The theme to use when a table's stored one has since been deleted from the file. */
    public TableTheme fallback() {
        return themes.values().iterator().next();
    }

    public List<TableTheme> all() {
        return new ArrayList<>(themes.values());
    }

    public List<String> ids() {
        return new ArrayList<>(themes.keySet());
    }

    public boolean has(String id) {
        return themes.containsKey(TableTheme.normaliseId(id));
    }

    /** Every theme id, comma separated, for the "which one?" messages. */
    public String idList() {
        return String.join(", ", themes.keySet());
    }

    // ------------------------------------------------------------------ writing

    public void save() {
        if (unreadable) {
            // Everything in memory is a stand-in; writing it would replace every theme the
            // admin made with the built-ins.
            log.severe("Not saving themes.yml: it has a mistake in it that needs fixing first. "
                    + "Fix it, run /uno reload, then save the theme again.");
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Legally Not Uno table themes.",
                "",
                "Each theme is the 3x3 top of a table plus the block each of its four seats is",
                "made from. Build one in game with /uno theme create <id> if you would rather",
                "not write it by hand — the editor writes back to this file.",
                "",
                "grid is in TABLE space, not compass directions: the first row is the FAR side",
                "of the table (away from whoever places it) and the first column is the LEFT.",
                "The whole pattern is turned to match the way the table is placed.",
                "",
                "A block may be written as:",
                "  minecraft:cherry_log[axis=y]   any block id, with its block state",
                "  CHERRY_LOG                     a bare material name also works",
                "  itemsadder:marble|minecraft:quartz_block",
                "                                 a custom block from ItemsAdder / Oraxen / Nexo,",
                "                                 and the vanilla block to lay instead when that",
                "                                 plugin isn't installed. Always give a fallback.",
                "",
                "seats accepts one block for all four, or near/far/left/right individually."));
        for (TableTheme t : themes.values()) {
            write(yml, t);
        }
        writeOffered(yml);
        try {
            File folder = file.getParentFile();
            if (folder != null && !folder.exists()) {
                folder.mkdirs();
            }
            yml.save(file);
        } catch (IOException e) {
            log.severe("Could not save themes.yml: " + e.getMessage());
        }
    }

    private static void write(YamlConfiguration yml, TableTheme t) {
        String base = "themes." + t.id();
        yml.set(base + ".name", t.displayName());
        if (t.builtIn()) {
            yml.set(base + ".built-in", true);
        }
        List<List<String>> rows = new ArrayList<>(TableTheme.SIZE);
        for (int r = 0; r < TableTheme.SIZE; r++) {
            List<String> row = new ArrayList<>(TableTheme.SIZE);
            for (int c = 0; c < TableTheme.SIZE; c++) {
                row.add(t.cell(r, c).written());
            }
            rows.add(row);
        }
        yml.set(base + ".grid", rows);
        for (TableTheme.Seat seat : TableTheme.Seat.values()) {
            yml.set(base + ".seats." + seat.key(), t.seat(seat).written());
        }
    }

    private void writeOffered(YamlConfiguration yml) {
        yml.set(OFFERED, new ArrayList<>(offered));
        // Bukkit writes a null comment line as a blank line; "" would come out as a bare '#'.
        yml.setComments(OFFERED, Arrays.asList(
                null,
                "The built-in themes this file has been given. Leave it alone: it is how an",
                "update adds a new built-in without putting back one you deleted."));
    }

    private void writeDefaults() {
        for (TableTheme t : builtIns()) {
            themes.put(t.id(), t);
            offered.add(t.id());
        }
        save();
        themes.clear(); // load() reads them straight back, so there is one code path in
        offered.clear();
    }

    // ------------------------------------------------------------- the built-ins

    /**
     * The themes that ship with the plugin.
     *
     * <p>Kept in code as well as in the file so a deleted or corrupted themes.yml still leaves
     * the server with working tables. All nine were drawn in the in-game editor and then moved
     * here, which is why each is named after its own id — that is what {@code /uno theme create}
     * writes. Every one is the same chequer: the primary wood on the corners and centre, the
     * secondary on the four edges.
     *
     * <p>The blocks carry no {@code [axis=y]}, because {@code TableManager.ringsUp} turns an
     * {@code Orientable} upright as it lays it. A theme that wants a log on its side still says
     * so explicitly and is left alone.
     *
     * <p>Public so {@code TableThemeTest} can check the legacy migration against the themes that
     * really ship rather than against a copy of the list that can drift out of step with it.
     */
    public static List<TableTheme> builtIns() {
        List<TableTheme> out = new ArrayList<>(9);
        out.add(chequer("darkoak",
                "minecraft:dark_oak_log",
                "minecraft:pale_oak_log",
                "minecraft:deepslate_tile_stairs"));
        out.add(chequer("lightcherry",
                "minecraft:stripped_cherry_log",
                "minecraft:stripped_pale_oak_log",
                "minecraft:cherry_stairs"));
        out.add(chequer("darkspruce",
                "minecraft:spruce_log",
                "minecraft:oak_log",
                "minecraft:dark_oak_stairs"));
        out.add(chequer("spruce",
                "minecraft:stripped_spruce_log",
                "minecraft:stripped_oak_log",
                "minecraft:spruce_stairs"));
        out.add(chequer("darkcherry",
                "minecraft:cherry_log",
                "minecraft:pale_oak_log",
                "minecraft:nether_brick_stairs"));
        out.add(chequer("cherry",
                "minecraft:cherry_log",
                "minecraft:pale_oak_log",
                "minecraft:pale_oak_stairs"));
        out.add(chequer("darkmangrove",
                "minecraft:dark_oak_log",
                "minecraft:mangrove_log",
                "minecraft:dark_oak_stairs"));
        out.add(chequer("mangrove",
                "minecraft:stripped_dark_oak_log",
                "minecraft:stripped_mangrove_log",
                "minecraft:dark_oak_stairs"));
        out.add(chequer("lightmangrove",
                "minecraft:stripped_mangrove_log",
                "minecraft:stripped_pale_oak_log",
                "minecraft:mangrove_stairs"));
        return out;
    }

    /** The classic layout: primary on corners and centre, secondary on the edges. */
    private static TableTheme chequer(String id, String primary, String secondary, String seat) {
        BlockSpec[][] grid = new BlockSpec[TableTheme.SIZE][TableTheme.SIZE];
        for (int r = 0; r < TableTheme.SIZE; r++) {
            for (int c = 0; c < TableTheme.SIZE; c++) {
                boolean corner = (Math.abs(r - 1) + Math.abs(c - 1)) % 2 == 0;
                grid[r][c] = BlockSpec.parse(corner ? primary : secondary);
            }
        }
        BlockSpec[] seats = new BlockSpec[TableTheme.Seat.values().length];
        for (TableTheme.Seat s : TableTheme.Seat.values()) {
            seats[s.ordinal()] = BlockSpec.parse(seat);
        }
        // Named after its own id: these were drawn with /uno theme create, which does the same.
        return new TableTheme(id, id, grid, seats, true);
    }

    /**
     * Legacy {@code tables.yml} type names, mapped onto the theme that replaced them.
     *
     * <p>Two generations of names arrive here: the {@code type:} enum constants from before
     * themes existed, and the four ids the first data-driven build shipped. Both are mapped by
     * palette rather than by name, because the wood is what a player recognises their table by.
     * Every one of those palettes still ships — they were renamed on the light/dark axis, and
     * that rename crosses over: what {@code cherry} used to mean is now {@code lightcherry},
     * and what {@code spruce} used to mean is now {@code darkspruce}. Mapping on the name would
     * quietly hand those tables a different wood.
     */
    public static String migrateLegacyId(String stored) {
        if (stored == null) {
            return "cherry";
        }
        return switch (stored.toUpperCase(Locale.ROOT)) {
            // stripped cherry + stripped pale oak, cherry stairs
            case "CASINO", "BLOSSOM", "CHERRY" -> "lightcherry";
            // cherry + pale oak, pale oak stairs
            case "MIDNIGHT", "DARK_CHERRY", "DARKCHERRY" -> "cherry";
            // spruce + oak, dark oak stairs
            case "TAVERN", "OAK", "SPRUCE" -> "darkspruce";
            // stripped spruce + stripped oak, spruce stairs
            case "HOMESTEAD", "BIRCH", "STRIPPED_OAK", "STRIPPEDOAK" -> "spruce";
            default -> TableTheme.normaliseId(stored);
        };
    }
}
