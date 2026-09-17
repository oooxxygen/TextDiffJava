package com.textdiff.custom;

import java.util.ArrayList;
import java.util.List;

/** 自定义格式对比摘要（custom_summary.json + /api/custom-jobs/{id}/summary 返回体）。 */
public final class CustomSummary {
    public long segmentsA, segmentsB;
    /** 段匹配统计：完全一致 / 有差异（主键相同内容不同）/ 单侧不匹配。 */
    public long equal, diff, onlyA, onlyB;
    /** 主键式未命中的段数（按侧）。 */
    public long keyMissA, keyMissB;
    /** 首段之前 / 末段之后的残行数（按侧）。 */
    public long headLinesA, headLinesB, tailLinesA, tailLinesB;
    /** 本次对比使用的格式配置回显。 */
    public String startPattern, endPattern, keyPattern;
    public List<String> warnings = new ArrayList<>();
}
