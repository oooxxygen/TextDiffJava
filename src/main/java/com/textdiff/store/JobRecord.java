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
    /** 用户标签（结果页/列表改名）。 */
    public String label;
    /** 锁定后禁止删除（bulk-delete 返回 locked 清单）。 */
    public boolean locked;
    /** 收藏星标。 */
    public boolean starred;
    /** 作业类型：null/"file" = 文件对比；"report" = 报表对比（模板核对）。 */
    public String jobType;
    /** 报表对比：header 模板文件路径（文件对比为 null）。 */
    public String templateFile;
    /** 完成时快照的差异行数（-1 = 未知：未完成/重跑中/旧记录）。列表视图据此免读 meta.json。 */
    public long diffCount = -1;
    /** 完成时快照的统计口径（-1 = 未知），供批次页/作业列表懒加载。 */
    public long totalA = -1;
    public long totalB = -1;
    public long equalCount = -1;
    public long onlyACount = -1;
    public long onlyBCount = -1;
    /** 增量过滤条件值（JSON 字符串数组，顺序对应规则 ADDMOD 栏位）；null/空 = 未启用增量。重跑/重试沿用。 */
    public String incrCond;
    /** 增量状态标注：null/"" = 未启用；"增量" = 过滤生效；"增量-空文本" = 过滤后两侧均 0 行。 */
    public String incrState;

    public JobRecord() {}

    /** 重跑/重试前清空统计快照，避免运行中展示上一次的旧结果。增量状态一并失效（重跑后按条件重算）。 */
    public void clearStats() {
        diffCount = -1;
        totalA = -1;
        totalB = -1;
        equalCount = -1;
        onlyACount = -1;
        onlyBCount = -1;
        incrState = null;
    }

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
