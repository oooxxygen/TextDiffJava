package com.textdiff.report;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.report.ReportParser.ParsedReport;
import com.textdiff.report.ReportParser.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 双侧报表对比：
 *  - 表头/表尾：逐段逐块逐行对照（块数不对齐时多出的块整体记单侧缺失），差异以整行为一个栏位呈现；
 *  - 业务行：无主键场景——双侧各按「全字段排序键」排序后归并，整行相等即匹配（天然免疫写入乱序）；
 *    归并剩余的单侧行再做相似度部分匹配（共享同一字段值才候选，字段级相似度 ≥ 阈值判为部分匹配，
 *    差异栏位逐列给出），其余为单侧不匹配。
 */
public final class ReportComparator {
    private ReportComparator() {}

    /** 部分匹配相似度阈值（字段级加权平均，0~1）。 */
    static final double PARTIAL_THRESHOLD = 0.6;
    /** 部分匹配打分对数上限：超出即停止配对（剩余记单侧不匹配），防止极端规模退化。 */
    private static final long SCORE_PAIR_CAP = 5_000_000L;

    public record Result(List<RowDiff> headerRows, List<RowDiff> footerRows,
                         List<RowDiff> dataRows, ReportSummary summary) {}

    /** @param fieldNames 业务行栏位名（铺底映射，可为空表；仅用于条数核对/展示语义，不参与算法） */
    public static Result compare(ParsedReport a, ParsedReport b, List<String> fieldNames) {
        List<RowDiff> headerRows = compareBlocks(a.headerBlocks(), b.headerBlocks(),
                Status.SECTION_HEADER, "H");
        List<RowDiff> footerRows = compareBlocks(a.footerBlocks(), b.footerBlocks(),
                Status.SECTION_FOOTER, "T");
        CompareData data = compareData(a.rows(), b.rows());

        ReportSummary s = new ReportSummary();
        s.sectionCountA = a.headerBlocks().size();
        s.sectionCountB = b.headerBlocks().size();
        s.rowCountA = a.rows().size();
        s.rowCountB = b.rows().size();
        s.headerLineDiff = countNonEqual(headerRows);
        s.footerLineDiff = countNonEqual(footerRows);
        s.headerBlockDiff = countNonEqualBlocks(headerRows);
        s.footerBlockDiff = countNonEqualBlocks(footerRows);
        s.equal = data.equal;
        s.partial = data.partial;
        s.onlyA = data.onlyA;
        s.onlyB = data.onlyB;
        if (fieldNames != null) s.fieldNames.addAll(fieldNames);
        s.warnings.addAll(a.warnings());
        s.warnings.addAll(b.warnings());
        s.countChecks.addAll(countChecks("A", a));
        s.countChecks.addAll(countChecks("B", b));
        return new Result(headerRows, footerRows, data.rows, s);
    }

    // ---- 表头/表尾：逐块逐行 ----

    private static List<RowDiff> compareBlocks(List<String[]> ba, List<String[]> bb,
                                               String section, String keyPrefix) {
        List<RowDiff> out = new ArrayList<>();
        int blocks = Math.max(ba.size(), bb.size());
        for (int i = 0; i < blocks; i++) {
            String[] la = i < ba.size() ? ba.get(i) : null;
            String[] lb = i < bb.size() ? bb.get(i) : null;
            if (la == null) {
                for (int j = 0; j < lb.length; j++) {
                    out.add(onlyB(key(i, j), section, lb[j]));
                }
                continue;
            }
            if (lb == null) {
                for (int j = 0; j < la.length; j++) {
                    out.add(onlyA(key(i, j), section, la[j]));
                }
                continue;
            }
            int lines = Math.max(la.length, lb.length);
            for (int j = 0; j < lines; j++) {
                String va = j < la.length ? la[j] : null;
                String vb = j < lb.length ? lb[j] : null;
                String k = key(i, j);
                if (va == null) {
                    out.add(onlyB(k, section, vb));
                } else if (vb == null) {
                    out.add(onlyA(k, section, va));
                } else if (va.equals(vb)) {
                    out.add(RowDiff.equal(k, section, new String[]{va}));
                } else {
                    out.add(RowDiff.diff(k, section, new String[]{va}, new String[]{vb}, new int[]{0}));
                }
            }
        }
        return out;
    }

