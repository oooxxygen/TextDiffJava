package com.textdiff.export;

import com.textdiff.engine.Summary;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 批次明细 Excel：Sheet1 对比总览（数量+对比配置）、Sheet2 差异栏位（表名/列号/列名/备注）。 */
class BatchDetailExcelTest {

    @Test
    void writesOverviewAndDiffColumns(@TempDir Path dir) throws Exception {
        // 作业1：有结果数据；作业2：无结果目录（容错占位）
        Path rdir = dir.resolve("results").resolve("j1");
        Files.createDirectories(rdir);
        Summary s = new Summary();
        s.totalA = 100;
        s.totalB = 98;
        s.equal = 40;
        s.diff = 55;
        s.onlyA = 3;
        s.onlyB = 2;
        s.diffColFreq.put(2, 50L);
        s.diffColFreq.put(0, 5L);
        ResultFiles.writeSummary(rdir, s);

        JobRecord j1 = new JobRecord("j1", "b1", "INCT0101 · 01A3020D.v01",
                "INCT0101:*.v01:KEYSEQ=3/4/5:OMITSEQ=1/2:DELIM= | :ENCA=auto:ENCB=auto:SRCA=A:SRCB=B:TRAILER=|||||",
                "a.v01", "b.v01", rdir.toAbsolutePath().toString());
        JobRecord j2 = new JobRecord("j2", "b1", "NORESULT · x.txt",
                "NORESULT:*.txt:KEYSEQ=1:DELIM= | :ENCA=auto:ENCB=auto:SRCA=A:SRCB=B:TRAILER=|||||",
                "a.txt", "b.txt", dir.resolve("missing").toString());

        Path out = dir.resolve("批次明细.xlsx");
        BatchDetailExcel.write(out, java.util.List.of(j1, j2), null);

        try (Workbook wb = new XSSFWorkbook(out.toFile())) {
            assertEquals("对比总览", wb.getSheetName(0));
            assertEquals("差异栏位", wb.getSheetName(1));

            Sheet ov = wb.getSheet("对比总览");
            Row head = ov.getRow(0);
            assertEquals("表名昵称", head.getCell(0).getStringCellValue());
            assertEquals("键值匹配有差异", head.getCell(4).getStringCellValue());
            assertEquals("状态情况", head.getCell(7).getStringCellValue());
            assertEquals("总体评判", head.getCell(8).getStringCellValue());
            assertEquals("对比配置（列号+名称）", head.getCell(9).getStringCellValue());
            Row r1 = ov.getRow(1);
            assertEquals("INCT0101 · 01A3020D.v01", r1.getCell(0).getStringCellValue());
            assertEquals(100, (long) r1.getCell(1).getNumericCellValue());
            assertEquals(98, (long) r1.getCell(2).getNumericCellValue());
            assertEquals(40, (long) r1.getCell(3).getNumericCellValue());
            assertEquals(55, (long) r1.getCell(4).getNumericCellValue());
            assertEquals(3, (long) r1.getCell(5).getNumericCellValue());
            assertEquals(2, (long) r1.getCell(6).getNumericCellValue());
            assertTrue(r1.getCell(7).getStringCellValue().contains("数量差 2"));
            assertEquals("正常", r1.getCell(8).getStringCellValue()); // |100-98|/(198/2)≈2%
            String cfg1 = r1.getCell(9).getStringCellValue();
            assertTrue(cfg1.contains("主键 KEYSEQ="), cfg1);
            assertTrue(cfg1.contains("共 3 列"), cfg1);
            assertTrue(cfg1.contains("第3列"), cfg1);
            assertTrue(cfg1.contains("跳过 OMITSEQ="), cfg1);
            assertTrue(cfg1.contains("分隔符"), cfg1);
            Row r2 = ov.getRow(2);
            assertEquals("NORESULT · x.txt", r2.getCell(0).getStringCellValue());
            assertEquals("（无结果数据）", r2.getCell(1).getStringCellValue());

            Sheet dc = wb.getSheet("差异栏位");
            assertEquals("表名昵称", dc.getRow(0).getCell(0).getStringCellValue());
            assertEquals("列号", dc.getRow(0).getCell(1).getStringCellValue());
            assertEquals("列名", dc.getRow(0).getCell(2).getStringCellValue());
            assertEquals("备注", dc.getRow(0).getCell(3).getStringCellValue());
            // 列号升序（1-based）：col0 → 1，col2 → 3；无映射时列名回落"栏位N"；备注空
            Row d1 = dc.getRow(1);
            assertEquals("INCT0101 · 01A3020D.v01", d1.getCell(0).getStringCellValue());
            assertEquals(1, (long) d1.getCell(1).getNumericCellValue());
            assertEquals("栏位1", d1.getCell(2).getStringCellValue());
            assertEquals("", d1.getCell(3).getStringCellValue());
            Row d2 = dc.getRow(2);
            assertEquals(3, (long) d2.getCell(1).getNumericCellValue());
            assertEquals("栏位3", d2.getCell(2).getStringCellValue());
            assertEquals(2, dc.getLastRowNum(), "仅作业1有差异栏位（2行数据）");
        }
    }
}
