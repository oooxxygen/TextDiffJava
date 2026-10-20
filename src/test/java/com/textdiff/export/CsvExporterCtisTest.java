package com.textdiff.export;

import com.textdiff.engine.DynExpand;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.store.JobMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CTIS 虚拟子列的导出命名：栏位号=107-1B、栏位名=107-1B（数据项名称）、值取自行级子值。 */
class CsvExporterCtisTest {

    private static JobMeta.DynCol dyn(int index, String parentLabel, String sub, String name) {
        JobMeta.DynCol d = new JobMeta.DynCol();
        d.index = index;
        d.parentCol = 1;
        d.parentLabel = parentLabel;
        d.sub = sub;
        d.name = name;
        d.dynName = "DYNAMIC_MESSAGE_01";
        d.itemName = "交易真实日期";
        return d;
    }

    @Test
    void 差异CSV_虚拟子列命名与取值(@TempDir Path dir) throws Exception {
        int idx1b = DynExpand.VBASE + 1;
        int idx1c = DynExpand.VBASE + 2;
        List<JobMeta.DynCol> dynCols = List.of(
                dyn(idx1b, "107", "1B", "107-1B（交易真实日期）"),
                dyn(idx1c, "107", "1C", "107-1C（交易真实时间）"));
        RowDiff r = new RowDiff("k1", Status.DIFF, com.textdiff.engine.Status.SECTION_DATA,
                new String[]{"k1", "~~1B20261018", "x"},
                new String[]{"k1", "~~1B20261017", "x"},
                new int[]{2, idx1b},
                null, null,
                List.of(newVal(idx1b, "20261018", "20261017")));

        Path f = dir.resolve("diff.csv");
        try (BufferedWriter w = CsvExporter.newBomWriter(f)) {
            CsvExporter.writeDiffHeader(w);
            CsvExporter.writeDiffRow(w, r, List.of(), dynCols);
        }
        List<String> lines = stripBom(f);
        // 表头 + 真实列 3 一行 + 虚拟子列 1B 一行
        assertEquals(3, lines.size());
        assertEquals("键值,栏位号,栏位名,A值,B值", lines.get(0));
        assertTrue(lines.get(1).startsWith("k1,3,"));
        String sub = lines.get(2);
        assertEquals("k1,107-1B,107-1B（交易真实日期）,20261018,20261017", sub);
    }

    @Test
    void 全量CSV_表头与单行追加虚拟子列(@TempDir Path dir) throws Exception {
        int idx1b = DynExpand.VBASE + 1;
        List<JobMeta.DynCol> dynCols = List.of(dyn(idx1b, "107", "1B", "107-1B（交易真实日期）"));
        Path f = dir.resolve("full.csv");
        try (BufferedWriter w = CsvExporter.newBomWriter(f)) {
            CsvExporter.writeFullHeader(w, List.of("键", "动态"), 2, dynCols);
            RowDiff r = new RowDiff("k1", Status.DIFF, com.textdiff.engine.Status.SECTION_DATA,
                    new String[]{"k1", "~~1B1"},
                    new String[]{"k1", "~~1B2"},
                    new int[]{idx1b},
                    null, null,
                    List.of(newVal(idx1b, "1", "2")));
            CsvExporter.writeFullRow(w, r, List.of("键", "动态"), 2, dynCols);
        }
        List<String> lines = stripBom(f);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith(",107-1B（交易真实日期）(A),107-1B（交易真实日期）(B)"));
        assertTrue(lines.get(1).endsWith(",1,2"));
    }

    @Test
    void 差异栏位摘要_虚拟子列用完整列名(@TempDir Path dir) {
        int idx1b = DynExpand.VBASE + 1;
        RowDiff r = new RowDiff("k1", Status.DIFF, com.textdiff.engine.Status.SECTION_DATA,
                new String[]{"k1"}, new String[]{"k1"}, new int[]{0, idx1b}, null, null,
                List.of(newVal(idx1b, "a", "b")));
        String label = CsvExporter.diffLabel(r, List.of("名"), List.of(dyn(idx1b, "107", "1B", "107-1B（交易真实日期）")));
        assertEquals("1-名；107-1B（交易真实日期）；", label);
    }

    private static DynExpand.Val newVal(int idx, String a, String b) {
        DynExpand.Val v = new DynExpand.Val();
        v.idx = idx;
        v.c = 1;
        v.k = "1B";
        v.a = a;
        v.b = b;
        return v;
    }

    private static List<String> stripBom(Path f) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first != null && !first.isEmpty() && first.charAt(0) == '\ufeff') first = first.substring(1);
            java.util.List<String> out = new java.util.ArrayList<>();
            if (first != null) out.add(first);
            String line;
            while ((line = r.readLine()) != null) out.add(line);
            return out;
        }
    }
}
