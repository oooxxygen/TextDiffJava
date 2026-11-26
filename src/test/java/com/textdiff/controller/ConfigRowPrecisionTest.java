package com.textdiff.controller;

import com.textdiff.config.AppPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置「精准写删」单元测试：不启动 Spring，@TempDir + 直接 new ConfigController
 * （save/delete 路径仅依赖 AppPaths，FieldMapStore/AppConfig 传 null）。
 * 覆盖：保存的精确行替换 / 按键替换（空串 glob ≠ *）/ 未命中追加 / 目标文件决定（原名 vs 补 .conf），
 * 以及删除的精确删行 / 行号越界 400 / 昵称不符 400 / 缺参数走旧行为。
 */
class ConfigRowPrecisionTest {

    @TempDir
    Path tempDir;

    private ConfigController controller;
    private Path configs;

    @BeforeEach
    void setUp() {
        // AppPaths(baseDir, bundleDir)：configsDir() = baseDir/configs
        controller = new ConfigController(new AppPaths(tempDir, tempDir.resolve("bundle")), null, null);
        configs = tempDir.resolve("configs");
    }

    // ---- 准备/读取辅助 ----

    private Path writeConf(String name, String... lines) throws IOException {
        Files.createDirectories(configs);
        Path f = configs.resolve(name);
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private List<String> readLines(String name) throws IOException {
        return Files.readAllLines(configs.resolve(name), StandardCharsets.UTF_8);
    }

    /** 构造请求体：key/value 交替。 */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- POST /api/configs（保存） ----

    @Test
    void 精确行替换_按行号定位忽略键匹配() throws IOException {
        writeConf("src.conf",
                "alpha:1.txt:KEYSEQ=1",
                "beta:2.txt::DELIM=,",
                "gamma:3.txt:KEYSEQ=3");
        // source_file=target + source_line=2：即使键（beta+9.txt）在文件中不存在也按行号替换
        Map<String, Object> resp = controller.save(body(
                "nickname", "beta", "file_glob", "9.txt", "delimiter", ",",
                "source_file", "src.conf", "source_line", 2, "target", "src.conf"));
        assertEquals(2, resp.get("replaced_line"));
        List<String> lines = readLines("src.conf");
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("alpha:1.txt"));
        assertTrue(lines.get(1).startsWith("beta:9.txt"));
        assertTrue(lines.get(2).startsWith("gamma:3.txt"));
    }

