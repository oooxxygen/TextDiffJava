package com.textdiff.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** config.ini [jvm] jvm_opts：读取 / 保存写回（保留注释与既有段）。 */
class AppConfigJvmOptsTest {

    private static final Function<String, String> NO_ENV = k -> null;

    @Test
    void defaultEmptyAndDefaultIniContainsJvmSection(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        AppConfig cfg = AppConfig.load(ini, NO_ENV);
        assertEquals("", cfg.jvmOpts());
        String text = Files.readString(ini);
        assertTrue(text.contains("[jvm]"), "默认 ini 应含 [jvm] 段");
        assertTrue(text.contains("jvm_opts ="), text);
    }

    @Test
    void readFromIniAndRoundTrip(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        Files.writeString(ini, """
                [server]
                port = 18080

                [jvm]
                # JVM 运行参数（注释应保留）
                jvm_opts = -Xmx4g -XX:+UseG1GC
                """);
        AppConfig cfg = AppConfig.load(ini, NO_ENV);
        assertEquals("-Xmx4g -XX:+UseG1GC", cfg.jvmOpts());

        cfg.updateJvmOpts("-Xmx2g");
        assertEquals("-Xmx2g", cfg.jvmOpts());
        String text = Files.readString(ini);
        assertTrue(text.contains("jvm_opts = -Xmx2g"), text);
        assertTrue(text.contains("# JVM 运行参数（注释应保留）"), "写回应保留注释");
        assertTrue(text.contains("port = 18080"), "写回应保留其他段");
        // 重载一致
        assertEquals("-Xmx2g", AppConfig.load(ini, NO_ENV).jvmOpts());
    }

    @Test
    void updateCreatesMissingSection(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("config.ini");
        AppConfig.load(ini, NO_ENV);
        // 模拟旧版 ini：移除 [jvm] 段
        String text = Files.readString(ini).replaceAll("(?s)\\[jvm\\].*", "").stripTrailing() + "\n";
        Files.writeString(ini, text);
        assertFalse(text.contains("[jvm]"));

        AppConfig cfg = AppConfig.load(ini, NO_ENV);
        cfg.updateJvmOpts("-Xmx1g");
        assertEquals("-Xmx1g", AppConfig.load(ini, NO_ENV).jvmOpts());
    }
}
