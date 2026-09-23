package com.unoplugin.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * config.yml and {@link Settings} held to each other.
 *
 * <p>The upgrade machinery can only add a setting to a server's file if the setting is in the
 * jar's config.yml, and the defaults only work if every read finds its key there. Both ways of
 * getting that wrong are silent: a key Settings never reads is a setting that does nothing
 * (config.yml promises every key is read), and a key Settings reads that config.yml doesn't
 * ship comes back as 0 or false on every server.
 */
class SettingsTest {

    /** Records every path Settings asks for. Every typed getter funnels through get(path, def). */
    private static final class Recording extends YamlConfiguration {
        final Set<String> read = new LinkedHashSet<>();

        @Override
        public Object get(String path, Object def) {
            read.add(path);
            return super.get(path, def);
        }
    }

    private static YamlConfiguration shipped() throws Exception {
        return ShippedYaml.parse(ShippedYamlTest.bundled("config.yml"));
    }

    @Test
    void everySettingConfigYmlShipsIsReadAndNothingElseIs() throws Exception {
        Recording config = new Recording();
        config.loadFromString(ShippedYamlTest.bundled("config.yml"));
        new Settings(config);

        List<String> unread = new ArrayList<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key) && !key.equals("config-version")
                    && !config.read.contains(key)) {
                unread.add(key);
            }
        }
        assertTrue(unread.isEmpty(), "config.yml ships settings nothing reads: " + unread
                + ". Wire them up in Settings.apply, or delete them.");

        List<String> missing = new ArrayList<>();
        for (String key : config.read) {
            if (!config.isSet(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "Settings reads keys config.yml doesn't ship: " + missing
                + ". Add them to config.yml, or servers read them as 0/false/empty.");
    }

    /**
     * A server whose file lacks a setting, or has one of the wrong type, runs on exactly what
     * the jar's config.yml says — the defaults live in that file and nowhere else.
     */
    @Test
    void aMissingOrMistypedSettingReadsAsTheShippedValue() throws Exception {
        Settings full = new Settings(shipped());

        YamlConfiguration empty = new YamlConfiguration();
        empty.setDefaults(shipped());
        assertSameSettings(full, new Settings(empty));

        YamlConfiguration mistyped = new YamlConfiguration();
        mistyped.setDefaults(shipped());
        mistyped.set("game.turn-timeout-seconds", "sixty");
        mistyped.set("gambling.enabled", "sure");
        mistyped.set("gambling.limits.blacklist", "SHULKER_BOX");
        assertSameSettings(full, new Settings(mistyped));
    }

    private static void assertSameSettings(Settings expected, Settings actual) throws Exception {
        for (Method getter : Settings.class.getMethods()) {
            if (getter.getDeclaringClass() != Settings.class || getter.getParameterCount() != 0
                    || getter.getName().equals("reload")) {
                continue;
            }
            assertEquals(getter.invoke(expected), getter.invoke(actual), getter.getName());
        }
    }

    /**
     * {@code config-version} has to move with the migrations: a migration whose version the
     * shipped file never reaches never runs, and a version bumped with no migration behind it
     * runs nothing while telling every server it did.
     */
    @Test
    void theConfigVersionMatchesTheMigrations() throws Exception {
        assertVersionMatches(shipped().getInt("config-version"), Settings.MIGRATIONS);
    }

    static void assertVersionMatches(int shippedVersion, List<ShippedYaml.Migration> migrations) {
        int expected = 1;
        Set<Integer> seen = new LinkedHashSet<>();
        for (ShippedYaml.Migration m : migrations) {
            assertTrue(m.from() >= 1, "a migration from version " + m.from());
            expected = Math.max(expected, m.from() + 1);
            seen.add(m.from());
        }
        assertEquals(expected, shippedVersion,
                "the shipped version doesn't match the newest migration");
        for (int v = 1; v < expected; v++) {
            assertTrue(seen.contains(v), "no migration from version " + v + " to " + (v + 1));
        }
    }
}
