package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.store.DiscardField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 差异 CSV（整合格式）writer：一条差异记录一行；多差异栏位整合进「差异明细」单元格，
 * 关注栏位 [X-YYYY @N] 前缀、非关注 [栏位不一致 @N]；单方存在写存在侧主键、另一侧留空。
 */
class CsvExporterDiffRecTest {

    @TempDir Path dir;

    private static Map<Integer, DiscardField> monitor(Object... kv) {
        Map<Integer, DiscardField> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            int seq = (Integer) kv[i];
            m.put(seq, new DiscardField("N", seq, "F" + seq, "M", (Integer) kv[i + 1], null, 0));
        }
        return m;
    }

    private String render(RowDiff r, List<String> names,
                          Map<Integer, DiscardField> mon) throws Exception {
        Path f = dir.resolve("out_" + System.nanoTime() + ".csv");
        try (var w = CsvExporter.newBomWriter(f)) {
            CsvExporter.writeDiffRecHeader(w);
            CsvExporter.writeDiffRecRow(w, r, names, null, mon);
        }
        String text = Files.readString(f, StandardCharsets.UTF_8);
        Files.delete(f);
        return text.startsWith("\ufeff") ? text.substring(1) : text; // 去 BOM 便于断言
    }

    /** 多差异整合：一条记录一行，关注栏位带 [1-废弃字段 @2]、非关注 [栏位不一致 @4]，值 A:'..' B:'..'。 */
    @Test
    void diffRecordMergedWithMonitorPrefixes() throws Exception {
        RowDiff r = RowDiff.diff("k1", Status.SECTION_DATA,
                new String[]{"v0", "a2", "x", "a4"}, new String[]{"v0", "b2", "x", "b4"},
                new int[]{1, 3});
        String out = render(r, List.of("C1", "C2", "C3", "C4"), monitor(2, 1));
        String[] lines = out.split("\r\n");
        assertEquals(2, lines.length, "表头 + 一条整合记录");
        assertTrue(lines[0].startsWith("键值,状态,差异明细"));
        // 差异明细多行（引号内换行），两个前缀都出现、顺序按 diffCols；键值/状态列无特殊字符不加引号
        assertTrue(lines[1].startsWith("k1,差异,\"[1-废弃字段 @2]"));
        assertTrue(lines[1].contains("[1-废弃字段 @2] A:'a2' B:'b2'"));
        assertTrue(lines[1].contains("[栏位不一致 @4] A:'a4' B:'b4'"));
        assertTrue(lines[1].indexOf("[1-废弃字段") < lines[1].indexOf("[栏位不一致"));
    }

    /** 单方存在：存在侧主键写入、另一侧留空；键缺失回退首栏/整行。 */
    @Test
    void unmatchedWritesExistingSideKeyOnly() throws Exception {
        RowDiff onlyA = RowDiff.onlyA("key-9", Status.SECTION_DATA, new String[]{"k", "v"});
        String a = render(onlyA, List.of("C1", "C2"), null);
        assertTrue(a.contains("key-9,仅A存在,[仅A存在] A:'key-9' B:''"), a);

        RowDiff onlyB = RowDiff.onlyB("", Status.SECTION_DATA, new String[]{"B1", "B2"});
        String b = render(onlyB, List.of("C1", "C2"), null);
        assertTrue(b.contains("B1,仅B存在,[仅B存在] A:'' B:'B1'"), b);
    }

    /** 无监测配置 → 全部 [栏位不一致 @N]；完全匹配/尾部记录不产出。 */
    @Test
    void noMonitorPrefixesAndSkippedRows() throws Exception {
        RowDiff diff = RowDiff.diff("k", Status.SECTION_DATA,
                new String[]{"a"}, new String[]{"b"}, new int[]{0});
        String out = render(diff, List.of("C1"), null);
        assertTrue(out.contains("[栏位不一致 @1] A:'a' B:'b'"), out);

        // 完全匹配/尾部记录不产出（仅表头）
        assertEquals("键值,状态,差异明细\r\n",
                render(RowDiff.equal("k", Status.SECTION_DATA, new String[]{"a"}), List.of(), null));
        assertEquals("键值,状态,差异明细\r\n",
                render(RowDiff.equal("k", Status.SECTION_TRAILER, new String[]{"a"}), List.of(), null));
    }
}
