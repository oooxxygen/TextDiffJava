package com.textdiff.store;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 关注字段监测 Excel 解析：真实附件表头（文本名称/栏位/栏位名/含义）+ 同义表头 + 无表头 + 坏序号告警。 */
class DiscardExcelTest {

    private static byte[] xlsx(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("sheet1");
            for (int ri = 0; ri < rows.length; ri++) {
                var r = sheet.createRow(ri);
                Object[] row = rows[ri];
                for (int i = 0; i < row.length; i++) {
                    Object v = row[i];
                    if (v instanceof Number n) r.createCell(i).setCellValue(n.doubleValue());
                    else if (v != null) r.createCell(i).setCellValue(String.valueOf(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void parsesRealAttachmentHeader() throws Exception {
        DiscardExcel.Parsed p = DiscardExcel.parse(xlsx(new Object[][]{
                {"文本名称", "栏位", "栏位名", "含义"},
                {"INCT0101", 27, "MERCH_COUNTRY_CODE", "国家码"},
                {"INCT0101", 44, "BREAK_RULE_IND_BEF_IMG", "银行信息"},
        }), "DISCARD.xlsx");
        assertEquals(2, p.fields().size());
        assertEquals("INCT0101", p.fields().get(0).nickname());
        assertEquals(27, p.fields().get(0).colSeq());
        assertEquals("MERCH_COUNTRY_CODE", p.fields().get(0).fieldName());
        assertEquals("国家码", p.fields().get(0).fieldMeaning());
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void synonymHeaderAndBadSeqWarned() throws Exception {
        DiscardExcel.Parsed p = DiscardExcel.parse(xlsx(new Object[][]{
                {"昵称", "字段序号", "字段名", "字段含义"},
                {"N1", "3", "F1", "M1"},      // 字符串序号可解析
                {"N1", "abc", "F2", "M2"},    // 坏序号 → 告警跳过
                {"N2", 0, "F3", "M3"},        // 序号 <1 → 告警跳过
                {"N1", "3", "F1d", "M1d"},    // 同昵称同栏位重复 → 去重保留首行
        }), "DISCARD.xlsx");
        assertEquals(1, p.fields().size());
        assertEquals(3, p.fields().get(0).colSeq());
        assertEquals("F1", p.fields().get(0).fieldName());
        assertEquals(2, p.warnings().size());
    }

    @Test
    void headerlessAssumesColumnOrder() throws Exception {
        DiscardExcel.Parsed p = DiscardExcel.parse(xlsx(new Object[][]{
                {"X1", 2, "FX", "MX"},
        }), "DISCARD.xlsx");
        assertEquals(1, p.fields().size());
        assertEquals("X1", p.fields().get(0).nickname());
        assertEquals(2, p.fields().get(0).colSeq());
    }
}
