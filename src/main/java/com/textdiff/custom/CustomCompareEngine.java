package com.textdiff.custom;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.custom.SegmentParser.Parsed;
import com.textdiff.custom.SegmentParser.Segment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 双侧文本段对比：
 *  - 段按主键配对（免疫段序差异；同主键多段按出现顺序逐一配对）——同键段逐行对照
 *    （行数不同的尾部记单侧缺失行），差异行号进 diffCols；
 *  - 主键未命中的段按段全文精确多重集匹配，余量记单侧；
 *  - 首段之前 / 末段之后的残行按「文件头 / 文件尾」逐行对照。
 * 行展示保持段内原始行（RowDiff.aCols/bCols = 段行数组）。
 */
public final class CustomCompareEngine {
    private CustomCompareEngine() {}

    /** 结果行分区标识（区别于 header/footer/data）。 */
    public static final String SECTION = "segment";

    public record Result(List<RowDiff> rows, CustomSummary summary) {}

    public static Result compare(List<String> linesA, List<String> linesB, CustomFormat cfg) {
        Parsed pa = SegmentParser.parse(linesA, cfg);
        Parsed pb = SegmentParser.parse(linesB, cfg);

        List<RowDiff> rows = new ArrayList<>();
        rows.addAll(compareLooseLines(pa.head(), pb.head(), "文件头"));
        rows.addAll(compareLooseLines(pa.tail(), pb.tail(), "文件尾"));

        LinkedHashMap<String, List<Segment>> keyedA = new LinkedHashMap<>();
        LinkedHashMap<String, List<Segment>> keyedB = new LinkedHashMap<>();
        List<Segment> nullA = splitByKey(pa.segments(), keyedA);
        List<Segment> nullB = splitByKey(pb.segments(), keyedB);

        LinkedHashSet<String> keys = new LinkedHashSet<>(keyedA.keySet());
        keys.addAll(keyedB.keySet());

        long equal = 0, diff = 0, onlyA = 0, onlyB = 0;
        for (String k : keys) {
            List<Segment> da = keyedA.getOrDefault(k, List.of());
            List<Segment> db = keyedB.getOrDefault(k, List.of());
            int m = Math.min(da.size(), db.size());
            for (int t = 0; t < m; t++) {
                Segment x = da.get(t), y = db.get(t);
                if (x.text().equals(y.text())) {
                    rows.add(RowDiff.equal(k, SECTION, toArray(x.lines())));
                    equal++;
                } else {
                    rows.add(RowDiff.diff(k, SECTION, toArray(x.lines()), toArray(y.lines()),
                            diffLines(x.lines(), y.lines())));
                    diff++;
                }
            }
            for (int t = m; t < da.size(); t++) {
                rows.add(RowDiff.onlyA(k, SECTION, toArray(da.get(t).lines())));
                onlyA++;
            }
            for (int t = m; t < db.size(); t++) {
                rows.add(RowDiff.onlyB(k, SECTION, toArray(db.get(t).lines())));
                onlyB++;
            }
        }

        // 无主键段：段全文精确多重集匹配，余量单侧
        Map<String, Integer> textB = new HashMap<>();
        for (Segment s : nullB) textB.merge(s.text(), 1, Integer::sum);
        List<Segment> leftA = new ArrayList<>();
        for (Segment s : nullA) {
            Integer c = textB.get(s.text());
            if (c != null && c > 0) {
                if (c == 1) textB.remove(s.text());
                else textB.put(s.text(), c - 1);
                rows.add(RowDiff.equal(displayKey(s), SECTION, toArray(s.lines())));
                equal++;
            } else {
                leftA.add(s);
            }
        }
        Map<String, Integer> usedB = new HashMap<>();
        for (Segment s : leftA) {
            rows.add(RowDiff.onlyA(displayKey(s), SECTION, toArray(s.lines())));
            onlyA++;
        }
        for (Segment s : nullB) {
            int used = usedB.getOrDefault(s.text(), 0);
            if (used < textB.getOrDefault(s.text(), 0)) {
                usedB.put(s.text(), used + 1);
                continue; // 已与 A 侧对上
            }
            rows.add(RowDiff.onlyB(displayKey(s), SECTION, toArray(s.lines())));
            onlyB++;
        }

        CustomSummary sum = new CustomSummary();
        sum.segmentsA = pa.segments().size();
        sum.segmentsB = pb.segments().size();
        sum.equal = equal;
        sum.diff = diff;
        sum.onlyA = onlyA;
        sum.onlyB = onlyB;
        sum.keyMissA = pa.keyMiss();
        sum.keyMissB = pb.keyMiss();
        sum.headLinesA = pa.head().size();
        sum.headLinesB = pb.head().size();
        sum.tailLinesA = pa.tail().size();
        sum.tailLinesB = pb.tail().size();
        sum.startPattern = cfg.startPattern();
        sum.endPattern = cfg.endPattern();
        sum.keyPattern = cfg.keyPattern();
        sum.warnings.addAll(pa.warnings());
        sum.warnings.addAll(pb.warnings());
        if (cfg.compiledKey() == null && !pa.segments().isEmpty()) {
            sum.warnings.add("未配置主键提取式：按段全文精确匹配，段内任意差异将记为两侧各一段不匹配");
        }
        return new Result(rows, sum);
    }

