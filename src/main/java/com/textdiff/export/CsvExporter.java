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
        try (BufferedWriter w = newBomWriter(file)) {
            int width = 0;
            for (RowDiff r : rows) {
                width = Math.max(width, r.aCols == null ? 0 : r.aCols.length);
                width = Math.max(width, r.bCols == null ? 0 : r.bCols.length);
            }
            List<String> names = colNames == null ? List.of() : colNames;
            w.write("键值,状态,分区");
            for (int i = 0; i < width; i++) {
                String name = i < names.size() && !names.get(i).isBlank() ? names.get(i) : "栏位" + (i + 1);
                w.write("," + escape(name + "(A)") + "," + escape(name + "(B)"));
            }
            w.write("\r\n");
            for (RowDiff r : rows) {
                w.write(escape(displayKey(r.key)));
                w.write(',');
                w.write(escape(r.status));
                w.write(',');
                w.write(escape(r.section));
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
        } catch (IOException e) {
            throw new UncheckedIOException("全量 CSV 导出失败: " + file, e);
        }
    }

    /** 差异 CSV：仅 diff 记录，一条字段差异一行（键值/栏位号/栏位名/A 值/B 值）。 */
    public static void writeDiffFields(Path file, List<RowDiff> rows, List<String> colNames) {
        try (BufferedWriter w = newBomWriter(file)) {
            w.write("键值,栏位号,栏位名,A值,B值\r\n");
            for (RowDiff r : rows) {
                if (!Status.DIFF.equals(r.status)) continue;
                for (int col : r.diffCols) {
                    String name = colNames != null && col < colNames.size() && !colNames.get(col).isBlank()
                            ? colNames.get(col) : "栏位" + (col + 1);
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
            }
        } catch (IOException e) {
            throw new UncheckedIOException("差异 CSV 导出失败: " + file, e);
        }
    }

    public static String escape(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static BufferedWriter newBomWriter(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write('\ufeff');
        return out;
    }
}
