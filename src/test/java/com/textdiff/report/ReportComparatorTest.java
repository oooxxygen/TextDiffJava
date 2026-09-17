package com.textdiff.report;

import com.textdiff.engine.Status;
import com.textdiff.report.ReportParser.ParsedReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 报表对比：排序免疫乱序、部分匹配、单侧不匹配、表头表尾与条数核对。 */
class ReportComparatorTest {

    private static final List<String> TPL = List.of(
            "1@OD@|@T@|RPT-ID:Z300|",
            "        LIST",
            "  COL1  COL2  COL3",
            "",
            "  TOTAL-COUNT   TOTAL-AMT",
            "  END");

    private static List<String> report(String orgId, List<String> rows, String count) {
        List<String> out = new ArrayList<>(List.of(
                "1@OD@|@T@|BANK-CODE:" + orgId + "|RPT-ID:Z300|",
                "        LIST",
                "  COL1  COL2  COL3"));
        out.addAll(rows);
        out.add("");
        out.add("  TOTAL-COUNT   TOTAL-AMT");
        out.add("  END|        " + count + "|");
        return out;
    }

    private static List<String> rows(String[]... fs) {
        List<String> out = new ArrayList<>();
        for (String[] f : fs) out.add("  " + String.join("  ", f));
        return out;
    }

    private static ParsedReport parse(List<String> rpt) {
        return ReportParser.parse(TPL, rpt);
    }

    @Test
    void 完全一致_乱序重排后仍全匹配() {
        List<String> a = report("105", rows(new String[]{"A1", "B1", "C1"},
                new String[]{"A2", "B2", "C2"}, new String[]{"A3", "B3", "C3"}), "3");
        List<String> bLines = new ArrayList<>(a);
        Collections.shuffle(bLines, new java.util.Random(7));
        // 乱序后仍要能重排回合法报表（表头在前、表尾在后）——直接手工换序业务行
        List<String> b = report("105", rows(new String[]{"A3", "B3", "C3"},
                new String[]{"A1", "B1", "C1"}, new String[]{"A2", "B2", "C2"}), "3");
        var r = ReportComparator.compare(parse(a), parse(b), List.of());
        assertEquals(3, r.summary().equal);
        assertEquals(0, r.summary().partial);
        assertEquals(0, r.summary().onlyA + r.summary().onlyB);
        assertEquals(0, r.summary().headerLineDiff);
        assertEquals(0, r.summary().footerLineDiff);
    }

    @Test
    void 单字段改动_判部分匹配并指出差异列() {
        List<String> a = report("105", rows(new String[]{"A1", "B1", "C1"},
                new String[]{"A2", "B2", "C2"}), "2");
        List<String> b = report("105", rows(new String[]{"A1", "B1", "C1"},
                new String[]{"A2", "B2", "C9"}), "2");
        var r = ReportComparator.compare(parse(a), parse(b), List.of());
        assertEquals(1, r.summary().equal);
        assertEquals(1, r.summary().partial);
        assertEquals(1, r.dataRows().stream().filter(x -> Status.DIFF.equals(x.status)).count());
        var diff = r.dataRows().stream().filter(x -> Status.DIFF.equals(x.status)).findFirst().orElseThrow();
        assertArrayEquals(new int[]{2}, diff.diffCols);
        assertEquals("C2", diff.aCols[2]);
        assertEquals("C9", diff.bCols[2]);
    }

    @Test
    void 单侧增删_判单侧不匹配() {
        List<String> a = report("105", rows(new String[]{"A1", "B1", "C1"},
                new String[]{"A2", "B2", "C2"}, new String[]{"A3", "B3", "C3"}), "3");
        List<String> b = report("105", rows(new String[]{"A1", "B1", "C1"},
                new String[]{"A4", "B4", "C4"}), "3");
        var r = ReportComparator.compare(parse(a), parse(b), List.of());
        assertEquals(1, r.summary().equal);
        assertEquals(2, r.summary().onlyA);
        assertEquals(1, r.summary().onlyB);
        assertTrue(r.dataRows().stream().anyMatch(x -> Status.UNMATCHED_A.equals(x.status)
                && x.aCols[0].equals("A2")));
        assertTrue(r.dataRows().stream().anyMatch(x -> Status.UNMATCHED_B.equals(x.status)
                && x.bCols[0].equals("A4")));
    }

