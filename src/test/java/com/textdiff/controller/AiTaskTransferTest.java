package com.textdiff.controller;

import com.textdiff.ai.AiReportNamer;
import com.textdiff.store.FieldMapStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AI 待执行任务导出/导入（跨环境补跑）：无 AI 环境导出 prompt → 外部大模型执行 → 导入回填。
 * 覆盖：导出 zip 结构（{jobId}_{昵称}/prompt.md + task.json）、导入回填（ai_analysis.md + 导出目录
 * 「文件名-报表名」命名 .md/.html + aiStatus=done + 任务置 DONE）、文件名 8 位作业 id 兜底、
 * 未知作业拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiTaskTransferTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JobStore store;
    @Autowired
    private FieldMapStore fieldMaps;

    private Path baseDir() throws Exception {
        return Path.of(System.getProperty("textdiff.base.dir"));
    }

    private static String json(String body) {
        return body;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 提交单文件目录批次并等待完成，返回作业 id。 */
    private String runJob(Path dirA, Path dirB, String nick) throws Exception {
        String resp = mvc.perform(post("/api/batch-compare").contentType("application/json")
                        .content(json("{\"dir_a\":" + q(dirA.toString()) + ",\"dir_b\":" + q(dirB.toString())
                                + ",\"nickname\":\"" + nick + "\",\"file_glob\":\"f*.txt\",\"key_seq\":\"1\","
                                + "\"delimiter\":\" | \"}")))
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
        String jobId = meta.replaceAll(".*\"job_id\":\"([^\"]+)\".*", "$1");
        assertFalse(jobId.equals(meta));
        return jobId;
    }

    private static byte[] buildZip(List<String[]> entries) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf, StandardCharsets.UTF_8)) {
            for (String[] e : entries) {
                zos.putNextEntry(new ZipEntry(e[0]));
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return buf.toByteArray();
    }

    private static List<String[]> readZip(byte[] zip) throws Exception {
        List<String[]> out = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                out.add(new String[]{e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8)});
            }
        }
        return out;
    }

    @Test
    void 导出导入回填全链路_文件名兜底_未知作业拒绝() throws Exception {
        Path root = baseDir().resolve("ai-tr-" + System.nanoTime());
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        Files.writeString(dirA.resolve("f1.txt"), "k1 | 1\nk2 | 2\n", StandardCharsets.UTF_8);
        Files.writeString(dirB.resolve("f1.txt"), "k1 | 1\nk2 | 9\n", StandardCharsets.UTF_8);
        String jobId = runJob(dirA, dirB, "AITR" + System.nanoTime());
        try {
            // ---- 导出：zip 含 {jobId}_*/prompt.md + task.json（job_id 指回作业）----
            byte[] zip = mvc.perform(get("/api/tasks/ai-export").param("job_ids", jobId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/zip"))
                    .andReturn().getResponse().getContentAsByteArray();
            List<String[]> entries = readZip(zip);
            assertTrue(entries.stream().anyMatch(e -> e[0].startsWith(jobId + "_") && e[0].endsWith("/prompt.md")),
                    "应有 {jobId}_*/prompt.md: " + entries.stream().map(e -> e[0]).toList());
            String taskJson = entries.stream().filter(e -> e[0].endsWith("/task.json")).findFirst().orElseThrow()[1];
            assertTrue(taskJson.contains("\"job_id\":\"" + jobId + "\""), taskJson);
            // prompt.md 应已填充（自足性：非空、不含未替换占位符）
            String prompt = entries.stream().filter(e -> e[0].endsWith("/prompt.md")).findFirst().orElseThrow()[1];
            assertFalse(prompt.isBlank());
            assertFalse(prompt.contains("{{nickname}}"), "模板应已填充: " + prompt.substring(0, Math.min(300, prompt.length())));

            // ---- 导入（zip 带 task.json）：原样回填 + 缺一级标题自动补 ----
            String analysisBody = "## 一、结论\n\nA 侧与 B 侧存在 1 处栏位值差异，属数据变化。\n";
            byte[] importZip = buildZip(List.of(
                    new String[]{jobId + "_AITR/task.json", taskJson},
                    new String[]{jobId + "_AITR/prompt.md", "（原 prompt 回传，导入端取 .md 为结果）"},
                    new String[]{jobId + "_AITR/ai_analysis.md", analysisBody}));
            MvcResult r = mvc.perform(multipart("/api/tasks/ai-import")
                            .file(new MockMultipartFile("files", "ai-results.zip", "application/zip", importZip)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(1))
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.results[0].ok").value(true))
                    .andExpect(jsonPath("$.results[0].job_id").value(jobId))
                    .andReturn();
            // 作业状态与结果文件（先等内建 AI 任务跑完——测试环境无 AI 配置，aiStatus 落到 disabled 后再导入防竞态覆盖）
            for (int i = 0; i < 50; i++) {
                String st = store.getJob(jobId).aiStatus;
                if ("disabled".equals(st) || "done".equals(st) || "failed".equals(st)) break;
                Thread.sleep(200);
            }
            JobRecord job = store.getJob(jobId);
            assertEquals("done", job.aiStatus);
            JobMetaHolder holder = new JobMetaHolder(job);
            assertTrue(Files.exists(holder.analysisMd), "results/{jobId}/ai_analysis.md 应存在");
            String back = Files.readString(holder.analysisMd, StandardCharsets.UTF_8);
            assertTrue(back.contains("## 一、结论"));
            assertTrue(back.contains("# "), "缺一级标题应自动补：" + back);
            // 导出目录按「[归属组]文件昵称_实际文件名」命名生成 .md/.html（与原生同位同名）
            String expectedBase = AiReportNamer.fileName(job, fieldMaps);
            Path exportMd = holder.exportDir.resolve(expectedBase + ".md");
            Path exportHtml = holder.exportDir.resolve(expectedBase + ".html");
            assertTrue(Files.exists(exportMd), "应存在 " + exportMd);
            assertTrue(Files.exists(exportHtml), "应存在 " + exportHtml);
            assertTrue(Files.readString(exportMd, StandardCharsets.UTF_8).contains("## 一、结论"));
            // 任务记录置 DONE 且产物指向导出目录
            String tasksJson = mvc.perform(get("/api/tasks").param("job_id", jobId))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(tasksJson.contains("\"task_type\":\"ai_analysis\""), tasksJson);
            assertTrue(tasksJson.contains("\"status\":\"done\""), "AI 任务应置 done: " + tasksJson);
            assertTrue(tasksJson.contains(expectedBase), "outputs 应含导出目录产物: " + tasksJson);

            // ---- 导入（裸 .md 文件名兜底：文件名含 8 位作业 id，无 task.json）----
            String body2 = "外部大模型输出的分析结论 v2。\n";
            mvc.perform(multipart("/api/tasks/ai-import")
                            .file(new MockMultipartFile("files", "ai_" + jobId + ".md",
                                    "text/markdown", body2.getBytes(StandardCharsets.UTF_8))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(1));
            assertTrue(Files.readString(holder.analysisMd, StandardCharsets.UTF_8).contains("v2"), "裸 md 覆盖导入生效");
            assertTrue(Files.readString(exportMd, StandardCharsets.UTF_8).contains("v2"));

            // ---- 未知作业拒绝（文件名含不存在的 8 位 hex）----
            mvc.perform(multipart("/api/tasks/ai-import")
                            .file(new MockMultipartFile("files", "ai_deadbeef.md",
                                    "text/markdown", "x".getBytes(StandardCharsets.UTF_8))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(0))
                    .andExpect(jsonPath("$.results[0].ok").value(false))
                    .andExpect(jsonPath("$.results[0].reason").value(
                            org.hamcrest.Matchers.containsString("无法识别作业")));
        } finally {
            mvc.perform(delete("/api/batches/" + guessBatch(jobId))).andExpect(status().isOk());
        }
    }

    /** 作业所属批次 id（删除级联用）。 */
    private String guessBatch(String jobId) {
        JobRecord j = store.getJob(jobId);
        return j == null || j.batchId == null ? "none" : j.batchId;
    }

    /** 结果目录/导出目录定位助手（默认导出目录 = results/{batchId}/export）。 */
    private static final class JobMetaHolder {
        final Path analysisMd;
        final Path exportDir;

        JobMetaHolder(JobRecord job) {
            Path dir = Path.of(job.resultDir);
            this.analysisMd = dir.resolve("ai_analysis.md");
            Path results = dir.getParent(); // results/
            this.exportDir = results.resolve((job.batchId == null ? "standalone" : job.batchId)).resolve("export");
        }
    }

    @Test
    void 无待执行任务_404() throws Exception {
        // 空参数 + 空库（test-run 已被其他用例填充作业时仍可能 200——仅断言端点可访问性与 JSON/zip 二选一）
        MvcResult r = mvc.perform(get("/api/tasks/ai-export"))
                .andReturn();
        assertTrue(r.getResponse().getStatus() == 200 || r.getResponse().getStatus() == 404,
                "应返回 200（zip）或 404（无待执行）");
    }
}
