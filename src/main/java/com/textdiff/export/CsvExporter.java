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
 */
public final class CsvExporter {
    private CsvExporter() {}

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
                w.write(escape(r.key));
                w.write(',');
                w.write(escape(r.status));
                w.write(',');
                w.write(escape(r.section));
                for (int i = 0; i < width; i++) {
                    String a = r.aCols != null && i < r.aCols.length ? r.aCols[i] : "";
                    String b = r.bCols != null && i < r.bCols.length ? r.bCols[i] : "";
                    w.write(',');
                    w.write(escape(a));
                    w.write(',');
                    w.write(escape(b));
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
                    w.write(escape(r.key));
                    w.write(',');
                    w.write(String.valueOf(col + 1));
                    w.write(',');
                    w.write(escape(name));
                    w.write(',');
                    w.write(escape(a));
                    w.write(',');
                    w.write(escape(b));
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
