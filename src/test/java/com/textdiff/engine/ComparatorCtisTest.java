package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** CTIS 作业端到端：compareFiles 携带动态栏位规格的虚拟子列展开。 */
class ComparatorCtisTest {

    private static Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static CompareConfig keyCfg() {
        CompareConfig c = new CompareConfig();
        c.keyColumns = new ArrayList<>(List.of(0));
        return c;
    }

    private static List<DynExpand.Spec> spec(int col) {
        Map<String, DynExpand.Item> items = new HashMap<>();
        items.put("1B", new DynExpand.Item("1B", "交易真实日期", "否", "X(8)", "右补空格", "YYYYMMDD"));
        items.put("1C", new DynExpand.Item("1C", "交易真实时间", "否", "X(8)", "", "HHMMSSHSS"));
        return List.of(new DynExpand.Spec(col, "107", "DYNAMIC_MESSAGE_01", items));
    }

    private static RowDiff find(List<RowDiff> rows, String key) {
        return rows.stream().filter(r -> r.key.equals(key) && Status.SECTION_DATA.equals(r.section))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + key));
    }

    @Test
    void 虚拟子列差异与汇总(@TempDir Path dir) throws Exception {
        Path a = write(dir, "a.txt", String.join("\n",
                "k1 | ~~1B20261018~~1C23053339",
                "k2 | ~~1B20261017~~1C09344973",
                "k3 | ~~1C23053339~~1B20261018",   // 与 k1 仅顺序不同 → 判等
                "k4 | ~~1B20260101~~1C00000001"));
        Path b = write(dir, "b.txt", String.join("\n",
                "k1 | ~~1B20261018~~1C23053339",   // 全等
                "k2 | ~~1B20261018~~1C09344973",   // 1B 子值差异
                "k3 | ~~1B20261018~~1C23053339",   // 等价（顺序无关）
                "k4 | ~~1B20260101"));              // 1C 单侧缺失

        ResultSink.ListSink sink = new ResultSink.ListSink();
        CompareOutcome out = Comparator.compareFiles(a, b, keyCfg(), sink,
                64L * 1024 * 1024, dir.resolve("tmp"), null, null, spec(1));
        Summary s = out.summary();

        assertEquals(2, s.equal);
        assertEquals(2, s.diff);

        RowDiff k2 = find(sink.rows, "k2");
        assertEquals(Status.DIFF, k2.status);
        assertEquals(1, k2.diffCols.length);
        assertTrue(k2.diffCols[0] >= DynExpand.VBASE, "虚拟子列索引");
        assertNotNull(k2.dyn);
        assertEquals(2, k2.dyn.size()); // 相等子项 1C 也随行携带供前端展示

        RowDiff k4 = find(sink.rows, "k4");
        assertEquals(Status.DIFF, k4.status);
        assertEquals(1, k4.diffCols.length);

        // 差异列频次：1B 一处、1C 一处（虚拟索引）
        assertEquals(2, s.diffColFreq.size());
        assertTrue(s.diffColFreq.keySet().stream().allMatch(c -> c >= DynExpand.VBASE));

        // 注册表：完整列名 + 悬停字段
        List<DynExpand.Desc> descs = s.dynCols;
        assertEquals(2, descs.size());
        DynExpand.Desc d1b = descs.stream().filter(d -> d.sub().equals("1B")).findFirst().orElseThrow();
        assertEquals("107-1B（交易真实日期）", d1b.name());
        assertEquals("DYNAMIC_MESSAGE_01", d1b.dynName());
    }

    @Test
    void only行携带单侧子值(@TempDir Path dir) throws Exception {
        Path a = write(dir, "a.txt", "k1 | ~~1B20261018");
        Path b = write(dir, "b.txt", "k2 | ~~1C23053339");
        ResultSink.ListSink sink = new ResultSink.ListSink();
        Comparator.compareFiles(a, b, keyCfg(), sink,
                64L * 1024 * 1024, dir.resolve("tmp"), null, null, spec(1));

        RowDiff oa = find(sink.rows, "k1");
        assertEquals(Status.UNMATCHED_A, oa.status);
        assertEquals(1, oa.dyn.size());
        assertEquals("20261018", oa.dyn.get(0).a);
        assertNull(oa.dyn.get(0).b);

        RowDiff ob = find(sink.rows, "k2");
        assertEquals(Status.UNMATCHED_B, ob.status);
        assertEquals(1, ob.dyn.size());
        assertNull(ob.dyn.get(0).a);
        assertEquals("23053339", ob.dyn.get(0).b);
    }

    @Test
    void 无规格时行为与legacy一致(@TempDir Path dir) throws Exception {
        Path a = write(dir, "a.txt", "k1 | ~~1B1");
        Path b = write(dir, "b.txt", "k1 | ~~1B2");
        ResultSink.ListSink sink = new ResultSink.ListSink();
        CompareOutcome out = Comparator.compareFiles(a, b, keyCfg(), sink,
                64L * 1024 * 1024, dir.resolve("tmp"), null, null, null);
        RowDiff r = find(sink.rows, "k1");
        assertEquals(Status.DIFF, r.status);
        assertArrayEquals(new int[]{1}, r.diffCols);
        assertNull(r.dyn);
        assertNull(out.summary().dynCols);
    }
}