    @Test
    void 键替换_空串glob只匹配空段行_不当星号() throws IOException {
        writeConf("cur.conf",
                "nokey::DELIM=,",
                "star:*.csv:KEYSEQ=1");
        // 空串 glob：只命中第二冒号段为空的行，不得误命中 star:*.csv，也不得当 * 用
        Map<String, Object> resp = controller.save(body("nickname", "nokey", "file_glob", "",
                "target", "cur.conf"));
        assertEquals(1, resp.get("replaced_line"));
        List<String> lines = readLines("cur.conf");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("nokey:") && !lines.get(0).contains("fullmatch.txt"));
        assertTrue(lines.get(1).startsWith("star:*.csv"));
    }

    @Test
    void 键替换_星号glob匹配星号行() throws IOException {
        writeConf("cur.conf",
                "nokey:fullmatch.txt::DELIM=,",
                "star:*.csv:KEYSEQ=1");
        Map<String, Object> resp = controller.save(body("nickname", "star", "file_glob", "*.csv",
                "target", "cur.conf"));
        assertEquals(2, resp.get("replaced_line"));
        assertEquals(2, readLines("cur.conf").size());
    }

    @Test
    void 键未命中_追加文件尾() throws IOException {
        writeConf("cur.conf", "alpha:1.txt:KEYSEQ=1");
        Map<String, Object> resp = controller.save(body("nickname", "beta", "file_glob", "*",
                "target", "cur.conf"));
        assertNull(resp.get("replaced_line"));
        List<String> lines = readLines("cur.conf");
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("beta:*"));
    }

    @Test
    void 目标文件已存在同名_用原名不补后缀() throws IOException {
        writeConf("配置.txt", "alpha:1.txt:KEYSEQ=1");
        controller.save(body("nickname", "beta", "file_glob", "2.txt", "target", "配置.txt"));
        assertTrue(Files.isRegularFile(configs.resolve("配置.txt")));
        assertFalse(Files.exists(configs.resolve("配置.txt.conf")));
        List<String> lines = readLines("配置.txt");
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("beta:2.txt"));
    }

    @Test
    void 目标文件不存在_新建补conf后缀() throws IOException {
        Map<String, Object> resp = controller.save(body("nickname", "beta", "file_glob", "2.txt", "target", "myrules"));
        assertEquals("myrules.conf", resp.get("target"));
        assertTrue(Files.isRegularFile(configs.resolve("myrules.conf")));
        assertEquals(1, readLines("myrules.conf").size());
    }

    @Test
    void target缺省_回写source_file原名() throws IOException {
        Map<String, Object> resp = controller.save(body(
                "nickname", "beta", "file_glob", "2.txt", "source_file", "配置.txt"));
        assertEquals("配置.txt", resp.get("target"));
        assertTrue(Files.isRegularFile(configs.resolve("配置.txt")));
        assertFalse(Files.exists(configs.resolve("配置.txt.conf")));
    }

    @Test
    void target缺省且无source_file_写current_conf() throws IOException {
        Map<String, Object> resp = controller.save(body("nickname", "beta", "file_glob", "2.txt"));
        assertEquals("current.conf", resp.get("target"));
        assertTrue(Files.isRegularFile(configs.resolve("current.conf")));
    }

    // ---- DELETE /api/configs（删除） ----

    @Test
    void 精确删行_只删该文件该行其余原样() throws IOException {
        writeConf("src.conf",
                "alpha:1.txt:KEYSEQ=1",
                "beta:2.txt:KEYSEQ=2",
                "alpha:9.txt:KEYSEQ=9");
        writeConf("other.conf", "beta:other.txt:KEYSEQ=2");
        Map<String, Object> resp = controller.delete("beta", "src.conf", 2);
        assertEquals(Boolean.TRUE, resp.get("deleted_line"));
        assertEquals("src.conf", resp.get("file"));
        assertEquals(2, resp.get("line_no"));
        // 只删该行：其余行保持原顺序；其他文件同昵称行不受影响
        assertEquals(List.of("alpha:1.txt:KEYSEQ=1", "alpha:9.txt:KEYSEQ=9"), readLines("src.conf"));
        assertEquals(1, readLines("other.conf").size());
    }

    @Test
    void 精确删行_行号越界返回400() throws IOException {
        writeConf("src.conf", "alpha:1.txt:KEYSEQ=1");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete("alpha", "src.conf", 99));
        assertEquals(400, ex.getStatusCode().value());
        assertEquals(1, readLines("src.conf").size());
    }

    @Test
    void 精确删行_昵称不符返回400() throws IOException {
        writeConf("src.conf", "alpha:1.txt:KEYSEQ=1", "beta:2.txt:KEYSEQ=2");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete("alpha", "src.conf", 2));
        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("与请求不符"));
        assertEquals(2, readLines("src.conf").size());
    }

    @Test
    void 精确删行_空行注释返回400() throws IOException {
        writeConf("src.conf", "alpha:1.txt:KEYSEQ=1", "# 注释行", "");
        assertThrows(ResponseStatusException.class, () -> controller.delete("alpha", "src.conf", 3));
        assertThrows(ResponseStatusException.class, () -> controller.delete("alpha", "src.conf", 2));
        assertEquals(3, readLines("src.conf").size());
    }

    @Test
    void 精确删行_文件不存在返回404() {
        assertThrows(ResponseStatusException.class, () -> controller.delete("alpha", "nope.conf", 1));
    }

    @Test
    void 精确删行_参数只给一半返回400() throws IOException {
        writeConf("src.conf", "alpha:1.txt:KEYSEQ=1");
        assertThrows(IllegalArgumentException.class, () -> controller.delete("alpha", "src.conf", null));
        assertThrows(IllegalArgumentException.class, () -> controller.delete("alpha", null, 1));
        assertEquals(1, readLines("src.conf").size());
    }

    @Test
    void 缺source与line_no_走旧跨文件删除逻辑() throws IOException {
        writeConf("a.conf", "beta:1.txt:KEYSEQ=1");
        writeConf("b.txt", "beta:2.txt:KEYSEQ=2");
        Map<String, Object> resp = controller.delete("beta", null, null);
        assertEquals(Boolean.TRUE, resp.get("deleted"));
        assertTrue(((List<?>) resp.get("files")).containsAll(List.of("a.conf", "b.txt")));
        assertTrue(readLines("a.conf").isEmpty());
        assertTrue(readLines("b.txt").isEmpty());
    }
}
