package com.textdiff.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 核心对比引擎（M1a：内存 HashMap 索引）。对等 Python comparator.py。
 *
 * 算法（时间 O(nA+nB)，空间 O(键数)）：
 *   1. 流式读 A，按主键建索引。
 *   2. 流式读 B，按主键查 A：命中则列级比对（equal/diff），未命中为 only_b。
 *   3. 遍历 A 中未被命中的键 → only_a。
 *   4. trailer 记录按元数据键对齐独立比对。
 * 字符级高亮在前端按 a/b 列实时计算，后端只输出差异列索引 + Summary。
 * M1b 将以 mmap 行存储 + MemorySegment 堆外哈希表替换内存索引。
 */
public final class Comparator {
    private Comparator() {}

    static final List<String> KEY_TRAILER_FIELDS = List.of(
            "RecNum", "SysID", "TabName", "Version", "GenTime",
            "CycFlag", "DataStartDate", "DataEndDate");

    /** 比对两条记录各列，返回差异列索引（0-based）。一律比原值；omit/ignore 列跳过；长度不齐按空串补。 */
    public static int[] compareCols(String[] aCols, String[] bCols, Rules.RuleEngine engine) {
        int la = aCols.length, lb = bCols.length;
        int n = Math.max(la, lb);
        List<Integer> diff = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (engine.skip.contains(i)) continue;
            String a = i < la ? aCols[i] : "";
            String b = i < lb ? bCols[i] : "";
            if (!a.equals(b)) diff.add(i);
        }
        int[] out = new int[diff.size()];
        for (int i = 0; i < out.length; i++) out[i] = diff.get(i);
        return out;
    }

    private static void compareTrailer(Parser.TrailerData ta, Parser.TrailerData tb,
                                       ResultSink sink, Summary summary) {
        Map<String, String> a = new LinkedHashMap<>();
        for (Parser.Rec r : ta.records) a.put(r.key(), r.value());  // 重复键后者覆盖
        Map<String, String> b = new LinkedHashMap<>();
        for (Parser.Rec r : tb.records) b.put(r.key(), r.value());

        TreeSet<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) {
            boolean inA = a.containsKey(key), inB = b.containsKey(key);
            String av = a.getOrDefault(key, ""), bv = b.getOrDefault(key, "");
            if (inA && inB) {
                if (!av.equals(bv)) {
                    sink.addRow(RowDiff.diff(key, Status.SECTION_TRAILER,
                            new String[]{av}, new String[]{bv}, new int[]{0}));
                } else {
                    sink.addRow(RowDiff.equal(key, Status.SECTION_TRAILER, new String[]{av}));
                }
            } else if (inA) {
                sink.addRow(RowDiff.onlyA(key, Status.SECTION_TRAILER, new String[]{av}));
            } else {
                sink.addRow(RowDiff.onlyB(key, Status.SECTION_TRAILER, new String[]{bv}));
            }
        }

        for (String field : KEY_TRAILER_FIELDS) {
            if (ta.meta.containsKey(field) || tb.meta.containsKey(field)) {
                String av = ta.meta.get(field), bv = tb.meta.get(field);
                summary.trailerFields.put(field, new Summary.TrailerField(av, bv, Objects.equals(av, bv)));
            }
        }
    }

    public static CompareOutcome compareFiles(Path pathA, Path pathB, CompareConfig config,
                                              ResultSink sink) throws IOException {
        Rules.RuleEngine engine = new Rules.RuleEngine(config);
        String delim = config.delimiter;
        String tp = config.trailerPrefix;
        String encA = Encoding.resolveEncoding(pathA, config.encodingA, delim);
        String encB = Encoding.resolveEncoding(pathB, config.encodingB, delim);

        Summary summary = new Summary();
        Parser.TrailerData trailerA = new Parser.TrailerData();
        Parser.TrailerData trailerB = new Parser.TrailerData();

        // 阶段 1：建 A 索引（LinkedHashMap 保插入序，使 only_a 输出顺序确定）
        Map<String, String[]> idx = new LinkedHashMap<>();
        try (Stream<String> la = Encoding.iterLines(pathA, encA, delim)) {
            for (String[] cols : Parser.parseData(la, delim, tp, trailerA)) {
                String key = engine.keyOf(cols);
                if (key == null) { summary.malformedA++; continue; }
                idx.put(key, cols);
            }
        }

        // 阶段 2：流式比对 B
        Set<String> seen = new HashSet<>();
        try (Stream<String> lb = Encoding.iterLines(pathB, encB, delim)) {
            for (String[] cols : Parser.parseData(lb, delim, tp, trailerB)) {
                String key = engine.keyOf(cols);
                if (key == null) { summary.malformedB++; continue; }
                String[] aCols = idx.get(key);
                if (aCols == null) {
                    sink.addRow(RowDiff.onlyB(key, Status.SECTION_DATA, cols));
                    summary.onlyB++;
                } else {
                    seen.add(key);
                    int[] diffCols = compareCols(aCols, cols, engine);
                    if (diffCols.length > 0) {
                        sink.addRow(RowDiff.diff(key, Status.SECTION_DATA, aCols, cols, diffCols));
                        summary.diff++;
                        for (int ci : diffCols) summary.diffColFreq.merge(ci, 1L, Long::sum);
                    } else {
                        // equal：B 隐含等于 A，不重复存 B
                        sink.addRow(RowDiff.equal(key, Status.SECTION_DATA, aCols));
                        summary.equal++;
                    }
                }
            }
        }

        // 阶段 3：仅 A 存在
        for (Map.Entry<String, String[]> e : idx.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            sink.addRow(RowDiff.onlyA(e.getKey(), Status.SECTION_DATA, e.getValue()));
            summary.onlyA++;
        }

        // 阶段 4：trailer 对比
        compareTrailer(trailerA, trailerB, sink, summary);

        // 汇总
        summary.dataRowsA = trailerA.dataCount;
        summary.dataRowsB = trailerB.dataCount;
        summary.trailerRowsA = trailerA.records.size();
        summary.trailerRowsB = trailerB.records.size();
        summary.totalA = summary.dataRowsA + summary.trailerRowsA;
        summary.totalB = summary.dataRowsB + summary.trailerRowsB;
        summary.recnumCheckA = Parser.recnumCheck(trailerA);
        summary.recnumCheckB = Parser.recnumCheck(trailerB);

        return new CompareOutcome(summary, encA, encB, false);
    }
}
