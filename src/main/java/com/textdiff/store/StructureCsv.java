package com.textdiff.store;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构 CSV 解析（列名映射导入）：
 * - bat_report_type_parm*.csv：report_file_name（文件名）/ report_id（文件昵称）/ parm_report_type（文件类型）
 *   / ownership_group（归属组）——建立 昵称 ↔ 文件名 映射
 * - bat_report_conf_field*.csv：report_id / field_index（1-based 列序）/ field_name（字段名）/
 *   field_format（字段类型）——建立 昵称 ↔ 字段清单及类型 映射（无显式序号时行序即列序）
 * 表头按名匹配（大小写不敏感、去 BOM/引号）；编码自适应 UTF-8 → GBK；
 * 记录级解析支持引号内换行（真实 conf_field 导出存在多行备注字段）。
 */
public final class StructureCsv {
    private StructureCsv() {}

    public enum Kind { TYPE_PARM, CONF_FIELD, UNKNOWN }

    public static Kind kindOf(String fileName, byte[] content) {
        String n = Path.of(fileName).getFileName().toString().toLowerCase();
        if (n.startsWith("bat_report_type_parm")) return Kind.TYPE_PARM;
        if (n.startsWith("bat_report_conf_field")) return Kind.CONF_FIELD;
        // 内容启发：无文件名约定时看表头特征列
        String header = headerLine(content);
        String lower = header.toLowerCase();
        if (lower.contains("parm_report_type")) return Kind.TYPE_PARM;
        if (lower.contains("field_format") || lower.contains("field_name")) return Kind.CONF_FIELD;
        return Kind.UNKNOWN;
    }

    public static List<FieldMaps.ReportType> parseTypes(byte[] content, String sourceFile) {
        List<String[]> rows = table(content);
        int cFile = col(rows, "report_file_name");
        int cId = col(rows, "report_id");
        int cType = col(rows, "parm_report_type");
        int cGroup = col(rows, "ownership_group");
        if (cId < 0 || cFile < 0) {
            throw new IllegalArgumentException(sourceFile + " 缺少 report_id / report_file_name 列");
        }
        List<FieldMaps.ReportType> out = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String id = cell(row, cId);
            if (id.isBlank()) continue;
            out.add(new FieldMaps.ReportType(id, cell(row, cFile),
                    cType >= 0 ? cell(row, cType) : "",
                    cGroup >= 0 ? cell(row, cGroup) : "",
                    sourceFile, now));
        }
        return out;
    }

    /** 昵称内列序：优先显式序号列（field_index 等，1-based），否则行序自 0 递增。 */
    public static List<FieldMaps.ReportField> parseFields(byte[] content, String sourceFile) {
        List<String[]> rows = table(content);
        int cId = col(rows, "report_id");
        int cName = col(rows, "field_name");
        int cFmt = col(rows, "field_format");
        int cLen = col(rows, "field_length");
        int cSeq = col(rows, "field_index", "field_seq", "order_no", "seq_no", "column_no", "col_no",
                "field_order");
        if (cId < 0 || cName < 0) {
            throw new IllegalArgumentException(sourceFile + " 缺少 report_id / field_name 列");
        }
        List<FieldMaps.ReportField> out = new ArrayList<>();
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String id = cell(row, cId);
            String name = cell(row, cName);
            if (id.isBlank() && name.isBlank()) continue;
            int idx;
            String seq = cSeq >= 0 ? cell(row, cSeq).trim() : "";
            if (!seq.isEmpty()) {
                try {
                    idx = Integer.parseInt(seq) - 1; // 序号列按 1-based
                } catch (NumberFormatException e) {
                    idx = counters.getOrDefault(id, 0);
                    counters.put(id, idx + 1);
                }
            } else {
                idx = counters.getOrDefault(id, 0);
                counters.put(id, idx + 1);
            }
            out.add(new FieldMaps.ReportField(id, idx, name,
                    cFmt >= 0 ? cell(row, cFmt) : "",
                    cLen >= 0 ? cell(row, cLen) : ""));
        }
        return out;
    }

    // ---- internals ----

    static String headerLine(byte[] content) {
        String text = decode(content);
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** 解析为二维表（含表头）：记录级扫描（引号内逗号/换行不切分）+ BOM 剥离。 */
    static List<String[]> table(byte[] content) {
        String text = decode(content);
        List<String[]> rows = new ArrayList<>();
        StringBuilder record = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuote) {
                record.append(ch);
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        record.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                }
            } else if (ch == '"') {
                inQuote = true;
                record.append(ch);
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (!record.isEmpty()) rows.add(splitCsvLine(record.toString()));
                record.setLength(0);
            } else {
                record.append(ch);
            }
        }
        if (!record.toString().isBlank()) rows.add(splitCsvLine(record.toString()));
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV 为空");
        return rows;
    }

    static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuote) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"' && cur.isEmpty()) {
                inQuote = true;
            } else if (ch == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    static int col(List<String[]> rows, String... names) {
        String[] header = rows.get(0);
        for (int i = 0; i < header.length; i++) {
            String h = header[i].toLowerCase();
            for (String n : names) {
                if (h.equals(n) || h.replace(" ", "").equals(n)) return i;
            }
        }
        return -1;
    }

    static String cell(String[] row, int idx) {
        return idx >= 0 && idx < row.length ? row[idx] : "";
    }

    /** UTF-8 优先，出现非法序列回退 GBK（银行侧 CSV 常见编码）。 */
    static String decode(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        String utf8 = new String(content, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        return new String(content, Charset.forName("GBK"));
    }
}
