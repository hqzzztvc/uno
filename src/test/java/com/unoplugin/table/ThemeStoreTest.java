package com.unoplugin.table;

import com.unoplugin.TestFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * themes.yml across plugin updates.
 *
 * <p>Built-ins are written into the file on first run, so without help a built-in added by a
 * later release never reaches a server that installed an earlier one — and the obvious help,
 * "add any built-in that's missing", puts back themes admins deliberately deleted.
 */
class ThemeStoreTest {

    @TempDir(cleanup = CleanupMode.NEVER) // see TestFiles
    Path dir;

    private final Logger log = Logger.getAnonymousLogger();

    @BeforeEach
    void quiet() {
        log.setUseParentHandlers(false);
    }

    @AfterEach
    void tidy() throws InterruptedException {
        TestFiles.deletePatiently(dir);
    }

    private Path file() {
        return dir.resolve("themes.yml");
    }

    private ThemeStore load() {
        ThemeStore store = new ThemeStore(dir.toFile(), log);
        assertNull(store.load());
        return store;
    }

    private static Set<String> builtInIds() {
        return ThemeStore.builtIns().stream().map(TableTheme::id).collect(Collectors.toSet());
    }

    /** One theme entry as a file would hold it. */
    private static String entry(String id, boolean builtIn) {
        return "  " + id + ":\n"
                + "    name: Old " + id + "\n"
                + (builtIn ? "    built-in: true\n" : "")
                + "    grid:\n"
                + "    - [minecraft:oak_log, minecraft:oak_log, minecraft:oak_log]\n"
                + "    - [minecraft:oak_log, minecraft:oak_log, minecraft:oak_log]\n"
                + "    - [minecraft:oak_log, minecraft:oak_log, minecraft:oak_log]\n"
                + "    seats: minecraft:oak_stairs\n";
    }

    @Test
    void aFirstRunWritesEveryBuiltInAndRecordsThemAsGiven() throws Exception {
        ThemeStore store = load();
        assertEquals(builtInIds(), Set.copyOf(store.ids()));
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file().toFile());
        assertEquals(builtInIds(), Set.copyOf(yml.getStringList(ThemeStore.OFFERED)));
    }

    /**
     * A file from before the record existed: it is taken to have been given the built-ins it
     * holds, so the ones it lacks arrive — without disturbing anything already in it,
     * including an entry that doesn't load and a comment somebody wrote.
     */
    @Test
    void anOlderFileGainsTheBuiltInsItNeverHad() throws Exception {
        Files.writeString(file(), "themes:\n"
                + entry("cherry", true)
                + "  # the admin's favourite\n"
                + entry("marble", false)
                + "  broken:\n    grid: nope\n");
        ThemeStore store = load();

        for (String id : builtInIds()) {
            assertTrue(store.has(id), id + " wasn't added");
        }
        assertFalse(store.get("marble").builtIn());
        assertEquals("Old cherry", store.get("cherry").displayName(),
                "an existing built-in was replaced by the shipped one");

        String text = Files.readString(file());
        assertTrue(text.contains("the admin's favourite"), "a comment was lost:\n" + text);
        assertTrue(text.contains("broken:"), "an entry that didn't load was thrown away:\n" + text);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file().toFile());
        assertEquals(builtInIds(), Set.copyOf(yml.getStringList(ThemeStore.OFFERED)));
    }

    @Test
    void aBuiltInDeletedByHandStaysDeleted() throws Exception {
        load();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file().toFile());
        String deleted = ThemeStore.builtIns().get(0).id();
        yml.set("themes." + deleted, null);
        yml.save(file().toFile());
        String before = Files.readString(file());

        ThemeStore store = load();
        assertFalse(store.has(deleted));
        assertEquals(before, Files.readString(file()), "a load with nothing new rewrote the file");
    }

    /** A new built-in whose id an admin already uses for their own theme never replaces it. */
    @Test
    void aNewBuiltInNeverOverwritesAnAdminsThemeOfTheSameName() throws Exception {
        String taken = ThemeStore.builtIns().get(0).id();
        List<String> offered = builtInIds().stream().filter(id -> !id.equals(taken)).toList();
        Files.writeString(file(), "themes:\n" + entry(taken, false)
                + ThemeStore.OFFERED + ": " + offered + "\n");

        ThemeStore store = load();
        assertFalse(store.get(taken).builtIn(), "the admin's theme was replaced");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file().toFile());
        assertTrue(yml.getStringList(ThemeStore.OFFERED).contains(taken));
    }

    @Test
    void aFileThatDoesntParseIsNeverWrittenOver() throws Exception {
        String broken = "themes:\n" + entry("marble", false) + "\t oops: [\n";
        Files.writeString(file(), broken);

        ThemeStore store = new ThemeStore(dir.toFile(), log);
        String error = store.load();
        assertNotNull(error);
        assertTrue(store.unreadable());
        assertTrue(store.has(ThemeStore.builtIns().get(0).id()), "no stand-in themes to use");

        assertFalse(store.put(ThemeStore.builtIns().get(1)), "the editor would say it saved");
        assertEquals(broken, Files.readString(file()), "a file that didn't parse was written over");
    }

    /** /uno reload onto a broken file keeps the themes that were already loaded. */
    @Test
    void aReloadOntoABrokenFileKeepsWhatWasLoaded() throws Exception {
        Files.writeString(file(), "themes:\n" + entry("marble", false));
        ThemeStore store = load();
        Files.writeString(file(), "themes:\n" + entry("marble", false) + "\t oops: [\n");

        assertNotNull(store.load());
        assertTrue(store.has("marble"), "a reload onto a broken file dropped the admin's themes");
        assertTrue(store.unreadable());
    }
}
