package com.textdiff.textline;

import java.util.ArrayList;
import java.util.List;

/** 普通文本比对摘要（textline_summary.json + /api/textline-jobs/{id}/summary 返回体）。 */
public final class TextLineSummary {
    public long totalA, totalB;
    /** 行匹配统计：完全一致 / 有差异 / 仅 A 有 / 仅 B 有。 */
    public long equal, diff, onlyA, onlyB;
    /** 主键区间越界（行长度不足区间）的行数，按侧累计。 */
    public long clippedA, clippedB;
    /** 本次对比使用的配置回显。 */
    public String keyPositions, encodingA, encodingB;
    public List<String> warnings = new ArrayList<>();
}
