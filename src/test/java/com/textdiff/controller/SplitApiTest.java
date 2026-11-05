package com.textdiff.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 文本拆分 API 贯穿：规则配置 CRUD → 提交（A 必填/B 可选）→ 轮询 → 组行浏览 → 符合/不符合条件 zip 导出 → 删除。 */
@SpringBootTest
@AutoConfigureMockMvc
class SplitApiTest {
    @Autowired MockMvc mvc;

    private static Path base;

    private Path baseDir() throws Exception {
        if (base == null) base = Path.of(System.getProperty("textdiff.base.dir"));
        return base;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void 拆分配置与作业贯穿() throws Exception {
        // 1. 规则配置 upsert（范围 + 且/或）
        String cfgJson = "{\"nickname\":\"拆分测试-按险种\",\"delimiter\":\" | \",\"encoding\":\"auto\","
                + "\"trailer_prefix\":\"|||||\",\"rules\":["
                + "{\"name\":\"养老\",\"logic\":\"all\",\"conditions\":[{\"col\":2,\"op\":\"eq\",\"value\":\"养老\"}]},"
                + "{\"name\":\"健康\",\"logic\":\"any\",\"conditions\":[{\"col\":2,\"op\":\"eq\",\"value\":\"健康\"},"
                + "{\"col\":3,\"op\":\"range\",\"value\":\"800~1000\"}]}]}";
        mvc.perform(post("/api/split-configs").contentType("application/json").content(cfgJson))
                .andExpect(status().isOk());
        String cfgList = mvc.perform(get("/api/split-configs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(cfgList.contains("拆分测试-按险种"), cfgList);
        String cfgGet = mvc.perform(get("/api/split-configs/get").param("nickname", "拆分测试-按险种"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(cfgGet.contains("800~1000"), cfgGet);

        // 2. 造输入文件（A 两条命中/未命中，B 一条；trailer 1 行）
        Path dirA = baseDir().resolve("split-in-a");
        Files.createDirectories(dirA);
        Files.writeString(dirA.resolve("in.txt"),
                "k1 | 养老 | 101\nk2 | 医疗 | 50\nk3 | 医疗 | 950\n|||||REC=3\n", StandardCharsets.UTF_8);
        Path dirB = baseDir().resolve("split-in-b");
        Files.createDirectories(dirB);
        Files.writeString(dirB.resolve("in.txt"), "k4 | 养老 | 20\n", StandardCharsets.UTF_8);

        // 3. A 必填校验（400）
        mvc.perform(post("/api/split").contentType("application/json")
                        .content("{\"inputs\":{\"b\":" + q(dirB.resolve("in.txt").toString())
                                + "},\"config\":" + cfgJson + "}"))
                .andExpect(status().isBadRequest());

        // 4. 提交拆分（A 必填 + B 可选）并轮询至完成
        String resp = mvc.perform(post("/api/split").contentType("application/json")
                        .content("{\"label\":\"月结拆分\",\"inputs\":{\"a\":" + q(dirA.resolve("in.txt").toString())
                                + ",\"b\":" + q(dirB.resolve("in.txt").toString()) + "},\"config\":" + cfgJson + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String jobId = resp.replaceAll(".*\"job_id\":\"([^\"]+)\".*", "$1");
        assertFalse(jobId.isBlank(), resp);

        String meta = null;
        for (int i = 0; i < 100; i++) {
            meta = mvc.perform(get("/api/split-jobs/" + jobId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (meta.contains("\"status\":\"done\"") || meta.contains("\"status\":\"error\"")) break;
            Thread.sleep(200);
        }
        assertTrue(meta.contains("\"status\":\"done\""), meta);
        assertTrue(meta.contains("养老") && meta.contains("健康") && meta.contains("其他"), meta);
        assertTrue(meta.contains("\"total\":3"), meta);

        // 5. 组内原始行浏览（未命中行在「其他」组）
        String rows = mvc.perform(get("/api/split-jobs/" + jobId + "/rows")
                        .param("side", "a").param("group", "其他"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(rows.contains("k2 | 医疗 | 50"), rows);
        assertTrue(rows.contains("\"total\":1"), rows);

        // 6. 符合条件 zip（规则组：a_养老 + a_健康 + b_养老）/ 不符合条件 zip（其他组）
        byte[] matched = mvc.perform(get("/api/split-jobs/" + jobId + "/export")
                        .param("fmt", "zip").param("bucket", "matched"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(entries(matched).contains("a_养老.txt"));
        assertTrue(entries(matched).contains("a_健康.txt"));
        assertTrue(entries(matched).contains("b_养老.txt"));
        assertFalse(entries(matched).contains("a_其他.txt"));

        byte[] other = mvc.perform(get("/api/split-jobs/" + jobId + "/export")
                        .param("fmt", "zip").param("bucket", "other"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(java.util.List.of("a_其他.txt"), entries(other));

        // xlsx 汇总
        mvc.perform(get("/api/split-jobs/" + jobId + "/export").param("fmt", "xlsx"))
                .andExpect(status().isOk());

        // 7. 标签 + 删除作业/配置
        mvc.perform(post("/api/split-jobs/" + jobId + "/label").contentType("application/json")
                        .content("{\"label\":\"新标签\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/split-jobs/" + jobId)).andExpect(status().isOk());
        mvc.perform(get("/api/split-jobs/" + jobId)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/split-configs/拆分测试-按险种")).andExpect(status().isOk());
        String after = mvc.perform(get("/api/split-configs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(after.contains("按险种"), after);
    }

    private static java.util.List<String> entries(byte[] zip) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) names.add(e.getName());
        }
        return names;
    }
}
