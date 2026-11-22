package com.textdiff.controller;

import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.Rules;
import com.textdiff.engine.Summary;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.ResultFiles;
import com.textdiff.task.JobManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 视图组装：记录 → 前端契约形态（status 映射 failed→error 等）。供 Job/Batch 控制器共用。 */
final class ApiViews {
    private ApiViews() {}

    static String apiStatus(JobRecord job) {
        return JobRecord.FAILED.equals(job.status) ? "error" : job.status;
    }

    /** 作业概要视图（joblist children / standalone 共用）。deep=false 时跳过 meta.json 兜底读
     *  （全局列表高频轮询路径：5000 旧记录 × 每次轮询的逐作业磁盘读曾是主要 IO 开销）。 */
    static Map<String, Object> jobBrief(JobRecord job, JobStore store, boolean deep) {
        return jobBrief(job, store, deep, null);
    }

    /** 归属组换源版（需求③）：groupOf 按昵称解析 type_parm 的 ownership_group，null/查不到回退昵称。 */
    static Map<String, Object> jobBrief(JobRecord job, JobStore store, boolean deep,
                                        java.util.function.Function<String, String> groupOf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", job.id);
        m.put("batch_id", job.batchId);
        m.put("file_a", job.fileA);
        m.put("file_b", job.fileB);
        m.put("status", apiStatus(job));
        m.put("error", job.error);
        m.put("created_at", job.createdAt);
        m.put("started_at", job.startedAt);
        m.put("finished_at", job.finishedAt);
        m.put("label", job.label == null || job.label.isEmpty() ? job.nickname : job.label);
        m.put("locked", job.locked);
        m.put("starred", job.starred);
        m.put("key_warning", job.keyWarning);
        m.put("job_type", job.jobType == null || job.jobType.isEmpty() ? "file" : job.jobType);
        m.put("incr_state", job.incrState == null || job.incrState.isEmpty() ? null : job.incrState);
        m.put("awaiting_cond", job.awaitingCond);
        m.put("conf_file", job.confFile);

        CompareConfig cfg = Rules.parseLegacy(job.configLine,
                ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("nickname", cfg.nickname);
        String owner = groupOf != null ? groupOf.apply(cfg.nickname) : null;
        config.put("group", owner != null && !owner.isEmpty() ? owner
                : (cfg.group == null || cfg.group.isEmpty() ? cfg.nickname : cfg.group));
        String[] src = srcNamesOf(job.configLine);
        config.put("source_a", src[0]);
        config.put("source_b", src[1]);
        m.put("config", config);

        if (job.diffCount >= 0) {
            // 完成快照直出：作业列表/批次页高频轮询免逐作业磁盘读
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("equal", job.equalCount);
            s.put("diff", job.diffCount);
            s.put("only_a", job.onlyACount);
            s.put("only_b", job.onlyBCount);
            s.put("total_a", job.totalA);
            s.put("total_b", job.totalB);
            m.put("summary", s);
        } else if (deep) {
            JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir)); // 旧记录/无快照来源兜底
            if (meta != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("equal", meta.equal);
                s.put("diff", meta.diff);
                s.put("only_a", meta.onlyA);
                s.put("only_b", meta.onlyB);
                s.put("total_a", meta.totalA);
                s.put("total_b", meta.totalB);
                m.put("summary", s);
            }
        }
        return m;
    }

    static Map<String, Object> jobBrief(JobRecord job, JobStore store) {
        return jobBrief(job, store, true);
    }

    /** 批次增量待条件清单（增量后置流程）：挂起作业的昵称/文件名/ADDMOD 1-based 栏位，批次页弹增量对话框的数据源。 */
    static List<Map<String, Object>> addmodPending(List<JobRecord> jobs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobRecord j : jobs) {
            if (!j.awaitingCond || !JobRecord.PENDING.equals(j.status)) continue;
            CompareConfig cfg = Rules.parseLegacy(j.configLine,
                    ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", j.id);
            m.put("nickname", cfg.nickname);
            String fn = null;
            try {
                fn = Path.of(j.fileA == null ? "" : j.fileA).getFileName().toString();
            } catch (RuntimeException ignored) {
            }
            m.put("file_name", fn);
            m.put("addmod", cfg.incrFields.stream().map(f -> f + 1).toList());
            out.add(m);
        }
        return out;
    }

    /**
     * configLine 来源名（需求①别名修复）：legacy 行走 SRCA/SRCB 令牌；custom 的 JSON 行
     * 读 srca/srcb 附加键——注意 Rules.parseLegacy 对 JSON 行<b>不抛错</b>只回默认 A/B，
     * 必须按 "{ " 前缀分流，否则 JSON 回退永不生效；均缺省 A/B。
     */
    static String[] srcNamesOf(String configLine) {
        String a = "A", b = "B";
        String line = configLine == null ? "" : configLine.strip();
        if (line.startsWith("{")) {
            try {
                Map<String, String> m = com.textdiff.store.Json.read(line, Map.class);
                if (m != null) {
                    String va = m.get("srca");
                    String vb = m.get("srcb");
                    if (va != null && !va.isBlank()) a = va.strip();
                    if (vb != null && !vb.isBlank()) b = vb.strip();
                }
            } catch (RuntimeException ignored) {
                // 无命名信息按默认处理
            }
            return new String[]{a, b};
        }
        try {
            CompareConfig cfg = Rules.parseLegacy(line,
                    ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
            if (cfg.sourceA != null && !cfg.sourceA.isBlank()) a = cfg.sourceA.strip();
            if (cfg.sourceB != null && !cfg.sourceB.isBlank()) b = cfg.sourceB.strip();
        } catch (RuntimeException ignored) {
            // 解析失败按默认处理
        }
        return new String[]{a, b};
    }

    /** 批次聚合状态与统计。slim=true（全局列表轮询）不带全量清单——5000 文件的 no_rule_files
     *  每次轮询都随响应序列化曾是响应体与 GC 的主要负担，清单只在批次详情页（slim=false）下发。 */
    static Map<String, Object> batchStats(BatchRecord batch, List<JobRecord> jobs, boolean slim) {
        String status = "done";
        long running = jobs.stream().filter(j -> JobRecord.RUNNING.equals(j.status)
                || JobRecord.PENDING.equals(j.status)).count();
        long failed = jobs.stream().filter(j -> JobRecord.FAILED.equals(j.status)).count();
        long diffFiles = jobs.stream().filter(j -> hasDiffs(j, !slim)).count();
        long done = jobs.size() - running;
        if (BatchRecord.PREPARING.equals(batch.prepareState)) status = BatchRecord.PREPARING;
        else if (BatchRecord.PREPARE_FAILED.equals(batch.prepareState)) status = "error";
        else if (running > 0) status = "running";
        else if (failed == jobs.size() && !jobs.isEmpty()) status = "error";

        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("batch_id", batch.id);
        b.put("dir_a", batch.dirA);
        b.put("dir_b", batch.dirB);
        b.put("config_source", batch.configSource);
        b.put("created_at", batch.createdAt);
        b.put("label", batch.label);
        b.put("locked", batch.locked);
        b.put("batch_type", batch.batchType == null || batch.batchType.isEmpty() ? "file" : batch.batchType);
        b.put("group_name", batch.groupName);
        b.put("cmp_batch", batch.cmpBatch);
        b.put("cmp_round", batch.cmpRound);
        b.put("template_dir", batch.templateDir);
        b.put("prepare_state", batch.prepareState == null ? BatchRecord.READY : batch.prepareState);
        if (batch.prepareError != null) b.put("prepare_error", batch.prepareError);
        b.put("no_rule_files", slim ? List.of() : batch.noRuleFiles == null ? List.of() : batch.noRuleFiles);
        b.put("missing_files", slim ? List.of() : batch.missingFiles == null ? List.of() : batch.missingFiles);
        b.put("extract_failed", slim ? List.of() : batch.extractFailed == null ? List.of() : batch.extractFailed);
        // 批次执行耗时区间：最早开始的作业 → 最晚完成的作业（无一完成时回退最早创建）
        long minStart = jobs.stream().mapToLong(j -> j.startedAt).filter(v -> v > 0).min().orElse(0);
        if (minStart == 0) minStart = jobs.stream().mapToLong(j -> j.createdAt).min().orElse(0);
        if (minStart == 0) minStart = batch.createdAt;
        b.put("started_at", minStart);
        b.put("finished_at", jobs.stream().mapToLong(j -> j.finishedAt).max().orElse(0));
        m.put("batch", b);
        m.put("status", status);
        m.put("total_files", jobs.size());
        m.put("diff_files", diffFiles);
        m.put("done", done);
        m.put("no_rule_count", batch.noRuleFiles == null ? 0 : batch.noRuleFiles.size());
        m.put("missing_count", batch.missingFiles == null ? 0 : batch.missingFiles.size());
        m.put("extract_failed_count", batch.extractFailed == null ? 0 : batch.extractFailed.size());
        return m;
    }

    static Map<String, Object> batchStats(BatchRecord batch, List<JobRecord> jobs) {
        return batchStats(batch, jobs, false);
    }

    /** 差异文件判定：优先用作业记录上的完成快照（免磁盘读）；deep 时旧记录回退读 meta.json。 */
    private static boolean hasDiffs(JobRecord job, boolean deep) {
        if (job.diffCount >= 0) return job.diffCount > 0;
        if (!deep) return false; // 全局轮询路径不做逐作业磁盘读
        JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir));
        return meta != null && meta.diff > 0;
    }

    /** meta 端点完整响应（meta + summary + zone_counts）。 */
    static Map<String, Object> metaResponse(JobRecord job, JobMeta meta, Summary summary) {
        return metaResponse(job, meta, summary, null);
    }

    /** 归属组换源版（需求③）：groupOf 按昵称解析 type_parm 的 ownership_group，null/查不到回退昵称。 */
    static Map<String, Object> metaResponse(JobRecord job, JobMeta meta, Summary summary,
                                            java.util.function.Function<String, String> groupOf) {
        Map<String, Object> resp = new LinkedHashMap<>();

        CompareConfig cfg = Rules.parseLegacy(job.configLine,
                ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
        Map<String, Object> config = ApiPayloads.configMap(cfg, "job",
                groupOf == null ? null : groupOf.apply(cfg.nickname));
        // 需求①别名修复：custom 的 JSON configLine 不含 SRCA 令牌，parseLegacy 只回默认 A/B——
        // 统一经 srcNamesOf 分流（JSON 行读 srca/srcb 附加键）覆盖，别名在各处展示真实生效
        String[] src = srcNamesOf(job.configLine);
        config.put("source_a", src[0]);
        config.put("source_b", src[1]);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", job.id);
        m.put("batch_id", job.batchId);
        m.put("status", apiStatus(job));
        m.put("message", JobRecord.RUNNING.equals(job.status) ? "比对进行中" : "");
        m.put("progress", JobRecord.DONE.equals(job.status) ? 1.0
                : JobRecord.RUNNING.equals(job.status) ? 0.5 : 0.0);
        m.put("error", job.error);
        m.put("label", job.label == null || job.label.isEmpty() ? job.nickname : job.label);
        m.put("locked", job.locked);
        m.put("created_at", job.createdAt);
        m.put("started_at", job.startedAt);
        m.put("finished_at", job.finishedAt);
        m.put("file_a", job.fileA);
        m.put("file_b", job.fileB);
        m.put("detected_encoding_a", meta != null ? meta.encodingA : null);
        m.put("detected_encoding_b", meta != null ? meta.encodingB : null);
        m.put("used_disk_fallback", false);
        m.put("key_warning", job.keyWarning);
        m.put("ai_status", job.aiStatus);
        m.put("incr_state", job.incrState == null || job.incrState.isEmpty() ? null : job.incrState);
        m.put("incr", meta != null ? meta.incr : null);
        m.put("conf_file", job.confFile);
        m.put("config", config);
        if (meta != null && meta.dynCols != null && !meta.dynCols.isEmpty()) m.put("dyn_cols", meta.dynCols);
        if (meta != null && meta.dynWarnings != null && !meta.dynWarnings.isEmpty()) {
            m.put("dyn_warnings", meta.dynWarnings);
        }
        resp.put("meta", m);

        if (summary != null) {
            resp.put("summary", summary);
        } else if (meta != null) {
            resp.put("summary", meta);
        } else {
            resp.put("summary", Map.of());
        }

        long zoneDiff = meta != null ? meta.zoneDiff : 0;
        long zoneEqual = meta != null ? meta.zoneEqual : 0;
        long zoneUnmatched = meta != null ? meta.zoneUnmatched : 0;
        long zoneTrailer = meta != null ? meta.zoneTrailer : 0;
        resp.put("zone_counts", Map.of("diff", zoneDiff, "unmatched", zoneUnmatched,
                "equal", zoneEqual, "trailer", zoneTrailer));
        return resp;
    }

    /** 结果分页行（仅 equal 行回填 b_cols=a_cols——B 隐含等于 A；单侧缺失保持 null，前端显示「无此行」）。 */
    static List<Map<String, Object>> rowViews(List<com.textdiff.engine.RowDiff> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key);
            m.put("status", r.status);
            m.put("section", r.section);
            m.put("a_cols", r.aCols);
            m.put("b_cols", r.bCols != null ? r.bCols
                    : com.textdiff.engine.Status.EQUAL.equals(r.status) ? r.aCols : null);
            m.put("diff_cols", r.diffCols);
            if (r.aRaw != null) m.put("a_raw", r.aRaw);
            if (r.bRaw != null) m.put("b_raw", r.bRaw);
            if (r.dyn != null && !r.dyn.isEmpty()) m.put("dyn", r.dyn);
            out.add(m);
        }
        return out;
    }

    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
