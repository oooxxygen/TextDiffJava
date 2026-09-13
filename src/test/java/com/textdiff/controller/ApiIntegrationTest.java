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

/** 核心链路端到端：建目录数据 → 提交批次 → 轮询 meta → 结果分页 → 评议 → 删除。 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;

    private static Path base;

    private Path baseDir() throws Exception {
        if (base == null) {
            base = Path.of(System.getProperty("textdiff.base.dir"));
        }
        return base;
    }

    @Test
    void fullCoreFlow() throws Exception {
        Path root = baseDir().resolve("api-it-" + System.nanoTime());
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        Files.writeString(dirA.resolve("f1.txt"), "k1 | 1 | x\nk2 | 2 | y\n", StandardCharsets.UTF_8);
        Files.writeString(dirB.resolve("f1.txt"), "k1 | 1 | x\nk2 | 9 | y\n", StandardCharsets.UTF_8);
        Files.writeString(dirA.resolve("other.txt"), "无规则文件\n", StandardCharsets.UTF_8);

        // 提交批次（内联规则）
        String body = """
                {"dir_a":"%s","dir_b":"%s","delimiter":" | ","encoding_a":"auto","encoding_b":"auto",
                 "nickname":"NICK","file_glob":"*.txt","key_seq":"1"}
                """.formatted(dirA.toString().replace('\\', '/'), dirB.toString().replace('\\', '/'));
        var res = mvc.perform(post("/api/batch-compare").contentType("application/json").content(body))
                .andReturn();
        if (res.getResponse().getStatus() != 200) {
            fail("batch-compare 失败: " + res.getResponse().getContentAsString(StandardCharsets.UTF_8)
                    + " 请求体: " + body);
        }
        String resp = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String batchId = resp.replaceAll(".*\"batch_id\":\"([^\"]+)\".*", "$1");

        // 轮询直至完成（最多 30s）
        String meta = null;
        for (int i = 0; i < 150; i++) {
            meta = mvc.perform(get("/api/batches/" + batchId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (meta.contains("\"status\":\"done\"") || meta.contains("\"status\":\"error\"")) break;
            Thread.sleep(200);
        }
        assertTrue(meta.contains("\"status\":\"done\""), meta);
        assertTrue(meta.contains("\"total_files\":1"), meta);
        assertTrue(meta.contains("\"no_rule_count\":1"), meta); // other.txt 无规则

        String jobId = meta.replaceAll(".*\"job_id\":\"([^\"]+)\".*", "$1");

        // meta：snake_case + zone_counts + summary
        String metaResp = mvc.perform(get("/api/jobs/" + jobId + "/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.job_id").value(jobId))
                .andExpect(jsonPath("$.meta.status").value("done"))
                .andExpect(jsonPath("$.meta.config.key_columns[0]").value(0))
                .andExpect(jsonPath("$.zone_counts.diff").value(1))
                .andExpect(jsonPath("$.zone_counts.equal").value(1))
                .andExpect(jsonPath("$.summary.diff").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(metaResp.contains("\"key_warning\":false"), metaResp);

        // 结果分页：diff zone
        mvc.perform(get("/api/jobs/" + jobId + "/result").param("zone", "diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].key").value("k2"))
                .andExpect(jsonPath("$.rows[0].diff_cols[0]").value(1));

        // q 过滤
        mvc.perform(get("/api/jobs/" + jobId + "/result").param("q", "k1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].status").value("equal"));

        // 评议：写入 → notes → bulk → has 过滤 → 删除
        mvc.perform(post("/api/jobs/" + jobId + "/notes").contentType("application/json")
                        .content("{\"key\":\"k2\",\"note\":\"重点\",\"zone\":\"diff\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs/" + jobId + "/notes"))
                .andExpect(jsonPath("$.zone_note_counts.diff").value(1));
        mvc.perform(post("/api/jobs/" + jobId + "/notes/bulk").contentType("application/json")
                        .content("{\"q\":\"k1\",\"note\":\"批量\"}"))
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/jobs/" + jobId + "/result").param("note", "has"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(post("/api/jobs/" + jobId + "/notes").contentType("application/json")
                        .content("{\"key\":\"k1\",\"note\":\"\",\"zone\":\"diff\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs/" + jobId + "/result").param("note", "has"))
                .andExpect(jsonPath("$.total").value(1));

        // 配置保存 → parse-config → configs 列表
        mvc.perform(post("/api/configs").contentType("application/json")
                        .content("{\"nickname\":\"测试配置\",\"file_glob\":\"*.txt\",\"key_seq\":\"1/2\",\"delimiter\":\" | \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("测试配置"));
        mvc.perform(post("/api/parse-config").contentType("application/json")
                        .content("{\"config_text\":\"NICK:*.v01:KEYSEQ=3/4/5\"}"))
                .andExpect(jsonPath("$.key_seq").value("3/4/5"))
                .andExpect(jsonPath("$.nickname").value("NICK"));
        mvc.perform(get("/api/configs").param("q", "测试"))
                .andExpect(jsonPath("$.configs[0].nickname").value("测试配置"));

        // joblist 含批次
        mvc.perform(get("/api/joblist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[0].batch.batch_id").value(batchId));

        // 终止/重试端点（已完成作业 retry 无副作用）
        mvc.perform(post("/api/jobs/" + jobId + "/retry")).andExpect(status().isOk());

        // 删除批次（级联）
        mvc.perform(delete("/api/batches/" + batchId)).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/" + jobId + "/meta")).andExpect(status().isNotFound());
    }

    @Test
    void encodingsAndAiStatus() throws Exception {
        mvc.perform(get("/api/encodings")).andExpect(jsonPath("$.encodings[0]").value("auto"));
        mvc.perform(get("/api/ai/status")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/jobs/none/analyze")).andExpect(status().isNotFound());
    }

    @Test
    void errorBodyUsesDetail() throws Exception {
        mvc.perform(get("/api/jobs/nonexistent/meta"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}
