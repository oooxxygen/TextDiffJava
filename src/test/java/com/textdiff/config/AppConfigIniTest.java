package com.textdiff.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 默认 config.ini 生成：首次启动自动落盘默认值；已有文件不覆盖；修改后解析生效。 */
class AppConfigIniTest {

    private static final Function<String, String> NO_ENV = k -> null;

    @Test
    void generatesDefaultIniWhenMissing(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        assertFalse(Files.exists(ini));

        AppConfig cfg = AppConfig.load(ini, NO_ENV);

        assertTrue(Files.exists(ini), "首次启动应生成默认 config.ini");
        String text = Files.readString(ini);
        assertTrue(text.contains("[server]"));
        assertTrue(text.contains("host = 0.0.0.0"));
        assertTrue(text.contains("port = 8080"));
        assertTrue(text.contains("[ai]"));
        assertTrue(text.contains("enabled = false"));
        assertTrue(text.contains("max-prompt-chars = 120000"));
        assertTrue(text.contains("max-concurrency = 2"));
        // 生成文件解析结果 = 内置默认值
        assertEquals(8080, cfg.server().port());
        assertTrue(cfg.store().enabled());
        assertFalse(cfg.ai().usable());
        // 再次加载不重复生成/不报错
        AppConfig again = AppConfig.load(ini, NO_ENV);
        assertEquals(8080, again.server().port());
    }

    @Test
    void doesNotOverwriteExistingIni(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        String original = String.format("[server]%nport = 9090%n");
        Files.writeString(ini, original);
        AppConfig cfg = AppConfig.load(ini, NO_ENV);
        assertEquals(9090, cfg.server().port(), "已有 ini 应生效");
        assertEquals(original, Files.readString(ini), "已有 ini 不得被覆盖");
    }

    @Test
    void editedIniTakesEffectAfterReload(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        AppConfig.load(ini, NO_ENV);
        Files.writeString(ini, """
                [ai]
                enabled = true
                base_url = http://127.0.0.1:9/v1
                model = test-model
                timeout = 300
                max-prompt-chars = 48000
                retries = 5
                retry-backoff-ms = 500
                max-concurrency = 4

                [engine]
                max-threads = 8
                """);
        AppConfig cfg = AppConfig.load(ini, NO_ENV);
        assertTrue(cfg.ai().usable());
        assertEquals("test-model", cfg.ai().model());
        assertEquals(300, cfg.ai().timeoutSeconds());
        assertEquals(48000, cfg.ai().maxPromptChars());
        assertEquals(5, cfg.ai().retries());
        assertEquals(500, cfg.ai().retryBackoffMs());
        assertEquals(4, cfg.ai().maxConcurrency());
        assertEquals(8, cfg.engine().maxThreads());
    }
}
