package com.unoplugin.table;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every table theme the server knows, loaded from {@code plugins/UNO/themes.yml}.
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
 */
public final class ThemeStore {

    private final Plugin plugin;
    private final File file;
    private final Map<String, TableTheme> themes = new LinkedHashMap<>();

    public ThemeStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "themes.yml");
    }

    /** Read themes.yml, writing the built-in themes out first if it isn't there yet. */
    public void load() {
        themes.clear();
        if (!file.exists()) {
            writeDefaults();
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
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
            // /uno give with nothing to hand out and no way to recover in game.
            plugin.getLogger().warning("themes.yml has no usable themes — falling back to the "
                    + "built-in ones. Fix the file and run /uno reload.");
            for (TableTheme t : builtIns()) {
                themes.put(t.id(), t);
            }
        }
        plugin.getLogger().info("Loaded " + themes.size() + " table theme(s).");
    }

    private TableTheme read(String id, ConfigurationSection s) {
        List<?> rows = s.getList("grid");
        if (rows == null || rows.size() != TableTheme.SIZE) {
            plugin.getLogger().warning("Theme '" + id + "' needs a grid of exactly "
                    + TableTheme.SIZE + " rows — skipped.");
            return null;
        }
        BlockSpec[][] grid = new BlockSpec[TableTheme.SIZE][TableTheme.SIZE];
        for (int r = 0; r < TableTheme.SIZE; r++) {
            if (!(rows.get(r) instanceof List<?> row) || row.size() != TableTheme.SIZE) {
                plugin.getLogger().warning("Theme '" + id + "' row " + (r + 1) + " needs exactly "
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

    /** Add or replace a theme and write the file. Used by the in-game editor. */
    public void put(TableTheme theme) {
        themes.put(theme.id(), theme);
        save();
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
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "UNO table themes.",
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
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save themes.yml: " + e.getMessage());
        }
    }

    private void writeDefaults() {
        for (TableTheme t : builtIns()) {
            themes.put(t.id(), t);
        }
        save();
        themes.clear(); // load() reads them straight back, so there is one code path in
    }

    // ------------------------------------------------------------- the built-ins

    /**
     * The four themes that shipped as enum constants.
     *
     * <p>Kept in code as well as in the file so a deleted or corrupted themes.yml still leaves
     * the server with working tables. The chequer is the same shape they always had: the
     * primary wood on the corners and centre, the secondary on the four edges.
     */
    private static List<TableTheme> builtIns() {
        List<TableTheme> out = new ArrayList<>(4);
        out.add(chequer("cherry", "Cherry Table",
                "minecraft:stripped_cherry_log[axis=y]",
                "minecraft:stripped_pale_oak_log[axis=y]",
                "minecraft:cherry_stairs"));
        out.add(chequer("darkcherry", "Dark Cherry Table",
                "minecraft:cherry_log[axis=y]",
                "minecraft:pale_oak_log[axis=y]",
                "minecraft:pale_oak_stairs"));
        out.add(chequer("spruce", "Spruce Table",
                "minecraft:spruce_log[axis=y]",
                "minecraft:oak_log[axis=y]",
                "minecraft:dark_oak_stairs"));
        out.add(chequer("strippedoak", "Stripped Oak Table",
                "minecraft:stripped_spruce_log[axis=y]",
                "minecraft:stripped_oak_log[axis=y]",
                "minecraft:spruce_stairs"));
        return out;
    }

    /** The classic layout: primary on corners and centre, secondary on the edges. */
    private static TableTheme chequer(String id, String name,
                                      String primary, String secondary, String seat) {
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
        return new TableTheme(id, name, grid, seats, true);
    }

    /** Legacy {@code tables.yml} type names, mapped onto the theme that replaced them. */
    public static String migrateLegacyId(String stored) {
        if (stored == null) {
            return "cherry";
        }
        return switch (stored.toUpperCase(Locale.ROOT)) {
            case "CASINO", "BLOSSOM", "CHERRY" -> "cherry";
            case "MIDNIGHT", "DARK_CHERRY", "DARKCHERRY" -> "darkcherry";
            case "TAVERN", "OAK", "SPRUCE" -> "spruce";
            case "HOMESTEAD", "BIRCH", "STRIPPED_OAK", "STRIPPEDOAK" -> "strippedoak";
            default -> TableTheme.normaliseId(stored);
        };
    }
}
