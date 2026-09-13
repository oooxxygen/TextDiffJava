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
        assertTrue(lines.get(0).replace("\ufeff", "").startsWith("键值,状态,分区"));
        assertTrue(lines.get(0).contains("金额(A),金额(B)"));
        // 5 行数据 + 表头
        assertEquals(6, lines.size());
        // onlyA 行 B 侧留空，onlyB 行 A 侧留空（宽度 = 最宽行 3 列）
        assertTrue(lines.get(3).startsWith("k3,unmatched_a,data,k3,,3,,"));
        assertTrue(lines.get(4).startsWith("k4,unmatched_b,data,,k4,,4,"));
        // trailer 行也在全量导出中
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("T1,diff,trailer")));
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
}
