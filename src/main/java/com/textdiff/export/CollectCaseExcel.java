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
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 差异采集对比案例 Excel（CollectComparisonCaseTemplate，21 列，sheet 名 data）：
 * 单文本对比结果的差异导出改为该模板。行粒度：主键匹配但存在差异 → 一条字段差异一行
 * （I 栏位索引 = 差异栏位列号 1-based，J 错误类型 = 栏位值不匹配，K 存在问题 = {别名}：值    {别名}：值）；
 * 单方存在（仅 A/仅 B）→ 一条记录一行，I = 0，J = 侧别固定文案，K = {存在侧}：主键值    {缺失侧}：(无)。
 * 列取数：A 采集日期 = 压缩文件名结尾 9 位数字的前 8 位，否则回退尾部信息 DataStartDate，
 * 再无则取生成采集表当日；B/C 批次/轮次 = 提交时对比界面指定值；D 重复轮次 = 预留空列；
 * E 银行号 = 文件名首段第 4–6 位数字（如 01A3020D → 302），取不到写 ***；
 * F 表类型 / N 分析团队 = type_parm 的 parm_report_type / ownership_group 映射开发团队（按昵称关联）；
 * G 表名 = 对比配置昵称；H 文件名 = 压缩文件取 .gz 前扩展名，非压缩取扩展名末三位；
 * 其余列（分析人/结论/方案/状态/备注/修改时间）暂不填；U 主键id = 每行随机 24 位小写十六进制串。
 * SXSSF 流式写出（窗口 500 行），大作业差异导出不整表驻留内存。
 */
public final class CollectCaseExcel {
    private CollectCaseExcel() {}

    /** 与采集模板列序一一对应（21 列：「重复轮次」插于「轮次」与「银行号」之间）。 */
    static final String[] HEADERS = {"采集日期", "批次", "轮次", "重复轮次", "银行号", "表类型", "表名", "文件名",
            "栏位索引", "错误类型", "存在问题", "分析人", "分析说明", "分析团队",
            "问题结论大类", "问题结论小类", "修改方案", "问题状态", "备注", "修改时间", "主键id"};

    /** 错误类型固定文案（键匹配有差异）。 */
    static final String ERR_FIELD_MISMATCH = "栏位值不匹配";
    /** 错误类型固定文案：仅 A 存在（A=主机 侧有、B=信创 侧无该记录）。 */
    static final String ERR_B_SIDE_MISSING = "主机记录在信创未找到";
    /** 错误类型固定文案：仅 B 存在（B=信创 侧有、A=主机 侧无该记录）。 */
    static final String ERR_A_SIDE_MISSING = "信创记录在主机未找到";

    /** 压缩文件名日期：结尾 .gz. + 9 位数字，日期取 9 位的前 8 位（如 …az1.gz.202609271 → 20260927）。 */
    private static final Pattern GZ_DATE = Pattern.compile("\\.gz\\.(\\d{9})$", Pattern.CASE_INSENSITIVE);
    /** 压缩文件后缀（文件名列取 .gz 前扩展名用）：.gz 结尾（可带 9 位数字日期段）。 */
    private static final Pattern GZ_SUFFIX = Pattern.compile("\\.gz(?:\\.\\d{9})?$", Pattern.CASE_INSENSITIVE);
    /** 尾部信息中的 DataStartDate 栏位值（8 位日期，容忍 =/: 与空白）。 */
    private static final Pattern DATA_START = Pattern.compile("DataStartDate\\s*[=:：]?\\s*((?:19|20)\\d{6})");

    /** 存在问题分隔：{别名}：{值}    {别名}：{值}（全角冒号 + 四空格）。 */
    static final String PROBLEM_SEP = "    ";
    /** 单方存在行缺失侧占位。 */
    static final String NO_RECORD = "(无)";

    /** 归属组 → 开发团队映射（需求：采集表分析团队）：顺序前缀匹配，命中即返回；未命中原样透传。 */
    private static final String[][] TEAM_MAP = {
            {"bocs_dep", "存款"},
            {"bocs_loan", "贷款"}, {"bocs_bgl", "贷款"}, {"bocs_fee", "贷款"}, {"loan", "贷款"},
            {"bocs_cif", "CIF"},
            {"bocs_kernel", "核心"}, {"bocs_s", "核心"}, {"bocs_parm", "核心"},
            {"bocs_batch", "主批"}, {"batch", "主批"},
            {"bocs_voucher", "借记卡"}, {"bocs_card", "借记卡"},
            {"bocs_pay", "支付"},
    };

