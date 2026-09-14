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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 列名映射导入端到端：multipart 上传结构 CSV → 纯映射落库（不生成 .conf）→ 字段映射查询。 */
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
    void importStructureStoresMappingsWithoutGeneratingConf() throws Exception {
        byte[] typeCsv = ("report_id,report_file_name,parm_report_type,ownership_group\n"
                + "bocso_it,bocso_it.txt,对公存款,bocs_dep\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] fieldCsv = ("report_id,field_index,field_name,field_format,field_length\n"
                + "bocso_it,1,客户号,CHAR,12\n"
                + "bocso_it,2,账户余额,DECIMAL,16\n"
                + "bocso_it,3,币种,CHAR,3\n")
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile f1 = new MockMultipartFile("files", "bat_report_type_parm_x.csv",
                "text/csv", typeCsv);
        MockMultipartFile f2 = new MockMultipartFile("files", "bat_report_conf_field_x.csv",
                "text/csv", fieldCsv);

        mvc.perform(multipart("/api/configs/import-structure").file(f1).file(f2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").value(1))
                .andExpect(jsonPath("$.fields").value(3))
                .andExpect(jsonPath("$.nicknames[0]").value("bocso_it"));

        // 纯映射落库：不再生成 .conf 对比配置
        assertFalse(Files.exists(baseDir().resolve("configs").resolve("bocso_it.conf")),
                "导入不应生成 .conf 配置文件");

        // 字段映射浏览 + 详情（含归属组与字段类型/长度）
        mvc.perform(get("/api/fieldmaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[?(@.report_id=='bocso_it')].ownership_group").value("bocs_dep"))
                .andExpect(jsonPath("$.reports[?(@.report_id=='bocso_it')].field_count").value(3));
        mvc.perform(get("/api/fieldmaps/get").param("report_id", "bocso_it"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].col_index").value(0))
                .andExpect(jsonPath("$.columns[0].field_name").value("客户号"))
                .andExpect(jsonPath("$.columns[0].field_format").value("CHAR"))
                .andExpect(jsonPath("$.columns[0].field_length").value("12"))
                .andExpect(jsonPath("$.columns[2].field_name").value("币种"))
                .andExpect(jsonPath("$.ownership_group").value("bocs_dep"));
    }

    @Test
    void importWithUnrecognizedCsvRejected() throws Exception {
        MockMultipartFile f = new MockMultipartFile("files", "unknown.csv", "text/csv",
                "foo,bar\n1,2\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/configs/import-structure").file(f))
                .andExpect(status().isBadRequest());
    }
}
