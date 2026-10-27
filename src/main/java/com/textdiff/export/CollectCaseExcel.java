package com.textdiff.export;

import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Rules;
import com.textdiff.engine.Status;
import com.textdiff.store.BatchRecord;
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
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 差异采集对比案例 Excel（CollectComparisonCaseTemplate，20 列，sheet 名 data）：
 * 单文本对比结果的差异导出改为该模板。行粒度：主键匹配但存在差异 → 一条字段差异一行
 * （H 栏位索引 = 差异栏位列号 1-based，I 错误类型 = A:'..' B:'..'）；
 * 单方存在（仅 A/仅 B）→ 一条记录一行，H = 0，I = A/B:'整行原貌'。
 * 列取数：A 采集日期 = A 侧文件名日期后缀，缺省回退尾部信息 DataStartDate 栏位（均无则空）；
 * B/C 批次/轮次 = 提交时对比界面指定值；D 银行号 = 配置通配符（01A***0D.v01 的 *** 或 #BANKNO）；
 * E 表类型 / M 分析团队 = type_parm 的 parm_report_type / ownership_group（按昵称关联，未导入为空）；
 * F 表名 = 对比配置昵称；G 文件名 = 实际文件扩展名（如 v01）；
 * 其余列（存在问题/分析人/结论/方案/状态/备注/修改时间/主键id）暂不填。
 * SXSSF 流式写出（窗口 500 行），大作业差异导出不整表驻留内存。
 */
public final class CollectCaseExcel {
    private CollectCaseExcel() {}

    /** 与采集模板列序一一对应。 */
    static final String[] HEADERS = {"采集日期", "批次", "轮次", "银行号", "表类型", "表名", "文件名",
            "栏位索引", "错误类型", "存在问题", "分析人", "分析说明", "分析团队",
            "问题结论大类", "问题结论小类", "修改方案", "问题状态", "备注", "修改时间", "主键id"};

