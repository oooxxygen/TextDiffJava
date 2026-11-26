package com.textdiff.custom;

import com.textdiff.custom.SegmentParser.Parsed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 段内场次块排序（sceneSort）：模式① 行区间 / 模式② 正则取键、稳定排序语义、
 * 非法配置校验（CustomFormat 层）、SegmentParser 端到端重排后两侧段文本可比。
 */
class SceneSorterTest {

    // ---- 测试数据：同一段，两种 :61: 场次先后 ----
    // 行区间键 "1,7~14"：20241231 < 20250101，故 SORTED_RANGE = 20241231 块在前；
    // 正则键 "/[0-9]{9}/"（续行取键）：000012642 < 000099999，故按键序 = ORIGINAL（20250101 块在前）。

    /** 头行 + 两个 :61: 块（20250101 块在前）+ 尾行 -}（匹配结束式，固定段尾）。 */
    private static final List<String> ORIGINAL = List.of(
            "{4:",
            ":20:REF001",
            ":61:20250101DEBIT100,",
            "/000012642/INV1",
            ":61:20241231CREDIT50,",
            "/000099999/INV2",
            "-}");

    /** 同段但两块乱序（20241231 块在前 = 行区间键的升序）。 */
    private static final List<String> SORTED_RANGE = List.of(
            "{4:",
            ":20:REF001",
            ":61:20241231CREDIT50,",
            "/000099999/INV2",
            ":61:20250101DEBIT100,",
            "/000012642/INV1",
            "-}");

    /** 段结束式（生产路径：SegmentParser 把 compiledEnd 传给排序器）。 */
    private static final Pattern END = Pattern.compile("^-\\}");

    private static List<String> reorder(List<String> seg, String spec) {
        return SceneSorter.reorder(seg, SceneSorter.compile(spec), END);
    }

    // ---- 模式①：行号+字符区间 ----

    @Test
    void 行区间_乱序块按键重排_头尾行不动() {
        List<String> out = reorder(ORIGINAL, "1,7~14");
        assertEquals(SORTED_RANGE, out);
        // 已按键序的输入重排后不变
        assertEquals(SORTED_RANGE, reorder(SORTED_RANGE, "1,7~14"));
    }

    @Test
    void 行区间_起止分隔符_横线与波浪等价() {
        assertEquals(SORTED_RANGE, reorder(ORIGINAL, "1,7-14"));
    }

    @Test
    void 行区间_越界截到行尾_整行越界无键() {
        // 止 99 越出 → 截到行尾，键仍可取
        assertEquals(SORTED_RANGE, reorder(ORIGINAL, "1,7~99"));
        // 行号超出块行数（块仅 2 行）：两组均无键 → 稳定保序
        assertEquals(ORIGINAL, reorder(ORIGINAL, "5,1~2"));
    }

    @Test
    void 多组条件_依次拼接比较() {
        // 两块第一键相同（第 1 行 7~14 字符 = 20250101），第二键取续行 3~6 字符：
        // "/000099999/X" → "0009"，"/000012642/X" → "0001" → /000012642 块在前
        List<String> sorted = List.of("{4:", ":61:20250101DEBIT,", "/000012642/X");
        List<String> shuffled = List.of("{4:", ":61:20250101DEBIT,", "/000099999/X",
                ":61:20250101DEBIT,", "/000012642/X");
        List<String> out = SceneSorter.reorder(shuffled, "1,7~14;2,3~6");
        assertEquals(sorted, out.subList(0, sorted.size()));
    }

    // ---- 模式②：正则取键 ----

    @Test
    void 正则_块内顺序首命中取键() {
        // 首行无 9 位连续数字（:61: 行仅 8 位日期），键取自续行 /000012642/、/000099999/
        assertEquals(ORIGINAL, reorder(SORTED_RANGE, "/[0-9]{9}/"));
        assertEquals(ORIGINAL, reorder(ORIGINAL, "/[0-9]{9}/"));
    }

    @Test
    void 正则_无命中即无键_保持原位() {
        List<String> out = reorder(ORIGINAL, "/ZZZZ/");
        assertEquals(ORIGINAL, out);
    }

    // ---- 稳定排序语义 ----

    @Test
    void 无键块保持原位_有键块正常重排() {
        // 键取第 2 行 2~10 字符（/000099999/ → 000099999 等）：
        // 000012642 < 000077777 < 000099999 → B, C, A
        List<String> seg = List.of(
                ":61:20250101A,",
                "/000099999/",
                ":61:20241231B,",
                "/000012642/",
                ":61:20240601C,",
                "/000077777/");
        List<String> out = SceneSorter.reorder(seg, "2,2~10");
        assertEquals(List.of(
                ":61:20241231B,", "/000012642/",
                ":61:20240601C,", "/000077777/",
                ":61:20250101A,", "/000099999/"), out);
    }

    @Test
    void 标签不同的块不互相重排() {
        List<String> seg = List.of(
                ":61:20250101A,",
                ":62:20241231B,");
        // :62 块键更小，但标签不同 → 不交换
        List<String> out = SceneSorter.reorder(seg, "1,2~10");
        assertEquals(seg, out);
    }

    @Test
    void 混合_无键块稳定夹在原位() {
        // 首块无键（正则不命中），其余两块按键重排，无键块相对顺序不变
        List<String> seg = List.of(
                ":61:ZZZZZ,",          // 无键（/[0-9]{9}/ 不命中）
                ":61:20250101A,",
                "/000099999/",
                ":61:20241231B,",
                "/000012642/");
        List<String> out = SceneSorter.reorder(seg, "/[0-9]{9}/");
        // 000012642 < 000099999 → A/B 两块交换，无键首块保持最前
        assertEquals(List.of(
                ":61:ZZZZZ,",
                ":61:20241231B,", "/000012642/",
                ":61:20250101A,", "/000099999/"), out);
    }

