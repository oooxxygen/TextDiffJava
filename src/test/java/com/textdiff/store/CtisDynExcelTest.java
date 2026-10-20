package com.textdiff.store;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CTIS 动态栏位配置 Excel 解析（BXXF_BUFFER 区域说明结构）。 */
class CtisDynExcelTest {

    /** 构造与附件同构的 xlsx：sheet1 适用清单（无表头）+ sheet2 子栏位明细（带表头）。 */
    private static byte[] build(boolean sheet1Header, boolean sheet2Header, List<String[]> sheet1,
                                List<String[]> sheet2) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var s1 = wb.createSheet("sheet1");
            int r = 0;
            if (sheet1Header) {
                var hr = s1.createRow(r++);
                hr.createCell(0).setCellValue("适用文本名");
                hr.createCell(1).setCellValue("动态栏位名");
                hr.createCell(2).setCellValue("动态栏位编号");
            }
            for (String[] row : sheet1) {
                var xr = s1.createRow(r++);
                for (int c = 0; c < row.length; c++) xr.createCell(c).setCellValue(row[c]);
            }
            var s2 = wb.createSheet("sheet2");
            r = 0;
            if (sheet2Header) {
                var hr = s2.createRow(r++);
                hr.createCell(0).setCellValue("数据项名称");
                hr.createCell(1).setCellValue("主键");
                hr.createCell(2).setCellValue("数据类型/长度");
                hr.createCell(3).setCellValue("分隔符\n字段编号");
                hr.createCell(4).setCellValue("对齐补位");
                hr.createCell(5).setCellValue("数据字段描述");
                hr.createCell(6).setCellValue("备注以及相关说明");
            }
            for (String[] row : sheet2) {
                var xr = s2.createRow(r++);
                for (int c = 0; c < row.length; c++) xr.createCell(c).setCellValue(row[c]);
            }
            var bos = new java.io.ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static final List<String[]> SHEET1 = List.of(
            new String[]{"BPAF", "107", "DYNAMIC_MESSAGE_01"},
            new String[]{"BCAF", "107", "DYNAMIC_MESSAGE_01"});

    private static final List<String[]> SHEET2 = List.of(
            new String[]{"终端号", "否", "X(8)", "01", "右补空格", "终端号", "核心使用"},
            new String[]{"交易真实日期", "否", "X(8)", "1B", "右补空格", "YYYYMMDD", "核心使用"},
            new String[]{"交易真实时间", "否", "X(8)", "1C", "", "HHMMSSHSS", "核心使用"});

    @Test
    void 解析_附件结构_无表头sheet1带表头sheet2(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("区域说明.xlsx");
        Files.write(f, build(false, true, SHEET1, SHEET2));
        CtisDynFields.Snapshot s = CtisDynExcel.parse(Files.readAllBytes(f), "区域说明.xlsx");
        assertEquals(2, s.applies().size());
        assertEquals(3, s.items().size());
        assertTrue(s.warnings().isEmpty());

        CtisDynFields.Apply a0 = s.applies().get(0);
        assertEquals("BPAF", a0.textName());
        assertEquals("107", a0.colSeq());
        assertEquals("DYNAMIC_MESSAGE_01", a0.dynName());

        // sheet2 绑定唯一关联行名称
        CtisDynFields.Item i1b = s.items().stream().filter(i -> "1B".equals(i.itemCode()))
                .findFirst().orElseThrow();
        assertEquals("DYNAMIC_MESSAGE_01", i1b.dynName());
        assertEquals("交易真实日期", i1b.itemName());
        assertEquals("X(8)", i1b.typeLen());
        assertEquals("右补空格", i1b.padAlign());
        assertEquals("YYYYMMDD", i1b.description());
    }

    @Test
    void 解析_两sheet均无表头亦可(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("x.xlsx");
        Files.write(f, build(false, false, SHEET1, SHEET2));
        CtisDynFields.Snapshot s = CtisDynExcel.parse(Files.readAllBytes(f), "x.xlsx");
        // 无表头时 sheet2 首行是数据（终端号/01 是合法行）
        assertEquals(3, s.items().size());
    }

    @Test
    void 解析_多个关联行名称拒绝(@TempDir Path dir) throws Exception {
        List<String[]> two = List.of(
                new String[]{"BPAF", "107", "DYNAMIC_MESSAGE_01"},
                new String[]{"CCAF", "105", "DYNAMIC_MESSAGE_02"});
        Path f = dir.resolve("x.xlsx");
        Files.write(f, build(false, true, two, SHEET2));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CtisDynExcel.parse(Files.readAllBytes(f), "x.xlsx"));
        assertTrue(e.getMessage().contains("只允许一个关联行名称"));
    }

    @Test
    void 解析_非法编号告警跳过_重复编号保留首行(@TempDir Path dir) throws Exception {
        List<String[]> bad = List.of(
                new String[]{"A", "否", "X(8)", "1B", "", "d", ""},
                new String[]{"B", "否", "X(8)", "1XY", "", "d", ""},   // 3 位 → 非法
                new String[]{"C", "否", "X(8)", "1B", "", "d", ""});   // 重复
        Path f = dir.resolve("x.xlsx");
        Files.write(f, build(false, true, SHEET1, bad));
        CtisDynFields.Snapshot s = CtisDynExcel.parse(Files.readAllBytes(f), "x.xlsx");
        assertEquals(1, s.items().size());
        assertEquals(2, s.warnings().size());
        assertTrue(s.warnings().get(0).contains("1XY"));
        assertTrue(s.warnings().get(1).contains("重复"));
    }

    @Test
    void 解析_数字单元格按整数读取(@TempDir Path dir) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var s1 = wb.createSheet("s1");
            var row = s1.createRow(0);
            row.createCell(0).setCellValue("BPAF");
            row.createCell(1).setCellValue(107);   // 数字单元格
            row.createCell(2).setCellValue("DYNAMIC_MESSAGE_01");
            var s2 = wb.createSheet("s2");
            var hr = s2.createRow(0);
            hr.createCell(0).setCellValue("数据项名称");
            var r2 = s2.createRow(1);
            r2.createCell(0).setCellValue("交易真实日期");
            r2.createCell(3).setCellValue("1B");
            var bos = new java.io.ByteArrayOutputStream();
            wb.write(bos);
            CtisDynFields.Snapshot s = CtisDynExcel.parse(bos.toByteArray(), "x.xlsx");
            assertEquals("107", s.applies().get(0).colSeq());
        }
    }

    @Test
    void 解析_缺第二个sheet报错(@TempDir Path dir) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var s1 = wb.createSheet("s1");
            var row = s1.createRow(0);
            row.createCell(0).setCellValue("BPAF");
            row.createCell(1).setCellValue("107");
            row.createCell(2).setCellValue("D1");
            var bos = new java.io.ByteArrayOutputStream();
            wb.write(bos);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CtisDynExcel.parse(bos.toByteArray(), "x.xlsx"));
            assertTrue(e.getMessage().contains("2 个 sheet"));
        }
    }
}
