package com.textdiff.report;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.report.ReportParser.ParsedReport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 控制行版式（1@OD@|）解析与对比：折行报表（DEPD602U 折 2 行 / PYID021U 折 3 行）、分页
 * （PYID020U 5 页）、表尾数量核对（CUR PG QTY / Total Quantity）；段签名配对免疫段序差异。
 * 真实样本期望值由独立脚本预演核对（DEPD602U 769 行 = 各页 CUR PG QTY 之和）。
 */
class ReportControlFormatTest {

    private static final Path SAMPLES = Path.of("O:/CodeRepos/ExampleData");

    private static List<String> lines(String name) throws Exception {
        return Files.readAllLines(SAMPLES.resolve(name), StandardCharsets.UTF_8);
    }

    // ---- 合成用例 ----

    @Test
    void 控制行识别() {
        assertTrue(ReportParser.hasControlLines(List.of("1@OD@|@T@|BANK-CODE:102|")));
        assertFalse(ReportParser.hasControlLines(List.of("plain text", "1 2 3")));
    }

    @Test
    void 合成折行报表_分区与折行记录() {
        List<String> rpt = List.of(
                "1@OD@|@T@|BANK-CODE:102|ORG-ID:51365|RPT-ID:X1000|DAT:2026/08/01|PRODUCT:50150206|",
                "1                                                    ( 51365-X1000 )",
                "                            Demo Journal(Branch)",
                "                            ===================",
                " ",
                "  BRCH : Demo Branch                Date : 2026/08/01             Page :     1",
                " ",
                "  A/C Type: 50150206",
                "  A/C          CCY        AMT",
                "  TX Time",
                "  00100000001  USD   100.00",
                "   00:06:04",
                "  00100000002  JPY   200.00",
                "   00:06:05",
                "",
                "   CUR PG QTY:              2",
                "");
        ParsedReport p = ReportParser.parseControlFormat(rpt);
        assertTrue(p.controlFormat());
        assertEquals(1, p.headerBlocks().size());
        assertEquals(1, p.footerBlocks().size());
        assertEquals(1, p.sectionKeys().size());
        assertTrue(p.sectionKeys().get(0).startsWith("1@OD@|"));
        assertEquals(2, p.rows().size());
        // 折行记录：display 保持 2 物理行；字段 = 主行 + 续行拼接
        ReportParser.Row r0 = p.rows().get(0);
        assertEquals(2, r0.display().length);
        assertEquals("  00100000001  USD   100.00", r0.display()[0]);
        assertEquals("   00:06:04", r0.display()[1]);
        assertEquals(4, r0.fields().length);
        assertEquals("00:06:04", r0.fields()[3]);
        // 表尾 = [数据与表尾间空行, QTY 行]
        assertTrue(String.join("\n", p.footerBlocks().get(0)).contains("CUR PG QTY:              2"));
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void 分页报表_页首重复表头跳过() {
        List<String> rpt = List.of(
                "1@OD@|@T@|RPT-ID:Y2000|",
                "1                                        ( -Y2000 )",
                "                            Demo Journal",
                "                            ============",
                " ",
                "  BRCH : Demo Branch         Page :     1",
                " ",
                "  A/C          CCY        AMT",
                "  00100000001  USD   100.00",
                "  00100000002  USD   200.00",
                "",
                "   CUR PG QTY:              2",
                "1                                        ( -Y2000 )",
                "                            Demo Journal",
                "                            ============",
                " ",
                "  BRCH : Demo Branch         Page :     2",
                " ",
                "  A/C          CCY        AMT",
                "  00100000003  JPY   300.00",
                "",
                "   CUR PG QTY:              1",
                "");
        ParsedReport p = ReportParser.parseControlFormat(rpt);
        assertEquals(1, p.headerBlocks().size()); // 分页不算新报表段
        assertEquals(3, p.rows().size());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("跨 2 页")));
        // 表尾数量核对：两页 QTY 之和 = 3 = 程序计数
        assertEquals(1, p.footerBlocks().size());
    }

    @Test
    void 折行记录不完整_告警并按余行处理() {
        List<String> rpt = List.of(
                "1@OD@|@T@|RPT-ID:Z3000|",
                "1   ( -Z3000 )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          CCY        AMT",
                "  TX Time",
                "  00100000001  USD   100.00",
                "   00:06:04",
                "  00100000002  USD   200.00",
                "");
        ParsedReport p = ReportParser.parseControlFormat(rpt);
        assertEquals(1, p.rows().size());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("折行记录不完整")));
    }

    @Test
    void 段签名缩写() {
        assertEquals("50150206", ReportParser.sigShort("1@OD@|@T@|BANK-CODE:102|ORG-ID:51365|RPT-ID:X1000|PRODUCT:50150206|"));
        assertEquals("51365", ReportParser.sigShort("1@OD@|@T@|ORG-ID:51365|RPT-ID:X1000|"));
        assertEquals("X1000", ReportParser.sigShort("1@OD@|@T@|RPT-ID:X1000|"));
        assertEquals("1@OD@|@T@|BANK-CODE:105|", ReportParser.sigShort("1@OD@|@T@|BANK-CODE:105|"));
    }

    @Test
    void 列名行与主行判定() {
        assertTrue(ReportParser.isColumnNameLine("  A/C          CCY        AMT"));
        assertTrue(ReportParser.isColumnNameLine("  TX Time"));
        assertFalse(ReportParser.isColumnNameLine("  00100000001  USD   100.00"));
        assertFalse(ReportParser.isColumnNameLine("  * * *  END OF LIST  * * *"));
        assertTrue(ReportParser.isMainLine("  00100000001  USD   100.00"));
        assertTrue(ReportParser.isMainLine("   ****"));
        assertFalse(ReportParser.isMainLine("  * * *  END OF LIST  * * *"));
        assertFalse(ReportParser.isMainLine("   CUR PG QTY:              2"));
        assertTrue(ReportParser.isRuleLine("  -------------------"));
        assertFalse(ReportParser.isRuleLine("  00100000001  USD"));
    }

    // ---- 对比用例 ----

    @Test
    void 折行对比_保持折行原貌_字段差异定位物理行() {
        List<String> a = List.of(
                "1@OD@|@T@|RPT-ID:M100|PRODUCT:5001|",
                "1   ( -M100 )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          CCY        AMT",
                "  TX Time",
                "  00100000001  USD   100.00",
                "   00:06:04",
                "",
                "   CUR PG QTY:              1",
                "");
        List<String> b = List.of(
                "1@OD@|@T@|RPT-ID:M100|PRODUCT:5001|",
                "1   ( -M100 )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          CCY        AMT",
                "  TX Time",
                "  00100000001  USD   101.00",
                "   00:06:04",
                "",
                "   CUR PG QTY:              1",
                "");
        ParsedReport pa = ReportParser.parseControlFormat(a);
        ParsedReport pb = ReportParser.parseControlFormat(b);
        ReportComparator.Result r = ReportComparator.compare(pa, pb, List.of());
        assertTrue(r.summary().controlFormat);
        assertEquals(2, r.summary().foldLines);
        assertEquals(0, r.summary().equal);
        assertEquals(1, r.summary().partial);
        // display = 2 物理行（保持折行显示），差异定位在主行（物理行 0）
        assertEquals(1, r.dataRows().size());
        RowDiff d = r.dataRows().get(0);
        assertEquals(Status.DIFF, d.status);
        assertEquals(2, d.aCols.length);
        assertEquals("  00100000001  USD   100.00", d.aCols[0]);
        assertEquals("  00100000001  USD   101.00", d.bCols[0]);
        assertArrayEquals(new int[]{0}, d.diffCols);
    }

    @Test
    void 签名配对_免疫段序差异() {
        List<String> a = List.of(
                "1@OD@|@T@|PRODUCT:5001|",
                "1   ( -X )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          AMT",
                "  00100000001  USD   100.00",
                "",
                "   CUR PG QTY:              1",
                "1@OD@|@T@|PRODUCT:5002|",
                "1   ( -X )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          AMT",
                "  00200000002  JPY   200.00",
                "",
                "   CUR PG QTY:              1",
                "");
        List<String> b = List.of( // 段序对调
                "1@OD@|@T@|PRODUCT:5002|",
                "1   ( -X )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          AMT",
                "  00200000002  JPY   200.00",
                "",
                "   CUR PG QTY:              1",
                "1@OD@|@T@|PRODUCT:5001|",
                "1   ( -X )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          AMT",
                "  00100000001  USD   100.00",
                "",
                "   CUR PG QTY:              1",
                "");
        ParsedReport pa = ReportParser.parseControlFormat(a);
        ParsedReport pb = ReportParser.parseControlFormat(b);
        ReportComparator.Result r = ReportComparator.compare(pa, pb, List.of());
        assertEquals(2, r.summary().sectionCountA);
        assertEquals(0, r.summary().headerLineDiff);
        assertEquals(0, r.summary().footerLineDiff);
        assertEquals(2, r.summary().equal);
        assertEquals(0, r.summary().onlyA);
        assertEquals(0, r.summary().onlyB);
    }

    @Test
    void 单侧段_表头与业务整体缺失() {
        List<String> a = List.of(
                "1@OD@|@T@|PRODUCT:5001|",
                "1   ( -X )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          AMT",
                "  00100000001  USD   100.00",
                "",
                "   CUR PG QTY:              1",
                "");
        List<String> b = List.of(
                "1@OD@|@T@|PRODUCT:5002|",
                "1   ( -X )",
                "  BRCH : Demo Branch         Page :     1",
                "  A/C          AMT",
                "  00200000002  JPY   200.00",
                "",
                "   CUR PG QTY:              1",
                "");
        ParsedReport pa = ReportParser.parseControlFormat(a);
        ParsedReport pb = ReportParser.parseControlFormat(b);
        ReportComparator.Result r = ReportComparator.compare(pa, pb, List.of());
        assertEquals(2, r.summary().headerBlockDiff); // 双侧各 1 个未配对段块
        assertEquals(2, r.summary().footerBlockDiff);
        assertEquals(1, r.summary().onlyA);
        assertEquals(1, r.summary().onlyB);
    }

    // ---- 真实样本（存在时） ----

    @Test
    void DEPD602U_折2行77段769行_QTY核对() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parseControlFormat(lines("01.DEPD602U.102"));
        assertTrue(p.controlFormat());
        assertEquals(77, p.headerBlocks().size());
        assertEquals(77, p.footerBlocks().size());
        assertEquals(2, p.rows().get(0).display().length);
        assertEquals(769, p.rows().size());
        // 折行记录：主行 10 个 token + 续行 1 个（TX Time）
        assertEquals(11, p.rows().get(0).fields().length);
        assertEquals("00:06:04", p.rows().get(0).fields()[10]);
        // 列名（首段列头折 2 行）
        assertTrue(p.columnNames().contains("A/C"));
        assertTrue(p.columnNames().stream().anyMatch(n -> n.startsWith("行2·")));
        // QTY 核对：769 = 各段 CUR PG QTY 之和（独立脚本预演值）
        List<ReportSummary.CountCheck> checks = ReportComparator.countChecks("A", p);
        assertEquals(77, checks.size());
        long sumDeclared = checks.stream().filter(c -> c.declared != null)
                .mapToLong(c -> Long.parseLong(c.declared)).sum();
        assertEquals(769, sumDeclared);
        assertTrue(checks.stream().allMatch(c -> c.match));
    }

    @Test
    void PYID021U_折3行2段10行() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parseControlFormat(lines("01.PYID021U.102"));
        assertEquals(2, p.headerBlocks().size());
        assertEquals(3, p.rows().get(0).display().length);
        assertEquals(10, p.rows().size());
        assertEquals("DD51369150000009          00881950        51369 Bank of China Limited Tokyo Branch  HKD                  575.00  2015/05/25",
                p.rows().get(0).display()[0].strip());
    }

    @Test
    void PYID020U_1段5页234行_TotalQuantity核对() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parseControlFormat(lines("01.PYID020U.102"));
        assertEquals(1, p.headerBlocks().size());
        assertEquals(234, p.rows().size());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("跨 5 页")));
        List<ReportSummary.CountCheck> checks = ReportComparator.countChecks("A", p);
        assertEquals(1, checks.size());
        assertEquals("234", checks.get(0).declared);
        assertTrue(checks.get(0).match);
    }

    @Test
    void CRDD019U_划线表头4行_ENDOFLIST表尾() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parseControlFormat(lines("01.CRDD019U.105"));
        assertEquals(1, p.headerBlocks().size());
        assertEquals(4, p.rows().size());
        // 表头含划线行：控制行/页标/标题/下划线/BRANCH/空行/列头/划线 共 8 行
        assertEquals(8, p.headerBlocks().get(0).length);
        // 表尾 = [数据与表尾间空行, END OF LIST]（保留 CRDD [空行,END] 形态）
        assertTrue(String.join("\n", p.footerBlocks().get(0)).contains("* * *  END OF LIST  * * *"));
    }
}
