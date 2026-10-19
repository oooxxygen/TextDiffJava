package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RFC 4180 CSV 导出（零依赖，自实现转义）。
 * ① 全量导出：所有记录所有字段（键值/状态/分区/逐列 A、B 值）；② 差异导出：仅差异记录，一条字段差异一行。
 * UTF-8 带 BOM（Excel 直接打开中文不乱码）。
 * 展示友好：组合主键内部连接符（0x1F 不可见）在导出层显示为 ':'；超过 15 位的纯数字值
 * 包裹为 ="…" 文本公式，避免 Excel 按数值渲染丢失精度（如 30 位长号变科学计数法）。
 */
public final class CsvExporter {
    private CsvExporter() {}

    /** ≥16 位的纯数字在 Excel 中超出 double 精度（15 位），按文本公式存储以保真展示。 */
    private static final java.util.regex.Pattern LONG_DIGITS = java.util.regex.Pattern.compile("\\d{16,}");

    /** 导出层键值展示：组合主键分隔符 0x1F → ':'。 */
    public static String displayKey(String key) {
        return key == null ? "" : key.replace(com.textdiff.engine.Rules.KEY_SEP, ":");
    }

    /** 长数字 → ="…" 文本公式（Excel 渲染为文本）；其余原样（escape 转义在拼接时统一处理）。 */
    static String excelSafe(String v) {
        if (v == null || v.isEmpty() || !LONG_DIGITS.matcher(v).matches()) return v;
        return "=\"" + v + "\"";
    }

    /** 全量 CSV 写入（rows 由调用方流式提供）。colNames 可空。 */
    public static void writeFull(Path file, List<RowDiff> rows, List<String> colNames) {
        writeFull(file, rows, colNames, -1);
    }

    /** 全量列宽：各行 A/B 栏位数的最大值（分片切割时按整批统一，保证各分片表头一致）。 */
    public static int fullWidth(List<RowDiff> rows) {
        int width = 0;
        for (RowDiff r : rows) {
            width = Math.max(width, r.aCols == null ? 0 : r.aCols.length);
            width = Math.max(width, r.bCols == null ? 0 : r.bCols.length);
        }
        return width;
    }

    /** 差异栏位摘要：状态为 diff 的记录按「列号-栏位名称；」拼接（其他状态为空串）。 */
    public static String diffLabel(RowDiff r, List<String> colNames) {
        if (!Status.DIFF.equals(r.status) || r.diffCols == null || r.diffCols.length == 0) return "";
        List<String> names = colNames == null ? List.of() : colNames;
        StringBuilder sb = new StringBuilder();
        for (int col : r.diffCols) {
            String name = col < names.size() && !names.get(col).isBlank() ? names.get(col) : "栏位" + (col + 1);
            sb.append(col + 1).append('-').append(name).append('；');
        }
        return sb.toString();
    }

    /** 全量 CSV 写入；width < 0 时按本批行扫描（分片调用方传整批宽度保持表头一致）。 */
    public static void writeFull(Path file, List<RowDiff> rows, List<String> colNames, int width) {
        try (BufferedWriter w = newBomWriter(file)) {
            if (width < 0) width = fullWidth(rows);
            writeFullHeader(w, colNames, width);
            for (RowDiff r : rows) writeFullRow(w, r, colNames, width);
        } catch (IOException e) {
            throw new UncheckedIOException("全量 CSV 导出失败: " + file + " (" + e + ")", e);
        }
    }

    /** 全量 CSV 表头（流式导出按整批宽度统一各分片表头）。 */
    public static void writeFullHeader(BufferedWriter w, List<String> colNames, int width) throws IOException {
        List<String> names = colNames == null ? List.of() : colNames;
        w.write("键值,状态,差异栏位");
        for (int i = 0; i < width; i++) {
            String name = i < names.size() && !names.get(i).isBlank() ? names.get(i) : "栏位" + (i + 1);
            w.write("," + escape(name + "(A)") + "," + escape(name + "(B)"));
        }
        w.write("\r\n");
    }

    /** 全量 CSV 单行（流式导出逐行调用，不驻留列表）。 */
    public static void writeFullRow(BufferedWriter w, RowDiff r, List<String> colNames, int width) throws IOException {
        List<String> names = colNames == null ? List.of() : colNames;
        w.write(escape(displayKey(r.key)));
        w.write(',');
        w.write(escape(r.status));
        w.write(',');
        w.write(escape(diffLabel(r, names)));
        for (int i = 0; i < width; i++) {
            String a = r.aCols != null && i < r.aCols.length ? r.aCols[i] : "";
            String b = r.bCols != null && i < r.bCols.length ? r.bCols[i] : "";
            w.write(',');
            w.write(escape(excelSafe(a)));
            w.write(',');
            w.write(escape(excelSafe(b)));
        }
        w.write("\r\n");
    }

    /** 差异 CSV：仅 diff 记录，一条字段差异一行（键值/栏位号/栏位名/A 值/B 值）。 */
    public static void writeDiffFields(Path file, List<RowDiff> rows, List<String> colNames) {
        try (BufferedWriter w = newBomWriter(file)) {
            writeDiffHeader(w);
            for (RowDiff r : rows) writeDiffRow(w, r, colNames);
        } catch (IOException e) {
            throw new UncheckedIOException("差异 CSV 导出失败: " + file + " (" + e + ")", e);
        }
    }

    /** 差异 CSV 表头。 */
    public static void writeDiffHeader(BufferedWriter w) throws IOException {
        w.write("键值,栏位号,栏位名,A值,B值\r\n");
    }

    /** 差异 CSV 单行：非 diff 记录不产出；返回是否写出了内容（空批判断用）。 */
    public static boolean writeDiffRow(BufferedWriter w, RowDiff r, List<String> colNames) throws IOException {
        if (!Status.DIFF.equals(r.status) || r.diffCols == null || r.diffCols.length == 0) return false;
        List<String> names = colNames == null ? List.of() : colNames;
        for (int col : r.diffCols) {
            String name = col < names.size() && !names.get(col).isBlank() ? names.get(col) : "栏位" + (col + 1);
            String a = r.aCols != null && col < r.aCols.length ? r.aCols[col] : "";
            String b = r.bCols != null && col < r.bCols.length ? r.bCols[col] : "";
            w.write(escape(displayKey(r.key)));
            w.write(',');
            w.write(String.valueOf(col + 1));
            w.write(',');
            w.write(escape(name));
            w.write(',');
            w.write(escape(excelSafe(a)));
            w.write(',');
            w.write(escape(excelSafe(b)));
            w.write("\r\n");
        }
        return true;
    }

    public static String escape(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** UTF-8 BOM 写入器（导出装配流式分片共用）。 */
    static BufferedWriter newBomWriter(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write('\ufeff');
        return out;
    }
}
