package com.textdiff.export;

import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.Rules;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import com.textdiff.engine.Summary;
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
import java.util.Map;

/**
 * 批次明细 Excel 导出（批次详情页「导出明细」）：
 * Sheet1「对比总览」——批内每张表的新旧总数、完全匹配、键值匹配有差异、未匹配（仅A/仅B）与
 * 对比配置（主键/跳过栏位的列号+字段名，字段名优先取列名映射）；
 * Sheet2「差异栏位」——所有表存在的差异栏位（表名昵称、列号、列名、备注默认空）。
 */
public final class BatchDetailExcel {

    private BatchDetailExcel() {}

    public static Path write(Path out, List<JobRecord> jobs, com.textdiff.store.FieldMapStore maps) {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);

            sheetOverview(wb, head, wrap, jobs, maps);
            sheetDiffCols(wb, head, jobs, maps);

            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            try (var fos = new java.io.FileOutputStream(out.toFile())) {
                wb.write(fos);
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("批次明细 Excel 写入失败: " + out + "（" + e.getMessage() + "）", e);
        }
    }

    /** Sheet1 对比总览：每表数量对比 + 对比配置。 */
    private static void sheetOverview(Workbook wb, CellStyle head, CellStyle wrap,
                                      List<JobRecord> jobs, com.textdiff.store.FieldMapStore maps) {
        Sheet sheet = wb.createSheet("对比总览");
        String[] headers = {"表名昵称", "A（旧）总数", "B（新）总数", "完全匹配",
                "键值匹配有差异", "未匹配（仅A）", "未匹配（仅B）", "对比配置（列号+名称）"};
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) cell(hr, i, headers[i]).setCellStyle(head);
        sheet.setColumnWidth(0, 28 * 256);
        for (int i = 1; i <= 6; i++) sheet.setColumnWidth(i, 13 * 256);
        sheet.setColumnWidth(7, 100 * 256);

        int r = 1;
        for (JobRecord job : jobs) {
            Row row = sheet.createRow(r++);
            cell(row, 0, job.nickname);
            Summary s = readSummary(job);
            if (s != null) {
                cell(row, 1, s.totalA);
                cell(row, 2, s.totalB);
                cell(row, 3, s.equal);
                cell(row, 4, s.diff);
                cell(row, 5, s.onlyA);
                cell(row, 6, s.onlyB);
            } else {
                for (int i = 1; i <= 6; i++) cell(row, i, "（无结果数据）");
            }
            Cell cfg = cell(row, 7, configDesc(job, maps));
            cfg.setCellStyle(wrap);
        }
    }

    /** Sheet2 差异栏位：所有表存在的差异栏位清单（备注默认空）。 */
    private static void sheetDiffCols(Workbook wb, CellStyle head,
                                      List<JobRecord> jobs, com.textdiff.store.FieldMapStore maps) {
        Sheet sheet = wb.createSheet("差异栏位");
        String[] headers = {"表名昵称", "列号", "列名", "备注"};
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) cell(hr, i, headers[i]).setCellStyle(head);
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 8 * 256);
        sheet.setColumnWidth(2, 32 * 256);
        sheet.setColumnWidth(3, 24 * 256);

        int r = 1;
        for (JobRecord job : jobs) {
            Summary s = readSummary(job);
            if (s == null || s.diffColFreq == null || s.diffColFreq.isEmpty()) continue;
            CompareConfig cfg = Rules.parseLegacy(job.configLine, " | ", "|||||");
            Map<Integer, String> names = com.textdiff.ai.PromptRenderer.colNames(job, cfg, maps);
            List<Integer> cols = s.diffColFreq.keySet().stream().sorted().toList();
            for (int c : cols) {
                Row row = sheet.createRow(r++);
                cell(row, 0, job.nickname);
                cell(row, 1, c + 1); // 1-based 列号，与结果页/提示词一致
                cell(row, 2, names.getOrDefault(c, "栏位" + (c + 1)));
                cell(row, 3, ""); // 备注：默认为空
            }
        }
    }

    /** 对比配置描述：主键/跳过栏位的 1-based 序列与列号+字段名，加分隔符。 */
    static String configDesc(JobRecord job, com.textdiff.store.FieldMapStore maps) {
        CompareConfig cfg = Rules.parseLegacy(job.configLine, " | ", "|||||");
        Map<Integer, String> names = com.textdiff.ai.PromptRenderer.colNames(job, cfg, maps);
        StringBuilder sb = new StringBuilder();
        sb.append("主键 KEYSEQ=").append(seq(cfg.keyColumns))
                .append("，").append(com.textdiff.ai.PromptRenderer.describeCols(cfg.keyColumns, names));
        sb.append("\n跳过 OMITSEQ=").append(seq(cfg.omitColumns))
                .append("，").append(com.textdiff.ai.PromptRenderer.describeCols(cfg.omitColumns, names));
        sb.append("\n分隔符：").append(cfg.delimiter);
        return sb.toString();
    }

    /** 1-based 列序列，如 3/4/5；空返回"无"。 */
    private static String seq(List<Integer> cols) {
        if (cols == null || cols.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (int c : cols) sb.append(c + 1).append("/");
        return sb.substring(0, sb.length() - 1);
    }

    private static Summary readSummary(JobRecord job) {
        try {
            return ResultFiles.readSummary(Path.of(job.resultDir));
        } catch (RuntimeException e) {
            return null;
        }
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
