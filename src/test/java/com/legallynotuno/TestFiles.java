package com.legallynotuno;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Deleting a test's scratch folder on Windows.
 *
 * <p>JUnit's own {@code @TempDir} cleanup fails the test the moment a delete doesn't take, and
 * on Windows one sometimes doesn't: an antivirus scan of a file the test just wrote holds it
 * open for a few milliseconds, the file sits "delete pending", and its folder isn't empty yet.
 * That was measured, not guessed — the delete always succeeds a moment later, with nothing of
 * ours still holding the file. Tests that write files use {@code @TempDir(cleanup = NEVER)}
 * and call this instead, which waits the scan out rather than failing on it.
 */
public final class TestFiles {

    private TestFiles() {
    }

    public static void deletePatiently(Path dir) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                if (!Files.exists(dir)) {
                    return;
                }
                List<Path> paths;
                try (Stream<Path> walk = Files.walk(dir)) {
                    paths = walk.sorted(Comparator.reverseOrder()).toList();
                }
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        // Still there after two seconds: leave it for the OS's temp cleanup rather than fail
        // a test whose assertions all passed.
    }
}
