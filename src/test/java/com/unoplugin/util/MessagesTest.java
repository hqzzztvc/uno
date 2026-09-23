package com.unoplugin.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spotting a messages.yml written by an older jar.
 *
 * <p>The case that shipped: {@code bet.ride-won} was given {@code <items>} until money came
 * in and then {@code <pot>}, and a server still holding the old line printed
 * "&lt;items&gt; item(s) won." to the whole table on every win. An admin's copy of a key
 * always beats the jar's, so nothing else in the plugin would ever have noticed.
 */
class MessagesTest {

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(text);
        return cfg;
    }

    @Test
    void aPlaceholderTheCodeNoLongerFillsInIsStale() throws Exception {
        YamlConfiguration admin = yaml("bet:\n  ride-won: \"<gold><player> takes the hand <gray>— <items> item(s) won.\"\n");
        YamlConfiguration shipped = yaml("bet:\n  ride-won: \"<gold><player> takes the hand <gray>— <pot> won.\"\n");
        assertEquals(List.of("bet.ride-won"), Messages.staleKeys(admin, shipped));
    }

    /** Restyling a message is the admin's business — only placeholders count. */
    @Test
    void recolouringAndFormattingAreNotStale() throws Exception {
        YamlConfiguration admin = yaml("game:\n  win: \"<rainbow><b><player></b> <click:run_command:'/uno ready'>wins!</click><newline><#ff8800>gg\"\n");
        YamlConfiguration shipped = yaml("game:\n  win: \"<gold><bold><player> wins!</bold>\"\n");
        assertTrue(Messages.staleKeys(admin, shipped).isEmpty());
    }

    @Test
    void droppingAPlaceholderOrAddingThePrefixIsNotStale() throws Exception {
        YamlConfiguration admin = yaml("game:\n  win: \"<prefix>Somebody won.\"\n");
        YamlConfiguration shipped = yaml("game:\n  win: \"<gold><player> wins!\"\n");
        assertTrue(Messages.staleKeys(admin, shipped).isEmpty());
    }

    /** A key the jar doesn't ship is just unused, not stale. */
    @Test
    void keysTheJarNoLongerHasAreIgnored() throws Exception {
        YamlConfiguration admin = yaml("bet:\n  stake-items: \"<yellow><items> item(s)\"\n");
        YamlConfiguration shipped = yaml("bet:\n  stake-list-items: \"<items>\"\n");
        assertTrue(Messages.staleKeys(admin, shipped).isEmpty());
    }

    /** See {@code SettingsTest.theConfigVersionMatchesTheMigrations} — the same rule. */
    @Test
    void theMessagesVersionMatchesTheMigrations() throws Exception {
        YamlConfiguration shipped = ShippedYaml.parse(ShippedYamlTest.bundled("messages.yml"));
        SettingsTest.assertVersionMatches(shipped.getInt("messages-version"), Messages.MIGRATIONS);
    }

    /** The jar's own file has to pass its own check, or every fresh install warns. */
    @Test
    void theBundledFileIsNotStaleAgainstItself() throws Exception {
        try (InputStream in = MessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "messages.yml is not on the test classpath");
            YamlConfiguration shipped = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(Messages.staleKeys(shipped, shipped).isEmpty());
        }
    }
}
