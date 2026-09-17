package com.textdiff.engine;

/** 行匹配状态与结果分区常量。串值与 Python models.py 完全一致（前端契约依赖）。 */
public final class Status {
    public static final String EQUAL = "equal";
    public static final String DIFF = "diff";
    public static final String UNMATCHED_A = "unmatched_a";
    public static final String UNMATCHED_B = "unmatched_b";

    public static final String SECTION_DATA = "data";
    public static final String SECTION_TRAILER = "trailer";
    /** 报表对比扩展分区（文件对比不产生）。 */
    public static final String SECTION_HEADER = "header";
    public static final String SECTION_FOOTER = "footer";

    private Status() {}
}
