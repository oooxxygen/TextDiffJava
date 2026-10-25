package com.textdiff.store;

/** 一次提交（可能包含多个文件对作业）的批次记录。 */
public final class BatchRecord {
    public String id;
    /** 来源说明：目录对 "dirA vs dirB" 或 "upload"。 */
    public String label;
    public String dirA;
    public String dirB;
    /** 配置来源描述（配置文件路径或行数）。 */
    public String configSource;
    public long createdAt;
    public int jobCount;
    /** 锁定后禁止删除。 */
    public boolean locked;
    /** dirA 中未被任何配置 glob 命中的文件名（提交时算好存档）。 */
    public java.util.List<String> noRuleFiles;
    /** 未能读取到相应文件：配置条目在两侧目录（含解压逻辑名）均未配对出文件（昵称 · 通配名）。 */
    public java.util.List<String> missingFiles;
    /** 压缩文件解压失败清单（文件名（原因）），解压期追加。 */
    public java.util.List<String> extractFailed;
    /** 批次类型：null/"file" = 文件对比；"report" = 报表对比；"custom" = 自定义格式；"ctis" = CTIS。 */
    public String batchType;
    /** 报表对比：模板目录（文件对比为 null）。 */
    public String templateDir;
    /** 配对/建档进度：null/"ready" = 已就绪（旧记录视为就绪）；"preparing" = 后台配对建档中；"failed" = 建档失败。 */
    public String prepareState;
    /** 建档失败原因（prepareState=failed 时）。 */
    public String prepareError;
    /** 目录对比圈定的对比配置分组名（null = 未选分组，对比全部文本）。 */
    public String groupName;

    public static final String PREPARING = "preparing";
    public static final String READY = "ready";
    public static final String PREPARE_FAILED = "failed";

    public BatchRecord() {}

    public BatchRecord(String id, String label, String dirA, String dirB, String configSource, int jobCount) {
        this.id = id;
        this.label = label;
        this.dirA = dirA;
        this.dirB = dirB;
        this.configSource = configSource;
        this.jobCount = jobCount;
        this.createdAt = System.currentTimeMillis() / 1000;
    }

    /** 逐字段拷贝（列表浅拷贝到新 ArrayList，防外部改写共享清单）。 */
    public BatchRecord copy() {
        BatchRecord c = new BatchRecord(id, label, dirA, dirB, configSource, jobCount);
        c.createdAt = createdAt; // 构造器把 createdAt 置为当下，漏拷会让列表时间随轮询漂移成"实时"
        c.locked = locked;
        c.noRuleFiles = noRuleFiles == null ? null : new java.util.ArrayList<>(noRuleFiles);
        c.missingFiles = missingFiles == null ? null : new java.util.ArrayList<>(missingFiles);
        c.extractFailed = extractFailed == null ? null : new java.util.ArrayList<>(extractFailed);
        c.batchType = batchType;
        c.templateDir = templateDir;
        c.prepareState = prepareState;
        c.prepareError = prepareError;
        c.groupName = groupName;
        return c;
    }
}
