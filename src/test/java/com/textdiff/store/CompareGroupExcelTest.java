package com.textdiff.store;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 对比配置分组 Excel 解析：两列表头（同义容错）+ 多行聚合 + 单元格多值拆分 + 告警。 */
class CompareGroupExcelTest {

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
    void parsesRowsAndAggregatesGroups() throws Exception {
        CompareGroupExcel.Parsed p = CompareGroupExcel.parse(xlsx(new Object[][]{
                {"分组名称", "文本昵称"},
                {"日常对比配置", "INXH"},
                {"日常对比配置", "INVM"},
                {"日常对比配置", "INVM_FACC"},
                {"月结", "INCT0101"},
        }), "groups.xlsx");
        assertEquals(2, p.groups().size());
        CompareGroup daily = p.groups().get(0);
        assertEquals("日常对比配置", daily.name());
        assertEquals(List.of("INXH", "INVM", "INVM_FACC"), daily.members());
        assertEquals(List.of("INCT0101"), p.groups().get(1).members());
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void synonymHeaderMultiValueCellAndWarnings() throws Exception {
        CompareGroupExcel.Parsed p = CompareGroupExcel.parse(xlsx(new Object[][]{
                {"组名", "表名"},
                {"G1", "A、B，C；D"},   // 单元格多值拆分 + 同组重复成员去重
                {"G1", "B"},
                {"", "X"},             // 分组名为空 → 告警
                {"G2", ""},            // 成员为空 → 告警
        }), "groups.xlsx");
        assertEquals(1, p.groups().size());
        assertEquals(List.of("A", "B", "C", "D"), p.groups().get(0).members());
        assertEquals(2, p.warnings().size());
    }

    @Test
    void headerlessDefaultsToFirstTwoColumns() throws Exception {
        CompareGroupExcel.Parsed p = CompareGroupExcel.parse(xlsx(new Object[][]{
                {"G1", "A"},
                {"G1", "B"},
        }), "groups.xlsx");
        assertEquals(1, p.groups().size());
        assertEquals(List.of("A", "B"), p.groups().get(0).members());
    }
}