    private static String key(int block, int line) {
        return (block + 1) + "#" + (line + 1);
    }

    private static RowDiff onlyA(String key, String section, String line) {
        return new RowDiff(key, Status.UNMATCHED_A, section, new String[]{line}, null, new int[0]);
    }

    private static RowDiff onlyB(String key, String section, String line) {
        return new RowDiff(key, Status.UNMATCHED_B, section, null, new String[]{line}, new int[0]);
    }

    private static long countNonEqual(List<RowDiff> rows) {
        return rows.stream().filter(r -> !Status.EQUAL.equals(r.status)).count();
    }

    private static long countNonEqualBlocks(List<RowDiff> rows) {
        return rows.stream().filter(r -> !Status.EQUAL.equals(r.status))
                .map(r -> r.key.split("#", 2)[0]).distinct().count();
    }

    // ---- 业务行：排序归并 + 部分匹配 ----

    private record CompareData(List<RowDiff> rows, long equal, long partial, long onlyA, long onlyB) {}

    private static CompareData compareData(List<Row> ra, List<Row> rb) {
        List<Row> sa = new ArrayList<>(ra);
        List<Row> sb = new ArrayList<>(rb);
        sa.sort(Comparator.comparing(Row::sortKey));
        sb.sort(Comparator.comparing(Row::sortKey));

        List<RowDiff> out = new ArrayList<>();
        List<Row> leftA = new ArrayList<>();
        List<Row> leftB = new ArrayList<>();
        long equal = 0;
        int i = 0, j = 0;
        while (i < sa.size() && j < sb.size()) {
            Row x = sa.get(i), y = sb.get(j);
            int c = x.sortKey().compareTo(y.sortKey());
            if (c == 0) {
                out.add(RowDiff.equal(x.raw().strip(), Status.SECTION_DATA, x.fields()));
                equal++;
                i++;
                j++;
            } else if (c < 0) {
                leftA.add(x);
                i++;
            } else {
                leftB.add(y);
                j++;
            }
        }
        while (i < sa.size()) leftA.add(sa.get(i++));
        while (j < sb.size()) leftB.add(sb.get(j++));

        // 部分匹配：单侧剩余行之间按相似度配对（贪婪，按 A 侧排序顺序）。
        // 倒排索引剪枝：仅对与 A 行共享同一非空字段值的 B 行打分，避免全交叉积。
        Map<String, List<Integer>> index = new HashMap<>();
        for (int bj = 0; bj < leftB.size(); bj++) {
            for (String v : leftB.get(bj).fields()) {
                if (!v.isEmpty() && v.length() <= 64) {
                    index.computeIfAbsent(v, k -> new ArrayList<>()).add(bj);
                }
            }
        }
        long scored = 0;
        long partial = 0;
        boolean[] usedB = new boolean[leftB.size()];
        List<Row> unmatchedA = new ArrayList<>();
        for (Row x : leftA) {
            int best = -1;
            double bestScore = 0;
            java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            for (String v : x.fields()) {
                List<Integer> cand = v.isEmpty() ? null : index.get(v);
                if (cand == null) continue;
                for (int bj : cand) {
                    if (usedB[bj] || !seen.add(bj)) continue;
                    if (++scored > SCORE_PAIR_CAP) break;
                    double sc = similarity(x.fields(), leftB.get(bj).fields());
                    if (sc >= PARTIAL_THRESHOLD && sc > bestScore) {
                        bestScore = sc;
                        best = bj;
                    }
                }
            }
            if (best >= 0) {
                usedB[best] = true;
                Row y = leftB.get(best);
                out.add(RowDiff.diff(x.raw().strip(), Status.SECTION_DATA,
                        x.fields(), y.fields(), diffCols(x.fields(), y.fields())));
                partial++;
            } else {
                unmatchedA.add(x);
            }
        }
        for (Row x : unmatchedA) {
            out.add(new RowDiff(x.raw().strip(), Status.UNMATCHED_A, Status.SECTION_DATA,
                    x.fields(), null, new int[0]));
        }
        for (int bj = 0; bj < leftB.size(); bj++) {
            if (!usedB[bj]) {
                Row y = leftB.get(bj);
                out.add(new RowDiff(y.raw().strip(), Status.UNMATCHED_B, Status.SECTION_DATA,
                        null, y.fields(), new int[0]));
            }
        }
        long onlyA = leftA.size() - partial;
        long onlyB = leftB.size() - partial;
        return new CompareData(out, equal, partial, onlyA, onlyB);
    }

