package com.textdiff.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 列名映射导入端到端：multipart 上传结构 CSV → 生成配置 → 字段映射查询。 */
@SpringBootTest
@AutoConfigureMockMvc
class FieldMapEndpointTest {
    @Autowired MockMvc mvc;

    private static Path base;

    private Path baseDir() {
        if (base == null) base = Path.of(System.getProperty("textdiff.base.dir"));
        return base;
    }

    @Test
    void importStructureGeneratesConfigsWithColumnNames() throws Exception {
        byte[] typeCsv = """
                report_id,report_file_name,parm_report_type
                bocso_it,bocso_it.txt,对公存款
                """.getBytes(StandardCharsets.UTF_8);
        byte[] fieldCsv = """
                report_id,field_name,field_format
                bocso_it,客户号,CHAR
                bocso_it,账户余额,DECIMAL
                bocso_it,币种,CHAR
                """.getBytes(StandardCharsets.UTF_8);
        long tag = System.nanoTime();
        MockMultipartFile f1 = new MockMultipartFile("files", "bat_report_type_parm_x.csv",
                "text/csv", typeCsv);
        MockMultipartFile f2 = new MockMultipartFile("files", "bat_report_conf_field_x.csv",
                "text/csv", fieldCsv);

        mvc.perform(multipart("/api/configs/import-structure").file(f1).file(f2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.types").value(1))
                .andExpect(jsonPath("$.fields").value(3))
                .andExpect(jsonPath("$.nicknames[0]").value("bocso_it"))
                .andExpect(jsonPath("$.file").value("configs/ bocso_it.conf"));

        // 生成的配置：昵称可搜索、含 COLS 列名令牌
        Path conf = baseDir().resolve("configs").resolve("bocso_it.conf");
        assertTrue(Files.exists(conf), "配置文件未生成: " + conf);
        String line = Files.readString(conf, StandardCharsets.UTF_8).strip();
        assertTrue(line.startsWith("bocso_it:"), line);
        assertTrue(line.contains("COLS="), line);

        // 配置列表接口能看到该昵称
        mvc.perform(get("/api/configs").param("q", "bocso_it"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs[0].nickname").value("bocso_it"));

        // 字段映射浏览 + 详情
        mvc.perform(get("/api/fieldmaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].report_id").value("bocso_it"))
                .andExpect(jsonPath("$.reports[0].field_count").value(3))
                .andExpect(jsonPath("$.reports[0].parm_report_type").value("对公存款"));
        mvc.perform(get("/api/fieldmaps/get").param("report_id", "bocso_it"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].col_index").value(0))
                .andExpect(jsonPath("$.columns[0].field_name").value("客户号"))
                .andExpect(jsonPath("$.columns[2].field_name").value("币种"));
    }

    @Test
    void importWithUnrecognizedCsvRejected() throws Exception {
        MockMultipartFile f = new MockMultipartFile("files", "unknown.csv", "text/csv",
                "foo,bar\n1,2\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/configs/import-structure").file(f))
                .andExpect(status().isBadRequest());
    }
}