    // ---- 空配置 / 无场次块 ----

    @Test
    void 无结束式信息_尾行随最后一块() {
        // 两参 reorder 不带段结束式：尾部散行归入最后一块，随其重排（建议用 sceneSort 时配置结束式）
        List<String> out = SceneSorter.reorder(ORIGINAL, "1,7~14");
        assertEquals(List.of(
                "{4:", ":20:REF001",
                ":61:20241231CREDIT50,", "/000099999/INV2", "-}",
                ":61:20250101DEBIT100,", "/000012642/INV1"), out);
    }

    @Test
    void spec空白_原样返回同实例() {
        assertSame(ORIGINAL, SceneSorter.reorder(ORIGINAL, null));
        assertSame(ORIGINAL, SceneSorter.reorder(ORIGINAL, "   "));
        assertSame(ORIGINAL, SceneSorter.reorder(ORIGINAL, ""));
    }

    @Test
    void 无场次块_原样返回() {
        List<String> seg = List.of("{4:", ":20:REF", "-}");
        assertEquals(seg, SceneSorter.reorder(seg, "1,1~2"));
        assertEquals(seg, SceneSorter.reorder(seg, "/[0-9]{9}/"));
    }

    // ---- 非法配置（CustomFormat 校验层，Controller 400） ----

    @Test
    void 非法spec_抛IAE() {
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "1,36~21")); // 起>止
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "0,1~2"));  // 行号<1
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "1,0~2"));  // 起<1
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "1,7~"));   // 缺止
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "1,7~14;")); // 空条件
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "abc"));    // 非法组
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "[0-9]{"));  // 未包裹
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("^\\{1:", null, null, "/[0-9{/")); // 不可编译
    }

    @Test
    void 合法spec通过校验_sceneSort规范化() {
        CustomFormat f = CustomFormat.of("^\\{1:", null, null, " 1,7~14 ");
        assertEquals("1,7~14", f.sceneSort());
        assertEquals("", CustomFormat.of("^\\{1:", null, null, "   ").sceneSort());
        assertNotNull(CustomFormat.of("^\\{1:", null, null, "/[0-9]{9}/").compiledSceneSort());
        assertNull(CustomFormat.of("^\\{1:", null, null, null).compiledSceneSort());
    }

    // ---- configLine 兼容 ----

    @Test
    void sceneSort编解码_旧configLine兼容() {
        CustomFormat f = CustomFormat.of("^\\{1:", "^-\\}", ":20:(\\S+)", "1,7~14;/[0-9]{9}/");
        CustomFormat g = CustomFormat.fromConfigLine(f.toConfigLine());
        assertEquals(f, g);
        // 旧 configLine（无 scene_sort）→ sceneSort 为空
        CustomFormat old = CustomFormat.fromConfigLine(
                CustomFormat.of("^\\{1:", "^-\\}", ":20:(\\S+)").toConfigLine());
        assertEquals("", old.sceneSort());
        assertNull(old.compiledSceneSort());
    }

    // ---- 端到端：SegmentParser 重排后两侧段文本可比 ----

    @Test
    void 端到端_两侧乱序场次_重排后段文本一致() {
        CustomFormat cfg = CustomFormat.of("^\\{1:", "^-\\}", ":20:(\\S+)", "1,7~14");
        List<String> docA = List.of(
                "{1:DOC1",
                "{4:",
                ":20:REF001",
                ":61:20250101DEBIT100,",
                "/000012642/INV1",
                ":61:20241231CREDIT50,",
                "/000099999/INV2",
                "-}",
                "{1:DOC2",
                "{4:",
                ":20:REF002",
                ":61:20241231CREDIT50,",
                "/000099999/INV2",
                ":61:20250101DEBIT100,",
                "/000012642/INV1",
                "-}");
        // docB：两段的 :61: 块先后与 docA 相反
        List<String> docB = List.of(
                "{1:DOC1",
                "{4:",
                ":20:REF001",
                ":61:20241231CREDIT50,",
                "/000099999/INV2",
                ":61:20250101DEBIT100,",
                "/000012642/INV1",
                "-}",
                "{1:DOC2",
                "{4:",
                ":20:REF002",
                ":61:20250101DEBIT100,",
                "/000012642/INV1",
                ":61:20241231CREDIT50,",
                "/000099999/INV2",
                "-}");
        Parsed pa = SegmentParser.parse(docA, cfg);
        Parsed pb = SegmentParser.parse(docB, cfg);
        assertEquals(2, pa.segments().size());
        assertEquals(pb.segments().size(), pa.segments().size());
        for (int i = 0; i < pa.segments().size(); i++) {
            assertEquals(pa.segments().get(i).key(), pb.segments().get(i).key());
            assertEquals(pa.segments().get(i).text(), pb.segments().get(i).text());
        }
        // startLineNo 仍为原段首行号（1-based）
        assertEquals(1, pa.segments().get(0).startLineNo());
        assertEquals(9, pa.segments().get(1).startLineNo());
        // 不配置 sceneSort 时保持原序（不重排）
        Parsed raw = SegmentParser.parse(docA, CustomFormat.of("^\\{1:", "^-\\}", ":20:(\\S+)"));
        assertEquals(List.of(":61:20250101DEBIT100,", "/000012642/INV1"),
                raw.segments().get(0).lines().subList(3, 5));
    }
}
