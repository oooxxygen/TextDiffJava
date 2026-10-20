package com.textdiff.store;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CTIS 动态栏位配置 Excel 解析（BXXF_BUFFER 区域说明.xlsx）。
 *
 * sheet1：适用文本名 | 关联行序号（栏位定位，数字或栏位名）| 关联行名称（动态栏位逻辑名）。
 * sheet2：数据项名称 | 主键 | 数据类型/长度 | 字段编号（'~~' 后 2 位编号）| 对齐补位 |
 *         数据字段描述 | 备注以及相关说明。
 *
 * 约束：一个文件只允许一个关联行名称（sheet2 无归属列，多名称无法区分归属）；
 * 编号必须为 2 位字母数字（否则告警跳过该行）。表头行自动识别跳过，两 sheet 均可带/不带头。
 */
public final class CtisDynExcel {
    private CtisDynExcel() {}

    /** 附件 sheet2 表头（第 4 列标题内嵌换行「分隔符\n字段编号」）。 */
    private static final Pattern HEADER_HINT = Pattern.compile("(?i)数据项名称|适用文本名");
    private static final Pattern CODE2 = Pattern.compile("[0-9A-Za-z]{2}");

    public static CtisDynFields.Snapshot parse(byte[] content, String sourceName) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (wb.getNumberOfSheets() < 2) {
                throw new IllegalArgumentException("Excel 需包含 2 个 sheet：sheet1 适用清单、sheet2 子栏位明细");
            }
            List<CtisDynFields.Apply> applies = parseSheet1(wb.getSheetAt(0));
            ParsedItems pi = parseSheet2(wb.getSheetAt(1));

            // sheet1 的关联行名称必须唯一，且 sheet2 绑定该名称
            Set<String> names = new LinkedHashSet<>();
            for (CtisDynFields.Apply a : applies) names.add(a.dynName());
            if (names.size() > 1) {
                throw new IllegalArgumentException("一个文件只允许一个关联行名称（动态栏位编号），当前出现: "
                        + String.join("、", names) + "；请拆分文件分次导入");
            }
            String dynName = names.isEmpty() ? "" : names.iterator().next();
            List<String> warnings = new ArrayList<>(pi.warnings);
            if (applies.isEmpty()) warnings.add("sheet1 无有效行（需：适用文本名/关联行序号/关联行名称）");
            if (pi.items.isEmpty()) warnings.add("sheet2 无有效行（需含 2 位字母数字字段编号）");
            List<CtisDynFields.Item> items = new ArrayList<>();
            for (CtisDynFields.Item r : pi.items) {
                items.add(new CtisDynFields.Item(dynName, r.itemCode(), r.itemName(), r.primaryKey(),
                        r.typeLen(), r.padAlign(), r.description(), r.remark(), r.sourceFile(), r.importedAt()));
            }
            return new CtisDynFields.Snapshot(applies, items, warnings);
        } catch (IOException e) {
            throw new IllegalArgumentException("Excel 解析失败（需 .xlsx）: " + e.getMessage(), e);
        }
    }

    private record ParsedItems(List<CtisDynFields.Item> items, List<String> warnings) {}

    private static List<CtisDynFields.Apply> parseSheet1(Sheet sheet) {
        List<CtisDynFields.Apply> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Row row : sheet) {
            String c0 = cellText(row.getCell(0));
            if (c0 == null || c0.isBlank()) continue;
            if (isHeader(c0)) continue;
            String colSeq = cellText(row.getCell(1));
            String dynName = cellText(row.getCell(2));
            if (colSeq == null || colSeq.isBlank() || dynName == null || dynName.isBlank()) {
                throw new IllegalArgumentException("sheet1 第 " + (row.getRowNum() + 1)
                        + " 行不完整：需 适用文本名 / 关联行序号 / 关联行名称 三列（当前: "
                        + c0 + " / " + nvl(colSeq) + " / " + nvl(dynName) + "）");
            }
            String key = c0.strip() + "#" + colSeq.strip();
            if (!seen.add(key)) continue; // 同文本同栏位重复行去重
            out.add(new CtisDynFields.Apply(c0.strip(), colSeq.strip(), dynName.strip(), null, 0));
        }
        return out;
    }

    private static ParsedItems parseSheet2(Sheet sheet) {
        List<CtisDynFields.Item> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Row row : sheet) {
            String c0 = cellText(row.getCell(0));
            if (c0 == null || c0.isBlank()) continue;
            if (isHeader(c0)) continue;
            String code = cellText(row.getCell(3));
            if (code == null || !CODE2.matcher(code.strip()).matches()) {
                warnings.add("sheet2 第 " + (row.getRowNum() + 1) + " 行（" + c0.strip()
                        + "）字段编号非法: " + nvl(code) + "（需 2 位字母数字），已跳过");
                continue;
            }
            if (!seen.add(code.strip())) {
                warnings.add("sheet2 字段编号 " + code.strip() + "（" + c0.strip() + "）重复，保留首行");
                continue;
            }
            out.add(new CtisDynFields.Item(null, code.strip(), c0.strip(),
                    cellText(row.getCell(1)),   // 主键
                    cellText(row.getCell(2)),   // 数据类型/长度
                    cellText(row.getCell(4)),   // 对齐补位
                    cellText(row.getCell(5)),   // 数据字段描述
                    cellText(row.getCell(6)),   // 备注以及相关说明
                    null, 0));
        }
        return new ParsedItems(out, warnings);
    }

    private static boolean isHeader(String firstCell) {
        return HEADER_HINT.matcher(firstCell).find();
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "（空）" : s.strip();
    }

    /** 单元格文本（数字按整数值展示，避免 107 → "107.0"；空白返回 null）。 */
    private static String cellText(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        String v = new DataFormatter().formatCellValue(cell);
        return v == null || v.isBlank() ? null : v.strip();
    }
}
