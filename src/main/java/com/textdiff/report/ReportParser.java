package com.textdiff.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 报表结构解析：把一份报表文本切成 表头块 / 业务行 / 表尾块 三个分区。支持两类版式：
 *
 * 1）模板版式（header 文件）：模板 = 报表骨架（值留空），一份报表文件可含多个报表段，每段 = 表头块
 * （模板表头逐行对应、值已填充）+ 业务行（替换模板中表头后的空白占位行）+ 表尾块（模板尾部骨架，
 * 值可能已填充）。分区算法（由 CORD9000/CRDD0020/DEPD8920/PYDD1040 四类样本归纳）：
 *  1. 表头长度 H = 模板与报表按行对齐时「逐行完全相等且非空白、且模板下一行为空白占位或模板结束」的
 *     最大行号（列头/划线行必逐字一致，可作锚点）；
 *  2. 表尾骨架 = 表头之后跳过一个空白占位行后的全部模板尾行（占位行被业务行替换，其余骨架行保留并填充值）；
 *  3. 报表段以表头锚点行（模板第 H 行原文）在报表中的出现位置分段，段内锚点之后到下一段表头之前为
 *     内容区：剥掉尾部空白（段间分隔空行）后，若内容区尾部与表尾骨架逐行对应（完全相等 / 空白对空白 /
     * 值填充形态匹配）则划为表尾块，其余非空白行为业务行。
 *
 * 1a）空行占位版式（用户指定规则）：模板中首个「≥5 连续空行且其后仍有骨架行」的空行段 = 报表体占位，
 * 段前（模板第一行起）= 表头骨架，段后 = 表尾骨架（去首尾空白行）。业务行整体替换空行段；
 * 段定位优先表头尾行精确锚点，失败时按表头骨架整块匹配（相等 / 值填充 / 竖线标签形态）兜底。
 * 适用模板：表头块与表尾骨架之间以 5~10 个连续空行标注报表体范围（如 PYDD1040 空行占位模板）。
 *
 * 2）控制行版式（自分区，无需模板）：以控制行 {@code 1@OD@|@T@|BANK-CODE:..|RPT-ID:..|..} 分段
 * （由 DEPD6020/PYID0200/PYID0210/CRDD0190 样本归纳），支持：
 *  - 折行报表：列头因栏位过多折成多行（F 行），每条业务记录同样折 F 行（如 DEPD602U 表头 2 行、
 *    记录 = 主行 + TX Time 续行；PYID021U 表头/记录均 3 行）。解析与展示保持折行原貌（{@link Row#display}），
 *    字段按物理行 ≥2 空格切分后拼接参与排序/匹配/差异定位；
 *  - 分页：同一报表段跨页时逐页重复 页标/标题/下划线/BRCH/A/C Type/列头，按列头原文重复定位页边界，
 *    页首重复块跳过（样本：DEPD602U 段 18/26/37、PYID020U 全段 5 页）；
 *  - 段签名：控制行原文作段标识，供双侧按签名配对表头/表尾块（免疫段序差异）。
 */
public final class ReportParser {
    private ReportParser() {}

    /** 业务行：所在段号（0-based）、行号（1-based、首物理行）、原始行、切分字段、排序键、折行原貌（可空）。 */
    public record Row(int section, int lineNo, String raw, String[] fields, String sortKey, String[] display) {
        public Row(int section, int lineNo, String raw, String[] fields, String sortKey) {
            this(section, lineNo, raw, fields, sortKey, null);
        }
    }

    /** 解析结果：表头/表尾各段块（原始行数组）+ 全部业务行 + 解析告警 + 控制行版式附加信息。 */
    public record ParsedReport(int headerLen, List<String[]> headerBlocks, List<String[]> footerBlocks,
                               List<Row> rows, List<String> warnings,
                               boolean controlFormat, List<String> sectionKeys, List<String> columnNames) {
        /** 模板版式兼容构造器。 */
        public ParsedReport(int headerLen, List<String[]> headerBlocks, List<String[]> footerBlocks,
                            List<Row> rows, List<String> warnings) {
            this(headerLen, headerBlocks, footerBlocks, rows, warnings, false, List.of(), List.of());
        }
    }

    /** 业务行字段切分：含竖线按竖线切（行尾竖线的尾空块丢弃）并去两端空白；否则按 ≥2 连续空格切。 */
    public static String[] splitFields(String raw) {
        if (raw.indexOf('|') >= 0) {
            String[] parts = raw.split("\\|", -1);
            int end = parts.length;
            if (end > 0 && raw.strip().endsWith("|") && parts[end - 1].isBlank()) end--;
            String[] out = new String[end];
            for (int i = 0; i < end; i++) out[i] = parts[i].strip();
            return out;
        }
        String s = raw.strip();
        if (s.isEmpty()) return new String[]{""};
        return s.split("\\s{2,}");
    }

    /** 排序键：全字段以 0x1F 连接（无主键场景下以整行字段为键的确定性全序）。 */
    public static String sortKey(String[] fields) {
        return String.join("\u001F", fields);
    }

    public static ParsedReport parse(List<String> template, List<String> report) {
        List<String> warnings = new ArrayList<>();
        int m = template.size(), n = report.size();
        // 新版式（空行占位）：模板首个「≥5 连续空行且其后仍有骨架行」的空行段 = 报表体占位，
        // 段前 = 表头骨架（整块），段后 = 表尾骨架（用户规则 5~10 空行代表报表体）。
        int[] blankRun = firstBlankRun(template, 5);
        if (blankRun != null) {
            return parseZonedTemplate(template, report, blankRun[0], blankRun[1], warnings);
        }
        int headerLen = detectHeaderLen(template, report);
        if (headerLen == 0) {
            warnings.add("未能从模板定位表头锚点（模板与报表无逐行一致的非空白行），整文件按业务内容处理");
        }
        // 表尾骨架：表头后跳过一个空白占位行
        List<String> footerZone = new ArrayList<>();
        if (headerLen > 0 && headerLen < m) {
            int fs = template.get(headerLen).isBlank() ? headerLen + 1 : headerLen;
            footerZone.addAll(template.subList(Math.min(fs, m), m));
        }

        List<String[]> headerBlocks = new ArrayList<>();
        List<String[]> footerBlocks = new ArrayList<>();
        List<Row> rows = new ArrayList<>();

        if (headerLen == 0) {
            for (int i = 0; i < n; i++) {
                String raw = report.get(i);
                if (raw.isBlank()) continue;
                String[] f = splitFields(raw);
                rows.add(new Row(0, i + 1, raw, f, sortKey(f)));
            }
            return new ParsedReport(0, headerBlocks, footerBlocks, rows, warnings);
        }

        String anchor = template.get(headerLen - 1);
        List<Integer> anchors = new ArrayList<>();
        for (int i = headerLen - 1; i < n; i++) {
            if (report.get(i).equals(anchor)) anchors.add(i);
        }
        if (anchors.isEmpty()) {
            // 表头尾行为值填充（无精确锚点）时按骨架整块匹配兜底（相等/值填充/竖线标签形态）
            anchors = locateHeaderBlocks(template.subList(0, headerLen), report);
            if (anchors.isEmpty()) {
                warnings.add("未能按模板表头骨架定位任何报表段，整文件按业务内容处理");
                for (int i = 0; i < n; i++) {
                    String raw = report.get(i);
                    if (raw.isBlank()) continue;
                    String[] f = splitFields(raw);
                    rows.add(new Row(0, i + 1, raw, f, sortKey(f)));
                }
                return new ParsedReport(headerLen, headerBlocks, footerBlocks, rows, warnings);
            }
        }
        return assembleSections(report, headerLen, anchors, footerZone, warnings,
                headerBlocks, footerBlocks, rows);
    }

    /**
     * 空行占位版式：表头骨架 = 模板 [0, bs)，表尾骨架 = 模板 [be, m)（去首尾空白行），
     * [bs, be) 的连续空行 = 报表体占位（被业务行替换）。段定位优先表头尾行精确锚点，
     * 失败时按骨架整块匹配（相等 / 值填充 / 竖线标签形态）。
     */
    private static ParsedReport parseZonedTemplate(List<String> template, List<String> report,
                                                   int bs, int be, List<String> warnings) {
        int m = template.size(), n = report.size();
        int headerLen = bs;
        List<String> footerZone = new ArrayList<>();
        int fs = be;
        while (fs < m && template.get(fs).isBlank()) fs++;
        int fe = m;
        while (fe > fs && template.get(fe - 1).isBlank()) fe--;
        footerZone.addAll(template.subList(fs, fe));

        List<String[]> headerBlocks = new ArrayList<>();
        List<String[]> footerBlocks = new ArrayList<>();
        List<Row> rows = new ArrayList<>();

        String anchor = template.get(headerLen - 1);
        List<Integer> anchors = new ArrayList<>();
        for (int i = headerLen - 1; i < n; i++) {
            if (report.get(i).equals(anchor)) anchors.add(i);
        }
        if (anchors.isEmpty()) {
            anchors = locateHeaderBlocks(template.subList(0, headerLen), report);
        }
        if (anchors.isEmpty()) {
            warnings.add("未能按模板表头骨架（空行占位规则，表头 " + headerLen + " 行）定位任何报表段，整文件按业务内容处理");
            for (int i = 0; i < n; i++) {
                String raw = report.get(i);
                if (raw.isBlank()) continue;
                String[] f = splitFields(raw);
                rows.add(new Row(0, i + 1, raw, f, sortKey(f)));
            }
            return new ParsedReport(headerLen, headerBlocks, footerBlocks, rows, warnings);
        }
        return assembleSections(report, headerLen, anchors, footerZone, warnings,
                headerBlocks, footerBlocks, rows);
    }

    /** 模板首个「长度 ≥ min 连续空白且其后仍有非空白行」的空行段；返回 {start, endExclusive} 或 null。 */
    static int[] firstBlankRun(List<String> template, int min) {
        int m = template.size();
        int runStart = -1;
        for (int i = 0; i <= m; i++) {
            boolean blank = i < m && template.get(i).isBlank();
            if (blank && runStart < 0) runStart = i;
            if ((!blank || i == m) && runStart >= 0) {
                int len = i - runStart;
                if (len >= min && i < m) return new int[]{runStart, i}; // 其后仍有骨架行才是报表体占位
                runStart = -1;
            }
        }
        return null;
    }

    /** 表头骨架整块匹配定位（块尾行下标，重叠丢弃）：逐行相等 / 双空白 / 值填充 / 竖线标签形态。 */
    static List<Integer> locateHeaderBlocks(List<String> skel, List<String> report) {
        List<Integer> ends = new ArrayList<>();
        int bs = skel.size(), n = report.size();
        int prevEnd = -1;
        for (int p = bs - 1; p < n; p++) {
            int start = p - bs + 1;
            if (start <= prevEnd) continue;
            boolean ok = true;
            for (int j = 0; j < bs; j++) {
                String g = report.get(start + j);
                String t = skel.get(j);
                if (g.equals(t) || (g.isBlank() && t.isBlank()) || filledLike(g, t)
                        || pipeLabelLike(g, t)) continue;
                ok = false;
                break;
            }
            if (ok) {
                ends.add(p);
                prevEnd = p;
            }
        }
        return ends;
    }

    /** 竖线行标签形态匹配：按 | 切分段数一致，模板段（去尾空白）为报表段前缀（值填充保留标签）。 */
    static boolean pipeLabelLike(String got, String tpl) {
        if (tpl.indexOf('|') < 0 || got.indexOf('|') < 0) return false;
        String[] ts = tpl.split("\\|", -1);
        String[] gs = got.split("\\|", -1);
        if (ts.length != gs.length) return false;
        for (int i = 0; i < ts.length; i++) {
            String t = ts[i].strip();
            String g = gs[i].strip();
            if (t.equals(g)) continue;
            if (!t.isEmpty() && g.startsWith(t)) continue;
            return false;
        }
        return true;
    }

    /** 段装配（普通/空行占位两种版式共用）：重叠锚点过滤 → 逐段切分表头块 / 表尾块 / 业务行。 */
    private static ParsedReport assembleSections(List<String> report, int headerLen, List<Integer> anchors,
                                                 List<String> footerZone, List<String> warnings,
                                                 List<String[]> headerBlocks, List<String[]> footerBlocks,
                                                 List<Row> rows) {
        int n = report.size();
        // 过滤重叠锚点（锚点行偶现于业务区时按噪声丢弃）
        List<Integer> kept = new ArrayList<>();
        int prevEnd = -1; // 上一表头块最后一行下标
        for (int p : anchors) {
            // blockStart = p-headerLen+1 必须落在上一表头块之后
            if (p - headerLen >= prevEnd) {
                kept.add(p);
                prevEnd = p;
            } else {
                warnings.add("忽略重叠的表头锚点（第 " + (p + 1) + " 行）");
            }
        }
        // 表尾骨架核（去前导/尾部空白行）；核之前的表尾前导空白行按可用情况保留
        //（CRDD 段 1-10 表尾=[空行,END]，末段=[END]；空行占位版式模板尾空白不再混入骨架核）
        List<String> core = new ArrayList<>();
        int leadBlanks = 0;
        boolean seenNonBlank = false;
        for (String f : footerZone) {
            if (!seenNonBlank && f.isBlank()) leadBlanks++;
            else {
                seenNonBlank = true;
                core.add(f);
            }
        }
        while (!core.isEmpty() && core.get(core.size() - 1).isBlank()) core.remove(core.size() - 1);

        int cursor = 0; // 尚未归类的起始行（首段表头块之前若有残行，按业务行处理）
        for (int s = 0; s < kept.size(); s++) {
            int p = kept.get(s);
            int blockStart = p - headerLen + 1;
            if (blockStart > cursor) {
                warnings.add("第 " + (cursor + 1) + "~" + blockStart + " 行未匹配任何分区，按业务内容处理");
                for (int i = cursor; i < blockStart; i++) {
                    String raw = report.get(i);
                    if (raw.isBlank()) continue;
                    String[] f = splitFields(raw);
                    rows.add(new Row(s, i + 1, raw, f, sortKey(f)));
                }
            }
            headerBlocks.add(report.subList(blockStart, p + 1).toArray(new String[0]));
            int regionStart = p + 1;
            int regionEnd = (s + 1 < kept.size()) ? kept.get(s + 1) - headerLen + 1 : n; // exclusive
            if (regionEnd < regionStart) regionEnd = regionStart;
            // 剥掉内容区尾部空白（段间分隔空行 / EOF 空行）
            int end = regionEnd - 1;
            while (end >= regionStart && report.get(end).isBlank()) end--;
            // 表尾块匹配：骨架核自内容区尾部向上逐行对齐（相等 / 空白对空白 / 值填充形态）
            boolean footerOk = !core.isEmpty() && end >= regionStart
                    && end - core.size() + 1 >= regionStart;
            if (footerOk) {
                int coreStart = end - core.size() + 1;
                for (int j = 0; j < core.size(); j++) {
                    String g = report.get(coreStart + j);
                    String f = core.get(j);
                    if (!(g.equals(f) || (g.isBlank() && f.isBlank()) || filledLike(g, f))) {
                        footerOk = false;
                        break;
                    }
                }
            }
            if (footerOk) {
                int fStart = end - core.size() + 1;
                int want = leadBlanks;
                while (want > 0 && fStart - 1 >= regionStart
                        && report.get(fStart - 1).isBlank()) {
                    fStart--;
                    want--;
                }
                footerBlocks.add(report.subList(fStart, end + 1).toArray(new String[0]));
                end = fStart - 1;
            }
            for (int i = regionStart; i <= end; i++) {
                String raw = report.get(i);
                if (raw.isBlank()) continue;
                String[] f = splitFields(raw);
                rows.add(new Row(s, i + 1, raw, f, sortKey(f)));
            }
            cursor = regionEnd;
        }
        if (cursor < n) { // 尾段之后若有残行（如尾部分隔空行）忽略；非空白按业务行
            for (int i = cursor; i < n; i++) {
                String raw = report.get(i);
                if (raw.isBlank()) continue;
                warnings.add("文件尾部第 " + (i + 1) + " 行未匹配任何分区，按业务内容处理");
                String[] f = splitFields(raw);
                rows.add(new Row(Math.max(0, kept.size() - 1), i + 1, raw, f, sortKey(f)));
            }
        }
        return new ParsedReport(headerLen, headerBlocks, footerBlocks, rows, warnings);
    }

    // ---- 控制行版式（1@OD@|...）：自分区、折行、分页 ----

    /** 控制行：{@code 1@OD@|@T@|BANK-CODE:102|ORG-ID:..|RPT-ID:..|DAT:..|..}。 */
    private static final Pattern CONTROL_LINE = Pattern.compile("^\\s*\\d+@OD@\\|");
    /** 页首机构行：BRCH / BRANCH / Branch : ...。 */
    private static final Pattern BR_ANCHOR = Pattern.compile("^\\s{0,4}(?:BRCH|BRANCH|Branch)\\s*:");
    /** 页首标签行（列头之前的 「A/C Type: 50150206」 形态，冒号后带值）。 */
    private static final Pattern LABEL_LIKE = Pattern.compile("^\\s*[A-Za-z][^|:]{0,30}:\\s");
    /** 页码行：行首 1~3 位数字（可带括号页标识），如 {@code 1   ( 51365-DEPD6020 )}。 */
    private static final Pattern PAGE_MARK = Pattern.compile("^\\s*\\d{1,3}\\s*(\\(.*?\\))?\\s*$");

    public static boolean hasControlLines(List<String> report) {
        for (String line : report) {
            if (line != null && CONTROL_LINE.matcher(line).find()) return true;
        }
        return false;
    }

    /**
     * 控制行版式解析（无需模板）：控制行分段 → 段内 BRCH 锚点定位列头块（折 F 行，可含划线行）
     * → 分页（列头原文重复为页界，页首重复块跳过）→ 主行含数字推进 F 行一组读业务记录
     * → 其余为表尾。段签名 = 控制行原文。
     */
    public static ParsedReport parseControlFormat(List<String> report) {
        List<String> warnings = new ArrayList<>();
        List<String[]> headerBlocks = new ArrayList<>();
        List<String[]> footerBlocks = new ArrayList<>();
        List<String> sectionKeys = new ArrayList<>();
        List<String> columnNames = new ArrayList<>();
        List<Row> rows = new ArrayList<>();

        int n = report.size();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (CONTROL_LINE.matcher(report.get(i)).find()) starts.add(i);
        }
        for (int s = 0; s < starts.size(); s++) {
            int from = starts.get(s);
            int to = (s + 1 < starts.size()) ? starts.get(s + 1) : n;
            parseControlSection(report, from, to, s, headerBlocks, footerBlocks, sectionKeys,
                    rows, warnings, columnNames);
        }
        return new ParsedReport(0, headerBlocks, footerBlocks, rows, warnings,
                true, sectionKeys, columnNames);
    }

    private static void parseControlSection(List<String> rep, int from, int to, int sIdx,
                                            List<String[]> headerBlocks, List<String[]> footerBlocks,
                                            List<String> sectionKeys, List<Row> rows,
                                            List<String> warnings, List<String> columnNames) {
        int len = to - from;
        String sig = rep.get(from);
        String sigShort = sigShort(sig);

        // 列头块定位：BRCH 锚点 → 跳过空白/标签行 → 连续列名行（折行）
        int anchor = -1;
        for (int i = 0; i < Math.min(len, 15); i++) {
            if (BR_ANCHOR.matcher(rep.get(from + i)).find()) { anchor = i; break; }
        }
        int colFirst = -1;
        if (anchor >= 0) {
            int i = anchor + 1, skipped = 0;
            while (i < len && skipped < 5
                    && (rep.get(from + i).isBlank() || LABEL_LIKE.matcher(rep.get(from + i)).find())) {
                i++;
                skipped++;
            }
            if (i < len && isColumnNameLine(rep.get(from + i))) colFirst = i;
        }
        if (colFirst < 0) {
            warnings.add("段 " + (sIdx + 1) + "（" + sigShort + "）未定位到列头（缺 BRCH 锚点或列名行），整段按业务内容处理");
            headerBlocks.add(new String[]{sig});
            footerBlocks.add(new String[0]);
            sectionKeys.add(sig);
            for (int i = 1; i < len; i++) {
                String raw = rep.get(from + i);
                if (raw.isBlank()) continue;
                String[] f = splitFields(raw);
                rows.add(new Row(sIdx, from + i + 1, raw, f, sortKey(f)));
            }
            return;
        }
        int colLast = colFirst;
        while (colLast + 1 < len && colLast - colFirst < 7 && isColumnNameLine(rep.get(from + colLast + 1))) {
            colLast++;
        }
        int fold = colLast - colFirst + 1;
        // 列头下可带一条划线行（------）作装饰
        boolean dashes = colLast + 1 < len && isRuleLine(rep.get(from + colLast + 1));
        int colEnd = dashes ? colLast + 1 : colLast;

        headerBlocks.add(slice(rep, from, from + colEnd + 1));
        sectionKeys.add(sig);
        if (columnNames.isEmpty()) collectColumnNames(rep, from, colFirst, colLast, dashes, columnNames);

        // 分页：列头首行原文的重复出现 = 页界
        String colhdr1 = rep.get(from + colFirst);
        List<Integer> pages = new ArrayList<>();
        pages.add(colFirst);
        for (int i = colEnd + 1; i < len; i++) {
            if (colhdr1.equals(rep.get(from + i))) pages.add(i);
        }
        if (pages.size() > 1) {
            warnings.add("段 " + (sIdx + 1) + "（" + sigShort + "）跨 " + pages.size() + " 页，页首重复表头已跳过");
        }

        // 页首行形态（首页表头区去掉控制行后的行集合 + 页码行 + BRCH 行），用于页界前回溯
        List<String> page1Preamble = rep.subList(from + 1, from + colFirst);
        // 内容区边界：剥掉段尾空白（EOF/段间分隔空行），防止空行被吞成折行续行
        int contentEnd = len;
        while (contentEnd > colEnd + 1 && rep.get(from + contentEnd - 1).isBlank()) contentEnd--;

        List<String> footer = new ArrayList<>();
        for (int pi = 0; pi < pages.size(); pi++) {
            int c = pages.get(pi);
            int blockEnd = c + fold;
            if (blockEnd < len && isRuleLine(rep.get(from + blockEnd))) blockEnd++;
            // 下一页页首起点：自下一列头行向上回溯连续的页首形态行
            int nextPre = contentEnd;
            if (pi + 1 < pages.size()) {
                int b = pages.get(pi + 1) - 1, steps = 0;
                while (b > blockEnd && steps < 10 && isPageHeaderIsh(rep.get(from + b), page1Preamble)) {
                    b--;
                    steps++;
                }
                nextPre = b + 1;
            }
            int p = blockEnd;
            while (p < nextPre) {
                if (!isMainLine(rep.get(from + p))) break;
                if (p + fold > nextPre) {
                    warnings.add("段 " + (sIdx + 1) + "（" + sigShort + "）第 " + (from + p + 1)
                            + " 行起折行记录不完整（不足 " + fold + " 行），余行按单行业务内容处理");
                    break;
                }
                String[] lines = slice(rep, from + p, from + p + fold);
                List<String> fs = new ArrayList<>(lines.length * 4);
                for (String ln : lines) {
                    for (String v : splitFields(ln)) if (!v.isEmpty()) fs.add(v);
                }
                String[] fields = fs.toArray(new String[0]);
                rows.add(new Row(sIdx, from + p + 1, String.join("\n", lines),
                        fields, sortKey(fields), lines));
                p += fold;
            }
            if (pi + 1 < pages.size()) {
                for (int i = p; i < nextPre; i++) {
                    String l = rep.get(from + i);
                    if (!l.isBlank()) footer.add(l); // 页间残行（罕见）：并入表尾区
                }
            } else {
                for (int i = p; i < contentEnd; i++) footer.add(rep.get(from + i));
            }
        }
        footerBlocks.add(footer.toArray(new String[0]));
    }

    /** 列名行：首 token 为字母开头、不含数字/掩码/竖线（数据行首 token 均含数字或以 * 开头）。 */
    static boolean isColumnNameLine(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.indexOf('|') >= 0) return false;
        String first = t.split("\\s+", 2)[0];
        if (first.indexOf('*') == 0 || first.indexOf('#') == 0) return false;
        for (int i = 0; i < first.length(); i++) {
            char ch = first.charAt(i);
            if (ch >= '0' && ch <= '9') return false;
        }
        return !first.isEmpty() && isLetter(first.charAt(0));
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 划线行：------ / ===== 等装饰分隔线。 */
    static boolean isRuleLine(String line) {
        String t = line.strip();
        if (t.length() < 6) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '-' && c != '=' && c != '+' && c != '_' && c != ' ') return false;
        }
        return true;
    }

    /**
     * 业务主行：首 token 含数字（账号/单号类）；或整行掩码（{@code ****}）且无 ≥3 字母的英文单词
     * （排除 {@code * * * END OF LIST * * *} 类表尾装饰行）。
     */
    static boolean isMainLine(String line) {
        String t = line.strip();
        if (t.isEmpty()) return false;
        String first = t.split("\\s+", 2)[0];
        boolean allStar = true;
        for (int i = 0; i < first.length(); i++) {
            if (first.charAt(i) != '*') { allStar = false; break; }
        }
        if (allStar) {
            for (int i = 0; i + 3 <= t.length(); i++) { // 任意连续 3 字母 = 非纯掩码行
                if (isLetter(t.charAt(i)) && isLetter(t.charAt(i + 1)) && isLetter(t.charAt(i + 2))) return false;
            }
            return true;
        }
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    /** 页首形态行：空白 / 页码行 / BRCH 行 / 与首页表头区某行完全一致（标题、下划线、A/C Type 等）。 */
    private static boolean isPageHeaderIsh(String line, List<String> page1Preamble) {
        if (line.isBlank()) return true;
        if (PAGE_MARK.matcher(line.strip()).matches() || BR_ANCHOR.matcher(line).find()) return true;
        for (String t : page1Preamble) {
            if (line.equals(t)) return true;
        }
        return false;
    }

    /** 列名清单（首页列头块）：折行 ≥2 行的列名带 「行k·」 前缀，划线行跳过。 */
    private static void collectColumnNames(List<String> rep, int from, int colFirst, int colLast,
                                           boolean dashes, List<String> out) {
        for (int i = colFirst; i <= colLast; i++) {
            String line = rep.get(from + i);
            if (dashes && i == colLast && isRuleLine(line)) continue;
            int lineNo = i - colFirst + 1;
            for (String t : line.strip().split("\\s{2,}")) {
                if (t.isBlank()) continue;
                out.add(lineNo >= 2 ? "行" + lineNo + "·" + t : t);
            }
        }
    }

    /** 段签名缩写（差异行标识用）：优先 PRODUCT / ORG-ID 值，否则控制行前 24 字符。 */
    static String sigShort(String controlLine) {
        String v = tagValue(controlLine, "PRODUCT");
        if (v == null) v = tagValue(controlLine, "ORG-ID");
        if (v == null) v = tagValue(controlLine, "RPT-ID");
        return v != null ? v : controlLine.strip().substring(0, Math.min(24, controlLine.strip().length()));
    }

    private static String tagValue(String controlLine, String tag) {
        int i = controlLine.indexOf("|" + tag + ":");
        if (i < 0) return null;
        int s = i + tag.length() + 2;
        int e = controlLine.indexOf('|', s);
        String v = e < 0 ? controlLine.substring(s) : controlLine.substring(s, e);
        return v.isBlank() ? null : v;
    }

    private static String[] slice(List<String> rep, int from, int toExclusive) {
        String[] out = new String[toExclusive - from];
        for (int i = 0; i < out.length; i++) out[i] = rep.get(from + i);
        return out;
    }

    /** 表头长度：逐行完全相等、非空白、且模板下一行为空白或结束的最大对齐行号。 */
    private static int detectHeaderLen(List<String> template, List<String> report) {
        int m = template.size(), n = report.size();
        int h = 0;
        for (int i = 0; i < Math.min(m, n); i++) {
            String t = template.get(i);
            if (t.equals(report.get(i)) && !t.isBlank()
                    && (i + 1 >= m || template.get(i + 1).isBlank())) {
                h = i + 1;
            }
        }
        return h;
    }

    /**
     * 值填充形态匹配：模板行 token 序列按顺序出现在报表行 token 中（token 级前缀匹配，
     * 如模板 {@code END} 匹配报表 {@code END|1301|...}、模板 {@code COUNT:} 匹配 {@code COUNT:1}）。
     */
    static boolean filledLike(String got, String tpl) {
        String t = tpl.strip();
        if (t.isEmpty()) return got.isBlank();
        String[] toks = t.split("\\s+");
        String[] gt = got.strip().split("\\s+");
        int k = 0;
        for (String want : toks) {
            while (k < gt.length && !tokMatch(gt[k], want)) k++;
            if (k >= gt.length) return false;
            k++;
        }
        return true;
    }

    private static boolean tokMatch(String gotTok, String wantTok) {
        return gotTok.equals(wantTok) || (wantTok.length() >= 3 && gotTok.startsWith(wantTok));
    }
}
