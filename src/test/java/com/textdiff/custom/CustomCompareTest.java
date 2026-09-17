package com.textdiff.custom;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.custom.SegmentParser.Parsed;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 自定义格式对比：段起止/主键正则的段解析与匹配。
 * 真实样本 MT950FILE.txt（MT950 报文：起始 {@code \{1:}、结束 {@code ^-\}}、主键 {@code :20:(\S+)}）。
 */
class CustomCompareTest {

    private static final Path SAMPLES = Path.of("O:/CodeRepos/NonStructuralData");

    private static final CustomFormat MT950 = CustomFormat.of("^\\{1:", "^-\\}", ":20:(\\S+)");

    private static List<String> lines(String name) throws Exception {
        return Files.readAllLines(SAMPLES.resolve(name), StandardCharsets.UTF_8);
    }

    // ---- 段解析 ----

    @Test
    void 格式配置编解码与校验() {
        CustomFormat f = CustomFormat.of(" ^\\{1: ", "", ":20:(\\S+)");
        CustomFormat g = CustomFormat.fromConfigLine(f.toConfigLine());
        assertEquals("^\\{1:", g.startPattern());
        assertEquals("", g.endPattern());
        assertEquals(":20:(\\S+)", g.keyPattern());
        assertThrows(IllegalArgumentException.class, () -> CustomFormat.of("[", null, null));
        assertNull(CustomFormat.fromConfigLine(null));
        assertNull(CustomFormat.fromConfigLine("not json"));
    }

    @Test
    void 合成段解析_起止式与主键() {
        List<String> txt = List.of(
                "HEAD INFO",
                "<REC ID=A1>",
                "name=alice",
                "</REC>",
                "<REC ID=B2>",
                "name=bob",
                "</REC>");
        CustomFormat f = CustomFormat.of("^<REC", "^</REC>", "ID=(\\w+)");
        Parsed p = SegmentParser.parse(txt, f);
        assertEquals(2, p.segments().size());
        assertEquals("A1", p.segments().get(0).key());
        assertEquals("B2", p.segments().get(1).key());
        assertEquals(List.of("HEAD INFO"), p.head());
        assertTrue(p.tail().isEmpty());
        assertEquals("<REC ID=A1>", p.segments().get(0).lines().get(0));
        assertEquals("</REC>", p.segments().get(0).lines().get(2));
        assertEquals(0, p.keyMiss());
    }

    @Test
    void 无结束式_段到下一段起始前() {
        List<String> txt = List.of(
                "##A", "x=1", "y=2", "##B", "x=3", "##C");
        CustomFormat f = CustomFormat.of("^##", "", "^##(\\w)");
        Parsed p = SegmentParser.parse(txt, f);
        assertEquals(3, p.segments().size());
        assertEquals(List.of("##A", "x=1", "y=2"), p.segments().get(0).lines()); // 段含起始行
        assertEquals(List.of("##B", "x=3"), p.segments().get(1).lines());
        assertEquals(List.of("##C"), p.segments().get(2).lines()); // 末段 = 起始行到文件尾
    }

    @Test
    void 主键未命中_计入keyMiss() {
        List<String> txt = List.of("<REC>", "no id here", "</REC>");
        CustomFormat f = CustomFormat.of("^<REC", "^</REC>", "ID=(\\w+)");
        Parsed p = SegmentParser.parse(txt, f);
        assertEquals(1, p.segments().size());
        assertNull(p.segments().get(0).key());
        assertEquals(1, p.keyMiss());
    }

