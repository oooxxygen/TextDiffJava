package com.textdiff.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AppPathsTest {
    @Test
    void baseDirHonorsSystemProperty(@TempDir Path dir) {
        String prev = System.getProperty("textdiff.base.dir");
        try {
            System.setProperty("textdiff.base.dir", dir.toString());
            AppPaths p = AppPaths.detect();
            assertEquals(dir.toAbsolutePath().normalize(), p.baseDir());
            assertEquals(dir.resolve("results").toAbsolutePath().normalize(), p.resultsDir());
        } finally {
            if (prev == null) System.clearProperty("textdiff.base.dir");
            else System.setProperty("textdiff.base.dir", prev);
        }
    }
}
