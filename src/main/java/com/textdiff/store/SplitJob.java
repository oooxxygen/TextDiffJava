package com.textdiff.store;

import java.util.List;

/**
 * 文本拆分作业记录（{@code store/split_jobs.jsonl}）：A/B 两侧按同一规则配置拆分的执行档案。
 * summary 按侧结构：total=数据行数（归属各组的行）、trailer=尾部区行数、groups=组名→行数（规则序 + 其他）。
 */
public record SplitJob(String jobId, String label, String status, String error,
                       SplitConfig config, Inputs inputs, Summary summary,
                       String resultDir, long createdAt, long finishedAt) {

    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    /** 输入文件：a 必填、b 可选（空串表示未提供）。 */
    public record Inputs(String a, String b) { }

    public record Summary(Side a, Side b) { }

    public record Side(long total, long trailer, List<GroupCount> groups) { }

    public record GroupCount(String name, long count) { }
}