    @Test
    void 结束式缺失_按文件尾截断并告警() {
        List<String> txt = List.of("<REC ID=X>", "a=1", "b=2");
        CustomFormat f = CustomFormat.of("^<REC", "^</REC>", "ID=(\\w+)");
        Parsed p = SegmentParser.parse(txt, f);
        assertEquals(1, p.segments().size());
        assertEquals(3, p.segments().get(0).lines().size());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("未匹配到结束式")));
    }

    // ---- MT950 真实样本 ----

    @Test
    void MT950样本_3段带主键() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        Parsed p = SegmentParser.parse(lines("MT950FILE.txt"), MT950);
        assertEquals(3, p.segments().size());
        assertEquals("SM26080100000001", p.segments().get(0).key());
        assertEquals("SM26080100000002", p.segments().get(1).key());
        assertEquals("SM26080100000003", p.segments().get(2).key());
        assertTrue(p.head().isEmpty());
        assertTrue(p.tail().isEmpty());
        assertEquals(0, p.keyMiss());
        // 段首行 = {1:...，段含 4: 块体与 -} 结束行
        assertTrue(p.segments().get(0).lines().get(0).startsWith("{1:"));
        assertEquals("-}", p.segments().get(0).lines().get(p.segments().get(0).lines().size() - 1));
    }

    @Test
    void MT950对比_全等_乱序_差异_单侧() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        List<String> a = lines("MT950FILE.txt");

        // 1) B = A 原样 → 全部等
        CustomCompareEngine.Result r0 = CustomCompareEngine.compare(a, a, MT950);
        assertEquals(3, r0.summary().equal);
        assertEquals(0, r0.summary().diff);
        assertEquals(0, r0.summary().onlyA);
        assertEquals(0, r0.summary().onlyB);

        // 2) B = 段序对调 + 段2 金额行改动 → 乱序仍配对；段2 diff
        List<String> b = new java.util.ArrayList<>();
        b.addAll(segment(a, 2));
        b.addAll(mutated(segment(a, 1), ":61:", "NMSCPP1005686353", "NMSCPP9999999999"));
        b.addAll(segment(a, 0));
        CustomCompareEngine.Result r1 = CustomCompareEngine.compare(a, b, MT950);
        assertEquals(3, r1.summary().segmentsB);
        assertEquals(2, r1.summary().equal);
        assertEquals(1, r1.summary().diff);
        assertEquals(0, r1.summary().onlyA);
        assertEquals(0, r1.summary().onlyB);
        // 差异段保持原始行展示，差异定位在改动的行
        RowDiff d = r1.rows().stream().filter(x -> Status.DIFF.equals(x.status)).findFirst().orElseThrow();
        assertEquals("SM26080100000002", d.key);
        assertTrue(d.aCols[0].startsWith("{1:"));
        assertEquals(d.aCols.length, d.bCols.length);
        int moneyRow = indexOfContaining(d.aCols, ":61:2608010801RD224,NMSCPP1005686353");
        assertTrue(moneyRow >= 0);
        assertEquals(1, d.diffCols.length);
        assertEquals(moneyRow, d.diffCols[0]);
        assertTrue(d.bCols[moneyRow].contains("NMSCPP9999999999"));

        // 3) B 删除段1 → onlyA；B 换主键为新增段 → onlyB
        List<String> c = new java.util.ArrayList<>();
        c.addAll(segment(a, 1));
        c.addAll(segment(a, 2));
        c.add("{1:F01BKCHJPJTAXXX0001000001}{2:I950BKCHJPJTAXXXN}{3:{108:BOCIPAY-RT}}{4:");
        c.add(":20:SM26080100000099");
        c.add("-}");
        CustomCompareEngine.Result r2 = CustomCompareEngine.compare(a, c, MT950);
        assertEquals(3, r2.summary().segmentsA);
        assertEquals(3, r2.summary().segmentsB);
        assertEquals(2, r2.summary().equal);
        assertEquals(1, r2.summary().onlyA);
        assertEquals(1, r2.summary().onlyB);
        assertTrue(r2.rows().stream().anyMatch(x -> Status.UNMATCHED_A.equals(x.status)
                && "SM26080100000001".equals(x.key)));
        assertTrue(r2.rows().stream().anyMatch(x -> Status.UNMATCHED_B.equals(x.status)
                && "SM26080100000099".equals(x.key)));
    }

    @Test
    void 无主键模式_段全文精确匹配() {
        List<String> a = List.of("<S>", "x=1", "</S>", "<S>", "y=2", "</S>");
        List<String> b = List.of("<S>", "y=2", "</S>", "<S>", "z=9", "</S>");
        CustomFormat f = CustomFormat.of("^<S", "^</S>", null);
        CustomCompareEngine.Result r = CustomCompareEngine.compare(a, b, f);
        assertEquals(1, r.summary().equal); // y=2 段全文相等（乱序）
        assertEquals(1, r.summary().onlyA); // x=1 段
        assertEquals(1, r.summary().onlyB); // z=9 段
        assertTrue(r.summary().warnings.stream().anyMatch(w -> w.contains("未配置主键")));
    }

    @Test
    void 文件头尾残行对照() {
        List<String> a = List.of("HDR-A", "<S>", "x=1", "</S>", "TAIL-A");
        List<String> b = List.of("HDR-A", "<S>", "x=1", "</S>", "TAIL-B");
        CustomFormat f = CustomFormat.of("^<S", "^</S>", "(\\w+)");
        CustomCompareEngine.Result r = CustomCompareEngine.compare(a, b, f);
        assertEquals(1, r.summary().equal);
        assertEquals(r.summary().headLinesA, r.summary().headLinesB);
        assertEquals(1, r.summary().tailLinesA);
        // 尾残行不同 → 一条 diff（key=文件尾#1）
        assertTrue(r.rows().stream().anyMatch(x -> Status.DIFF.equals(x.status)
                && x.key.startsWith("文件尾")));
        assertEquals(0, r.summary().diff); // 段本身全部相等
    }

    // ---- helpers ----

    /** 取第 seg 段（0-based，按 MT950 分段）的原始行列表。 */
    private static List<String> segment(List<String> txt, int seg) {
        Parsed p = SegmentParser.parse(txt, MT950);
        return p.segments().get(seg).lines();
    }

    /** 段内文本替换（模拟对抗差异）。 */
    private static List<String> mutated(List<String> seg, String marker, String from, String to) {
        List<String> out = new java.util.ArrayList<>();
        boolean done = false;
        for (String l : seg) {
            if (!done && l.contains(marker) && l.contains(from)) {
                out.add(l.replace(from, to));
                done = true;
            } else {
                out.add(l);
            }
        }
        return out;
    }

    private static int indexOfContaining(String[] lines, String needle) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) return i;
        }
        return -1;
    }
}
