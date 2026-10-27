package com.textdiff.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * 批次情况总结 Excel（一个分区一个 Sheet）：
 *  Sheet1 批次概览（指标 kv）；Sheet2 偏离度（全部文本，内容/条数双指标 + 等级）；
 *  Sheet3 关注字段监测（DISCARD_COLS 同源全量行）。
 * 与 BatchSummaryHtml/summary-report JSON 同一数据源（DiscardController.summaryPayload）。
 */
public final class BatchSummaryExcel {
    private BatchSummaryExcel() {}

    public static byte[] render(Map<String, Object> p) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle head = headStyle(wb);
            sheetOverview(wb, head, p);
            sheetDeviation(wb, head, p);
            sheetDiscard(wb, head, p);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            wb.write(buf);
            return buf.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("批次总结 Excel 生成失败: " + e.getMessage(), e);
        }
    }

    // ---- Sheet1 批次概览 ----

    private static void sheetOverview(XSSFWorkbook wb, CellStyle head, Map<String, Object> p) {
        Sheet sh = wb.createSheet("批次概览");
        String srcA = str(p.get("src_a"), "A");
        String srcB = str(p.get("src_b"), "B");
        String[][] rows = {
                {"批次ID", str(p.get("batch_id"))},
                {"标签", str(p.get("label"))},
                {"批次类型", batchTypeText(p.get("batch_type"))},
                {"状态", statusText(p.get("status"))},
                {"对比范围", BatchSummaryHtml.scopeText(p.get("group_name"))},
                {"对比批次", str(p.get("cmp_batch"), "")},
                {"轮次", str(p.get("cmp_round"), "")},
                {srcA + " 目录", str(p.get("dir_a"))},
                {srcB + " 目录", str(p.get("dir_b"))},
                {"开始时间", fmtTime(p.get("started_at"))},
                {"完成时间", fmtTime(p.get("finished_at"))},
                {"文本总数", str(p.get("total_files"), "0")},
                {"差异文本数", str(p.get("diff_files"), "0")},
                {"已完成", str(p.get("done"), "0")},
                {"失败", str(p.get("failed"), "0")},
                {"无规则文件", str(p.get("no_rule_count"), "0")},
                {"缺失文件", str(p.get("missing_count"), "0")},
                {"A 来源命名", srcA},
                {"B 来源命名", srcB},
                {"报告生成时间", fmtTime(p.get("generated_at"))},
        };
        int r = 0;
        for (String[] kv : rows) {
            Row row = sh.createRow(r++);
            cell(row, 0, kv[0], head);
            cell(row, 1, kv[1]);
        }
        sh.setColumnWidth(0, 18 * 256);
        sh.setColumnWidth(1, 60 * 256);
    }

    // ---- Sheet2 偏离度 ----

    @SuppressWarnings("unchecked")
    private static void sheetDeviation(XSSFWorkbook wb, CellStyle head, Map<String, Object> p) {
        Sheet sh = wb.createSheet("偏离度");
        String[] heads = {"表名(昵称)", "归属组", "文件名", "内容偏离度", "内容等级",
                "条数偏离度", "条数等级", "完全匹配", "有差异", "仅A", "仅B", "A总条数", "B总条数"};
        Row r0 = sh.createRow(0);
        for (int c = 0; c < heads.length; c++) cell(r0, c, heads[c], head);
        List<Map<String, Object>> devs = (List<Map<String, Object>>) p.getOrDefault("deviations", List.of());
        int r = 1;
        for (Map<String, Object> d : devs) {
            Row row = sh.createRow(r++);
            cell(row, 0, str(d.get("nickname")));
            cell(row, 1, str(d.get("group")));
            cell(row, 2, str(d.get("file_name")));
            cell(row, 3, pct(d, "content_ratio"));
            cell(row, 4, str(d.get("content_grade")));
            cell(row, 5, pct(d, "count_ratio"));
            cell(row, 6, str(d.get("count_grade")));
            cell(row, 7, num(d.get("equal")));
            cell(row, 8, num(d.get("diff")));
            cell(row, 9, num(d.get("only_a")));
            cell(row, 10, num(d.get("only_b")));
            cell(row, 11, num(d.get("total_a")));
            cell(row, 12, num(d.get("total_b")));
        }
        widths(sh, heads.length);
        note(sh, r, "内容偏离度=（有差异+仅A+仅B）/A总条数；条数偏离度=|B总条数−A总条数|/A总条数；"
                + "分级 ≥50% 严重问题、≥20% 务必关注、≥10% 微量偏移。");
    }

    // ---- Sheet3 关注字段监测 ----

    @SuppressWarnings("unchecked")
    private static void sheetDiscard(XSSFWorkbook wb, CellStyle head, Map<String, Object> p) {
        Sheet sh = wb.createSheet("关注字段监测");
        Map<String, Object> discard = (Map<String, Object>) p.getOrDefault("discard", Map.of());
        String srcA = str(discard.get("src_a"), str(p.get("src_a"), "A"));
        String srcB = str(discard.get("src_b"), str(p.get("src_b"), "B"));
        String[] heads = {"昵称", "归属组", "实际文件名", "字段序号", "字段名", "字段含义",
                srcA + "的所有取值", srcB + "的所有取值", "是否匹配"};
        Row r0 = sh.createRow(0);
        for (int c = 0; c < heads.length; c++) cell(r0, c, heads[c], head);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) discard.getOrDefault("rows", List.of());
        int r = 1;
        for (Map<String, Object> d : rows) {
            Row row = sh.createRow(r++);
            cell(row, 0, str(d.get("nickname")));
            cell(row, 1, str(d.get("group")));
            cell(row, 2, str(d.get("file_name")));
            cell(row, 3, num(d.get("col_seq")));
            cell(row, 4, str(d.get("field_name")));
            cell(row, 5, str(d.get("field_meaning")));
            cell(row, 6, DiscardColsService.valuesCell(d, true, srcA));
            cell(row, 7, DiscardColsService.valuesCell(d, false, srcB));
            cell(row, 8, DiscardColsService.matchText(d));
        }
        widths(sh, heads.length);
        sh.setColumnWidth(6, 60 * 256);
        sh.setColumnWidth(7, 60 * 256);
        note(sh, r, Boolean.TRUE.equals(discard.get("hit"))
                ? "取值按首次出现顺序去重（每侧上限 " + DiscardColsService.MAX_VALUES
                        + " 个）；（空）= 栏位存在但值为空。是否匹配：新线取值为旧线取值的子集或相等 → 新线输出正常，否则 → 新线不为旧线子集。"
                : "本批次无可监测命中文本。");
    }

    // ---- helpers ----

    private static CellStyle headStyle(XSSFWorkbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        st.setFont(f);
        return st;
    }

    private static void cell(Row row, int col, String v) {
        cell(row, col, v, null);
    }

    private static void cell(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        if (style != null) c.setCellStyle(style);
    }

    private static void widths(Sheet sh, int n) {
        for (int c = 0; c < n; c++) sh.setColumnWidth(c, 16 * 256);
    }

    private static void note(Sheet sh, int rowIdx, String text) {
        Row row = sh.createRow(rowIdx + 1);
        cell(row, 0, text);
    }

    private static String pct(Map<String, Object> m, String key) {
        double v = Double.parseDouble(str(m.get(key), "0"));
        return String.format("%.1f", v * 100) + "%";
    }

    private static String num(Object v) {
        return v == null ? "0" : String.valueOf(v);
    }

    /** 状态中文化（与 HTML 报告同文案）。 */
    static String statusText(Object s) {
        return switch (s == null ? "" : String.valueOf(s)) {
            case "done" -> "已完成";
            case "running" -> "执行中";
            case "preparing" -> "准备中";
            case "error" -> "异常";
            default -> s == null ? "" : String.valueOf(s);
        };
    }

    /** 批次类型中文化。 */
    static String batchTypeText(Object t) {
        return switch (t == null ? "file" : String.valueOf(t)) {
            case "file", "" -> "目录对比";
            case "report" -> "报表对比";
            case "custom" -> "自定义格式对比";
            case "ctis" -> "CTIS对比";
            default -> String.valueOf(t);
        };
    }

    private static String fmtTime(Object epochSec) {
        long t = 0;
        try {
            t = Long.parseLong(str(epochSec, "0"));
        } catch (NumberFormatException ignored) {
        }
        if (t <= 0) return "—";
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochSecond(t));
    }

    private static String str(Object v, String dft) {
        return v == null ? dft : String.valueOf(v);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
