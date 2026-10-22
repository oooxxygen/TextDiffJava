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
 * 关注字段监测 Excel 解析（DISCARD.xlsx）。
 *
 * 列结构（表头行自动识别，同义表头容错；无表头时按列序 A~D 处理）：
 *   文本名称/昵称 | 栏位/字段序号/序号 | 栏位名/字段名 | 含义/字段含义/说明
 *
 * 栏位序号必须为 ≥1 整数（1-based，同 KEYSEQ 语义）；非法行告警跳过不阻断导入。
 */
public final class DiscardExcel {
    private DiscardExcel() {}

    /** 表头提示词：首列命中即视为表头行。 */
    private static final Pattern HEADER_HINT = Pattern.compile("(?i)文本名称|昵称");
    private static final Pattern NICK_HINT = Pattern.compile("(?i)文本名称|昵称");
    private static final Pattern SEQ_HINT = Pattern.compile("(?i)栏位序号|字段序号|^栏位$|^序号$");
    private static final Pattern NAME_HINT = Pattern.compile("(?i)栏位名|字段名");
    private static final Pattern MEANING_HINT = Pattern.compile("(?i)含义|说明");

    public record Parsed(List<DiscardField> fields, List<String> warnings) {}

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
        List<DiscardField> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int nickCol = 0, seqCol = 1, nameCol = 2, meaningCol = 3;
        for (Row row : sheet) {
            String c0 = cellText(row.getCell(0));
            if (c0 == null || c0.isBlank()) continue;
            if (isHeader(c0)) {
                int[] cols = mapHeader(row);
                if (cols != null) {
                    nickCol = cols[0];
                    seqCol = cols[1];
                    nameCol = cols[2];
                    meaningCol = cols[3];
                }
                continue;
            }
            String nick = cellText(row.getCell(nickCol));
            String seq = cellText(row.getCell(seqCol));
            String name = cellText(row.getCell(nameCol));
            String meaning = cellText(row.getCell(meaningCol));
            int line = row.getRowNum() + 1;
            if (nick == null || nick.isBlank()) {
                warnings.add("第 " + line + " 行文本名称为空，已跳过");
                continue;
            }
            Integer colSeq = parseInt(seq);
            if (colSeq == null || colSeq < 1) {
                warnings.add("第 " + line + " 行（" + nick.strip() + "）栏位序号非法: "
                        + (seq == null ? "（空）" : seq) + "（需 ≥1 整数），已跳过");
                continue;
            }
            String key = nick.strip().toLowerCase() + "#" + colSeq;
            if (!seen.add(key)) continue; // 同昵称同栏位重复行去重
            out.add(new DiscardField(nick.strip(), colSeq,
                    name == null ? "" : name.strip(),
                    meaning == null ? "" : meaning.strip(), null, 0));
        }
        if (out.isEmpty()) warnings.add("无有效监测行（需：文本名称 / 栏位序号 / 栏位名 / 含义）");
        return new Parsed(out, warnings);
    }

    /** 表头列定位：四类提示词各归其位；定位不出四列时返回 null（沿用默认列序）。 */
    private static int[] mapHeader(Row row) {
        int nick = findCol(row, NICK_HINT);
        int seq = findCol(row, SEQ_HINT);
        int name = findCol(row, NAME_HINT);
        int meaning = findCol(row, MEANING_HINT);
        if (nick < 0 || seq < 0) return null;
        if (name < 0) name = firstFree(nick, seq, meaning, row.getLastCellNum());
        if (meaning < 0) meaning = firstFree(nick, seq, name, row.getLastCellNum());
        if (name < 0 || meaning < 0) return null;
        return new int[]{nick, seq, name, meaning};
    }

    private static int findCol(Row row, Pattern hint) {
        for (Cell c : row) {
            String t = cellText(c);
            if (t != null && hint.matcher(t).matches()) return c.getColumnIndex();
        }
        return -1;
    }

    /** 默认列序兜底：取已占列之外的最小列号。 */
    private static int firstFree(int a, int b, int c, int last) {
        for (int i = 0; i < last && i < 8; i++) {
            if (i != a && i != b && i != c) return i;
        }
        return -1;
    }

    private static boolean isHeader(String firstCell) {
        return HEADER_HINT.matcher(firstCell).find();
    }

    private static Integer parseInt(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单元格文本（数字按整数值展示，避免 27 → "27.0"；空白返回 null）。 */
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
