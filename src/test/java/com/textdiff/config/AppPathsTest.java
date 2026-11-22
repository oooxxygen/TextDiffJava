package com.textdiff.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据根识别（AppPaths.detect）：
 * -Dtextdiff.base.dir 显式最优先；bin+lib 同级便携镜像根 → 数据收口 data/（自动建目录，
 * 启动器工作目录在 bin/ 下时向上识别）；已有 data/ 直接使用；其余维持 CWD 语义（开发态/裸 jar 不变）。
 */
class AppPathsTest {

    @TempDir
    Path dir;

    private String orig;

    @BeforeEach
    void saveAndClearGlobalProp() {
        orig = System.getProperty("textdiff.base.dir"); // gradle test 全局隔离属性，测完还原
        System.clearProperty("textdiff.base.dir");
    }

    @AfterEach
    void restoreGlobalProp() {
        if (orig != null) System.setProperty("textdiff.base.dir", orig);
        else System.clearProperty("textdiff.base.dir");
    }

    @Test
    void baseDirHonorsSystemProperty(@TempDir Path propDir) {
        System.setProperty("textdiff.base.dir", propDir.toString());
        try {
            AppPaths p = AppPaths.detect();
            assertEquals(propDir.toAbsolutePath().normalize(), p.baseDir());
            assertEquals(propDir.toAbsolutePath().normalize().resolve("results"), p.resultsDir());
        } finally {
            System.clearProperty("textdiff.base.dir");
        }
    }

    @Test
    void 显式属性最优先_便携布局存在也以属性为准() throws Exception {
        Files.createDirectories(dir.resolve("bin"));
        Files.createDirectories(dir.resolve("lib"));
        Path custom = dir.resolve("custom-data");
        System.setProperty("textdiff.base.dir", custom.toString());
        try {
            assertEquals(custom, AppPaths.detect(dir).baseDir());
        } finally {
            System.clearProperty("textdiff.base.dir");
        }
    }

    @Test
    void 便携布局_数据收口data目录并自动创建() throws Exception {
        Files.createDirectories(dir.resolve("bin"));
        Files.createDirectories(dir.resolve("lib"));
        AppPaths p = AppPaths.detect(dir);
        assertEquals(dir.resolve("data"), p.baseDir());
        assertTrue(Files.isDirectory(dir.resolve("data")), "检测到便携布局应自动建 data 目录");
        assertEquals(dir.resolve("data").resolve("configs"), p.configsDir());
        assertEquals(dir.resolve("data").resolve("results"), p.resultsDir());
        assertEquals(dir.resolve("data").resolve("config.ini"), p.userConfigIni());
    }

    @Test
    void 启动器工作目录在bin下_向上识别包根() throws Exception {
        Files.createDirectories(dir.resolve("bin"));
        Files.createDirectories(dir.resolve("lib"));
        assertEquals(dir.resolve("data"), AppPaths.detect(dir.resolve("bin")).baseDir());
    }

    @Test
    void 已有data目录_直接使用不再探测() throws Exception {
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        assertEquals(data, AppPaths.detect(dir).baseDir());
    }

    @Test
    void 非便携目录_维持CWD语义() {
        assertEquals(dir, AppPaths.detect(dir).baseDir());
        assertEquals(dir.resolve("configs"), AppPaths.detect(dir).configsDir());
    }
}
