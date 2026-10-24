package com.textdiff.store;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对比配置分组 Excel 导入解析。
 *
 * 列结构（表头行自动识别，同义表头容错；无表头时按列序 A/B 处理）：
 *   分组名称/分组/组名 | 文本昵称/昵称/文本名称/表名
 *
 * 一行一个成员，同分组多行自动聚合；成员单元格内顿号/逗号/分号/竖线分隔的多个昵称一并拆分。
 * 非法行（分组名空或成员空）告警跳过不阻断导入。
 */
public final class CompareGroupExcel {
    private CompareGroupExcel() {}

    private static final Pattern HEADER_HINT = Pattern.compile("(?i)分组|组名");
    private static final Pattern GROUP_HINT = Pattern.compile("(?i)分组名称|分组名|分组|组名|名称");
    private static final Pattern MEMBER_HINT = Pattern.compile("(?i)文本昵称|昵称|文本名称|表名|文本|配置昵称");

    /** 成员单元格内多值分隔符：顿号/全半角逗号/分号/竖线/斜杠。 */
    private static final Pattern SPLIT = Pattern.compile("[、,，;；|/]+");

    public record Parsed(List<CompareGroup> groups, List<String> warnings) {}

    public static Parsed parse(byte[] content, String sourceName) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IllegalArgumentException("Excel 无数据 sheet");
            return parseSheet(sheet);
        } catch (IOException e) {
            throw new IllegalArgumentException("Excel 解析失败（需 .xlsx）: " + e.getMessage(), e);
        }
    }

    private static Parsed parseSheet(Sheet sheet) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int groupCol = 0, memberCol = 1;
        for (Row row : sheet) {
            String c0 = cellText(row.getCell(0));
            String c1 = cellText(row.getCell(1));
            if ((c0 == null || c0.isBlank()) && (c1 == null || c1.isBlank())) continue;
            if (isHeaderLike(c0)) {
                int[] cols = mapHeader(row);
                if (cols != null) {
                    groupCol = cols[0];
                    memberCol = cols[1];
                }
                continue;
            }
            String group = cellText(row.getCell(groupCol));
            String member = cellText(row.getCell(memberCol));
            int line = row.getRowNum() + 1;
            if (group == null || group.isBlank()) {
                if (member != null && !member.isBlank()) {
                    warnings.add("第 " + line + " 行分组名称为空，已跳过");
                }
                continue;
            }
            if (member == null || member.isBlank()) {
                warnings.add("第 " + line + " 行（" + group.strip() + "）文本昵称为空，已跳过");
                continue;
            }
            List<String> members = byName.computeIfAbsent(group.strip(), k -> new ArrayList<>());
            for (String m : SPLIT.split(member)) {
                String t = m.strip();
                if (!t.isEmpty() && !members.contains(t)) members.add(t);
            }
        }
        List<CompareGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byName.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            out.add(new CompareGroup(e.getKey(), List.copyOf(e.getValue()), null, 0));
        }
        return new Parsed(out, warnings);
    }

    /** 表头列定位：分组列 + 成员列；两列均未命中返回 null（沿用当前列序）。 */
    private static int[] mapHeader(Row row) {
        int groupCol = -1, memberCol = -1;
        for (Cell cell : row) {
            String v = cellText(cell);
            if (v == null || v.isBlank()) continue;
            if (groupCol < 0 && GROUP_HINT.matcher(v.strip()).matches()) groupCol = cell.getColumnIndex();
            else if (memberCol < 0 && MEMBER_HINT.matcher(v.strip()).matches()) memberCol = cell.getColumnIndex();
        }
        return groupCol >= 0 && memberCol >= 0 ? new int[]{groupCol, memberCol} : null;
    }

    private static boolean isHeaderLike(String v) {
        return HEADER_HINT.matcher(v).find();
    }

    /** 单元格文本（数值取整防 "3.0"，布尔转是/否，其余按字符串取值）。 */
    private static String cellText(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> cell.getBooleanCellValue() ? "是" : "否";
            case STRING -> cell.getStringCellValue() == null ? null : cell.getStringCellValue().strip();
            default -> null;
        };
    }
}
