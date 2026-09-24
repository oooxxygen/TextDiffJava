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
 * 单报表差异 CSV（【报表对比】结果页导出）：
 * 仅导出「单侧不匹配」与「行部分匹配」两类记录；部分匹配一条差异栏位一行，单侧不匹配整行一条。
 * 折行记录（控制行版式）的差异定位为物理行号，栏位名输出「第N行」。
 * UTF-8 带 BOM。
 */
public final class ReportDiffCsv {
    private ReportDiffCsv() {}

    public static void write(Path file, List<RowDiff> rows, List<String> colNames) {
        write(file, rows, colNames, false);
    }

    public static void write(Path file, List<RowDiff> rows, List<String> colNames, boolean folded) {
        try (BufferedWriter w = newBomWriter(file)) {
            w.write("状态,行标识,栏位号,栏位名,A值,B值\r\n");
            for (RowDiff r : rows) {
                if (Status.UNMATCHED_A.equals(r.status)) {
                    w.write("unmatched_a,");
                    w.write(escape(r.key));
                    w.write(",,,");
                    w.write(escape(joinCols(r.aCols)));
                    w.write(",\r\n");
                } else if (Status.UNMATCHED_B.equals(r.status)) {
                    w.write("unmatched_b,");
                    w.write(escape(r.key));
                    w.write(",,,," );
                    w.write(escape(joinCols(r.bCols)));
                    w.write("\r\n");
                } else if (Status.DIFF.equals(r.status)) {
                    for (int col : r.diffCols) {
                        w.write("diff,");
                        w.write(escape(r.key));
                        w.write(',');
                        w.write(String.valueOf(col + 1));
                        w.write(',');
                        w.write(escape(colLabel(r, col, colNames, folded)));
                        w.write(',');
                        w.write(escape(colAt(r.aCols, col)));
                        w.write(',');
                        w.write(escape(colAt(r.bCols, col)));
                        w.write("\r\n");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("报表差异 CSV 导出失败: " + file, e);
        }
    }

    private static String colAt(String[] cols, int col) {
        return cols != null && col < cols.length ? cols[col] : "";
    }

    private static String joinCols(String[] cols) {
        return cols == null ? "" : String.join("  ", cols);
    }

    static String colName(int col, List<String> names) {
        return names != null && col < names.size() && !names.get(col).isBlank()
                ? names.get(col) : "栏位" + (col + 1);
    }

    /**
     * 差异位标签：表头/表尾块与折行记录的差异位是物理行号 → 「第N行」；
     * 业务行 → 导出映射列名（fieldNames[col]，缺省「栏位N」）。
     */
    public static String colLabel(RowDiff r, int col, List<String> names, boolean folded) {
        boolean lineNo = folded || Status.SECTION_HEADER.equals(r.section)
                || Status.SECTION_FOOTER.equals(r.section);
        return lineNo ? "第" + (col + 1) + "行" : colName(col, names);
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
