package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.report.ReportCompareService;
import com.textdiff.report.ReportSummary;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.Path;
import java.util.List;

/**
 * 报表对比批次详情 Excel（批次页「导出明细」）：
 * Sheet1「报表对比总览」——每张报表的昵称、文件名、总条数（程序计数）、报表段数、表头/表尾差异、
 * 业务行匹配统计（完全匹配/部分匹配/仅A/仅B）、条数核对与状态；
 * Sheet2「差异明细」——表头/表尾/业务分区的差异行（部分匹配逐差异栏位一行，单侧不匹配整行一行，封顶截断）。
 */
public final class ReportDetailExcel {

    /** Sheet2 差异明细最大行数（超出截断，附说明行）。 */
    static final long DIFF_ROWS_CAP = 5000;

    private ReportDetailExcel() {}

    public static Path write(Path out, List<JobRecord> jobs) {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            sheetOverview(wb, head, jobs);
            sheetDiff(wb, head, jobs);

            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            try (var fos = new java.io.FileOutputStream(out.toFile())) {
                wb.write(fos);
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("报表批次明细 Excel 写入失败: " + out + "（" + e.getMessage() + "）", e);
        }
    }

    private static void sheetOverview(Workbook wb, CellStyle head, List<JobRecord> jobs) {
        Sheet sheet = wb.createSheet("报表对比总览");
        String[] headers = {"报表昵称", "文件名", "总条数(A)", "总条数(B)", "报表段数(A/B)",
                "表头差异", "表尾差异", "完全匹配", "部分匹配", "仅A有", "仅B有", "条数核对", "状态"};
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) cell(hr, i, headers[i]).setCellStyle(head);
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 26 * 256);
        for (int i = 2; i <= 11; i++) sheet.setColumnWidth(i, 12 * 256);
        sheet.setColumnWidth(12, 10 * 256);

        int r = 1;
        for (JobRecord job : jobs) {
            Row row = sheet.createRow(r++);
            ReportSummary s = readSummary(job);
            cell(row, 0, job.nickname);
            cell(row, 1, baseName(job.fileA));
            if (s != null) {
                cell(row, 2, s.rowCountA);
                cell(row, 3, s.rowCountB);
                cell(row, 4, s.sectionCountA + "/" + s.sectionCountB);
                cell(row, 5, s.headerLineDiff);
                cell(row, 6, s.footerLineDiff);
                cell(row, 7, s.equal);
                cell(row, 8, s.partial);
                cell(row, 9, s.onlyA);
                cell(row, 10, s.onlyB);
                cell(row, 11, countCheckDesc(s));
            } else {
                for (int i = 2; i <= 11; i++) cell(row, i, JobRecord.FAILED.equals(job.status)
                        ? "（对比失败: " + job.error + "）" : "（无结果数据）");
            }
            cell(row, 12, job.status);
        }
    }

    private static void sheetDiff(Workbook wb, CellStyle head, List<JobRecord> jobs) {
        Sheet sheet = wb.createSheet("差异明细");
        String[] headers = {"报表昵称", "分区", "状态", "行标识", "栏位号", "栏位名", "A值", "B值"};
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) cell(hr, i, headers[i]).setCellStyle(head);
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 8 * 256);
        sheet.setColumnWidth(2, 12 * 256);
        sheet.setColumnWidth(3, 36 * 256);
        sheet.setColumnWidth(4, 8 * 256);
        sheet.setColumnWidth(5, 20 * 256);
        sheet.setColumnWidth(6, 42 * 256);
        sheet.setColumnWidth(7, 42 * 256);

        int r = 1;
        long written = 0;
        boolean truncated = false;
        for (JobRecord job : jobs) {
            if (written >= DIFF_ROWS_CAP) {
                truncated = true;
                break;
            }
            Path jsonl = Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL);
            List<String> names = readFieldNames(job);
            try (var stream = ResultFiles.stream(jsonl)) {
                for (RowDiff row : (Iterable<RowDiff>) stream::iterator) {
                    if (Status.EQUAL.equals(row.status)) continue;
                    if (written >= DIFF_ROWS_CAP) {
                        truncated = true;
                        break;
                    }
                    String sectionText = sectionText(row.section);
                    if (Status.DIFF.equals(row.status)) {
                        for (int col : row.diffCols) {
                            Row sheetRow = sheet.createRow(r++);
                            written++;
                            cell(sheetRow, 0, job.nickname);
                            cell(sheetRow, 1, sectionText);
                            cell(sheetRow, 2, "部分匹配");
                            cell(sheetRow, 3, row.key);
                            cell(sheetRow, 4, col + 1);
                            cell(sheetRow, 5, ReportDiffCsv.colName(col, names));
                            cell(sheetRow, 6, colAt(row.aCols, col));
                            cell(sheetRow, 7, colAt(row.bCols, col));
                        }
                    } else {
                        Row sheetRow = sheet.createRow(r++);
                        written++;
                        boolean aSide = Status.UNMATCHED_A.equals(row.status);
                        cell(sheetRow, 0, job.nickname);
                        cell(sheetRow, 1, sectionText);
                        cell(sheetRow, 2, aSide ? "仅A有" : "仅B有");
                        cell(sheetRow, 3, row.key);
                        cell(sheetRow, 4, "");
                        cell(sheetRow, 5, "");
                        cell(sheetRow, 6, aSide ? joinCols(row.aCols) : "");
                        cell(sheetRow, 7, aSide ? "" : joinCols(row.bCols));
                    }
                }
            }
        }
        if (truncated) {
            Row note = sheet.createRow(r);
            cell(note, 0, "（差异明细超过 " + DIFF_ROWS_CAP + " 行，已截断；完整内容请用单报表差异 CSV 导出）");
        }
    }

    static String sectionText(String section) {
        return switch (section == null ? "" : section) {
            case Status.SECTION_HEADER -> "表头";
            case Status.SECTION_FOOTER -> "表尾";
            case Status.SECTION_TRAILER -> "文件尾";
            default -> "业务内容";
        };
    }

    private static String countCheckDesc(ReportSummary s) {
        if (s.countChecks == null || s.countChecks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ReportSummary.CountCheck c : s.countChecks) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(c.side).append("侧段").append(c.section + 1).append(": 声明 ")
                    .append(c.declared).append(" / 实计 ").append(c.counted)
                    .append(c.match ? " ✓" : " ✗ 不符");
        }
        return sb.toString();
    }

    private static String colAt(String[] cols, int col) {
        return cols != null && col < cols.length ? cols[col] : "";
    }

    private static String joinCols(String[] cols) {
        return cols == null ? "" : String.join("  ", cols);
    }

    private static List<String> readFieldNames(JobRecord job) {
        ReportSummary s = readSummary(job);
        return s == null || s.fieldNames == null ? List.of() : s.fieldNames;
    }

    private static ReportSummary readSummary(JobRecord job) {
        try {
            return com.textdiff.store.Json.read(
                    java.nio.file.Files.readString(
                            Path.of(job.resultDir).resolve(ReportCompareService.SUMMARY_JSON)),
                    ReportSummary.class);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (java.io.IOException e) {
            return null; // 损坏/缺失按无结果处理
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String baseName(String path) {
        return Path.of(path == null ? "file" : path).getFileName().toString();
    }

    private static Cell cell(Row row, int col, String value) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        return c;
    }

    private static Cell cell(Row row, int col, long value) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        return c;
    }
}
