package com.textdiff.store;

/** 结果页单条记录的人工评议（按 jobId + 键值 + 分区唯一）。对等 Python note 持久化。 */
public record NoteRecord(String jobId, String key, String zone, String note, long updatedAt) {}
