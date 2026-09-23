package com.textdiff.engine;

/**
 * 一条记录（数据行或 trailer 项）的对比结果。对等 Python models.RowDiff。
 * 字符级高亮不在后端：前端按 aCols/bCols 实时计算，后端只给 diffCols + Summary。
 */
public final class RowDiff {
    public final String key;
    public final String status;     // Status.EQUAL/DIFF/UNMATCHED_A/UNMATCHED_B
    public final String section;    // Status.SECTION_DATA / SECTION_TRAILER
    public final String[] aCols;    // 可空
    public final String[] bCols;    // 可空
    public final int[] diffCols;    // 差异列索引（0-based）
    public final String aRaw;       // 可空：整行原貌（报表模块界面整行展示用；折行记录含 \n）
    public final String bRaw;       // 可空：同上 B 侧

    private static final int[] NO_DIFF = new int[0];

    public RowDiff(String key, String status, String section,
                   String[] aCols, String[] bCols, int[] diffCols) {
        this(key, status, section, aCols, bCols, diffCols, null, null);
    }

    public RowDiff(String key, String status, String section,
                   String[] aCols, String[] bCols, int[] diffCols, String aRaw, String bRaw) {
        this.key = key;
        this.status = status;
        this.section = section;
        this.aCols = aCols;
        this.bCols = bCols;
        this.diffCols = diffCols == null ? NO_DIFF : diffCols;
        this.aRaw = aRaw;
        this.bRaw = bRaw;
    }

    /** 派生副本：附加整行原貌（其余字段不变）。 */
    public RowDiff withRaw(String aRaw, String bRaw) {
        return new RowDiff(key, status, section, aCols, bCols, diffCols, aRaw, bRaw);
    }

    public static RowDiff equal(String key, String section, String[] aCols) {
        return new RowDiff(key, Status.EQUAL, section, aCols, null, NO_DIFF);
    }

    public static RowDiff diff(String key, String section, String[] aCols, String[] bCols, int[] diffCols) {
        return new RowDiff(key, Status.DIFF, section, aCols, bCols, diffCols);
    }

    public static RowDiff onlyA(String key, String section, String[] aCols) {
        return new RowDiff(key, Status.UNMATCHED_A, section, aCols, null, NO_DIFF);
    }

    public static RowDiff onlyB(String key, String section, String[] bCols) {
        return new RowDiff(key, Status.UNMATCHED_B, section, null, bCols, NO_DIFF);
    }
}
