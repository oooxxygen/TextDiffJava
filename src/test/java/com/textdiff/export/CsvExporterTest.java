package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvExporterTest {

    @Test
    void fullExportAllRowsAllFields(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("full.csv");
        List<RowDiff> rows = List.of(
                RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "1", "x"}),
                RowDiff.diff("k2", Status.SECTION_DATA, new String[]{"k2", "2", "y"}, new String[]{"k2", "9", "y"}, new int[]{1}),
                RowDiff.onlyA("k3", Status.SECTION_DATA, new String[]{"k3", "3"}),
                RowDiff.onlyB("k4", Status.SECTION_DATA, new String[]{"k4", "4"}),
                RowDiff.diff("T1", Status.SECTION_TRAILER, new String[]{"T1", "10"}, new String[]{"T1", "20"}, new int[]{1}));
        CsvExporter.writeFull(f, rows, List.of("主键", "金额", "备注"));

        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        // 单栏位表头：字段名(1-based 列序号)，不再区分 A/B
        assertEquals("键值,状态,差异栏位,主键(1),金额(2),备注(3)", lines.get(0).replace("\ufeff", ""));
        // equal 1 行 + diff 2 行（A/B 分区各一条）+ 单方存在 2 行 + trailer diff 2 行 + 表头
        assertEquals(8, lines.size());
        assertTrue(lines.get(1).startsWith("k1,完全匹配,,k1,1,x"));
        assertTrue(lines.get(2).startsWith("k2,有差异·A,2-金额；,k2,2,y"));
        assertTrue(lines.get(3).startsWith("k2,有差异·B,2-金额；,k2,9,y"));
        // 单方存在行：单栏位取存在侧值
        assertTrue(lines.get(4).startsWith("k3,仅A存在,,k3,3,"));
        assertTrue(lines.get(5).startsWith("k4,仅B存在,,k4,4,"));
        // trailer diff 行也按 A/B 分区两条
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("T1,有差异·A,2-金额；,T1,10,")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("T1,有差异·B,2-金额；,T1,20,")));
    }

    /** 状态导出名与前端分区文案一致，A/B 侧用配置来源名（SRCA/SRCB）替换，缺省回退 A/B。 */
    @Test
    void 状态导出名按来源名替换() {
        assertEquals("完全匹配", CsvExporter.statusText("equal", "旧线", "新线"));
        assertEquals("有差异", CsvExporter.statusText("diff", "旧线", "新线"));
        assertEquals("仅旧线存在", CsvExporter.statusText("unmatched_a", "旧线", "新线"));
        assertEquals("仅新线存在", CsvExporter.statusText("unmatched_b", "旧线", "新线"));
        assertEquals("仅A存在", CsvExporter.statusText("unmatched_a", "", null));
        assertEquals("仅B存在", CsvExporter.statusText("unmatched_b", null, ""));
        assertEquals("未知状态", CsvExporter.statusText("未知状态", "A", "B"));
    }

    /** 全量表头无名回退：有名列出 字段名(序号)，无名回退 栏位N。 */
    @Test
    void 全量表头_无名栏位回退() {
        assertEquals("主键(1)", CsvExporter.colHeader(List.of("主键"), 0));
        assertEquals("F28(28)", CsvExporter.colHeader(
                java.util.stream.IntStream.rangeClosed(1, 28).mapToObj(i -> "F" + i).toList(), 27));
        assertEquals("栏位3", CsvExporter.colHeader(List.of("主键", ""), 2));
        assertEquals("栏位1", CsvExporter.colHeader(null, 0));
    }

    @Test
    void 差异栏位摘要_多列与无名栏位回退(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("full.csv");
        List<RowDiff> rows = List.of(RowDiff.diff("k1", Status.SECTION_DATA,
                new String[]{"a", "b", "c"}, new String[]{"a", "B", "C"}, new int[]{1, 2}));
        CsvExporter.writeFull(f, rows, List.of("主键", "金额"));
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        // diff → A/B 分区各一行，栏位取本侧原值；差异栏位仅列序号+栏位名（无名回退 栏位3）
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("k1,有差异·A,2-金额；3-栏位3；,a,b,c"), () -> lines.get(1));
        assertTrue(lines.get(2).startsWith("k1,有差异·B,2-金额；3-栏位3；,a,B,C"), () -> lines.get(2));
    }

    @Test
    void diffExportOneFieldPerRow(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("diff.csv");
        List<RowDiff> rows = List.of(
                RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "1"}),
                RowDiff.diff("k2", Status.SECTION_DATA, new String[]{"k2", "2", "a"}, new String[]{"k2", "9", "b"}, new int[]{1, 2}),
                RowDiff.onlyA("k3", Status.SECTION_DATA, new String[]{"k3", "3"}));
        CsvExporter.writeDiffFields(f, rows, List.of("主键", "金额", "备注"));

        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertEquals(1 + 2, lines.size()); // 仅 k2 的两个差异字段
        assertEquals("键值,栏位号,栏位名,A值,B值", lines.get(0).replace("\ufeff", ""));
        assertEquals("k2,2,金额,2,9", lines.get(1));
        assertEquals("k2,3,备注,a,b", lines.get(2));
    }

    @Test
    void rfc4180Escaping(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("esc.csv");
        List<RowDiff> rows = List.of(RowDiff.diff("k\"1\",x", Status.SECTION_DATA,
                new String[]{"a,b", "含\"引号\""}, new String[]{"换\n行", "z"}, new int[]{0, 1}));
        CsvExporter.writeDiffFields(f, rows, List.of());
        String content = Files.readString(f, StandardCharsets.UTF_8).replace("\ufeff", "");
        // 引号翻倍、含逗号/换行的字段整体加引号（RFC 4180）；栏位 0：A=a,b B=换行；栏位 1：A=引号 B=z
        assertTrue(content.contains("\"k\"\"1\"\",x\",1,栏位1,\"a,b\",\"换\n行\""));
        assertTrue(content.contains("\"k\"\"1\"\",x\",2,栏位2,\"含\"\"引号\"\"\",z"));
    }

    @Test
    void utf8BomPresent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("bom.csv");
        CsvExporter.writeFull(f, List.of(), List.of());
        byte[] head = Files.readAllBytes(f);
        assertTrue(head.length > 3);
        assertEquals((byte) 0xEF, head[0]);
        assertEquals((byte) 0xBB, head[1]);
        assertEquals((byte) 0xBF, head[2]);
    }

    // ---- 展示友好化（键分隔 / 长数字保真）----

    @Test
    void 组合主键显示为冒号分隔() {
        String key = "K1" + com.textdiff.engine.Rules.KEY_SEP + "K2" + com.textdiff.engine.Rules.KEY_SEP + "K3";
        assertEquals("K1:K2:K3", CsvExporter.displayKey(key));
        assertEquals("普通键", CsvExporter.displayKey("普通键"));
        assertEquals("", CsvExporter.displayKey(null));
    }

    @Test
    void 长数字按文本公式存储() {
        String digits30 = "123456789012345678901234567890"; // 30 位（28 位整数场景）
        assertEquals("=\"" + digits30 + "\"", CsvExporter.excelSafe(digits30)); // 单元格文本 ="30位数字"
        assertEquals("12345", CsvExporter.excelSafe("12345"));                              // 短数字原样
        assertEquals("A123456789012345678", CsvExporter.excelSafe("A123456789012345678"));  // 非纯数字原样
        assertEquals("", CsvExporter.excelSafe(""));
        assertNull(CsvExporter.excelSafe(null));
    }

    @Test
    void 导出中长数字与键分隔生效(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("diff.csv");
        String key = "A1" + com.textdiff.engine.Rules.KEY_SEP + "B2";
        List<RowDiff> rows = List.of(RowDiff.diff(key, Status.SECTION_DATA,
                new String[]{"A1", "123456789012345678901234567890"},
                new String[]{"A1", "999"}, new int[]{1}));
        CsvExporter.writeDiffFields(f, rows, List.of());
        String content = Files.readString(f, StandardCharsets.UTF_8).replace("\ufeff", "");
        String[] lines = content.split("\r\n");
        assertEquals(2, lines.length);
        assertTrue(lines[1].startsWith("A1:B2,"), () -> content);
        // 单元格文本 ="30位数字" 经 RFC4180 转义 → "=""123..."""
        assertTrue(content.contains("\"=\"\"123456789012345678901234567890\"\"\""));
    }
}
