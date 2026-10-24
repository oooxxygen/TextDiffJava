package com.textdiff.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 对比配置分组 + 目录批次分组圈定 + 配置管理端点：
 * 分组 CRUD → batch-preview 按分组过滤（名单外文本不操作）→ 未选分组对比全部 → 配置 manage/删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompareGroupApiTest {
    @Autowired MockMvc mvc;

    private static Path base;

    private Path baseDir() throws Exception {
        if (base == null) {
            base = Path.of(System.getProperty("textdiff.base.dir"));
        }
        return base;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String body(Path a, Path b, String extra) {
        return "{\"dir_a\":" + q(a.toString()) + ",\"dir_b\":" + q(b.toString()) + extra + "}";
    }

    @Test
    void groupCrudAndBatchPreviewScoping() throws Exception {
        Path root = baseDir().resolve("grp-it-" + System.nanoTime());
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        Files.writeString(dirA.resolve("f1.txt"), "k1 | 1\n", StandardCharsets.UTF_8);
        Files.writeString(dirB.resolve("f1.txt"), "k1 | 1\n", StandardCharsets.UTF_8);
        Files.writeString(dirA.resolve("f2.txt"), "k2 | 2\n", StandardCharsets.UTF_8);
        Files.writeString(dirB.resolve("f2.txt"), "k2 | 9\n", StandardCharsets.UTF_8);

        // 分组 CRUD
        mvc.perform(post("/api/compare-groups").contentType("application/json")
                        .content("{\"name\":\"日常对比配置\",\"members\":[\"NICK1\",\"NICK2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/compare-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].name").value("日常对比配置"))
                .andExpect(jsonPath("$.groups[0].members[0]").value("NICK1"));

        // 临时写入两条配置（NICK1/NICK2 均通配 f*.txt），测完清理
        Path cfg = baseDir().resolve("configs");
        Files.createDirectories(cfg);
        Path c1 = cfg.resolve("NICK1_UT.conf");
        Path c2 = cfg.resolve("NICK2_UT.conf");
        Files.writeString(c1, "NICK1:f1*.txt:KEYSEQ=1:ENCA=utf-8:ENCB=utf-8\r\n", StandardCharsets.UTF_8);
        Files.writeString(c2, "NICK2:f2*.txt:KEYSEQ=1:ENCA=utf-8:ENCB=utf-8\r\n", StandardCharsets.UTF_8);
        try {
            // 未知分组 → 400
            mvc.perform(post("/api/batch-preview").contentType("application/json")
                            .content(body(dirA, dirB, ",\"use_all\":true,\"group\":\"无此分组\"")))
                    .andExpect(status().isBadRequest());

            // 分组含 NICK1+NICK2 → 两规则都保留 → f1、f2 各一对
            String withGroup = mvc.perform(post("/api/batch-preview").contentType("application/json")
                            .content(body(dirA, dirB, ",\"use_all\":true,\"group\":\"日常对比配置\"")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(withGroup.contains("NICK1") && withGroup.contains("NICK2"), withGroup);
            assertTrue(withGroup.contains("f1.txt") && withGroup.contains("f2.txt"), withGroup);

            // 分组仅含 NICK1 → NICK2 规则被过滤 → 只有 f1 一对（名单外文本不操作）
            mvc.perform(post("/api/compare-groups").contentType("application/json")
                            .content("{\"name\":\"仅一\",\"members\":[\"NICK1\"]}"))
                    .andExpect(status().isOk());
            String onlyOne = mvc.perform(post("/api/batch-preview").contentType("application/json")
                            .content(body(dirA, dirB, ",\"use_all\":true,\"group\":\"仅一\"")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(onlyOne.contains("NICK1"), onlyOne);
            assertFalse(onlyOne.contains("NICK2"), onlyGroupSafe(onlyOne));
            assertTrue(onlyOne.contains("f1.txt"), onlyOne);
            assertFalse(onlyOne.contains("f2.txt"), onlyOne);

            // 未选分组 → 全部生效配置 → 两规则都在（对比全部）
            String noGroup = mvc.perform(post("/api/batch-preview").contentType("application/json")
                            .content(body(dirA, dirB, ",\"use_all\":true")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(noGroup.contains("NICK1") && noGroup.contains("NICK2"), noGroup);

            // 分组删除 → 再用该分组 → 400
            mvc.perform(delete("/api/compare-groups").param("name", "仅一"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/batch-preview").contentType("application/json")
                            .content(body(dirA, dirB, ",\"use_all\":true,\"group\":\"仅一\"")))
                    .andExpect(status().isBadRequest());
        } finally {
            Files.deleteIfExists(c1);
            Files.deleteIfExists(c2);
            mvc.perform(delete("/api/compare-groups").param("name", "日常对比配置"))
                    .andExpect(status().isOk());
            mvc.perform(delete("/api/compare-groups").param("name", "日常对比配置"))
                    .andExpect(status().isNotFound());
        }
    }

    /** 失败信息脱手（避免打印整段响应） */
    private static String onlyGroupSafe(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    @Test
    void configManageListAndDelete() throws Exception {
        // 新增一条配置 → manage 列表可见（含来源文件与行号）→ 删除 → 不再包含
        mvc.perform(post("/api/configs").contentType("application/json")
                        .content("{\"nickname\":\"CFG_UT_X1\",\"file_glob\":\"x*.txt\",\"key_seq\":\"1\","
                                + "\"delimiter\":\" | \",\"trailer_prefix\":\"|||||\"}"))
                .andExpect(status().isOk());
        try {
            String list = mvc.perform(get("/api/configs/manage").param("q", "CFG_UT_X1"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(list.contains("CFG_UT_X1"), list);
            assertTrue(list.contains(".conf") && list.contains("line_no"), list);
        } finally {
            mvc.perform(delete("/api/configs").param("nickname", "CFG_UT_X1"))
                    .andExpect(status().isOk());
        }
        String after = mvc.perform(get("/api/configs/manage").param("q", "CFG_UT_X1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(after.contains("CFG_UT_X1"), after);
        mvc.perform(delete("/api/configs").param("nickname", "CFG_UT_X1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void summaryReportHasDualDeviationMetrics() throws Exception {
        // 跑一个最小批次 → 总结 JSON 含 deviations 双指标字段与等级
        Path root = baseDir().resolve("sum-it-" + System.nanoTime());
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        Files.writeString(dirA.resolve("f1.txt"), "k1 | 1\nk2 | 2\n", StandardCharsets.UTF_8);
        Files.writeString(dirB.resolve("f1.txt"), "k1 | 1\nk2 | 9\n", StandardCharsets.UTF_8);

        String resp = mvc.perform(post("/api/batch-compare").contentType("application/json")
                        .content(body(dirA, dirB, ",\"nickname\":\"SUMNICK\",\"file_glob\":\"f*.txt\","
                                + "\"key_seq\":\"1\",\"delimiter\":\" | \",\"label\":\"总结链路\"")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String batchId = resp.replaceAll(".*\"batch_id\":\"([^\"]+)\".*", "$1");

        String meta = null;
        for (int i = 0; i < 150; i++) {
            meta = mvc.perform(get("/api/batches/" + batchId))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (meta.contains("\"status\":\"done\"") || meta.contains("\"status\":\"error\"")) break;
            Thread.sleep(200);
        }
        assertTrue(meta.contains("\"status\":\"done\""), meta);

        // 差异 1 / A 总 2 = 内容偏离 50%（严重问题）；|B−A|=0 → 条数偏离 0%（正常）
        mvc.perform(get("/api/batches/" + batchId + "/summary-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviations[0].nickname").value("SUMNICK"))
                .andExpect(jsonPath("$.deviations[0].content_ratio").value(0.5))
                .andExpect(jsonPath("$.deviations[0].content_grade").value("严重问题"))
                .andExpect(jsonPath("$.deviations[0].count_ratio").value(0.0))
                .andExpect(jsonPath("$.deviations[0].count_grade").value("正常"))
                .andExpect(jsonPath("$.deviations[0].group").value(""));
        // Excel 端点：一分区一 Sheet 的 xlsx
        mvc.perform(get("/api/batches/" + batchId + "/summary-report.xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        // 清理批次（避免影响其他测试对批次列表顺序的断言）
        mvc.perform(delete("/api/batches/" + batchId)).andExpect(status().isOk());
    }
}
