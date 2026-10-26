package com.textdiff.export;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 批次总结 Excel：一个分区一个 Sheet（概览 / 偏离度双指标 / 关注字段监测），表头与取值语义。 */
class BatchSummaryExcelTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** 概览 Sheet 按第一列标签取值。 */
    private static String cellValue(Sheet sh, String label) {
        for (Row r : sh) {
            if (label.equals(r.getCell(0).getStringCellValue())) return r.getCell(1).getStringCellValue();
        }
        return null;
    }

    private static Map<String, Object> payload() {
        return map(
                "batch_id", "ab12cd34", "label", "日常对比", "batch_type", "file", "status", "done",
                "total_files", 2, "diff_files", 1, "done", 2, "failed", 0,
                "no_rule_count", 0, "missing_count", 0,
                "src_a", "旧线", "src_b", "新线",
                "dir_a", "O:\\A", "dir_b", "O:\\B",
                "group_name", "日常对比配置",
                "generated_at", 1770000200L,
                "deviations", List.of(map(
                        "nickname", "INCT0101", "group", "卡中心", "file_name", "01A3020D.v01",
                        "content_ratio", 0.25, "content_grade", "务必关注",
                        "count_ratio", 0.5, "count_grade", "严重问题",
                        "equal", 6, "diff", 2, "only_a", 0, "only_b", 0,
                        "total_a", 8, "total_b", 12)),
                "discard", map("hit", true, "src_a", "旧线", "src_b", "新线", "status", "hit",
                        "rows", List.of(map(
                                "nickname", "INCT0101", "group", "卡中心", "file_name", "01A3020D.v01",
                                "col_seq", 27, "field_name", "MERCH_COUNTRY_CODE", "field_meaning", "国家码",
                                "values_a", List.of("CN", "", "US"), "values_b", List.of("CN", "US"),
                                "truncated_a", false, "truncated_b", false, "file_missing", false,
                                "matched", true))));
    }

    @Test
    void rendersThreeSheetsWithDeviationAndDiscard() throws Exception {
        byte[] bytes = BatchSummaryExcel.render(payload());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(3, wb.getNumberOfSheets());
            assertEquals("批次概览", wb.getSheetName(0));
            assertEquals("偏离度", wb.getSheetName(1));
            assertEquals("关注字段监测", wb.getSheetName(2));

            Sheet ov = wb.getSheet("批次概览");
            assertEquals("批次ID", ov.getRow(0).getCell(0).getStringCellValue());
            assertEquals("ab12cd34", ov.getRow(0).getCell(1).getStringCellValue());
            // 状态/类型中文化 + 对比范围（分组）
            assertEquals("目录对比", cellValue(ov, "批次类型"));
            assertEquals("已完成", cellValue(ov, "状态"));
            assertEquals("分组「日常对比配置」（仅分组成员命中的文本）", cellValue(ov, "对比范围"));

            Sheet dev = wb.getSheet("偏离度");
            Row head = dev.getRow(0);
            assertEquals("表名(昵称)", head.getCell(0).getStringCellValue());
            assertEquals("内容偏离度", head.getCell(3).getStringCellValue());
            assertEquals("条数偏离度", head.getCell(5).getStringCellValue());
            Row r1 = dev.getRow(1);
            assertEquals("INCT0101", r1.getCell(0).getStringCellValue());
            assertEquals("卡中心", r1.getCell(1).getStringCellValue());
            assertEquals("25.0%", r1.getCell(3).getStringCellValue());
            assertEquals("务必关注", r1.getCell(4).getStringCellValue());
            assertEquals("50.0%", r1.getCell(5).getStringCellValue());
            assertEquals("严重问题", r1.getCell(6).getStringCellValue());
            assertEquals("8", r1.getCell(11).getStringCellValue());

            Sheet disc = wb.getSheet("关注字段监测");
            assertEquals("旧线的所有取值", disc.getRow(0).getCell(6).getStringCellValue());
            assertEquals("新线的所有取值", disc.getRow(0).getCell(7).getStringCellValue());
            assertEquals("是否匹配", disc.getRow(0).getCell(8).getStringCellValue());
            assertEquals("CN、（空）、US", disc.getRow(1).getCell(6).getStringCellValue());
            assertEquals("27", disc.getRow(1).getCell(3).getStringCellValue());
            assertEquals("新线输出正常", disc.getRow(1).getCell(8).getStringCellValue());
        }
    }

    @Test
    void noHitDiscardSheetRendersNote() throws Exception {
        Map<String, Object> p = payload();
        p.put("deviations", List.of());
        p.put("discard", map("hit", false, "status", "no_hit"));
        byte[] bytes = BatchSummaryExcel.render(p);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet dev = wb.getSheet("偏离度");
            assertEquals("表名(昵称)", dev.getRow(0).getCell(0).getStringCellValue());
            assertNull(dev.getRow(1)); // 无偏离行
            Sheet disc = wb.getSheet("关注字段监测");
            assertTrue(disc.getRow(1) == null || disc.getRow(1).getCell(0).getStringCellValue().isEmpty()
                    || disc.getRow(1).getCell(0).getStringCellValue().contains("无可监测"));
        }
    }
}
