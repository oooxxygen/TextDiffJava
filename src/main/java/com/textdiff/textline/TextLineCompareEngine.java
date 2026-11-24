package com.textdiff.textline;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 普通文本比对（逐行、逐字）：每行按配置的字符位置区间提取主键（未配置取整行），
 * A 侧行按主键命中 B 侧同键行（同键多行按出现顺序逐一配对），命中两侧整行逐字符比对——
 * 完全一致记 equal；任一字符不同记 diff（diffCols=[0]，前端对整行格做逐字符差异高亮）；
 * 未命中记仅 A / 仅 B。行展示保持原貌（aCols/bCols = 整行原文）。
 */
public final class TextLineCompareEngine {
    private TextLineCompareEngine() {}

    /** 结果行分区标识（区别于 header/footer/data/segment）。 */
    public static final String SECTION = "line";

    public record Result(List<RowDiff> rows, TextLineSummary summary) {}

    public static Result compare(List<String> linesA, List<String> linesB, TextLineFormat cfg) {
        int[][] ranges = cfg.ranges();
        TextLineSummary s = new TextLineSummary();
        s.totalA = linesA.size();
        s.totalB = linesB.size();
        s.keyPositions = cfg.keyPositions();
        s.encodingA = cfg.encodingA();
        s.encodingB = cfg.encodingB();

        // B 侧主键索引：同键多行按出现顺序消费（FIFO）
        Map<String, Deque<Integer>> indexB = new HashMap<>();
        for (int bi = 0; bi < linesB.size(); bi++) {
            indexB.computeIfAbsent(cfg.keyOf(linesB.get(bi)), k -> new ArrayDeque<>()).addLast(bi);
        }
        boolean[] consumedB = new boolean[linesB.size()];

        List<RowDiff> rows = new ArrayList<>();
        for (String lineA : linesA) {
            String key = cfg.keyOf(lineA);
            Deque<Integer> q = indexB.get(key);
            if (q != null && !q.isEmpty()) {
                int bi = q.poll();
                consumedB[bi] = true;
                String lineB = linesB.get(bi);
                if (lineA.equals(lineB)) { // 整行逐字符一致
                    rows.add(RowDiff.equal(key, SECTION, new String[]{lineA}));
                    s.equal++;
                } else {
                    rows.add(RowDiff.diff(key, SECTION, new String[]{lineA}, new String[]{lineB}, new int[]{0}));
                    s.diff++;
                }
            } else {
                rows.add(RowDiff.onlyA(key, SECTION, new String[]{lineA}));
                s.onlyA++;
            }
            if (clipped(lineA, ranges)) s.clippedA++;
        }
        for (int bi = 0; bi < linesB.size(); bi++) {
            if (consumedB[bi]) continue;
            String lineB = linesB.get(bi);
            rows.add(RowDiff.onlyB(cfg.keyOf(lineB), SECTION, new String[]{lineB}));
            s.onlyB++;
            if (clipped(lineB, ranges)) s.clippedB++;
        }
        if (s.clippedA + s.clippedB > 0) {
            s.warnings.add((s.clippedA + s.clippedB) + " 行长度不足主键区间（缺位按空串参与匹配）");
        }
        return new Result(rows, s);
    }

    /** 行长度是否不足以覆盖全部主键区间（区间越界 → 提取值被截短）。 */
    private static boolean clipped(String line, int[][] ranges) {
        if (ranges.length == 0) return false;
        for (int[] r : ranges) {
            if (line.length() < r[1]) return true;
        }
        return false;
    }
}