    /** 文件名中的日期后缀（YYYYMMDD，1900/20xx 世纪，月日合法），取文件名中最后一段命中。 */
    private static final Pattern DATE_SUFFIX =
            Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)");
    /** 尾部信息中的 DataStartDate 栏位值（8 位日期，容忍 =/: 与空白）。 */
    private static final Pattern DATA_START = Pattern.compile("DataStartDate\\s*[=:：]?\\s*((?:19|20)\\d{6})");
    /** 配置通配符：命名占位 #BANKNO 优先（名称不以 _ 结尾——尾部下划线视作分隔符），其次 * 连续段（*** ）。 */
    private static final Pattern NAMED_WILD = Pattern.compile("#[A-Za-z0-9_]*[A-Za-z0-9]");
    private static final Pattern STAR_WILD = Pattern.compile("\\*+");

    /** 导出单作业差异采集表；返回落盘文件。batch 可空（独立作业无批次 → B/C 列空）。 */
    public static Path write(Path out, JobRecord job, BatchRecord batch, String parmType, String ownershipGroup) {
        CompareConfig cfg = configOf(job);
        String nick = DiscardColsService.monitorKey(job);
        String glob = cfg.fileGlob == null ? "" : cfg.fileGlob;
        String dateA = collectDate(job);
        String wild = wildcardOf(glob);
        String ext = extOf(fileName(job.fileA), glob);
        String cmpBatch = batch == null ? "" : nvl(batch.cmpBatch);
        String cmpRound = batch == null ? "" : nvl(batch.cmpRound);
        String type = nvl(parmType);
        String team = nvl(ownershipGroup);

        try (Workbook wb = new SXSSFWorkbook(500)) {
            Sheet sheet = wb.createSheet("data");
            CellStyle head = headStyle(wb);
            Row hr = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
            }
            int[] widths = {12, 10, 8, 12, 10, 22, 12, 10, 40, 10, 10, 10, 12,
                    10, 10, 10, 10, 10, 10, 10};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

            int[] r = {1};
            try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
                stream.filter(CollectCaseExcel::isCaseRow).forEach(row -> emit(sheet, r, row, cfg,
                        cmpBatch, cmpRound, wild, ext, dateA, nick, type, team));
            } catch (RuntimeException e) {
                throw new IllegalStateException("差异采集表读取失败: " + job.id + "（" + e.getMessage() + "）", e);
            }
            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            try (var fos = new java.io.FileOutputStream(out.toFile())) {
                wb.write(fos);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("差异采集表写入失败: " + out, e);
        }
    }

    /** 采集行 = 数据区 且（有差异 或 单方存在）；完全匹配/尾部区不出现在采集表。 */
    private static boolean isCaseRow(RowDiff row) {
        if (!Status.SECTION_DATA.equals(row.section)) return false;
        return Status.DIFF.equals(row.status)
                || Status.UNMATCHED_A.equals(row.status) || Status.UNMATCHED_B.equals(row.status);
    }

    /**
     * 一条采集记录展开为一行（主键匹配但存在差异 → 逐差异栏位各一行）；
     * 标识列（采集日期~文件名）每行重复填，便于下载后按列筛选与多文本合并。
     */
    private static void emit(Sheet sheet, int[] cursor, RowDiff row, CompareConfig cfg,
                             String cmpBatch, String cmpRound, String wild, String ext,
                             String date, String nick, String type, String team) {
        if (Status.DIFF.equals(row.status)) {
            for (int col : row.diffCols) {
                Row r = sheet.createRow(cursor[0]++);
                text(r, 0, date);
                text(r, 1, cmpBatch);
                text(r, 2, cmpRound);
                text(r, 3, wild);
                text(r, 4, type);
                text(r, 5, nick);
                text(r, 6, ext);
                text(r, 12, team);
                if (col >= CsvExporter.VBASE) {
                    var d = CsvExporter.dynOf(null, col);
                    text(r, 7, d == null ? String.valueOf(col) : d.label());
                    text(r, 8, "A:'" + CsvExporter.dynVal(row, col, true)
                            + "' B:'" + CsvExporter.dynVal(row, col, false) + "'");
                } else {
                    r.createCell(7).setCellValue(col + 1);
                    text(r, 8, "A:'" + at(row.aCols, col) + "' B:'" + at(row.bCols, col) + "'");
                }
            }
        } else {
            Row r = sheet.createRow(cursor[0]++);
            text(r, 0, date);
            text(r, 1, cmpBatch);
            text(r, 2, cmpRound);
            text(r, 3, wild);
            text(r, 4, type);
            text(r, 5, nick);
            text(r, 6, ext);
            text(r, 12, team);
            r.createCell(7).setCellValue(0);
            boolean sideA = Status.UNMATCHED_A.equals(row.status);
            String line = sideA ? lineOf(row.aRaw, row.aCols, cfg) : lineOf(row.bRaw, row.bCols, cfg);
            text(r, 8, (sideA ? "A:'" : "B:'") + line + "'");
        }
    }

    private static void text(Row r, int col, String v) {
        r.createCell(col).setCellValue(v == null ? "" : v);
    }

    private static String at(String[] cols, int i) {
        return cols != null && i < cols.length && cols[i] != null ? cols[i] : "";
    }

    /** 整行原貌：优先 raw（报表折行记录含 \n），回退按分隔符拼接栏位。 */
    private static String lineOf(String raw, String[] cols, CompareConfig cfg) {
        if (raw != null && !raw.isEmpty()) return raw;
        if (cols == null) return "";
        return String.join(cfg.delimiter == null ? " | " : cfg.delimiter, cols);
    }

    /** configLine 解析失败（custom 的 JSON 行等）回退：昵称空、通配符空、分隔符默认。 */
    private static CompareConfig configOf(JobRecord job) {
        try {
            return Rules.parseLegacy(job.configLine == null ? "" : job.configLine, " | ", "|||||");
        } catch (RuntimeException e) {
            return new CompareConfig();
        }
    }

    /**
     * 采集日期：A 侧文件名日期后缀（取最后一段命中，如 XXX_20250916.dat → 20250916）；
     * 无则回退尾部信息 DataStartDate 栏位；均无为空串。A 侧无文件名时试 B 侧。
     */
    static String collectDate(JobRecord job) {
        String fromName = dateFromName(fileName(job.fileA));
        if (!fromName.isEmpty()) return fromName;
        fromName = dateFromName(fileName(job.fileB));
        if (!fromName.isEmpty()) return fromName;
        return dateFromTrailer(job);
    }

    static String dateFromName(String name) {
        if (name == null || name.isEmpty()) return "";
        Matcher m = DATE_SUFFIX.matcher(name);
        String last = "";
        while (m.find()) last = m.group(1) + m.group(2) + m.group(3);
        return last;
    }

    /** 尾部区扫描：键值/整行原貌含 DataStartDate 即提取（首个命中返回）。 */
    private static String dateFromTrailer(JobRecord job) {
        if (job.resultDir == null || job.resultDir.isBlank()) return "";
        try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            var found = stream.filter(r -> Status.SECTION_TRAILER.equals(r.section))
                    .filter(r -> DATA_START.matcher(nvl(r.key) + " " + nvl(r.aRaw) + " " + nvl(r.bRaw))
                            .find())
                    .findFirst();
            if (found.isEmpty()) return "";
            RowDiff r = found.get();
            for (String s : new String[]{r.key, r.aRaw, r.bRaw}) {
                Matcher m = DATA_START.matcher(nvl(s));
                if (m.find()) return m.group(1);
            }
            return "";
        } catch (RuntimeException e) {
            return ""; // 结果文件缺失/损坏不阻断导出，日期列留空
        }
    }

    /** 通配符：#BANKNO 等命名占位优先，否则 *** 等 * 连续段；无则空串。 */
    static String wildcardOf(String fileGlob) {
        if (fileGlob == null || fileGlob.isEmpty()) return "";
        Matcher m = NAMED_WILD.matcher(fileGlob);
        if (m.find()) return m.group();
        m = STAR_WILD.matcher(fileGlob);
        if (m.find()) return m.group();
        return "";
    }

    /** 扩展名：实际文件名优先（如 XX_20250916.v01 → v01），回退通配名扩展；均无空串（不带点）。 */
    static String extOf(String name, String fileGlob) {
        String e = extPart(name);
        return e.isEmpty() ? extPart(fileGlob) : e;
    }

    private static String extPart(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).trim();
        if (ext.isEmpty() || ext.contains("/") || ext.contains("\\")) return "";
        return ext;
    }

    static String fileName(String path) {
        if (path == null || path.isEmpty()) return "";
        String n = path.replace('\\', '/');
        int i = n.lastIndexOf('/');
        return i < 0 ? n : n.substring(i + 1);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static CellStyle headStyle(Workbook wb) {
        CellStyle head = wb.createCellStyle();
        Font bold = wb.createFont();
        bold.setBold(true);
        head.setFont(bold);
        head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return head;
    }
}
