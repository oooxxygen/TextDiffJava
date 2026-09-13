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

    public BatchRecord copy() {
        return Json.read(Json.write(this), BatchRecord.class);
    }
}
