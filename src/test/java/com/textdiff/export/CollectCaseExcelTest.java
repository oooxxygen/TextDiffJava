package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 差异采集对比案例 Excel（CollectComparisonCaseTemplate 21 列）：列取数规则与行粒度。 */
class CollectCaseExcelTest {

    /** 造作业：k1 相等（不采集）、k2 双栏位差异（I=2/3 两行）、k3 仅 A、k4 仅 B、尾部含 DataStartDate。 */
    private static JobRecord jobWithRows(Path dir, String fileA, String configLine) throws Exception {
        JobRecord job = new JobRecord("j1", "b1", "CASENICK", configLine, fileA, "/data/b/B_INCT.dat",
                dir.resolve("results").resolve("j1").toString());
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(
                Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            sink.addRow(RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "CN", "100"}));
            sink.addRow(new RowDiff("k2", Status.DIFF, Status.SECTION_DATA,
                    new String[]{"k2", "CN", "ICBC"}, new String[]{"k2", "US", "ICBC"}, new int[]{1, 2}));
            sink.addRow(RowDiff.onlyA("k3", Status.SECTION_DATA, new String[]{"k3", "JP"}));
            sink.addRow(RowDiff.onlyB("k4", Status.SECTION_DATA, new String[]{"k4", "KR"}));
            sink.addRow(new RowDiff("TRX", Status.EQUAL, Status.SECTION_TRAILER, null, null, new int[0],
                    "TRX_DATE=20251001|DataStartDate=20250901", null));
        }
        return job;
    }

    @Test
    void 全列取数_差异逐栏位_单方侧别文案(@TempDir Path dir) throws Exception {
        // 用户口径样例文件：压缩文件 01A3020D.az1.gz.202609271 → 日期 20260927 / 银行号 302 / 文件名 az1
        JobRecord job = jobWithRows(dir, "/data/a/01A3020D.az1.gz.202609271",
                "CASENICK:01A***0D.v01:KEYSEQ=1:SRCA=主机:SRCB=信创");
        BatchRecord batch = new BatchRecord("b1", "dirA vs dirB", "/data/a", "/data/b", "ut", 1);
        batch.cmpBatch = "P2703";
        batch.cmpRound = "2";
        Path out = dir.resolve("out").resolve("CASENICK_差异.xlsx");
        CollectCaseExcel.write(out, job, batch, "C64", "bocs_dep");

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            assertEquals(1, wb.getNumberOfSheets());
            Sheet sh = wb.getSheet("data");
            Row head = sh.getRow(0);
            assertEquals(21, CollectCaseExcel.HEADERS.length);
            assertEquals("重复轮次", CollectCaseExcel.HEADERS[3]);
            assertEquals("银行号", CollectCaseExcel.HEADERS[4]);
            assertEquals("主键id", CollectCaseExcel.HEADERS[20]);
            for (int i = 0; i < CollectCaseExcel.HEADERS.length; i++) {
                assertEquals(CollectCaseExcel.HEADERS[i], head.getCell(i).getStringCellValue(), "表头列" + i);
            }
            // 行1：k2 栏位2 差异——日期取压缩文件名 9 位尾缀前 8 位，银行号取首段 4-6 位数字，文件名取 gz 前扩展名
            Row r1 = sh.getRow(1);
            assertEquals("20260927", r1.getCell(0).getStringCellValue());
            assertEquals("P2703", r1.getCell(1).getStringCellValue());
            assertEquals("2", r1.getCell(2).getStringCellValue());
            assertEquals("", r1.getCell(3).getStringCellValue()); // 重复轮次默认不赋值
            assertEquals("302", r1.getCell(4).getStringCellValue());
            assertEquals("C64", r1.getCell(5).getStringCellValue());
            assertEquals("CASENICK", r1.getCell(6).getStringCellValue());
            assertEquals("az1", r1.getCell(7).getStringCellValue());
            assertEquals(2.0, r1.getCell(8).getNumericCellValue(), 1e-9);
            assertEquals("栏位值不匹配", r1.getCell(9).getStringCellValue());
            assertEquals("主机：CN    信创：US", r1.getCell(10).getStringCellValue());
            assertEquals("存款", r1.getCell(13).getStringCellValue()); // bocs_dep → 存款
            assertCaseId(r1.getCell(20).getStringCellValue());
            // 行2：k2 栏位3 差异（同记录第二个差异栏位独立成行，标识列重复）
            Row r2 = sh.getRow(2);
            assertEquals(3.0, r2.getCell(8).getNumericCellValue(), 1e-9);
            assertEquals("主机：ICBC    信创：ICBC", r2.getCell(10).getStringCellValue());
            assertEquals("P2703", r2.getCell(1).getStringCellValue());
            // 行3：k3 仅 A（主机有、信创无）——栏位索引 0，错误类型/存在问题固定格式
            Row r3 = sh.getRow(3);
            assertEquals(0.0, r3.getCell(8).getNumericCellValue(), 1e-9);
            assertEquals("主机记录在信创未找到", r3.getCell(9).getStringCellValue());
            assertEquals("主机：k3    信创：(无)", r3.getCell(10).getStringCellValue());
            // 行4：k4 仅 B（信创有、主机无）
            Row r4 = sh.getRow(4);
            assertEquals("信创记录在主机未找到", r4.getCell(9).getStringCellValue());
            assertEquals("信创：k4    主机：(无)", r4.getCell(10).getStringCellValue());
            assertNull(sh.getRow(5)); // 相等记录与尾部区不采集，恰好 4 行
        }
    }

    @Test
    void 文件名无压缩日期回退尾部DataStartDate_银行号星号_未映射团队透传(@TempDir Path dir) throws Exception {
        JobRecord job = jobWithRows(dir, "/data/a/B_INCT.dat", "CASENICK:01A#BANKNO.dat:KEYSEQ=1");
        Path out = dir.resolve("out").resolve("CASENICK_差异.xlsx");
        CollectCaseExcel.write(out, job, null, null, "unknown_grp");

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            Sheet sh = wb.getSheet("data");
            Row r1 = sh.getRow(1);
            assertEquals("20250901", r1.getCell(0).getStringCellValue()); // 非压缩 → 尾部 DataStartDate 兜底
            assertEquals("", r1.getCell(1).getStringCellValue());          // 无批次 → 批次/轮次空
            assertEquals("", r1.getCell(2).getStringCellValue());
            assertEquals("", r1.getCell(3).getStringCellValue());
            assertEquals("***", r1.getCell(4).getStringCellValue());      // B_INCT 首段无 4-6 位数字 → ***
            assertEquals("", r1.getCell(5).getStringCellValue());          // 未导入 type_parm → 空
            assertEquals("dat", r1.getCell(7).getStringCellValue());       // 非压缩 → 扩展名末三位
            assertEquals("A：CN    B：US", r1.getCell(10).getStringCellValue()); // 缺省别名 A/B
            assertEquals("unknown_grp", r1.getCell(13).getStringCellValue()); // 未命中映射 → 原样透传
        }
    }

    @Test
    void 文件名_银行号_日期_团队映射_边界() {
        // 采集日期：仅压缩文件 .gz + 9 位尾缀取前 8 位；普通文件名日期不再读取（走 DataStartDate/自然日）
        assertEquals("20260927", CollectCaseExcel.dateFromName("01A3020D.az1.gz.202609271"));
        assertEquals("20260927", CollectCaseExcel.dateFromName("X.GZ.202609271"));          // 大小写不敏感
        assertEquals("20261327", CollectCaseExcel.dateFromName("X.gz.202613271"));          // 按规则取 8 位不校验合法性
        assertEquals("", CollectCaseExcel.dateFromName("X.dat.gz"));                        // 无 9 位尾缀 → 走兜底
        assertEquals("", CollectCaseExcel.dateFromName("XX_INCT_20250912.dat"));            // 非压缩文件名日期不再读取
        assertEquals("", CollectCaseExcel.dateFromName(""));
        assertEquals("", CollectCaseExcel.dateFromName(null));

        // 银行号：首段第 4-6 位全数字才写入，A 侧优先 B 侧兜底，取不到 ***
        assertEquals("302", CollectCaseExcel.bankNoOf("01A3020D.az1.gz.202609271", null));
        assertEquals("302", CollectCaseExcel.bankNoOf("/data/a/INCT0101.dat", "01A3020D.v01")); // B 侧兜底
        assertEquals("***", CollectCaseExcel.bankNoOf("ABC.dat", null));
        assertEquals("***", CollectCaseExcel.bankNoOf("01A3X20D.v01", null));                   // 含非数字
        assertEquals("***", CollectCaseExcel.bankNoOf("01.v01", null));                         // 段太短
        assertEquals("***", CollectCaseExcel.bankNoOf(null, null));

        // 文件名：压缩取 .gz 前扩展名，非压缩取扩展名末三位
        assertEquals("az1", CollectCaseExcel.fileTypeOf("01A3020D.az1.gz.202609271"));
        assertEquals("az1", CollectCaseExcel.fileTypeOf("01A3020D.az1"));
        assertEquals("v01", CollectCaseExcel.fileTypeOf("/x/ABC_20250916.v01"));
        assertEquals("cde", CollectCaseExcel.fileTypeOf("X.abcde"));                       // 超 3 位取末三位
        assertEquals("txt", CollectCaseExcel.fileTypeOf("x.txt.gz.123456789"));
        assertEquals("", CollectCaseExcel.fileTypeOf("01A3020D"));
        assertEquals("", CollectCaseExcel.fileTypeOf(null));

        // 分析团队：顺序前缀映射（含补充的 bocs_pay/loan），未命中透传
        assertEquals("存款", CollectCaseExcel.devTeam("bocs_dep"));
        assertEquals("贷款", CollectCaseExcel.devTeam("bocs_loan"));
        assertEquals("贷款", CollectCaseExcel.devTeam("bocs_loan_old")); // 前缀命中
        assertEquals("贷款", CollectCaseExcel.devTeam("bocs_bgl"));
        assertEquals("贷款", CollectCaseExcel.devTeam("bocs_fee"));
        assertEquals("贷款", CollectCaseExcel.devTeam("loan"));
        assertEquals("CIF", CollectCaseExcel.devTeam("bocs_cif"));
        assertEquals("核心", CollectCaseExcel.devTeam("bocs_kernel"));
        assertEquals("核心", CollectCaseExcel.devTeam("bocs_s"));
        assertEquals("核心", CollectCaseExcel.devTeam("bocs_score"));
        assertEquals("核心", CollectCaseExcel.devTeam("bocs_parm"));
        assertEquals("主批", CollectCaseExcel.devTeam("bocs_batch"));
        assertEquals("主批", CollectCaseExcel.devTeam("batch"));
        assertEquals("借记卡", CollectCaseExcel.devTeam("bocs_voucher"));
        assertEquals("借记卡", CollectCaseExcel.devTeam("bocs_card"));
        assertEquals("支付", CollectCaseExcel.devTeam("bocs_pay"));
        assertEquals("存款", CollectCaseExcel.devTeam("BOCS_DEP")); // 大小写不敏感命中
        assertEquals("unknown_grp", CollectCaseExcel.devTeam("unknown_grp"));
        assertEquals("", CollectCaseExcel.devTeam(""));
        assertEquals("", CollectCaseExcel.devTeam(null));

        // 主键id：24 位小写十六进制，逐行独立
        String a = CollectCaseExcel.caseId();
        String b = CollectCaseExcel.caseId();
        assertCaseId(a);
        assertCaseId(b);
        assertNotEquals(a, b);
    }

    private static void assertCaseId(String id) {
        assertEquals(24, id.length());
        assertTrue(id.matches("[0-9a-f]{24}"), () -> "非 24 位小写十六进制: " + id);
    }

    @Test
    void 自然日兜底_非压缩且无DataStartDate(@TempDir Path dir) throws Exception {
        JobRecord job = new JobRecord("j3", null, "CASENICK", "CASENICK:e*.txt:KEYSEQ=1",
                "/x/e.dat", "/y/e.dat", dir.resolve("results").resolve("j3").toString());
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(
                Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            sink.addRow(RowDiff.onlyA("k9", Status.SECTION_DATA, new String[]{"k9", "1"}));
        }
        Path out = dir.resolve("out").resolve("CASENICK_差异.xlsx");
        CollectCaseExcel.write(out, job, null, "", "");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(out)))) {
            Sheet sh = wb.getSheet("data");
            Row r1 = sh.getRow(1);
            String date = r1.getCell(0).getStringCellValue();
            assertEquals(8, date.length()); // 生成采集表当日（自然日，YYYYMMDD）
            assertTrue(date.matches("\\d{8}"), () -> "非自然日格式: " + date);
            assertEquals("主机记录在信创未找到", r1.getCell(9).getStringCellValue());
            assertEquals("A：k9    B：(无)", r1.getCell(10).getStringCellValue()); // 缺省别名 A/B
            assertCaseId(r1.getCell(20).getStringCellValue());
        }
    }

    @Test
    void 无差异_仅表头文件(@TempDir Path dir) throws Exception {
        JobRecord job = new JobRecord("j2", null, "EMPTYNICK", "EMPTYNICK:e*.txt",
                "/x/e.dat", "/y/e.dat", dir.resolve("results").resolve("j2").toString());
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(
                Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            sink.addRow(RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "1"}));
        }
        Path out = dir.resolve("out").resolve("EMPTYNICK_差异.xlsx");
        CollectCaseExcel.write(out, job, null, "", "");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(out)))) {
            Sheet sh = wb.getSheet("data");
            assertNotNull(sh.getRow(0));
            assertEquals(CollectCaseExcel.HEADERS[7], sh.getRow(0).getCell(7).getStringCellValue());
            assertNull(sh.getRow(1));
        }
    }
}
