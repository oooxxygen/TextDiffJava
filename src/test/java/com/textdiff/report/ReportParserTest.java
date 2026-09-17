package com.textdiff.report;

import com.textdiff.report.ReportParser.ParsedReport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 报表分区解析：合成用例覆盖分区规则；真实样本（存在时）覆盖四类报表形态。 */
class ReportParserTest {

    /** 真实样本目录（验收数据，测试环境不存在时跳过对应用例）。 */
    private static final Path SAMPLES = Path.of("O:/CodeRepos/ExampleData");

    private static List<String> lines(Path p) throws Exception {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    // ---- 合成用例 ----

    @Test
    void 无表尾模板_业务行全部识别() {
        List<String> tpl = List.of(
                "1@OD@|@T@|BANK-CODE:   |RPT-ID:X100|",
                "1                    ( -X100 )",
                "        DEMO REPORT",
                "        ===========",
                "  COL1  COL2  COL3");
        List<String> rpt = List.of(
                "1@OD@|@T@|BANK-CODE:105|RPT-ID:X100|",
                "1                    ( 00000-X100 )",
                "        DEMO REPORT",
                "        ===========",
                "  COL1  COL2  COL3",
                "  A1  B1  C1",
                "  A2  B2  C2",
                "  A3  B3  C3");
        ParsedReport p = ReportParser.parse(tpl, rpt);
        assertEquals(5, p.headerLen());
        assertEquals(1, p.headerBlocks().size());
        assertEquals(0, p.footerBlocks().size());
        assertEquals(3, p.rows().size());
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void 空白占位加表尾_段间分隔空行不混入业务() {
        // CRDD 形态：表头8行 + 占位(T9) + 表尾[空行,END]；段后另有分隔空行
        List<String> tpl = List.of(
                "1@OD@|@T@|RPT-ID:Y200|",
                "        ( -Y200 )",
                "        LIST",
                "        ====",
                "  BRANCH :        DATE :        PAGE :     1",
                "",
                "  ACC NO        CARD NO1",
                "  ------------  --------",
                "",
                "",
                "                                * * *  END OF LIST  * * *");
        List<String> rpt = List.of(
                "1@OD@|@T@|RPT-ID:Y200|",
                "        ( 00000-Y200 )",
                "        LIST",
                "        ====",
                "  BRANCH : HQ        DATE : 2026/07/10        PAGE :     1",
                "",
                "  ACC NO        CARD NO1",
                "  ------------  --------",
                "  001  6222",
                "  002  6333",
                "",
                "                                * * *  END OF LIST  * * *",
                "");
        ParsedReport p = ReportParser.parse(tpl, rpt);
        assertEquals(8, p.headerLen());
        assertEquals(1, p.headerBlocks().size());
        assertEquals(1, p.footerBlocks().size());
        assertArrayEquals(new String[]{"", "                                * * *  END OF LIST  * * *"},
                p.footerBlocks().get(0));
        assertEquals(2, p.rows().size());
        assertEquals("001", p.rows().get(0).fields()[0]);
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void 多段报表_每段独立表头表尾() {
        List<String> tpl = List.of(
                "1@OD@|@T@|RPT-ID:Z300|",
                "        LIST",
                "  COL1  COL2",
                "",
                "  TOTAL-COUNT   TOTAL-AMT",
                "  END");
        String row1 = "  A1|  B1|";
        String row2 = "  A2|  B2|";
        List<String> rpt = List.of(
                "1@OD@|@T@|RPT-ID:Z300|",
                "        LIST",
                "  COL1  COL2",
                row1,
                "",
                "  TOTAL-COUNT   TOTAL-AMT",
                "  END|        2|",
                "",                                  // 段间分隔空行
                "1@OD@|@T@|RPT-ID:Z300|",
                "        LIST",
                "  COL1  COL2",
                row2,
                "",
                "  TOTAL-COUNT   TOTAL-AMT",
                "  END|        1|",
                "");
        ParsedReport p = ReportParser.parse(tpl, rpt);
        assertEquals(3, p.headerLen());
        assertEquals(2, p.headerBlocks().size());
        assertEquals(2, p.footerBlocks().size());
        assertArrayEquals(new String[]{"  TOTAL-COUNT   TOTAL-AMT", "  END|        2|"},
                p.footerBlocks().get(0));
        assertEquals(2, p.rows().size());
        assertEquals(0, p.rows().get(0).section());
        assertEquals(1, p.rows().get(1).section());
        assertArrayEquals(new String[]{"A1", "B1"}, p.rows().get(0).fields());
    }

    @Test
    void 竖线行尾空块丢弃_空格行按两空格切() {
        assertArrayEquals(new String[]{"A", "B", "C"}, ReportParser.splitFields("  A|  B|  C|"));
        assertArrayEquals(new String[]{"A", "", "C"}, ReportParser.splitFields("  A||  C|"));
        assertArrayEquals(new String[]{"A1", "B2", "C3"}, ReportParser.splitFields("  A1  B2  C3"));
        // 值内部单个空格不切分
        assertArrayEquals(new String[]{"MAYBANK BERHAD", "11"}, ReportParser.splitFields("  MAYBANK BERHAD  11"));
    }

    @Test
    void 锚点缺失_整文件按业务行降级() {
        List<String> tpl = List.of("HEAD", "  COL1");
        List<String> rpt = List.of("垃圾行", "  数据1", "  数据2");
        ParsedReport p = ReportParser.parse(tpl, rpt);
        assertEquals(0, p.headerLen());
        assertEquals(3, p.rows().size());
        assertFalse(p.warnings().isEmpty());
    }

    // ---- 真实样本用例 ----

    @Test
    void 样本_CORD9000_纯表头无业务行() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parse(
                lines(SAMPLES.resolve("01.CORD900U.header")),
                lines(SAMPLES.resolve("01.CORD900U.105")));
        assertEquals(7, p.headerLen());
        assertEquals(1, p.headerBlocks().size());
        assertEquals(0, p.footerBlocks().size());
        assertEquals(0, p.rows().size());
    }

    @Test
    void 样本_CRDD0020_11段58业务行() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parse(
                lines(SAMPLES.resolve("01.CRDD002U.header")),
                lines(SAMPLES.resolve("01.CRDD002U.105")));
        assertEquals(8, p.headerLen());
        assertEquals(11, p.headerBlocks().size());
        assertEquals(11, p.footerBlocks().size());
        assertEquals(58, p.rows().size());
        assertTrue(p.warnings().isEmpty(), "不应有解析告警: " + p.warnings());
        // 首行业务字段：账号 + 卡号
        assertEquals("00100000405038342", p.rows().get(0).fields()[0]);
        assertEquals("6291522000059703", p.rows().get(0).fields()[1]);
    }

    @Test
    void 样本_DEPD8920_两段2602行_末段表尾() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parse(
                lines(SAMPLES.resolve("01.DEPD892U.header")),
                lines(SAMPLES.resolve("01.DEPD892U.105")));
        assertEquals(7, p.headerLen());
        assertEquals(2, p.headerBlocks().size());
        assertEquals(1, p.footerBlocks().size());
        assertEquals(2602, p.rows().size());
        assertTrue(p.warnings().isEmpty(), "不应有解析告警: " + p.warnings());
        // 竖线切分 + 尾空块丢弃
        assertArrayEquals(new String[]{"51016", "51016", "MYR", "6003", "5502", "0",
                "0.020", "0.010", "1", "1", ""}, p.rows().get(0).fields());
        // 末段表尾含 END 行（值填充）
        String[] lastFooter = p.footerBlocks().get(0);
        assertTrue(lastFooter[lastFooter.length - 1].startsWith("  END|"));
    }

    @Test
    void 样本_PYDD1040_单段1行业尾核对行() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "样本目录不存在");
        ParsedReport p = ReportParser.parse(
                lines(SAMPLES.resolve("01.PYDD104U.header")),
                lines(SAMPLES.resolve("01.PYDD104U.105")));
        assertEquals(8, p.headerLen());
        assertEquals(1, p.headerBlocks().size());
        assertEquals(1, p.footerBlocks().size());
        assertEquals(1, p.rows().size());
        // 两空格切分：CHQ_INST_NAME 空白区并入相邻空白串，无空字段
        assertArrayEquals(new String[]{"UU01", "00100000400087973", "MYR", "50,000.000", "11"},
                p.rows().get(0).fields());
    }
}
