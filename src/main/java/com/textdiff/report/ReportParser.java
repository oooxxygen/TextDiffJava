package com.textdiff.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表结构解析：基于 header 模板把一份报表文本切成 表头块 / 业务行 / 表尾块 三个分区。
 *
 * 模板（header 文件）= 报表骨架（值留空），一份报表文件可含多个报表段，每段 = 表头块（模板表头逐行对应、
 * 值已填充）+ 业务行（替换模板中表头后的空白占位行）+ 表尾块（模板尾部骨架，值可能已填充）。
 *
 * 分区算法（由 CORD9000/CRDD0020/DEPD8920/PYDD1040 四类样本归纳）：
 *  1. 表头长度 H = 模板与报表按行对齐时「逐行完全相等且非空白、且模板下一行为空白占位或模板结束」的
 *     最大行号（列头/划线行必逐字一致，可作锚点）；
 *  2. 表尾骨架 = 表头之后跳过一个空白占位行后的全部模板尾行（占位行被业务行替换，其余骨架行保留并填充值）；
 *  3. 报表段以表头锚点行（模板第 H 行原文）在报表中的出现位置分段，段内锚点之后到下一段表头之前为
 *     内容区：剥掉尾部空白（段间分隔空行）后，若内容区尾部与表尾骨架逐行对应（完全相等 / 空白对空白 /
     * 值填充形态匹配）则划为表尾块，其余非空白行为业务行。
 */
public final class ReportParser {
    private ReportParser() {}

    /** 业务行：所在段号（0-based）、行号（1-based）、原始行、切分字段、排序键。 */
    public record Row(int section, int lineNo, String raw, String[] fields, String sortKey) {}

    /** 解析结果：表头/表尾各段块（原始行数组）+ 全部业务行 + 解析告警。 */
    public record ParsedReport(int headerLen, List<String[]> headerBlocks, List<String[]> footerBlocks,
                               List<Row> rows, List<String> warnings) {}

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
            // 表尾块匹配：骨架核（去前导空白）自内容区尾部向上逐行对齐，
            // 核之前的表尾前导空白行按可用情况保留（CRDD 段 1-10 表尾=[空行,END]，末段=[END]）
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
