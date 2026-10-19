package com.textdiff.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 增量过滤（ADDMOD）：解压后、正式对比前，按提交条件值过滤数据行并重组源文本。
 * 行保留规则：各过滤栏位值全部命中所填条件（栏位间 AND；栏位内逗号分隔多值任一精确相等命中）；
 * 空条件栏位不约束；全部栏位未填 = 不过滤（全量对比）。
 * 重组文本 = 命中的数据行（原文）+ trailer 区原样保留；空行丢弃（与 Parser.parseData 同口径）。
 * 产物以源文件的解析编码写回，后续对比链路（编码检测/混合编码栏位转码）不受影响。
 */
public final class IncrFilter {
    private IncrFilter() {}

    /** 过滤结果：两侧产物路径与过滤前/后数据行数。 */
    public record Result(Path filteredA, Path filteredB,
                         long aBefore, long aAfter, long bBefore, long bAfter) {}

    /** 是否启用过滤：规则声明了 ADDMOD 栏位且条件值至少一个非空。 */
    public static boolean enabled(CompareConfig cfg, List<String> conds) {
        return cfg != null && !cfg.incrFields.isEmpty()
                && conds != null && conds.stream().anyMatch(c -> c != null && !c.isBlank());
    }

    public static Result apply(Path inputA, Path inputB, CompareConfig cfg, List<String> conds,
                               Path tmpDir) throws IOException {
        Files.createDirectories(tmpDir);
        RowMatcher matcher = RowMatcher.of(cfg, conds);
        String encA = Encoding.resolveEncoding(inputA, cfg.encodingA, cfg.delimiter);
        String encB = Encoding.resolveEncoding(inputB, cfg.encodingB, cfg.delimiter);
        Path fa = tmpDir.resolve("incr_A.txt");
        Path fb = tmpDir.resolve("incr_B.txt");
        long[] a = filterSide(inputA, encA, fa, cfg, matcher);
        long[] b = filterSide(inputB, encB, fb, cfg, matcher);
        return new Result(fa, fb, a[0], a[1], b[0], b[1]);
    }

    /** 删除过滤产物（对比完成后调用；不存在时静默）。 */
    public static void cleanup(Result r) {
        if (r == null) return;
        try {
            Files.deleteIfExists(r.filteredA());
            Files.deleteIfExists(r.filteredB());
        } catch (IOException ignored) {
        }
    }

    /** 单侧过滤：返回 {过滤前数据行数, 过滤后数据行数}。trailer 区原样保留。 */
    private static long[] filterSide(Path in, String resolvedEnc, Path out,
                                     CompareConfig cfg, RowMatcher matcher) throws IOException {
        Charset cs = Encoding.toCharset(resolvedEnc);
        String tp = cfg.trailerPrefix;
        long before = 0;
        long after = 0;
        try (BufferedReader r = Files.newBufferedReader(in, cs);
             BufferedWriter w = Files.newBufferedWriter(out, cs)) { // 与源同编码写回，下游编码链路不变
            boolean inTrailer = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (!inTrailer && tp != null && !tp.isEmpty() && line.startsWith(tp)) {
                    inTrailer = true;
                }
                if (inTrailer) {
                    w.write(line);
                    w.write('\n');
                    continue;
                }
                if (line.isEmpty()) continue; // 空行不入源（对等 Parser.parseData 计数口径）
                before++;
                if (matcher.match(Parser.splitColumns(line, cfg.delimiter))) {
                    after++;
                    w.write(line);
                    w.write('\n');
                }
            }
        }
        return new long[]{before, after};
    }

    /** 行条件匹配器：栏位间 AND，栏位内多值（逗号分隔）任一精确相等。 */
    static final class RowMatcher {
        private final List<Integer> fields;
        private final List<Set<String>> values;

        private RowMatcher(List<Integer> fields, List<Set<String>> values) {
            this.fields = fields;
            this.values = values;
        }

        static RowMatcher of(CompareConfig cfg, List<String> conds) {
            List<Integer> fields = cfg.incrFields;
            List<Set<String>> values = new ArrayList<>();
            for (int k = 0; k < fields.size(); k++) {
                Set<String> set = new HashSet<>();
                String cond = conds != null && k < conds.size() ? conds.get(k) : null;
                if (cond != null && !cond.isBlank()) {
                    for (String v : cond.split(",")) {
                        v = v.strip();
                        if (!v.isEmpty()) set.add(v);
                    }
                }
                values.add(set);
            }
            return new RowMatcher(fields, values);
        }

        boolean match(String[] cols) {
            boolean anyConstraint = false;
            for (int k = 0; k < fields.size(); k++) {
                Set<String> vs = values.get(k);
                if (vs.isEmpty()) continue;
                anyConstraint = true;
                int f = fields.get(k);
                String v = f < cols.length ? cols[f] : "";
                if (!vs.contains(v)) return false;
            }
            return anyConstraint; // 全部栏位未填 = 不过滤（调用方已用 enabled() 拦截，此处兜底）
        }
    }
}
