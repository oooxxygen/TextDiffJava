package com.textdiff.report;

import java.util.ArrayList;
import java.util.List;

/** 报表对比摘要（report_summary.json + /api/report-jobs/{id}/summary 返回体）。 */
public final class ReportSummary {
    public long sectionCountA, sectionCountB;
    /** 业务条数（程序计数，报表内可能读不到总数）。 */
    public long rowCountA, rowCountB;
    public long headerBlockDiff, footerBlockDiff;
    public long headerLineDiff, footerLineDiff;
    /** 业务行匹配统计：完全相等 / 部分匹配（排序后相似配对、有字段差异）/ 单侧不匹配。 */
    public long equal, partial, onlyA, onlyB;
    /** 控制行版式（1@OD@| 自分区）标识；foldLines = 折行记录的物理行数（非折行 = 1）。 */
    public boolean controlFormat;
    public long foldLines;
    /** 条数核对：表尾声明条数 vs 程序计数（按侧按段）。 */
    public List<CountCheck> countChecks = new ArrayList<>();
    /** 业务行栏位名（铺底映射优先，供差异展示）。 */
    public List<String> fieldNames = new ArrayList<>();
    /** 对比配置（0-based 列号）：主键列（空 = 整行对比）与跳过栏位。 */
    public List<Integer> keyColumns = new ArrayList<>();
    public List<Integer> omitColumns = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    public static final class CountCheck {
        public String side;      // A / B
        public int section;      // 0-based 段号
        public String declared;  // 表尾声明条数（未识别 = null）
        public long counted;     // 程序计数
        public boolean match;

        public CountCheck() {}

        public CountCheck(String side, int section, String declared, long counted, boolean match) {
            this.side = side;
            this.section = section;
            this.declared = declared;
            this.counted = counted;
            this.match = match;
        }
    }
}