    private static final SecureRandom RND = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 导出单作业差异采集表；返回落盘文件。batch 可空（独立作业无批次 → B/C 列空）。 */
    public static Path write(Path out, JobRecord job, BatchRecord batch, String parmType, String ownershipGroup) {
        CompareConfig cfg = configOf(job);
        String nick = DiscardColsService.monitorKey(job);
        String dateA = collectDate(job);
        String bank = bankNoOf(fileName(job.fileA), fileName(job.fileB));
        String fileType = fileTypeOf(fileName(job.fileA));
        String cmpBatch = batch == null ? "" : nvl(batch.cmpBatch);
        String cmpRound = batch == null ? "" : nvl(batch.cmpRound);
        String type = nvl(parmType);
        String team = devTeam(ownershipGroup);
        String srcA = blankTo(cfg.sourceA, "A");
        String srcB = blankTo(cfg.sourceB, "B");

        try (Workbook wb = new SXSSFWorkbook(500)) {
            Sheet sheet = wb.createSheet("data");
            CellStyle head = headStyle(wb);
            Row hr = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
            }
            int[] widths = {12, 10, 8, 10, 10, 10, 22, 10, 10, 26, 50, 10, 10, 12,
                    10, 10, 10, 10, 10, 10, 26};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

            int[] r = {1};
            try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
                stream.filter(CollectCaseExcel::isCaseRow).forEach(row -> emit(sheet, r, row, cfg,
                        cmpBatch, cmpRound, bank, fileType, dateA, nick, type, team, srcA, srcB));
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
     * 标识列（采集日期~文件名）每行重复填，便于下载后按列筛选与多文本合并；
     * 主键id 每行独立随机生成（24 位小写十六进制）。
     */
    private static void emit(Sheet sheet, int[] cursor, RowDiff row, CompareConfig cfg,
                             String cmpBatch, String cmpRound, String bank, String fileType,
                             String date, String nick, String type, String team, String srcA, String srcB) {
        if (Status.DIFF.equals(row.status)) {
            for (int col : row.diffCols) {
                Row r = sheet.createRow(cursor[0]++);
                text(r, 0, date);
                text(r, 1, cmpBatch);
                text(r, 2, cmpRound);
                text(r, 3, ""); // 重复轮次：预留列，默认无赋值（人工填写）
                text(r, 4, bank);
                text(r, 5, type);
                text(r, 6, nick);
                text(r, 7, fileType);
                text(r, 13, team);
                String aVal = col >= CsvExporter.VBASE ? CsvExporter.dynVal(row, col, true) : at(row.aCols, col);
                String bVal = col >= CsvExporter.VBASE ? CsvExporter.dynVal(row, col, false) : at(row.bCols, col);
                if (col >= CsvExporter.VBASE) {
                    var d = CsvExporter.dynOf(null, col);
                    text(r, 8, d == null ? String.valueOf(col) : d.label());
                } else {
                    r.createCell(8).setCellValue(col + 1);
                }
                text(r, 9, ERR_FIELD_MISMATCH);
                text(r, 10, srcA + "：" + aVal + PROBLEM_SEP + srcB + "：" + bVal);
                text(r, 20, caseId());
            }
        } else {
            boolean sideA = Status.UNMATCHED_A.equals(row.status);
            String existSide = sideA ? srcA : srcB;
            String missSide = sideA ? srcB : srcA;
            Row r = sheet.createRow(cursor[0]++);
            text(r, 0, date);
            text(r, 1, cmpBatch);
            text(r, 2, cmpRound);
            text(r, 3, ""); // 重复轮次：预留列，默认无赋值（人工填写）
            text(r, 4, bank);
            text(r, 5, type);
            text(r, 6, nick);
            text(r, 7, fileType);
            text(r, 13, team);
            r.createCell(8).setCellValue(0);
            text(r, 9, sideA ? ERR_B_SIDE_MISSING : ERR_A_SIDE_MISSING);
            text(r, 10, existSide + "：" + CsvExporter.displayKey(row.key) + PROBLEM_SEP + missSide + "：" + NO_RECORD);
            text(r, 20, caseId());
        }
    }

    private static void text(Row r, int col, String v) {
        r.createCell(col).setCellValue(v == null ? "" : v);
    }

    private static String at(String[] cols, int i) {
        return cols != null && i < cols.length && cols[i] != null ? cols[i] : "";
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s.strip();
    }

    /** configLine 解析失败（custom 的 JSON 行等）回退：昵称空、来源名缺省、分隔符默认。 */
    private static CompareConfig configOf(JobRecord job) {
        try {
            return Rules.parseLegacy(job.configLine == null ? "" : job.configLine, " | ", "|||||");
        } catch (RuntimeException e) {
            return new CompareConfig();
        }
    }

    /**
     * 采集日期（三级）：①压缩文件名结尾 .gz + 9 位数字 → 取 9 位的前 8 位（A 侧文件名优先，B 侧兜底）；
     * ②否则读结果文件尾 DataStartDate 栏位（如存在）；③再无 → 生成采集表当日。
     */
    static String collectDate(JobRecord job) {
        String fromName = dateFromName(fileName(job.fileA));
        if (!fromName.isEmpty()) return fromName;
        fromName = dateFromName(fileName(job.fileB));
        if (!fromName.isEmpty()) return fromName;
        String fromTrailer = dateFromTrailer(job);
        if (!fromTrailer.isEmpty()) return fromTrailer;
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 压缩文件名日期：结尾 .gz.9 位数字的前 8 位；非压缩/无 9 位尾缀返回空串。 */
    static String dateFromName(String name) {
        if (name == null || name.isEmpty()) return "";
        Matcher m = GZ_DATE.matcher(name);
        return m.find() ? m.group(1).substring(0, 8) : "";
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
            return ""; // 结果文件缺失/损坏不阻断导出，走自然日兜底
        }
    }

    /**
     * 银行号：文件名首段（第一个 . 前）第 4–6 位字符全为数字才写入（01A3020D → 302），
     * 否则 *** 。A 侧文件名优先，B 侧兜底。
     */
    static String bankNoOf(String nameA, String nameB) {
        String v = bankPart(nameA);
        if (!"***".equals(v)) return v;
        return bankPart(nameB);
    }

    private static String bankPart(String name) {
        if (name == null || name.isEmpty()) return "***";
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.indexOf('.');
        String head = dot >= 0 ? n.substring(0, dot) : n;
        if (head.length() >= 6) {
            String mid = head.substring(3, 6);
            if (mid.chars().allMatch(Character::isDigit)) return mid;
        }
        return "***";
    }

    /**
     * 文件名列：压缩文件取 .gz 前的扩展名（01A3020D.az1.gz.202609271 → az1）；
     * 非压缩取扩展名末三位（01A3020D.az1 → az1，01A3020D.v01 → v01）；无扩展名空串。
     */
    static String fileTypeOf(String name) {
        if (name == null || name.isEmpty()) return "";
        String n = name.replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        Matcher gz = GZ_SUFFIX.matcher(n);
        if (gz.find()) return extPart(n.substring(0, gz.start()));
        String e = extPart(n);
        return e.length() <= 3 ? e : e.substring(e.length() - 3);
    }

    private static String extPart(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).trim();
        if (ext.isEmpty() || ext.contains("/") || ext.contains("\\")) return "";
        return ext;
    }

    /**
     * 分析团队 = 归属组映射开发团队（需求）：前缀匹配，命中即返回映射值；
     * 未命中映射的归属组原样输出，未导入/为空返回空串。
     */
    static String devTeam(String ownershipGroup) {
        String g = nvl(ownershipGroup).strip();
        if (g.isEmpty()) return "";
        String low = g.toLowerCase();
        for (String[] m : TEAM_MAP) {
            if (low.startsWith(m[0])) return m[1];
        }
        return g;
    }

    /** 主键id：每行随机生成的 24 位小写十六进制串（a–f/0–9）。 */
    static String caseId() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) sb.append(HEX[RND.nextInt(16)]);
        return sb.toString();
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
