package com.textdiff.store;

/** 生成任务记录：批次/作业完成后衍生的默认行为（差异 CSV 导出、文本模板 AI 分析）的跟踪状态。 */
public final class TaskRecord {
    public static final String EXPORT_DIFF = "export_diff";
    public static final String AI_ANALYSIS = "ai_analysis";

    public static final String PENDING = "pending";   // 待开始
    public static final String RUNNING = "running";   // 进行中
    public static final String DONE = "done";         // 已完成
    public static final String FAILED = "failed";     // 失败（可通过重新生成恢复）

    public static final String TRIGGER_AUTO = "auto";     // 作业完成自动触发
    public static final String TRIGGER_MANUAL = "manual"; // 任务管理页手动重新生成
    public static final String TRIGGER_BATCH = "batch";   // 任务管理页批量重新生成（可定时）

    public String taskId;
    public String batchId;   // 可空（单文件作业）
    public String jobId;
    public String taskType;  // EXPORT_DIFF / AI_ANALYSIS
    public String status = PENDING;
    public String trigger = TRIGGER_AUTO;
    public String outputPath = "";   // 生成产物路径（展示与审计）
    public String error = "";
    public long createdAt;
    public long startedAt;
    public long finishedAt;
    /** 定时执行时间（epoch 秒）；0 = 立即。未来时间时任务保持 pending，由调度器到点执行。 */
    public long scheduledAt;

    public TaskRecord() {}

    public TaskRecord(String taskId, String batchId, String jobId, String taskType) {
        this.taskId = taskId;
        this.batchId = batchId;
        this.jobId = jobId;
        this.taskType = taskType;
        this.createdAt = System.currentTimeMillis() / 1000;
    }
}