    /** 无主键段的行标识：段首行截断。 */
    private static String displayKey(Segment s) {
        String head = s.lines().isEmpty() ? "" : s.lines().get(0).strip();
        return (head.isEmpty() ? "段@" + s.startLineNo() : head.length() > 60 ? head.substring(0, 60) : head)
                + " @" + s.startLineNo();
    }

    private static List<Segment> splitByKey(List<Segment> segments, Map<String, List<Segment>> keyed) {
        List<Segment> nullKey = new ArrayList<>();
        for (Segment s : segments) {
            if (s.key() == null) nullKey.add(s);
            else keyed.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(s);
        }
        return nullKey;
    }

    /** 头/尾残行逐行对照（行数不同的尾部记单侧缺失）。 */
    private static List<RowDiff> compareLooseLines(List<String> la, List<String> lb, String label) {
        List<RowDiff> out = new ArrayList<>();
        int lines = Math.max(la.size(), lb.size());
        for (int j = 0; j < lines; j++) {
            String va = j < la.size() ? la.get(j) : null;
            String vb = j < lb.size() ? lb.get(j) : null;
            String k = label + "#" + (j + 1);
            if (va == null) {
                out.add(new RowDiff(k, Status.UNMATCHED_B, SECTION, null, new String[]{vb}, new int[0]));
            } else if (vb == null) {
                out.add(new RowDiff(k, Status.UNMATCHED_A, SECTION, new String[]{va}, null, new int[0]));
            } else if (va.equals(vb)) {
                out.add(RowDiff.equal(k, SECTION, new String[]{va}));
            } else {
                out.add(RowDiff.diff(k, SECTION, new String[]{va}, new String[]{vb}, new int[]{0}));
            }
        }
        return out;
    }

    /** 段内差异行号（0-based；一侧缺失的行也计差异）。 */
    static int[] diffLines(List<String> la, List<String> lb) {
        List<Integer> cols = new ArrayList<>();
        int width = Math.max(la.size(), lb.size());
        for (int c = 0; c < width; c++) {
            String va = c < la.size() ? la.get(c) : null;
            String vb = c < lb.size() ? lb.get(c) : null;
            if (va == null || vb == null || !va.equals(vb)) cols.add(c);
        }
        int[] out = new int[cols.size()];
        for (int k = 0; k < out.length; k++) out[k] = cols.get(k);
        return out;
    }

    private static String[] toArray(List<String> lines) {
        return lines.toArray(new String[0]);
    }
}
