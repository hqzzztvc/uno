package com.unoplugin.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading Modrinth's answer and comparing versions — the two halves of the update check that
 * decide what an admin is told, and the only ones that can be checked without the network.
 *
 * <p>The failure this guards against is a wrong notice, not a crash: "update available" for a
 * beta, for a Velocity build, or for 1.9 when 1.10 is installed sends an admin off to download
 * something that is not an update.
 */
class UpdateCheckerTest {

    private static int compare(String a, String b) {
        return Integer.signum(UpdateChecker.compareVersions(a, b));
    }

    @Test
    void versionsCompareAsNumbersNotText() {
        assertEquals(1, compare("1.1", "1.0"));
        assertEquals(1, compare("1.10", "1.9"), "1.10 must beat 1.9");
        assertEquals(1, compare("2.0", "1.99.99"));
        assertEquals(-1, compare("1.0", "1.0.1"));
        assertEquals(0, compare("1.0", "1.0.0"), "a missing part is a zero");
        assertEquals(0, compare("01.02", "1.2"));
        assertEquals(1, compare("100000000000000000001", "100000000000000000000"),
                "long numbers must not overflow");
    }

    @Test
    void decorationsAroundTheNumberAreIgnored() {
        assertEquals(0, compare("v1.2", "1.2"));
        assertEquals(0, compare("1.2+26.2", "1.2"), "build metadata is not part of the order");
        assertEquals(0, compare("LegallyNotUno 1.2", "1.2"));
    }

    @Test
    void aPreReleaseIsOlderThanItsRelease() {
        assertEquals(-1, compare("1.1-beta.2", "1.1"));
        assertEquals(-1, compare("1.0-SNAPSHOT", "1.0"));
        assertEquals(1, compare("1.1-beta.2", "1.0"));
        assertEquals(1, compare("1.1-beta.10", "1.1-beta.2"));
        assertEquals(1, compare("1.1-rc.1", "1.1-beta.9"));
        assertEquals(1, compare("1.1-beta.1", "1.1-beta"));
    }

    @Test
    void aStringWithNoVersionInItIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> compare("latest", "1.0"));
        assertThrows(IllegalArgumentException.class, () -> compare("1.0", "${project.version}"));
    }

    // ------------------------------------------------------ Modrinth's answer

    /** Shaped like the real response from /v2/project/{id}/version, newest first. */
    private static String version(String number, String type, String status, String loaders) {
        return """
                {"id":"x","project_id":"p","name":"%1$s","version_number":"%1$s",\
                "version_type":"%2$s","status":"%3$s","loaders":%4$s,\
                "game_versions":["26.2"],"date_published":"2026-09-19T07:45:35Z","files":[]}"""
                .formatted(number, type, status, loaders);
    }

    @Test
    void theNewestReleaseForThisPlatformIsPicked() {
        String json = "[" + String.join(",",
                version("2.0", "beta", "listed", "[\"paper\"]"),
                version("1.9", "release", "listed", "[\"velocity\"]"),
                version("1.8", "release", "archived", "[\"paper\"]"),
                version("1.2", "release", "listed", "[\"paper\",\"purpur\"]"),
                version("1.10", "release", "listed", "[\"bukkit\",\"spigot\"]"),
                version("1.1", "release", "listed", "[\"paper\"]")) + "]";
        assertEquals("1.10", UpdateChecker.newestRelease(json));
    }

    @Test
    void nothingToOfferIsNull() {
        assertNull(UpdateChecker.newestRelease("[]"));
        assertNull(UpdateChecker.newestRelease("[" + version("1.0", "alpha", "listed", "[\"paper\"]") + "]"));
    }

    @Test
    void aVersionWithNoLoadersListedStillCounts() {
        assertEquals("1.3", UpdateChecker.newestRelease("[" + version("1.3", "release", "listed", "[]") + "]"));
    }

    @Test
    void anAnswerThatIsntAVersionListIsAnError() {
        assertThrows(RuntimeException.class,
                () -> UpdateChecker.newestRelease("{\"error\":\"not_found\"}"));
        assertThrows(RuntimeException.class, () -> UpdateChecker.newestRelease("<html>"));
    }

    // --------------------------------------------------------- our own version

    /**
     * plugin.yml's version is filled in from the pom at build time. If filtering ever stops
     * working it ships as the literal {@code ${project.version}}, which compares with nothing,
     * and every server's update check fails.
     */
    @Test
    void pluginYmlCarriesARealVersionNumber() throws Exception {
        try (InputStream in = UpdateCheckerTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml is not on the test classpath");
            YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String version = plugin.getString("version");
            assertNotNull(version);
            assertFalse(version.contains("${"), "plugin.yml wasn't filtered: " + version);
            assertEquals(0, UpdateChecker.compareVersions(version, version));
            assertTrue(version.matches("\\d+(\\.\\d+)*(-[0-9A-Za-z.]+)?"),
                    "publish this exact string as the Modrinth version number: " + version);
        }
    }
}
