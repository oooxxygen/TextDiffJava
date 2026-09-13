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

    private static final String[] EMPTY_COLS = new String[0];

    static final List<String> KEY_TRAILER_FIELDS = List.of(
            "RecNum", "SysID", "TabName", "Version", "GenTime",
            "CycFlag", "DataStartDate", "DataEndDate");

    private static void addDupSample(Summary summary, String key) {
        if (summary.dupKeySamples.size() < Summary.DUP_SAMPLE_CAP && !summary.dupKeySamples.contains(key)) {
            summary.dupKeySamples.add(key);
        }
    }

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

    /** 默认堆外预算：环境 TEXTDIFF_MAX_IN_MEMORY_BYTES，否则 1 GiB（对等 Python）。 */
    public static long defaultMaxInMemoryBytes() {
        String e = System.getenv("TEXTDIFF_MAX_IN_MEMORY_BYTES");
        if (e != null && !e.isBlank()) {
            try { return Long.parseLong(e.trim()); } catch (NumberFormatException ignored) {}
        }
        return 1024L * 1024 * 1024;
    }

    public static CompareOutcome compareFiles(Path pathA, Path pathB, CompareConfig config,
                                              ResultSink sink) throws IOException {
        return compareFiles(pathA, pathB, config, sink,
                defaultMaxInMemoryBytes(), Path.of(System.getProperty("java.io.tmpdir")));
    }

    public static CompareOutcome compareFiles(Path pathA, Path pathB, CompareConfig config,
                                              ResultSink sink, long maxInMemoryBytes, Path tmpDir)
            throws IOException {
        Rules.RuleEngine engine = new Rules.RuleEngine(config);
        String delim = config.delimiter;
        String tp = config.trailerPrefix;
        String encA = Encoding.resolveEncoding(pathA, config.encodingA, delim);
        String encB = Encoding.resolveEncoding(pathB, config.encodingB, delim);

        Summary summary = new Summary();
        Parser.TrailerData trailerA = new Parser.TrailerData();
        Parser.TrailerData trailerB = new Parser.TrailerData();
        boolean spilled;

        try (KeyIndex index = new AutoKeyIndex(tmpDir, maxInMemoryBytes);
             KeyIndex bKeys = new AutoKeyIndex(tmpDir, maxInMemoryBytes)) {
            // 阶段 1：建 A 索引；put 前探查以捕获重复键（主键唯一性检测，需求：键应唯一定位一条记录）
            try (Stream<String> la = Encoding.iterLines(pathA, encA, delim)) {
                for (String[] cols : Parser.parseData(la, delim, tp, trailerA)) {
                    String key = engine.keyOf(cols);
                    if (key == null) { summary.malformedA++; continue; }
                    if (index.get(key) != null) {
                        summary.keyDupA++;
                        addDupSample(summary, key);
                    }
                    index.put(key, cols);
                }
            }

            // 阶段 2：流式比对 B；bKeys 仅存键集，检测 B 侧重复键
            try (Stream<String> lb = Encoding.iterLines(pathB, encB, delim)) {
                for (String[] cols : Parser.parseData(lb, delim, tp, trailerB)) {
                    String key = engine.keyOf(cols);
                    if (key == null) { summary.malformedB++; continue; }
                    if (bKeys.get(key) != null) {
                        summary.keyDupB++;
                        addDupSample(summary, key);
                    } else {
                        bKeys.put(key, EMPTY_COLS);
                    }
                    String[] aCols = index.get(key);
                    if (aCols == null) {
                        sink.addRow(RowDiff.onlyB(key, Status.SECTION_DATA, cols));
                        summary.onlyB++;
                    } else {
                        index.markSeen(key);
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
            for (Map.Entry<String, String[]> e : index.unseen()) {
                sink.addRow(RowDiff.onlyA(e.getKey(), Status.SECTION_DATA, e.getValue()));
                summary.onlyA++;
            }
            spilled = index.spilled();
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

        return new CompareOutcome(summary, encA, encB, spilled);
    }
}
