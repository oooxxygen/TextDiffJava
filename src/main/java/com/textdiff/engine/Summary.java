package com.textdiff.engine;

import java.util.*;

/** 对比简要概括，前端置顶展示。对等 Python models.Summary。 */
public final class Summary {
    public long totalA, totalB;
    public long dataRowsA, dataRowsB;
    public long trailerRowsA, trailerRowsB;
    public long equal, diff, onlyA, onlyB;
    public long malformedA, malformedB;
    /** 主键唯一性检测：A/B 侧重复键出现次数（第二次及以后每次出现计 1）。 */
    public long keyDupA, keyDupB;
    /** 重复键样例（两侧合并，封顶 {@link #DUP_SAMPLE_CAP} 条），供前端告警与 AI 分析。 */
    public List<String> dupKeySamples = new ArrayList<>();
    public static final int DUP_SAMPLE_CAP = 20;
    /** 差异列频次：{列号(0-based) -> 作为差异列出现的 diff 行数}。 */
    public Map<Integer, Long> diffColFreq = new LinkedHashMap<>();
    /** trailer 关键字段一致性: {key -> TrailerField}。 */
    public Map<String, TrailerField> trailerFields = new LinkedHashMap<>();
    public Boolean recnumCheckA;  // null = 无 RecNum
    public Boolean recnumCheckB;

    /** trailer 关键字段的 A/B 值与一致性。a/b 可空（仅一侧存在该字段时）。 */
    public record TrailerField(String a, String b, boolean equal) {}
}
