package com.textdiff.ai;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.store.ResultFiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差异特征提取：流式扫描 result.jsonl 仅 diff 行（O(n)、内存恒定）。
 * 每列采样封顶 {@link #SAMPLE_CAP} 对，供提示词模板填充与 AI 归纳分析。
 */
public final class DiffFeatureExtractor {
    public static final int SAMPLE_CAP = 30;

    /** 单个差异列的特征。 */
    public static final class ColumnFeature {
        public int col;                       // 0-based
        public long count;                    // 该列作为差异列出现的行数
        public List<String[]> samples = new ArrayList<>();   // [A值, B值, 记录主键] 采样对
        public String commonPrefix = "";
        public String commonSuffix = "";
        public long numericPairs;             // 两侧均可解析为数值的样本数
        public long equalNumericDiff;         // 数值差恒定（差值唯一的样本数超过一半）
        public String numericDeltaMode;       // 恒定差值（如 "+1"）
        public long dateShaped;               // 日期形态样本数（y-n-d / yyyymmdd 等）
        public long emptyA, emptyB;           // 空值占比
        public long lengthGrows, lengthShrinks;
    }

    public static final class Features {
        public Map<Integer, ColumnFeature> columns = new LinkedHashMap<>();
        public List<RowDiff> unmatchedSamples = new ArrayList<>();
    }

    private Features features;

    /** 提取（整个 result.jsonl 扫描一遍）。 */
    public static Features extract(Path resultFile) {
        DiffFeatureExtractor ex = new DiffFeatureExtractor();
        ex.features = new Features();
        try (var stream = ResultFiles.stream(resultFile)) {
            stream.forEach(ex::feed);
        }
        // 后处理：数值差众数
        for (ColumnFeature f : ex.features.columns.values()) {
            finishNumeric(f);
        }
        return ex.features;
    }

    private void feed(RowDiff row) {
        if (!Status.DIFF.equals(row.status)) {
            if ((Status.UNMATCHED_A.equals(row.status) || Status.UNMATCHED_B.equals(row.status))
                    && features.unmatchedSamples.size() < 10) {
                features.unmatchedSamples.add(row);
            }
            return;
        }
        for (int col : row.diffCols) {
            ColumnFeature f = features.columns.computeIfAbsent(col, c -> {
                ColumnFeature n = new ColumnFeature();
                n.col = c;
                return n;
            });
            f.count++;
            String a = col < row.aCols.length ? row.aCols[col] : "";
            String b = col < row.bCols.length ? row.bCols[col] : "";
            if (f.samples.size() < SAMPLE_CAP) {
                f.samples.add(new String[]{a, b, row.key == null ? "" : row.key});
                f.commonPrefix = commonPrefix(f.commonPrefix, a, b);
                f.commonSuffix = commonSuffix(f.commonSuffix, a, b);
                if (isNumeric(a) && isNumeric(b)) {
                    f.numericPairs++;
                    if (a.isBlank()) f.emptyA++;
                    if (b.isBlank()) f.emptyB++;
                }
                if (looksLikeDate(a) && looksLikeDate(b)) f.dateShaped++;
                if (a.length() < b.length()) f.lengthGrows++;
                else if (a.length() > b.length()) f.lengthShrinks++;
            }
        }
    }

    private static void finishNumeric(ColumnFeature f) {
        if (f.numericPairs < 2) return;
        Map<String, Integer> deltas = new LinkedHashMap<>();
        int n = 0;
        for (String[] s : f.samples) {
            try {
                if (isNumeric(s[0]) && isNumeric(s[1])) {
                    java.math.BigDecimal d = new java.math.BigDecimal(s[1].trim())
                            .subtract(new java.math.BigDecimal(s[0].trim()));
                    deltas.merge(d.toPlainString(), 1, Integer::sum);
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String mode = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : deltas.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                mode = e.getKey();
            }
        }
        if (mode != null && n > 0 && best * 2 > n) {
            f.equalNumericDiff = best;
            f.numericDeltaMode = new java.math.BigDecimal(mode).signum() >= 0 ? "+" + mode : mode;
        }
    }

    static String commonPrefix(String acc, String a, String b) {
        String p = prefixOf(a, b);
        if (acc.isEmpty()) return p;
        int n = Math.min(acc.length(), p.length());
        int i = 0;
        while (i < n && acc.charAt(i) == p.charAt(i)) i++;
        return acc.substring(0, i);
    }

    static String commonSuffix(String acc, String a, String b) {
        String s = suffixOf(a, b);
        if (acc.isEmpty()) return s;
        int n = Math.min(acc.length(), s.length());
        int i = 0;
        while (i < n && acc.charAt(acc.length() - 1 - i) == s.charAt(s.length() - 1 - i)) i++;
        return s.substring(s.length() - i);
    }

    private static String prefixOf(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    private static String suffixOf(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) i++;
        return a.substring(a.length() - i);
    }

    static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            new java.math.BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 日期形态宽松识别：20240102 / 2024-01-02 / 2024/01/02 / 20240102123456 等。 */
    static boolean looksLikeDate(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.matches("\\d{8}(|\\d{6})")
                || t.matches("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?.*");
    }
}
