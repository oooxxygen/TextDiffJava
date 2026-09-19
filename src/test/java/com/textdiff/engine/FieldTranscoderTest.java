package com.textdiff.engine;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合编码字段转码：EBCDIC 文件内嵌 UTF-16 栏位的还原、单侧兼容（对侧正常文本原样保留）、
 * 非目标编码值探测回退、以及端到端对比（A 侧乱码转码后与 B 侧可读文本判等）。
 */
class FieldTranscoderTest {

    private static final Charset CP037 = Charset.forName("cp037");

    /** 转码器基本行为：cp037 + UTF-16 列启用；其他文件编码不启用。 */
    @Test
    void 启用条件() {
        var t = new FieldTranscoder("cp037", Map.of(1, "UTF-16"));
        assertTrue(t.enabled());
        assertFalse(new FieldTranscoder("utf-8", Map.of(1, "UTF-16")).enabled());
        assertFalse(new FieldTranscoder("cp037", Map.of()).enabled());
        assertFalse(new FieldTranscoder("cp037", null).enabled());
    }

    /** UTF-16BE 内嵌值还原为可读文本；定长补位（UTF-16 空格）去除。 */
    @Test
    void 内嵌UTF16还原() {
        var t = new FieldTranscoder("cp037", Map.of(1, "UTF-16"));
        String padded = "海外产品" + "  "; // UTF-16 空格补位
        byte[] raw = padded.getBytes(StandardCharsets.UTF_16BE);
        String embedded = new String(raw, CP037); // 主机解码后的乱码形态
        String[] cols = {"K1", embedded, "NORMAL"};
        String[] out = t.apply(cols);
        assertEquals("海外产品", out[1]);
        assertEquals("NORMAL", out[2]);
    }

    /** 非 UTF-16 的值（正常 EBCDIC 文本）解码出控制/替换字符或失败时原样保留。 */
    @Test
    void 非目标编码原样保留() {
        var t = new FieldTranscoder("cp037", Map.of(0, "UTF-16"));
        String[] cols = {"PLAINTEXT", "X"};
        String[] out = t.apply(cols);
        assertEquals("PLAINTEXT", out[0]); // ASCII 文本经回转+UTF-16 解码不会洁净，保留原值
    }

    /** 端到端：A 侧 cp037 文件内嵌 UTF-16 栏位，B 侧 UTF-8 可读文本，转码后按主键判等。 */
    @Test
    void 单侧混合编码对比判等() throws Exception {
        Path dir = Files.createTempDirectory("transcode");
        String delim = " | ";
        // A 侧：cp037 字节流，第 2 列为 UTF-16BE 原始字节（含 UTF-16 空格补位）
        Path fa = dir.resolve("VSNA_A.dat");
        byte[] sp = delim.getBytes(CP037);
        byte[] nl = "\n".getBytes(CP037);
        var a = new java.io.ByteArrayOutputStream();
        for (int i = 1; i <= 3; i++) {
            a.write(("K" + i).getBytes(CP037));
            a.write(sp);
            a.write(("值" + i).getBytes(StandardCharsets.UTF_16BE));
            a.write(sp);
            a.write(("N" + i).getBytes(CP037));
            a.write(nl);
        }
        Files.write(fa, a.toByteArray());
        // B 侧：UTF-8 可读文本，第 2 列已是正常文本（单侧问题场景）
        Path fb = dir.resolve("VSNA_B.dat");
        var b = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            b.append("K").append(i).append(delim).append("值").append(i).append(delim).append("N").append(i).append("\n");
        }
        Files.writeString(fb, b.toString(), StandardCharsets.UTF_8);

        var cfg = new CompareConfig();
        cfg.delimiter = delim;
        cfg.keyColumns = new java.util.ArrayList<>(java.util.List.of(0));

        var transA = new FieldTranscoder("cp037", Map.of(1, "UTF-16"));
        var transB = new FieldTranscoder("utf-8", Map.of(1, "UTF-16")); // 非 EBCDIC 文件 → 不启用

        var sink = com.textdiff.store.ResultFiles.JsonlSink.create(dir.resolve("r.jsonl"));
        CompareOutcome outcome;
        try (sink) {
            outcome = Comparator.compareFiles(fa, fb, cfg, sink, 1 << 20, dir, transA, transB);
        }
        assertEquals(3, outcome.summary().equal, "A 侧 UTF-16 乱码转码后应与 B 侧可读文本判等");
        assertEquals(0, outcome.summary().diff);
        assertEquals(0, outcome.summary().onlyA);
        assertEquals(0, outcome.summary().onlyB);
    }

    // ---- Excel 解析 ----

    /** 数据表结构 Excel 解析：表头列名定位、E 码剔除、1-based 序号转 0-based、多 sheet 累积。 */
    @Test
    void 表结构Excel解析() throws Exception {
        var wb = new XSSFWorkbook();
        String[] header = {"数据表英文名", "数据表中文名", "表内字段序号", "BOCS字段内部存储英文名",
                "数据项中文名", "", "", "BOCS转换标志", "BOCS字段内部存储字符集", "数据类型", "", "数据最大长度"};
        for (String sheetName : new String[]{"BOCS-O", "BOCS-O-C64"}) {
            Sheet sh = wb.createSheet(sheetName);
            Row h = sh.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            Object[][] rows = {
                    {"VSNA", "客户表", 1, "F1", "", "", "", "不转换", "E", "CHAR", "", "10"},
                    {"VSNA", "客户表", 2, "F2", "地址", "", "", "不转换", "UTF-16", "CHAR", "", "70"},
                    {"VSNA", "客户表", 3, "F3", "备注", "", "", "不转换", "UTF-16", "CHAR", "", "200"},
            };
            if ("BOCS-O-C64".equals(sheetName)) rows = new Object[][]{
                    {"ETQD0101", "问卷表", 8, "ID", "证件号", "", "", "", "UTF-16", "CHAR", "", "64"}};
            for (int i = 0; i < rows.length; i++) {
                Row r = sh.createRow(i + 1);
                for (int j = 0; j < rows[i].length; j++) {
                    if (rows[i][j] instanceof Integer n) r.createCell(j).setCellValue(n);
                    else r.createCell(j).setCellValue(String.valueOf(rows[i][j]));
                }
            }
        }
        var records = com.textdiff.controller.CharsetMapController.parse(wb, "结构.xlsx");
        assertEquals(3, records.size(), "两个 sheet 的非 E 码字段全部保留");
        var byTable = new java.util.LinkedHashMap<String, Map<Integer, String>>();
        for (var r : records) {
            byTable.computeIfAbsent(r.tableName(), k -> new java.util.LinkedHashMap<>())
                    .put(r.colIndex(), r.charset());
        }
        assertEquals(Map.of(1, "UTF-16", 2, "UTF-16"), byTable.get("VSNA"), "1-based 序号 → 0-based 列号");
        assertEquals(Map.of(7, "UTF-16"), byTable.get("ETQD0101"));
        wb.close();
    }
}