    @Test
    void 表头值差异与表尾声明条数核对() {
        List<String> a = report("105", rows(new String[]{"A1", "B1", "C1"}), "5");
        List<String> b = report("511", rows(new String[]{"A1", "B1", "C1"}), "1");
        var r = ReportComparator.compare(parse(a), parse(b), List.of());
        assertEquals(1, r.summary().headerLineDiff, "控制行 BANK-CODE 不同");
        // A 侧声明 5 实计 1 → 不符；B 侧声明 1 实计 1 → 相符
        assertEquals(2, r.summary().countChecks.size());
        var chkA = r.summary().countChecks.get(0);
        var chkB = r.summary().countChecks.get(1);
        assertEquals("A", chkA.side);
        assertEquals("5", chkA.declared);
        assertEquals(1, chkA.counted);
        assertFalse(chkA.match);
        assertTrue(chkB.match);
        assertEquals(1, r.summary().footerLineDiff, "END 行声明值不同");
    }

    @Test
    void 双侧同改_整行相等仍匹配() {
        // 同一行两侧都被加工（值一致）→ 排序后全匹配，不受写入顺序影响
        List<String> a = report("105", rows(new String[]{"A2", "B2", "C2"},
                new String[]{"A1", "X1", "C1"}), "2");
        List<String> b = report("105", rows(new String[]{"A1", "X1", "C1"},
                new String[]{"A2", "B2", "C2"}), "2");
        var r = ReportComparator.compare(parse(a), parse(b), List.of());
        assertEquals(2, r.summary().equal);
        assertEquals(0, r.summary().partial + r.summary().onlyA + r.summary().onlyB);
    }

    @Test
    void 相似度与差异列计算() {
        assertEquals(1.0, ReportComparator.similarity(
                new String[]{"A", "B"}, new String[]{"A", "B"}));
        assertEquals(1.0, ReportComparator.similarity(
                new String[]{"A", ""}, new String[]{"A", ""}));
        assertTrue(ReportComparator.similarity(
                new String[]{"A", "B"}, new String[]{"A", ""}) < ReportComparator.PARTIAL_THRESHOLD);
        assertTrue(ReportComparator.similarity(
                new String[]{"ACC1", "CARD1"}, new String[]{"ACC1", "CARD2"})
                >= ReportComparator.PARTIAL_THRESHOLD);
        assertArrayEquals(new int[]{1}, ReportComparator.diffCols(
                new String[]{"A", "B1"}, new String[]{"A", "B2"}));
        assertArrayEquals(new int[]{2}, ReportComparator.diffCols(
                new String[]{"A", "B", "C"}, new String[]{"A", "B"}));
        assertEquals(0, ReportComparator.levenshtein("ABC", "ABC"));
        assertEquals(1, ReportComparator.levenshtein("ABC", "ABD"));
    }

    @Test
    void 表尾块数不对齐_记单侧缺失() {
        List<String> a = report("105", List.of(), "0");
        // B 无表尾（END 行缺失）
        List<String> b = new ArrayList<>(List.of(
                "1@OD@|@T@|BANK-CODE:105|RPT-ID:Z300|",
                "        LIST",
                "  COL1  COL2  COL3"));
        var r = ReportComparator.compare(parse(a), parse(b), List.of());
        assertTrue(r.footerRows().stream().anyMatch(x -> Status.UNMATCHED_A.equals(x.status)),
                "A 的表尾块应记单侧缺失");
    }

    @Test
    void 条数声明提取_容忍千分位逗号() throws Exception {
        var m = ReportComparator.class.getDeclaredMethod("countChecks", String.class, ParsedReport.class);
        m.setAccessible(true);
        List<String> rpt = List.of(
                "  TOTAL-COUNT   TOTAL-AMT",
                "  END|     1,301|");
        ParsedReport p = ReportParser.parse(TPL, List.of(
                "1@OD@|@T@|RPT-ID:Z300|", "        LIST", "  COL1  COL2  COL3",
                "  A1  B1  C1", "", "  TOTAL-COUNT   TOTAL-AMT", "  END|     1,301|"));
        @SuppressWarnings("unchecked")
        List<ReportSummary.CountCheck> checks =
                (List<ReportSummary.CountCheck>) m.invoke(null, "A", p);
        assertEquals(1, checks.size());
        assertEquals("1301", checks.get(0).declared);
        assertEquals(1, checks.get(0).counted);
        assertFalse(checks.get(0).match);
    }
}
