package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.textline.TextLineCompareEngine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 普通文本比对差异 CSV（【普通文本比对】结果页导出）：
 * 仅导出「有差异行」与「单侧行」（一条一行，整行原文）；UTF-8 带 BOM。
 */
public final class TextLineDiffCsv {
    private TextLineDiffCsv() {}

    public static void write(Path file, List<RowDiff> rows) {
        try (BufferedWriter w = newBomWriter(file)) {
            w.write("状态,主键,A内容,B内容\r\n");
            for (RowDiff r : rows) {
                if (!TextLineCompareEngine.SECTION.equals(r.section)) continue;
                if (Status.UNMATCHED_A.equals(r.status)) {
                    w.write("unmatched_a,");
                    w.write(escape(r.key));
                    w.write(',');
                    w.write(escape(colAt(r.aCols, 0)));
                    w.write(",\r\n");
                } else if (Status.UNMATCHED_B.equals(r.status)) {
                    w.write("unmatched_b,");
                    w.write(escape(r.key));
                    w.write(",,");
                    w.write(escape(colAt(r.bCols, 0)));
                    w.write("\r\n");
                } else if (Status.DIFF.equals(r.status)) {
                    w.write("diff,");
                    w.write(escape(r.key));
                    w.write(',');
                    w.write(escape(colAt(r.aCols, 0)));
                    w.write(',');
                    w.write(escape(colAt(r.bCols, 0)));
                    w.write("\r\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("普通文本比对差异 CSV 导出失败: " + file, e);
        }
    }

    private static String colAt(String[] cols, int col) {
        return cols != null && col < cols.length ? cols[col] : "";
    }

    private static BufferedWriter newBomWriter(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        var out = Files.newOutputStream(file);
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        return new BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    /** CSV 字段转义：含逗号/引号/换行时整体加引号并双写内部引号。 */
    private static String escape(String v) {
        if (v == null) return "";
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
