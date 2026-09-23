package com.legallynotuno.util;

import com.legallynotuno.TestFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upgrading an admin's config.yml / messages.yml when a new jar arrives.
 *
 * <p>Everything here fails quietly on a live server, which is the reason it is all pinned: a
 * setting that isn't added is a feature nobody finds, a value that gets "upgraded" changes how
 * somebody's server plays, a rewrite of a file that didn't parse throws away every setting in
 * it, and a load that isn't idempotent writes a backup on every restart forever.
 */
class ShippedYamlTest {

    @TempDir(cleanup = CleanupMode.NEVER) // see TestFiles
    Path dir;

    /** Where the file under test lives: {@link #dir}, or a folder of its own per bundled file. */
    private Path folder;

    private final List<String> logged = new ArrayList<>();
    private final Logger log = Logger.getAnonymousLogger();

    @BeforeEach
    void setUp() {
        folder = dir;
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterEach
    void tidy() throws InterruptedException {
        TestFiles.deletePatiently(dir);
    }

    private ShippedYaml settings(String shipped, ShippedYaml.Migration... migrations) {
        return new ShippedYaml(folder.toFile(), log, "test.yml", "version", false,
                List.of(migrations), shipped);
    }

    private ShippedYaml wording(String shipped, ShippedYaml.Migration... migrations) {
        return new ShippedYaml(folder.toFile(), log, "test.yml", "version", true,
                List.of(migrations), shipped);
    }

    /** A clean folder for one bundled file, so its records don't leak into the next one's. */
    private ShippedYaml bundledFile(String name, String shipped) throws IOException {
        folder = Files.createDirectories(dir.resolve(name.replace('.', '_')));
        boolean messages = name.equals("messages.yml");
        return new ShippedYaml(folder.toFile(), log, "test.yml",
                messages ? "messages-version" : "config-version", messages, List.of(), shipped);
    }

    private Path file() {
        return folder.resolve("test.yml");
    }

    private String text() throws IOException {
        return Files.readString(file());
    }

    private void write(String text) throws IOException {
        Files.writeString(file(), text);
    }

    private long backups() throws IOException {
        Path backups = folder.resolve(ShippedYaml.BACKUP_DIR);
        if (!Files.isDirectory(backups)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(backups)) {
            return files.count();
        }
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        return ShippedYaml.parse(text);
    }

    static String bundled(String name) throws IOException {
        try (InputStream in = ShippedYamlTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- install

    @Test
    void aFirstRunInstallsTheJarsCopyByteForByte() throws Exception {
        String shipped = "# the header\n\n# a comment\nkey: \"quoted\"   # inline\nversion: 1\n";
        ShippedYaml.Loaded loaded = settings(shipped).load();

        assertNull(loaded.error());
        assertEquals(shipped, text(), "the admin's first copy must be exactly what shipped");
        assertEquals("quoted", loaded.config().getString("key"));
        assertTrue(Files.exists(folder.resolve(".state/test.yml.shipped")));
        assertTrue(Files.exists(folder.resolve(".state/test.yml.last-good")));
        assertEquals(0, backups());
    }

    /**
     * The whole-plugin guarantee: once a file is current, loading it again touches nothing. A
     * load that always finds something to change rewrites the admin's file and leaves a new
     * backup behind on every single restart.
     */
    @Test
    void loadingAnUpToDateFileWritesNothing() throws Exception {
        for (String name : List.of("config.yml", "messages.yml")) {
            String shipped = bundled(name);
            ShippedYaml file = bundledFile(name, shipped);
            // An older copy: no version key, one value edited. Upgrade it, then load again.
            write(shipped.replace("debug: false", "debug: true")
                    .replaceAll("(?m)^(config|messages)-version: 1\\R", ""));
            file.load();
            String once = text();
            assertEquals(1, backups(), name + " wasn't upgraded");

            file.load();
            file.load();
            assertEquals(once, text(), name + " changed on a load with nothing to do");
            assertEquals(1, backups(), name + " backed up again with nothing to do");
        }
    }

    // ------------------------------------------------------------------ values

    @Test
    void newSettingsArriveWithTheirCommentsAndExistingValuesAreKept() throws Exception {
        String v1 = "game:\n  # seconds\n  timeout: 60\n";
        write("game:\n  # seconds\n  timeout: 15\n");
        settings(v1).load();

        String v2 = "game:\n  # seconds\n  timeout: 60\n  # brand new\n  fresh: true\n"
                + "updates:\n  # ask modrinth\n  enabled: true\nversion: 1\n";
        String before = text();
        ShippedYaml.Loaded loaded = settings(v2).load();

        YamlConfiguration onDisk = yaml(text());
        assertEquals(15, onDisk.getInt("game.timeout"), "an admin's value was changed");
        assertTrue(onDisk.getBoolean("game.fresh"));
        assertTrue(onDisk.getBoolean("updates.enabled"));
        assertEquals(List.of("brand new"), onDisk.getComments("game.fresh"));
        assertEquals(List.of("ask modrinth"), onDisk.getComments("updates.enabled"));
        assertEquals(15, loaded.config().getInt("game.timeout"));

        assertEquals(1, backups());
        try (Stream<Path> files = Files.list(folder.resolve(ShippedYaml.BACKUP_DIR))) {
            Path backup = files.findFirst().orElseThrow();
            assertEquals(before, Files.readString(backup), "the backup isn't the old file");
        }
        assertTrue(logged.stream().anyMatch(l -> l.contains("game.fresh")),
                "the upgrade wasn't reported: " + logged);
    }

    /** "Upgrading never changes the game a server is already running" — not even a default. */
    @Test
    void aSettingIsNeverMovedToANewDefault() throws Exception {
        settings("timeout: 60\n").load(); // installed, never touched
        settings("timeout: 90\n").load();
        assertEquals(60, yaml(text()).getInt("timeout"));
    }

    @Test
    void untouchedWordingFollowsTheJarAndEditedWordingDoesnt() throws Exception {
        wording("a: old a\nb: old b\n").load();
        write("a: old a\nb: mine\n");

        ShippedYaml.Merge merge = ShippedYaml.merge(yaml(text()), "a: new a\nb: new b\n",
                yaml("a: old a\nb: old b\n"), "version", true, List.of());
        assertEquals(List.of("a"), merge.updated());

        wording("a: new a\nb: new b\n").load();
        YamlConfiguration onDisk = yaml(text());
        assertEquals("new a", onDisk.getString("a"));
        assertEquals("mine", onDisk.getString("b"), "an admin's own wording was overwritten");
    }

    /** With no record of the old jar, nothing can be called untouched: keep every line. */
    @Test
    void withoutARecordOfTheOldJarEveryLineIsKept() throws Exception {
        write("a: old a\n");
        wording("a: new a\nb: new b\n").load();
        YamlConfiguration onDisk = yaml(text());
        assertEquals("old a", onDisk.getString("a"));
        assertEquals("new b", onDisk.getString("b"));
    }

    @Test
    void keysTheJarDroppedGoIfUntouchedAndStayIfEdited() throws Exception {
        settings("keep: 1\ngone: 2\nedited: 3\n").load();
        write("keep: 1\ngone: 2\nedited: 30\n");

        settings("keep: 1\n").load();
        YamlConfiguration onDisk = yaml(text());
        assertFalse(onDisk.isSet("gone"), "an unedited dead key was left behind");
        assertEquals(30, onDisk.getInt("edited"), "an admin's edited value was thrown away");
        assertTrue(logged.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("edited")),
                "the leftover key wasn't reported: " + logged);
    }

    @Test
    void aValueOfTheWrongTypeIsKeptInTheFileButTheDefaultIsRead() throws Exception {
        write("timeout: sixty\nenabled: maybe\nlist: one\n");
        ShippedYaml.Loaded loaded = settings("timeout: 60\nenabled: true\nlist: [a]\n").load();

        assertEquals(60, loaded.config().getInt("timeout"));
        assertTrue(loaded.config().getBoolean("enabled"));
        assertEquals(List.of("a"), loaded.config().getStringList("list"));
        assertEquals("sixty", yaml(text()).getString("timeout"), "the admin's value was destroyed");
        assertTrue(logged.stream().anyMatch(l -> l.contains("'timeout' should be a number")),
                "the mistake wasn't reported: " + logged);
    }

    // ---------------------------------------------------------------- comments

    @Test
    void commentsTheAdminWroteAreKeptAndTheRestAreRefreshed() throws Exception {
        settings("# old k doc\nk: 1\n# j doc\nj: 2\n").load();
        write("# old k doc\nk: 1\n# my own note\nj: 2\n");

        settings("# new k doc\nk: 1\n# newer j doc\nj: 2\n").load();
        YamlConfiguration onDisk = yaml(text());
        assertEquals(List.of("new k doc"), onDisk.getComments("k"));
        assertEquals(List.of("my own note"), onDisk.getComments("j"));
    }

    // ------------------------------------------------------------ broken files

    @Test
    void aFileThatDoesntParseIsLeftAloneAndItsLastGoodCopyIsUsed() throws Exception {
        write("limit: 5\n");
        settings("limit: 0\n").load();
        String broken = "limit: 5\n\tlimit2: [unclosed\n";
        write(broken);

        ShippedYaml.Loaded loaded = settings("limit: 0\nextra: 1\n").load();
        assertNotNull(loaded.error());
        assertTrue(loaded.error().contains("line 2"), loaded.error());
        assertEquals(broken, text(), "a file that didn't parse was written over");
        assertEquals(5, loaded.config().getInt("limit"), "fell back to defaults, not last-good");
        assertEquals(1, loaded.config().getInt("extra"));
        assertEquals(0, backups());
    }

    @Test
    void aFileThatNeverParsedRunsOnTheJarsCopy() throws Exception {
        write("limit: [\n");
        ShippedYaml.Loaded loaded = settings("limit: 3\n").load();
        assertNotNull(loaded.error());
        assertEquals(3, loaded.config().getInt("limit"));
        assertEquals("limit: [\n", text());
    }

    @Test
    void aFileFromANewerVersionIsNotTouched() throws Exception {
        String newer = "version: 7\nsomething-new: yes\n";
        write(newer);
        ShippedYaml.Loaded loaded = settings("version: 1\nold: 1\n").load();
        assertNull(loaded.error());
        assertEquals(newer, text());
        assertEquals(1, loaded.config().getInt("old"), "defaults still apply");
    }

    @Test
    void aFileThatIsntUtf8IsUpgradedInMemoryButNeverRewritten() throws Exception {
        byte[] latin1 = "name: café\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(file(), latin1);
        ShippedYaml.Loaded loaded = settings("name: x\nfresh: 1\n").load();
        assertArrayEquals(latin1, Files.readAllBytes(file()));
        assertEquals(1, loaded.config().getInt("fresh"));
    }

    @Test
    void aByteOrderMarkDoesntBreakTheFile() throws Exception {
        write("﻿limit: 4\n");
        ShippedYaml.Loaded loaded = settings("limit: 0\n").load();
        assertNull(loaded.error());
        assertEquals(4, loaded.config().getInt("limit"));
    }

    // -------------------------------------------------------------- migrations

    @Test
    void aRenameCarriesTheAdminsValueNotTheDefault() throws Exception {
        write("# timeout doc\nold-timeout: 15\n");
        ShippedYaml.Loaded loaded = settings("version: 2\n# timeout doc\nturn:\n  timeout: 60\n",
                ShippedYaml.Migration.rename(1, "old-timeout", "turn.timeout")).load();

        YamlConfiguration onDisk = yaml(text());
        assertEquals(15, onDisk.getInt("turn.timeout"));
        assertFalse(onDisk.isSet("old-timeout"));
        assertEquals(2, onDisk.getInt("version"));
        assertEquals(15, loaded.config().getInt("turn.timeout"));
        assertTrue(logged.stream().noneMatch(l -> l.contains("doesn't use")),
                "a migrated key was reported as unused: " + logged);
    }

    /** A migration only runs once: a file already at the new version is left as it is. */
    @Test
    void aMigrationDoesNotRunTwice() throws Exception {
        ShippedYaml.Migration doubling = new ShippedYaml.Migration(1,
                yml -> yml.set("n", yml.getInt("n") * 2));
        write("n: 5\n");
        settings("version: 2\nn: 1\n", doubling).load();
        assertEquals(10, yaml(text()).getInt("n"));
        settings("version: 2\nn: 1\n", doubling).load();
        assertEquals(10, yaml(text()).getInt("n"));
    }

    /**
     * The old jar's record is migrated too, so a message moved to a new key that the admin
     * never touched is still recognised as untouched, and still gets the new wording.
     */
    @Test
    void aRenamedMessageTheAdminNeverTouchedStillGetsNewWording() throws Exception {
        wording("old-key: first wording\nedited: first\n").load();
        write("old-key: first wording\nedited: theirs\n");

        wording("version: 2\nnew-key: second wording\nedited2: second\n",
                ShippedYaml.Migration.rename(1, "old-key", "new-key"),
                ShippedYaml.Migration.rename(1, "edited", "edited2")).load();
        YamlConfiguration onDisk = yaml(text());
        assertEquals("second wording", onDisk.getString("new-key"));
        assertEquals("theirs", onDisk.getString("edited2"));
    }

    // -------------------------------------------------------- the real files

    /**
     * An older server's copy of each bundled file — a value customised, every comment out of
     * date, no version key, a setting missing — comes out as the current file with the
     * customised value in it: same keys, same comments, same header.
     */
    @Test
    void anOldCopyOfEachBundledFileUpgradesToTheCurrentOneWithItsValuesKept() throws Exception {
        for (String name : List.of("config.yml", "messages.yml")) {
            String shipped = bundled(name);
            ShippedYaml file = bundledFile(name, shipped);
            String versionKey = name.equals("messages.yml") ? "messages-version" : "config-version";
            YamlConfiguration current = yaml(shipped);

            YamlConfiguration old = yaml(shipped);
            old.set(versionKey, null);
            String customised = null;
            String dropped = null;
            for (String key : current.getKeys(true)) {
                if (current.isConfigurationSection(key) || key.equals(versionKey)) {
                    continue;
                }
                if (dropped == null) {
                    dropped = key;
                    old.set(key, null);
                    continue;
                }
                if (customised == null && current.isString(key)) {
                    customised = key;
                    old.set(key, "customised by the admin");
                }
                old.setComments(key, List.of(" an out-of-date comment"));
            }
            write(ShippedYaml.render(old));

            file.load();

            YamlConfiguration expected = yaml(shipped);
            expected.set(customised, "customised by the admin");
            assertEquals(ShippedYaml.fingerprint(expected), ShippedYaml.fingerprint(yaml(text())),
                    name + " didn't upgrade to the current file");
        }
    }

    @Test
    void theBundledFilesHaveAVersionKey() throws Exception {
        assertTrue(yaml(bundled("config.yml")).isInt("config-version"));
        assertTrue(yaml(bundled("messages.yml")).isInt("messages-version"));
    }
}
