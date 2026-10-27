package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 差异采集对比案例 Excel（CollectComparisonCaseTemplate 20 列）：列取数规则与行粒度。 */
class CollectCaseExcelTest {

    /** 造作业：k1 相等（不采集）、k2 双栏位差异（H=2/3 两行）、k3 仅 A（H=0）、尾部含 DataStartDate。 */
    private static JobRecord jobWithRows(Path dir, String fileA, String configLine) throws Exception {
        JobRecord job = new JobRecord("j1", "b1", "CASENICK", configLine, fileA, "/data/b/B_INCT.dat",
                dir.resolve("results").resolve("j1").toString());
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(
                Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            sink.addRow(RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "CN", "100"}));
            sink.addRow(new RowDiff("k2", Status.DIFF, Status.SECTION_DATA,
                    new String[]{"k2", "CN", "ICBC"}, new String[]{"k2", "US", "ICBC"}, new int[]{1, 2}));
            sink.addRow(RowDiff.onlyA("k3", Status.SECTION_DATA, new String[]{"k3", "JP"}));
            sink.addRow(new RowDiff("TRX", Status.EQUAL, Status.SECTION_TRAILER, null, null, new int[0],
                    "TRX_DATE=20251001|DataStartDate=20250901", null));
        }
        return job;
    }

    @Test
    void 全列取数_差异逐栏位_单方零索引(@TempDir Path dir) throws Exception {
        JobRecord job = jobWithRows(dir, "/data/a/INCT0101_20250916.dat",
                "CASENICK:01A***0D.v01 | KEYSEQ=1");
        BatchRecord batch = new BatchRecord("b1", "dirA vs dirB", "/data/a", "/data/b", "ut", 1);
        batch.cmpBatch = "P2703";
        batch.cmpRound = "2";
        Path out = dir.resolve("out").resolve("CASENICK_差异.xlsx");
        CollectCaseExcel.write(out, job, batch, "原表", "核心");

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            assertEquals(1, wb.getNumberOfSheets());
            Sheet sh = wb.getSheet("data");
            Row head = sh.getRow(0);
            for (int i = 0; i < CollectCaseExcel.HEADERS.length; i++) {
                assertEquals(CollectCaseExcel.HEADERS[i], head.getCell(i).getStringCellValue(), "表头列" + i);
            }
            // 行1：k2 栏位2（0-based 1）差异——日期取文件名后缀，批次/轮次来自提交指定，通配符/扩展名来自配置
            Row r1 = sh.getRow(1);
            assertEquals("20250916", r1.getCell(0).getStringCellValue());
            assertEquals("P2703", r1.getCell(1).getStringCellValue());
            assertEquals("2", r1.getCell(2).getStringCellValue());
            assertEquals("***", r1.getCell(3).getStringCellValue());
            assertEquals("原表", r1.getCell(4).getStringCellValue());
            assertEquals("CASENICK", r1.getCell(5).getStringCellValue());
            assertEquals("dat", r1.getCell(6).getStringCellValue()); // 实际文件扩展名优先于通配名
            assertEquals(2.0, r1.getCell(7).getNumericCellValue(), 1e-9);
            assertEquals("A:'CN' B:'US'", r1.getCell(8).getStringCellValue());
            // 行2：k2 栏位3 差异（同记录第二个差异栏位独立成行，标识列重复）
            Row r2 = sh.getRow(2);
            assertEquals(3.0, r2.getCell(7).getNumericCellValue(), 1e-9);
            assertEquals("A:'ICBC' B:'ICBC'", r2.getCell(8).getStringCellValue());
            assertEquals("P2703", r2.getCell(1).getStringCellValue());
            // 行3：k3 仅 A——栏位索引 0，错误类型带整行原貌（raw 缺省按分隔符拼接）
            Row r3 = sh.getRow(3);
            assertEquals(0.0, r3.getCell(7).getNumericCellValue(), 1e-9);
            assertEquals("A:'k3 | JP'", r3.getCell(8).getStringCellValue());
            assertEquals("核心", r3.getCell(12).getStringCellValue());
            assertNull(sh.getRow(4)); // 相等记录与尾部区不采集，恰好 3 行
        }
    }

    @Test
    void 文件名无日期回退尾部DataStartDate_无批次留空_命名通配符(@TempDir Path dir) throws Exception {
        JobRecord job = jobWithRows(dir, "/data/a/B_INCT.dat", "CASENICK:01A#BANKNO.dat");
        Path out = dir.resolve("out").resolve("CASENICK_差异.xlsx");
        CollectCaseExcel.write(out, job, null, null, null);

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            Sheet sh = wb.getSheet("data");
            Row r1 = sh.getRow(1);
            assertEquals("20250901", r1.getCell(0).getStringCellValue()); // 尾部 DataStartDate 兜底
            assertEquals("", r1.getCell(1).getStringCellValue());          // 无批次 → 批次/轮次空
            assertEquals("", r1.getCell(2).getStringCellValue());
            assertEquals("#BANKNO", r1.getCell(3).getStringCellValue());
            assertEquals("", r1.getCell(4).getStringCellValue());          // 未导入 type_parm → 空
            assertEquals("dat", r1.getCell(6).getStringCellValue());       // 实际文件扩展名
            assertEquals("", r1.getCell(12).getStringCellValue());
        }
    }

    @Test
    void 通配符_扩展名_日期提取_边界() {
        assertEquals("***", CollectCaseExcel.wildcardOf("01A***0D.v01"));
        assertEquals("*", CollectCaseExcel.wildcardOf("01A*0D.v01"));
        assertEquals("#BANKNO", CollectCaseExcel.wildcardOf("01A#BANKNO.dat"));
        assertEquals("#BANKNO", CollectCaseExcel.wildcardOf("01A#BANKNO_***.dat")); // 命名占位优先
        assertEquals("", CollectCaseExcel.wildcardOf("FIXED01D.v01"));
        assertEquals("", CollectCaseExcel.wildcardOf(""));

        assertEquals("v01", CollectCaseExcel.extOf("/x/ABC_20250916.v01", "01A***0D.v01"));
        assertEquals("v01", CollectCaseExcel.extOf("/x/ABC", "01A***0D.v01")); // 实际无扩展 → 回退通配名
        assertEquals("dat", CollectCaseExcel.extOf("/x/B_INCT.dat", "01A#BANKNO"));
        assertEquals("", CollectCaseExcel.extOf("/x/ABC", "ABC"));

        assertEquals("20250912", CollectCaseExcel.dateFromName("XX_INCT_20250912.dat"));
        assertEquals("20250912", CollectCaseExcel.dateFromName("a_20250913_20250912.dat")); // 取最后一段
        assertEquals("", CollectCaseExcel.dateFromName("ABC_20251399.dat"));                // 非法月日不误判
        assertEquals("", CollectCaseExcel.dateFromName("ABC.dat"));
        assertEquals("", CollectCaseExcel.dateFromName(""));
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
            Cell h7 = sh.getRow(0).getCell(7);
            assertEquals(CollectCaseExcel.HEADERS[7], h7.getCellType() == CellType.STRING
                    ? h7.getStringCellValue() : "");
            assertNull(sh.getRow(1));
        }
    }
}
