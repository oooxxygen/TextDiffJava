package com.textdiff.store;

/**
 * 一次文件对对比作业的可变记录。Jackson 按公共字段序列化。
 * 状态机：pending → running → done / failed / stopped。
 */
public final class JobRecord {
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String STOPPED = "stopped";

    public String id;
    public String batchId;
    public String nickname;
    /** 完整 legacy 配置行（Rules.toLegacyLine full），结果页配置展示与重跑依据。 */
    public String configLine;
    public String fileA;
    public String fileB;
    public String status = PENDING;
    public String error;
    /** 主键唯一性告警：任一侧存在重复键（需求：醒目提示配置需重检）。 */
    public boolean keyWarning;
    /** AI 归纳分析状态：none/pending/running/done/failed。 */
    public String aiStatus = "none";
    public long createdAt;
    public long startedAt;
    public long finishedAt;
    /** 结果目录（results/{jobId}），重启后加载展示依据。 */
    public String resultDir;

    public JobRecord() {}

    public JobRecord(String id, String batchId, String nickname, String configLine,
                     String fileA, String fileB, String resultDir) {
        this.id = id;
        this.batchId = batchId;
        this.nickname = nickname;
        this.configLine = configLine;
        this.fileA = fileA;
        this.fileB = fileB;
        this.resultDir = resultDir;
        this.createdAt = System.currentTimeMillis() / 1000;
    }

    public JobRecord copy() {
        return Json.read(Json.write(this), JobRecord.class);
    }
}
