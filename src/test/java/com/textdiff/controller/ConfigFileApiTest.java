package com.textdiff.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 配置文件体系（v0.9.15 需求④⑤）：
 * 保存目标隔离（current.conf 与独立文件互不影响）→ 同名规则多文件提示 → 文件改名/删除与系统文件保护 →
 * conf 导出（注释辅助信息 + 规则行 + COLS 重编码）→ Excel 导出回导往返。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigFileApiTest {
    @Autowired MockMvc mvc;

    private static Path base;

    private Path baseDir() throws Exception {
        if (base == null) base = Path.of(System.getProperty("textdiff.base.dir"));
        return base;
    }

    private static String json(String body) {
        return body;
    }

    @Test
    void saveTargetIsolationFileCrudAndDups() throws Exception {
        Path cfg = baseDir().resolve("configs");
        Files.createDirectories(cfg);
        Path cur = cfg.resolve("current.conf");
        String origCurrent = Files.isRegularFile(cur) ? Files.readString(cur, StandardCharsets.UTF_8) : "";
        String nick = "TGTISO" + System.nanoTime();
        String ownName = "DPTM_TEST_" + System.nanoTime();
        String renamed = "RENAMED_" + System.nanoTime();
        // default.conf 测试环境可能不存在：受保护改名/删除需要文件存在——补建种子文件，finally 还原
        Path defP = cfg.resolve("default.conf");
        boolean defExisted = Files.isRegularFile(defP);
        String origDefault = defExisted ? Files.readString(defP, StandardCharsets.UTF_8) : null;
        if (!defExisted) Files.writeString(defP, "SEED:x.txt:KEYSEQ=1\r\n", StandardCharsets.UTF_8);
        try {
            // 保存默认 target=current.conf
            mvc.perform(post("/api/configs").contentType(MediaType.APPLICATION_JSON).content(json("""
                    {"nickname":"%s","file_glob":"f*.txt","key_seq":"1","delimiter":" | ","target":"current.conf"}"""
                    .formatted(nick))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.target").value("current.conf"));
            assertTrue(Files.readString(cur, StandardCharsets.UTF_8).contains(nick + ":f*.txt:KEYSEQ=1"));

            // 保存到独立文件 → current.conf 原文不变（Q14/Q15 口径：文件间互不影响）
            String before = Files.readString(cur, StandardCharsets.UTF_8);
            mvc.perform(post("/api/configs").contentType(MediaType.APPLICATION_JSON).content(json("""
                    {"nickname":"%s","file_glob":"f*.txt","key_seq":"1/2","delimiter":" | ","target":"%s"}"""
                    .formatted(nick, ownName))))
                    .andExpect(status().isOk());
            assertEquals(before, Files.readString(cur, StandardCharsets.UTF_8), "保存到独立文件不得改动 current.conf");
            assertTrue(Files.readString(cfg.resolve(ownName + ".conf"), StandardCharsets.UTF_8)
                    .contains(nick + ":f*.txt:KEYSEQ=1/2"));

            // 同（昵称+通配名）规则多文件共存 → manage 端点 dups 提示
            mvc.perform(get("/api/configs/manage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dups[?(@.nickname=='" + nick + "')]").exists());

            // 独立文件重命名
            mvc.perform(post("/api/config-files/rename").contentType(MediaType.APPLICATION_JSON)
                            .content(json("{\"from\":\"%s\",\"to\":\"%s\"}".formatted(ownName, renamed))))
                    .andExpect(status().isOk());
            assertFalse(Files.exists(cfg.resolve(ownName + ".conf")));
            assertTrue(Files.exists(cfg.resolve(renamed + ".conf")));

            // 系统文件保护：current.conf/default.conf 不可新建/改名/删除
            mvc.perform(post("/api/config-files").contentType(MediaType.APPLICATION_JSON)
                            .content(json("{\"name\":\"current.conf\"}")))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/config-files/rename").contentType(MediaType.APPLICATION_JSON)
                            .content(json("{\"from\":\"default.conf\",\"to\":\"X\"}")))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete("/api/config-files?name=current.conf"))
                    .andExpect(status().isBadRequest());

            // 独立文件删除
            mvc.perform(delete("/api/config-files?name=" + renamed)).andExpect(status().isOk());
            assertFalse(Files.exists(cfg.resolve(renamed + ".conf")));
        } finally {
            Files.writeString(cur, origCurrent, StandardCharsets.UTF_8);
            if (defExisted) Files.writeString(defP, origDefault, StandardCharsets.UTF_8);
            else Files.deleteIfExists(defP);
            try (var s = Files.list(cfg)) {
                for (Path p : s.filter(x -> {
                    String n = x.getFileName().toString();
                    return n.startsWith("DPTM_TEST_") || n.startsWith("RENAMED_");
                }).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    void confExportAndExcelRoundTrip() throws Exception {
        Path cfg = baseDir().resolve("configs");
        Files.createDirectories(cfg);
        Path cur = cfg.resolve("current.conf");
        String origCurrent = Files.isRegularFile(cur) ? Files.readString(cur, StandardCharsets.UTF_8) : "";
        String nick = "RTX" + System.nanoTime();
        try {
            // 带列名保存（column_names 重编码 COLS——修复旧版保存忽略列名导致 COLS 陈旧）
            mvc.perform(post("/api/configs").contentType(MediaType.APPLICATION_JSON).content(json("""
                    {"nickname":"%s","file_glob":"rt*.txt","key_seq":"3/4/5","omit_seq":"1/2",
                     "delimiter":" | ","target":"current.conf","column_names":["AA","BB","CC","DD","EE"]}"""
                    .formatted(nick))))
                    .andExpect(status().isOk());
            assertTrue(Files.readString(cur, StandardCharsets.UTF_8).contains("COLS="));

            // conf 导出：# 注释头（导出时间/来源文件修改时间，Q13 辅助信息）+ 合并生效规则 + 可回导
            byte[] conf = mvc.perform(get("/api/configs/export?fmt=conf"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            String text = new String(conf, StandardCharsets.UTF_8);
            assertTrue(text.contains("# TextDiffJava 生效配置导出"));
            assertTrue(text.contains("# 导出时间:"));
            assertTrue(text.contains("# 来源文件修改时间:"));
            assertTrue(text.contains(nick + ":rt*.txt"), "生效规则应出现在 conf 导出中");
            assertTrue(text.contains("COLS="));

            // Excel 导出 → 回导往返（导入按（昵称+通配名）合并写入 current.conf）
            byte[] xlsx = mvc.perform(get("/api/configs/export"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            Path tmp = baseDir().resolve("tmp-roundtrip-" + System.nanoTime() + ".xlsx");
            Files.write(tmp, xlsx);
            try {
                MockMultipartFile mf = new MockMultipartFile("file", "configs.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        Files.readAllBytes(tmp));
                mvc.perform(multipart("/api/configs/import-excel").file(mf))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.file").value("current.conf"));
                String after = Files.readString(cur, StandardCharsets.UTF_8);
                assertTrue(after.contains(nick + ":rt*.txt"), "回导后规则应合并进 current.conf");
                assertTrue(after.contains("COLS="), "回导应按列名明文重建 COLS");
            } finally {
                Files.deleteIfExists(tmp);
            }
        } finally {
            Files.writeString(cur, origCurrent, StandardCharsets.UTF_8);
        }
    }
}
