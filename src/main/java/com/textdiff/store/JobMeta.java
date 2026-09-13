package com.textdiff.store;

import java.util.List;
import java.util.Map;

/** 作业结果元数据（meta.json）：/api/jobs/{id}/meta 的返回体来源，含 Summary 快照。 */
public final class JobMeta {
    public String jobId, batchId, nickname, configLine, fileA, fileB;
    public String encodingA, encodingB;
    public String status, error;
    public boolean keyWarning;
    public String aiStatus;
    public long createdAt, startedAt, finishedAt;

    public long totalA, totalB;
    public long equal, diff, onlyA, onlyB;
    public long keyDupA, keyDupB;
    public List<String> dupKeySamples;
    public Map<Integer, Long> diffColFreq;
    public Map<String, SummaryTrailerField> trailerFields;
    public Boolean recnumCheckA, recnumCheckB;

    /** 三分区 + trailer 行数计数（result.jsonl 写入时统计）。 */
    public long zoneEqual, zoneDiff, zoneUnmatched, zoneTrailer;

    /** Summary.TrailerField 的镜像（engine 类型不进 store 层 API 契约）。 */
    public static final class SummaryTrailerField {
        public String a, b;
        public boolean equal;

        public SummaryTrailerField() {}

        public SummaryTrailerField(String a, String b, boolean equal) {
            this.a = a;
            this.b = b;
            this.equal = equal;
        }
    }
}
