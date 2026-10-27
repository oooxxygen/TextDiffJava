package com.textdiff.controller;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 对比批次/轮次贯穿 + 差异采集表导出：
 * batch-compare 指定 cmp_batch/cmp_round → 批次详情可见 → mode=diff 导出为采集对比案例 xlsx
 * （批次/轮次列、栏位索引、A:'..' B:'..'、单方存在 H=0）；未指定时列留空。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseExportApiTest {
    @Autowired MockMvc mvc;

    private static Path base;

    private Path baseDir() throws Exception {
        if (base == null) base = Path.of(System.getProperty("textdiff.base.dir"));
        return base;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 提交目录批次并等待完成，返回批次详情 JSON（含 children）。 */
    private String runBatch(Path dirA, Path dirB, String extra) throws Exception {
        String resp = mvc.perform(post("/api/batch-compare").contentType("application/json")
                        .content("{\"dir_a\":" + q(dirA.toString()) + ",\"dir_b\":" + q(dirB.toString())
                                + ",\"nickname\":\"CASEXP\",\"file_glob\":\"f*.txt\",\"key_seq\":\"1\","
                                + "\"delimiter\":\" | \"" + extra + "}"))
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
        return batchId;
    }

    private static Path dirs(Path baseDir, String bContent) throws Exception {
        Path root = baseDir.resolve("case-it-" + System.nanoTime());
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        Files.writeString(dirA.resolve("f1.txt"), "k1 | 1\nk2 | 2\nk3 | 3\n", StandardCharsets.UTF_8);
        Files.writeString(dirB.resolve("f1.txt"), bContent, StandardCharsets.UTF_8);
        return root;
    }

    @Test
    void 批次轮次贯穿并导出采集表() throws Exception {
        Path root = dirs(baseDir(), "k1 | 1\nk2 | 9\n");
        String batchId = runBatch(root.resolve("a"), root.resolve("b"),
                ",\"cmp_batch\":\"P2703\",\"cmp_round\":\"2\",\"label\":\"采集链路\"");
        try {
            String meta = mvc.perform(get("/api/batches/" + batchId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(meta.contains("\"cmp_batch\":\"P2703\""), meta);
            assertTrue(meta.contains("\"cmp_round\":\"2\""), meta);
            String jobId = meta.replaceAll(".*\"job_id\":\"([^\"]+)\".*", "$1");
            assertFalse(jobId.equals(meta), "children 中应能取到 job_id");

            byte[] xlsx = mvc.perform(get("/api/jobs/" + jobId + "/export").param("mode", "diff"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .andReturn().getResponse().getContentAsByteArray();
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
                Sheet sh = wb.getSheet("data");
                Row head = sh.getRow(0);
                assertEquals("采集日期", head.getCell(0).getStringCellValue());
                assertEquals("批次", head.getCell(1).getStringCellValue());
                assertEquals("轮次", head.getCell(2).getStringCellValue());
                assertEquals("银行号", head.getCell(3).getStringCellValue());
                assertEquals("栏位索引", head.getCell(7).getStringCellValue());
                assertEquals("分析团队", head.getCell(12).getStringCellValue());
                // 行1：k2 主键匹配有差异 → 栏位索引 2（1-based），错误类型 A/B 实际值；批次轮次来自提交
                Row r1 = sh.getRow(1);
                assertEquals("P2703", r1.getCell(1).getStringCellValue());
                assertEquals("2", r1.getCell(2).getStringCellValue());
                assertEquals("*", r1.getCell(3).getStringCellValue());   // 通配符取自配置通配名 f*.txt 的 *
                assertEquals("CASEXP", r1.getCell(5).getStringCellValue()); // 表名 = 昵称
                assertEquals("txt", r1.getCell(6).getStringCellValue());   // 扩展名取实际文件
                assertEquals(2.0, r1.getCell(7).getNumericCellValue(), 1e-9);
                assertEquals("A:'2' B:'9'", r1.getCell(8).getStringCellValue());
                // 行2：k3 仅 A 存在 → 栏位索引 0，整行原貌
                Row r2 = sh.getRow(2);
                assertEquals(0.0, r2.getCell(7).getNumericCellValue(), 1e-9);
                assertEquals("A:'k3 | 3'", r2.getCell(8).getStringCellValue());
                assertNull(sh.getRow(3));
            }
        } finally {
            mvc.perform(delete("/api/batches/" + batchId)).andExpect(status().isOk());
        }
    }

    @Test
    void 批次轮次可留空_导出列空() throws Exception {
        Path root = dirs(baseDir(), "k1 | 1\n");
        String batchId = runBatch(root.resolve("a"), root.resolve("b"), "");
        try {
            String meta = mvc.perform(get("/api/batches/" + batchId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(meta.contains("\"cmp_batch\":null"), meta);
            assertTrue(meta.contains("\"cmp_round\":null"), meta);
            String jobId = meta.replaceAll(".*\"job_id\":\"([^\"]+)\".*", "$1");
            byte[] xlsx = mvc.perform(get("/api/jobs/" + jobId + "/export").param("mode", "diff"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
                Sheet sh = wb.getSheet("data");
                Row r1 = sh.getRow(1); // k1 完全匹配不采集 → 首行是 k2 仅 A
                assertEquals("", r1.getCell(1).getStringCellValue());
                assertEquals("", r1.getCell(2).getStringCellValue());
            }
        } finally {
            mvc.perform(delete("/api/batches/" + batchId)).andExpect(status().isOk());
        }
    }
}
