package com.textdiff.export;

import com.textdiff.custom.CustomCompareEngine;
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
 * 自定义格式对比差异 CSV（【自定义格式对比】结果页导出）：
 * 仅导出「单侧不匹配段」与「有差异段」；差异段一条差异行一行，单侧段整段一条（段内换行保留）。
 * UTF-8 带 BOM。
 */
public final class CustomDiffCsv {
    private CustomDiffCsv() {}

    public static void write(Path file, List<RowDiff> rows) {
        try (BufferedWriter w = newBomWriter(file)) {
            w.write("状态,主键,段内行号,A内容,B内容\r\n");
            for (RowDiff r : rows) {
                if (!CustomCompareEngine.SECTION.equals(r.section)) continue;
                if (Status.UNMATCHED_A.equals(r.status)) {
                    w.write("unmatched_a,");
                    w.write(escape(r.key));
                    w.write(",,");
                    w.write(escape(join(r.aCols)));
                    w.write(",\r\n");
                } else if (Status.UNMATCHED_B.equals(r.status)) {
                    w.write("unmatched_b,");
                    w.write(escape(r.key));
                    w.write(",,,");
                    w.write(escape(join(r.bCols)));
                    w.write("\r\n");
                } else if (Status.DIFF.equals(r.status)) {
                    for (int col : r.diffCols) {
                        w.write("diff,");
                        w.write(escape(r.key));
                        w.write(',');
                        w.write(String.valueOf(col + 1));
                        w.write(',');
                        w.write(escape(colAt(r.aCols, col)));
                        w.write(',');
                        w.write(escape(colAt(r.bCols, col)));
                        w.write("\r\n");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("自定义格式差异 CSV 导出失败: " + file, e);
        }
    }

    private static String colAt(String[] cols, int col) {
        return cols != null && col < cols.length ? cols[col] : "";
    }

    private static String join(String[] cols) {
        return cols == null ? "" : String.join("\n", cols);
    }

    private static String escape(String v) {
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
