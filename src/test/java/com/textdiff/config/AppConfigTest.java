package com.textdiff.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @Test
    void defaultsWhenNoFileNoEnv() {
        AppConfig cfg = AppConfig.load(null, k -> null);
        assertEquals("0.0.0.0", cfg.server().host());
        assertEquals(8080, cfg.server().port());
    }

    @Test
    void iniOverridesDefault(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        Files.writeString(ini, "[server]\nhost = 127.0.0.1\nport = 9000\n");
        AppConfig cfg = AppConfig.load(ini, k -> null);
        assertEquals("127.0.0.1", cfg.server().host());
        assertEquals(9000, cfg.server().port());
    }

    @Test
    void envOverridesIni(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        Files.writeString(ini, "[server]\nport = 9000\n");
        AppConfig cfg = AppConfig.load(ini, k -> "TEXTDIFF_PORT".equals(k) ? "9090" : null);
        assertEquals(9090, cfg.server().port());
    }
}
