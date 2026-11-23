package com.textdiff.export;

import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Summary;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 单作业差异对比 Excel（v0.9.17 验收需求②，替代原「全量差异CSV」下载）：
 * Sheet1「总览」——本次对比的配置、时间、数量对比、头部字段对照、差异栏位分布（对比概览页同源信息的文字版）；
 * Sheet2「差异记录详情」——与差异CSV整合格式完全同构（键值/状态/差异明细，一条差异记录一行，
 * 复用 {@link CsvExporter#diffRecParts}，含关注栏位 [X-YYYY @N] 前缀）。SXSSF 流式写，百万行内单表。
 */
public final class DiffDetailExcel {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DiffDetailExcel() {}

    /** 生成 {base}_差异对比.xlsx；返回文件路径。monitor 可空（未导入监测配置）。 */
    public static Path write(Path out, JobRecord job,
                             java.util.function.Predicate<RowDiff> filter,
                             Map<Integer, com.textdiff.store.DiscardField> monitor) {
        CompareConfig cfg = ExportAssembler.compareConfig(job);
        List<com.textdiff.store.JobMeta.DynCol> dyn = ExportAssembler.dynCols(job);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            CellStyle head = headStyle(wb);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            sheetOverview(wb, head, wrap, job, cfg);
            sheetDetail(wb, job, filter, cfg, dyn, monitor);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            try (var fos = new java.io.FileOutputStream(out.toFile())) {
                wb.write(fos);
            }
            wb.dispose();
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("差异对比 Excel 写入失败: " + out + "（" + e.getMessage() + "）", e);
        }
    }

    // ---- Sheet1 总览：配置 / 时间 / 数量对比 / 头部字段对照 / 差异栏位分布 ----

    private static void sheetOverview(SXSSFWorkbook wb, CellStyle head, CellStyle wrap,
                                      JobRecord job, CompareConfig cfg) throws IOException {
        SXSSFSheet sheet = wb.createSheet("总览");
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 110 * 256);
        int r = 0;
        r = section(sheet, r, "本次对比", head);
        Summary s = readSummary(job);
        String srcA = cfg.sourceA == null || cfg.sourceA.isBlank() ? "A" : cfg.sourceA.strip();
        String srcB = cfg.sourceB == null || cfg.sourceB.isBlank() ? "B" : cfg.sourceB.strip();
        r = kv(sheet, r, "表名昵称", job.nickname);
        r = kv(sheet, r, "对比文件", job.fileA + "  ↔  " + job.fileB);
        r = kv(sheet, r, wrap, "对比配置（列号+名称）", BatchDetailExcel.configDesc(job, null));
        r = kv(sheet, r, "对比配置行", BatchDetailExcel.capCell(job.configLine));
        r = kv(sheet, r, "触发时间", ts(job.startedAt));
        r = kv(sheet, r, "完成时间", ts(job.finishedAt));
        r = kv(sheet, r, "耗时", dur(job.startedAt, job.finishedAt));

        r = section(sheet, r, "数量对比", head);
        if (s != null) {
            r = kv(sheet, r, srcA + " 总行数（数据" + s.dataRowsA + "+尾部" + s.trailerRowsA + "）", s.totalA);
            r = kv(sheet, r, srcB + " 总行数（数据" + s.dataRowsB + "+尾部" + s.trailerRowsB + "）", s.totalB);
            r = kv(sheet, r, "完全匹配", s.equal);
            r = kv(sheet, r, "键值匹配有差异", s.diff);
            r = kv(sheet, r, "仅 " + srcA + " 存在", s.onlyA);
            r = kv(sheet, r, "仅 " + srcB + " 存在", s.onlyB);
            DiffGrade.Grade g = DiffGrade.of(s.totalA, s.totalB);
            r = kv(sheet, r, "状态情况", g.desc());
            r = kv(sheet, r, "总体评判", g.label());

            if (s.trailerFields != null && !s.trailerFields.isEmpty()) {
                r = section(sheet, r, "头部字段对照", head);
                for (Map.Entry<String, Summary.TrailerField> e : s.trailerFields.entrySet()) {
                    Summary.TrailerField t = e.getValue();
                    String v = srcA + "=" + nvl(t.a()) + " · " + srcB + "=" + nvl(t.b())
                            + "  " + (t.equal() ? "一致" : "不一致");
                    r = kv(sheet, r, wrap, e.getKey() + (t.equal() ? "（一致）" : "（不一致）"), v);
                }
            }
            if (s.diffColFreq != null && !s.diffColFreq.isEmpty()) {
                r = section(sheet, r, "差异栏位分布（共 " + s.diffColFreq.size() + " 列）", head);
                List<String> names = cfg.columnNames;
                for (Map.Entry<Integer, Long> e : s.diffColFreq.entrySet()) {
                    int col = e.getKey(); // 0-based；CTIS 虚拟子列 ≥ VBASE
                    String label = col >= com.textdiff.engine.DynExpand.VBASE
                            ? "子列 " + (col - com.textdiff.engine.DynExpand.VBASE + 1)
                            : "列 " + (col + 1) + " · " + colName(names, col);
                    r = kv(sheet, r, label, e.getValue() + " 次 · " + pct(e.getValue(), s.diff));
                }
            }
        } else {
            r = kv(sheet, r, "数量对比", "（无结果数据）");
        }
    }

    // ---- Sheet2 差异记录详情：与差异CSV整合格式同构（键值/状态/差异明细） ----

    private static void sheetDetail(SXSSFWorkbook wb, JobRecord job,
                                    java.util.function.Predicate<RowDiff> filter,
                                    CompareConfig cfg, List<com.textdiff.store.JobMeta.DynCol> dyn,
                                    Map<Integer, com.textdiff.store.DiscardField> monitor) throws IOException {
        SXSSFSheet sheet = wb.createSheet("差异记录详情");
        sheet.setColumnWidth(0, 40 * 256);
        sheet.setColumnWidth(1, 20 * 256);
        sheet.setColumnWidth(2, 120 * 256);
        Row hr = sheet.createRow(0);
        String[] headers = {"键值", "状态", "差异明细"};
        for (int i = 0; i < headers.length; i++) hr.createCell(i).setCellValue(headers[i]);
        int[] r = {1};
        try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            stream.filter(filter).forEach(row -> {
                String[] parts = CsvExporter.diffRecParts(row, cfg.columnNames, dyn, monitor,
                        cfg.sourceA, cfg.sourceB);
                if (parts == null) return;
                Row row0 = sheet.createRow(r[0]++);
                for (int i = 0; i < parts.length; i++) row0.createCell(i).setCellValue(parts[i]);
            });
        }
    }

    // ---- 小工具 ----

    /** 栏位名：列名映射命中取映射名（列 N 格式回退），与导出 CSV 列头规则一致。 */
    private static String colName(List<String> names, int col) {
        return names != null && col < names.size() && names.get(col) != null && !names.get(col).isBlank()
                ? names.get(col) : "栏位" + (col + 1);
    }

    private static int section(SXSSFSheet sheet, int r, String title, CellStyle head) {
        Row row = sheet.createRow(r++);
        Cell c = row.createCell(0);
        c.setCellValue(title);
        c.setCellStyle(head);
        return r;
    }

    private static int kv(SXSSFSheet sheet, int r, String k, Object v) {
        return kv(sheet, r, null, k, v);
    }

    private static int kv(SXSSFSheet sheet, int r, CellStyle wrap, String k, Object v) {
        Row row = sheet.createRow(r++);
        row.createCell(0).setCellValue(k);
        Cell c = row.createCell(1);
        c.setCellValue(v == null ? "" : String.valueOf(v));
        if (wrap != null) c.setCellStyle(wrap);
        return r;
    }

    private static CellStyle headStyle(SXSSFWorkbook wb) {
        CellStyle head = wb.createCellStyle();
        Font bold = wb.createFont();
        bold.setBold(true);
        head.setFont(bold);
        head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return head;
    }

    private static Summary readSummary(JobRecord job) {
        try {
            return ResultFiles.readSummary(Path.of(job.resultDir));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "（空）" : s;
    }

    private static String ts(long epochSec) {
        return epochSec <= 0 ? "—"
                : TS.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault()));
    }

    private static String dur(long from, long to) {
        if (from <= 0 || to <= 0 || to < from) return "—";
        long sec = to - from;
        return sec < 60 ? sec + " 秒" : (sec / 60) + " 分 " + (sec % 60) + " 秒";
    }

    private static String pct(long v, long total) {
        if (total <= 0) return "0.0%";
        return Math.round(v * 1000.0 / total) / 10.0 + "%";
    }
}
