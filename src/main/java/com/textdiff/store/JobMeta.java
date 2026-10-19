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
    /** 重复键 TOP10（分侧）。 */
    public List<DupKey> dupTopA, dupTopB;
    /** 增量过滤信息（ADDMOD 启用时非空）。 */
    public Incr incr;
    public Map<Integer, Long> diffColFreq;
    public Map<String, SummaryTrailerField> trailerFields;
    public Boolean recnumCheckA, recnumCheckB;

    /** 三分区 + trailer 行数计数（result.jsonl 写入时统计）。 */
    public long zoneEqual, zoneDiff, zoneUnmatched, zoneTrailer;

    /** 重复键条目（键值 + 第 2 次及以后出现次数）。 */
    public static final class DupKey {
        public String key;
        public long count;

        public DupKey() {}

        public DupKey(String key, long count) {
            this.key = key;
            this.count = count;
        }
    }

    /** 增量过滤统计：栏位（1-based）、条件值、两侧过滤前/后数据行数。 */
    public static final class Incr {
        public List<Integer> fields;
        public List<String> values;
        public long aBefore, aAfter, bBefore, bAfter;

        public Incr() {}

        public Incr(List<Integer> fields, List<String> values,
                    long aBefore, long aAfter, long bBefore, long bAfter) {
            this.fields = fields;
            this.values = values;
            this.aBefore = aBefore;
            this.aAfter = aAfter;
            this.bBefore = bBefore;
            this.bAfter = bAfter;
        }
    }

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
