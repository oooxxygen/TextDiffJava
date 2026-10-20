package com.textdiff.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** CTIS 动态栏位 '~~' 分段解析与虚拟子列展开规则。 */
class DynExpandTest {

    @Test
    void 解析_编号与内容按段首2位切分() {
        LinkedHashMap<String, String> m = DynExpand.parse("~~1B20261018~~1C23053339");
        assertEquals(2, m.size());
        assertEquals("20261018", m.get("1B"));
        assertEquals("23053339", m.get("1C"));
    }

    @Test
    void 解析_首个分段是静态前缀不取编号() {
        // '~~' 之前的内容是静态前缀（如 S + 空格补位），不构成编号 → 自然编号 1
        LinkedHashMap<String, String> m = DynExpand.parse("S   xx~~1B20261017~~1C09344973");
        assertEquals(3, m.size());
        assertEquals("S   xx", m.get("#1"));
        assertEquals("20261017", m.get("1B"));
        assertEquals("09344973", m.get("1C"));
    }

    @Test
    void 解析_首段两字母数字也不当编号() {
        // 无 '~~' 的普通值整体按自然编号（拼接组成规则：编号必在 '~~' 之后）
        LinkedHashMap<String, String> m = DynExpand.parse("AB123");
        assertEquals(1, m.size());
        assertEquals("AB123", m.get("#1"));
    }

    @Test
    void 解析_首尾定界空段丢弃() {
        LinkedHashMap<String, String> m = DynExpand.parse("~~1B1~~1C2~~");
        assertEquals(2, m.size());
        assertEquals("1", m.get("1B"));
        assertEquals("2", m.get("1C"));
    }

    @Test
    void 解析_编号不足两位或含非字母数字则自然编号() {
        LinkedHashMap<String, String> m = DynExpand.parse("~~A1~~1 x~~n1v");
        // "A1" 是 2 位字母数字 → 编号（内容空）；"1 x" 第二位空格 → 自然；"n1v" → n1/v
        assertEquals("", m.get("A1"));
        assertEquals("1 x", m.get("#1"));
        assertEquals("v", m.get("n1"));
    }

    @Test
    void 解析_同编号重复出现按出现顺序配对() {
        LinkedHashMap<String, String> m = DynExpand.parse("~~1Ba~~1Bb~~1Cc");
        assertEquals("a", m.get("1B"));
        assertEquals("b", m.get("1B*2"));
        assertEquals("c", m.get("1C"));
    }

    @Test
    void 解析_不去前后空格补位参与比对() {
        LinkedHashMap<String, String> m = DynExpand.parse("~~1B20261018    ");
        assertEquals("20261018    ", m.get("1B"));
    }

    @Test
    void 解析_空值无分段() {
        assertTrue(DynExpand.parse(null).isEmpty());
        assertTrue(DynExpand.parse("").isEmpty());
    }

    private static DynExpand oneDyn(int col, Map<String, DynExpand.Item> items) {
        return DynExpand.of(List.of(new DynExpand.Spec(col, "107", "DYNAMIC_MESSAGE_01", items)));
    }

    @Test
    void 展开_编号配对顺序无关判等() {
        DynExpand d = oneDyn(1, Map.of());
        String[] a = {"k", "~~1B20261018~~1C23053339"};
        String[] b = {"k", "~~1C23053339~~1B20261018"};
        DynExpand.RowResult r = d.compareRow(a, b, Set.of());
        assertEquals(0, r.diffCols().length);
        assertEquals(2, r.vals().size());
    }

    @Test
    void 展开_子值差异进虚拟列() {
        DynExpand d = oneDyn(1, Map.of());
        DynExpand.RowResult r = d.compareRow(
                new String[]{"k", "~~1B20261018~~1C1"},
                new String[]{"k", "~~1B20261017~~1C1"}, Set.of());
        assertEquals(1, r.diffCols().length);
        assertTrue(r.diffCols()[0] >= DynExpand.VBASE);
        assertEquals(2, d.descs().size()); // 相等子列同样注册（前端展示全子项）
        DynExpand.Desc d1b = d.descs().stream().filter(x -> x.sub().equals("1B")).findFirst().orElseThrow();
        assertEquals("107-1B", d1b.label());
        assertNull(d1b.itemName()); // 未配置元数据 → 无数据项名称
    }

    @Test
    void 展开_单侧缺失编号判差异() {
        DynExpand d = oneDyn(1, Map.of());
        DynExpand.RowResult r = d.compareRow(
                new String[]{"k", "~~1B1~~1C2"},
                new String[]{"k", "~~1B1"}, Set.of());
        assertEquals(1, r.diffCols().length);
        DynExpand.Val v = r.vals().stream().filter(x -> "1C".equals(x.k)).findFirst().orElseThrow();
        assertEquals("2", v.a);
        assertNull(v.b);
    }

    @Test
    void 展开_元数据命名悬停字段() {
        DynExpand d = oneDyn(1, Map.of("1B",
                new DynExpand.Item("1B", "交易真实日期", "否", "X(8)", "右补空格", "YYYYMMDD")));
        d.compareRow(new String[]{"k", "~~1B20261018"}, new String[]{"k", "~~1B20261018"}, Set.of());
        DynExpand.Desc desc = d.descs().get(0);
        assertEquals("107-1B（交易真实日期）", desc.name());
        assertEquals("DYNAMIC_MESSAGE_01", desc.dynName());
        assertEquals("交易真实日期", desc.itemName());
    }

    @Test
    void 展开_自然编号子列命名为107序号() {
        DynExpand d = oneDyn(1, Map.of());
        d.compareRow(new String[]{"k", "S xx~~1B1"},
                new String[]{"k", "S yy~~1B1"}, Set.of());
        // 两个差异子列：自然段 #1 与编号 1B
        assertEquals(2, d.descs().size());
        assertEquals("107-1", d.descs().stream().filter(x -> x.sub().equals("1")).findFirst().orElseThrow().name());
    }

    @Test
    void 展开_父栏位被skip时不展开() {
        DynExpand d = oneDyn(1, Map.of());
        DynExpand.RowResult r = d.compareRow(
                new String[]{"k", "~~1B1"},
                new String[]{"k", "~~1B2"}, Set.of(1));
        assertEquals(0, r.diffCols().length);
        assertTrue(r.vals().isEmpty());
    }

    @Test
    void 展开_父栏位不参与真实列比对_其余列照常() {
        DynExpand d = oneDyn(1, Map.of());
        DynExpand.RowResult r = d.compareRow(
                new String[]{"k", "~~1B1", "same"},
                new String[]{"k", "~~1B1", "DIFF"}, Set.of());
        assertEquals(1, r.diffCols().length);
        assertEquals(2, r.diffCols()[0]); // 真实列 2 差异；父栏位 1 不再整列比对
    }

    @Test
    void 展开_单侧行子值只带存在侧() {
        DynExpand d = oneDyn(1, Map.of());
        List<DynExpand.Val> vals = d.expandSingle(new String[]{"k", "~~1B1~~1C2"}, Set.of(), true);
        assertEquals(2, vals.size());
        assertTrue(vals.stream().allMatch(v -> v.a != null && v.b == null));
    }

    @Test
    void 空规格返回null走legacy路径() {
        assertNull(DynExpand.of(null));
        assertNull(DynExpand.of(List.of()));
    }
}