    /** 差异栏位号（0-based；一侧缺失的栏位也计差异）。 */
    static int[] diffCols(String[] fa, String[] fb) {
        List<Integer> cols = new ArrayList<>();
        int width = Math.max(fa.length, fb.length);
        for (int c = 0; c < width; c++) {
            String va = c < fa.length ? fa[c] : null;
            String vb = c < fb.length ? fb[c] : null;
            if (va == null || vb == null || !va.equals(vb)) cols.add(c);
        }
        int[] out = new int[cols.size()];
        for (int k = 0; k < out.length; k++) out[k] = cols.get(k);
        return out;
    }

    /** 字段级加权平均相似度（两侧均空 = 一致；单侧空 = 0；否则 1 - 编辑距离/较长长度）。 */
    static double similarity(String[] fa, String[] fb) {
        int width = Math.max(fa.length, fb.length);
        if (width == 0) return 1.0;
        double sum = 0;
        for (int c = 0; c < width; c++) {
            String va = c < fa.length ? fa[c] : "";
            String vb = c < fb.length ? fb[c] : "";
            if (va.isEmpty() && vb.isEmpty()) {
                sum += 1.0;
            } else if (va.isEmpty() || vb.isEmpty()) {
                sum += 0.0;
            } else if (va.equals(vb)) {
                sum += 1.0;
            } else {
                int max = Math.max(va.length(), vb.length());
                sum += max == 0 ? 1.0 : 1.0 - (double) levenshtein(va, vb) / max;
            }
        }
        return sum / width;
    }

    /** 小串编辑距离（滚动数组）。 */
    static int levenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i2 = 1; i2 <= n; i2++) {
            cur[0] = i2;
            for (int j2 = 1; j2 <= m; j2++) {
                int cost = a.charAt(i2 - 1) == b.charAt(j2 - 1) ? 0 : 1;
                cur[j2] = Math.min(Math.min(cur[j2 - 1] + 1, prev[j2] + 1), prev[j2 - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    // ---- 条数核对：表尾声明条数 vs 程序计数 ----

    private static final Pattern COUNT_LABEL =
            Pattern.compile("(?:TOTAL[-_]?COUNT|COUNT)\\s*[:=]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*");

    static List<ReportSummary.CountCheck> countChecks(String side, ParsedReport p) {
        List<ReportSummary.CountCheck> out = new ArrayList<>();
        if (p.footerBlocks().isEmpty()) return out;
        Map<Integer, Long> perSection = new HashMap<>();
        for (Row r : p.rows()) perSection.merge(r.section(), 1L, Long::sum);
        for (int sIdx = 0; sIdx < p.footerBlocks().size(); sIdx++) {
            String[] block = p.footerBlocks().get(sIdx);
            String joined = String.join("\n", block);
            String declared = null;
            Matcher label = COUNT_LABEL.matcher(joined);
            while (label.find()) {
                int from = label.end();
                if (from >= joined.length()) break;
                Matcher num = NUMBER.matcher(joined.substring(from, Math.min(joined.length(), from + 80)));
                if (num.find()) {
                    declared = num.group().replace(",", "");
                    break;
                }
            }
            if (declared == null) continue;
            long counted = perSection.getOrDefault(sIdx, 0L);
            boolean match;
            try {
                match = Long.parseLong(declared) == counted;
            } catch (NumberFormatException e) {
                match = false;
            }
            out.add(new ReportSummary.CountCheck(side, sIdx, declared, counted, match));
        }
        return out;
    }
}
