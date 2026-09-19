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

    /** 作业概要视图（joblist children / standalone 共用）。 */
    static Map<String, Object> jobBrief(JobRecord job, JobStore store) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", job.id);
        m.put("batch_id", job.batchId);
        m.put("file_a", job.fileA);
        m.put("file_b", job.fileB);
        m.put("status", apiStatus(job));
        m.put("error", job.error);
        m.put("created_at", job.createdAt);
        m.put("finished_at", job.finishedAt);
        m.put("label", job.label == null || job.label.isEmpty() ? job.nickname : job.label);
        m.put("locked", job.locked);
        m.put("starred", job.starred);
        m.put("key_warning", job.keyWarning);
        m.put("job_type", job.jobType == null || job.jobType.isEmpty() ? "file" : job.jobType);

        CompareConfig cfg = Rules.parseLegacy(job.configLine,
                ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("nickname", cfg.nickname);
        config.put("group", cfg.group == null || cfg.group.isEmpty() ? cfg.nickname : cfg.group);
        config.put("source_a", cfg.sourceA);
        config.put("source_b", cfg.sourceB);
        m.put("config", config);

        JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir));
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
        return m;
    }

    /** 批次聚合状态与统计。 */
    static Map<String, Object> batchStats(BatchRecord batch, List<JobRecord> jobs) {
        String status = "done";
        long running = jobs.stream().filter(j -> JobRecord.RUNNING.equals(j.status)
                || JobRecord.PENDING.equals(j.status)).count();
        long failed = jobs.stream().filter(j -> JobRecord.FAILED.equals(j.status)).count();
        long diffFiles = jobs.stream().filter(j -> {
            JobMeta meta = ResultFiles.readMeta(Path.of(j.resultDir));
            return meta != null && meta.diff > 0;
        }).count();
        long done = jobs.size() - running;
        if (running > 0) status = "running";
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
        b.put("template_dir", batch.templateDir);
        b.put("no_rule_files", batch.noRuleFiles == null ? List.of() : batch.noRuleFiles);
        m.put("batch", b);
        m.put("status", status);
        m.put("total_files", jobs.size());
        m.put("diff_files", diffFiles);
        m.put("done", done);
        m.put("no_rule_count", batch.noRuleFiles == null ? 0 : batch.noRuleFiles.size());
        return m;
    }

    /** meta 端点完整响应（meta + summary + zone_counts）。 */
    static Map<String, Object> metaResponse(JobRecord job, JobMeta meta, Summary summary) {
        Map<String, Object> resp = new LinkedHashMap<>();

        CompareConfig cfg = Rules.parseLegacy(job.configLine,
                ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
        Map<String, Object> config = ApiPayloads.configMap(cfg, "job");

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
        m.put("finished_at", job.finishedAt);
        m.put("file_a", job.fileA);
        m.put("file_b", job.fileB);
        m.put("detected_encoding_a", meta != null ? meta.encodingA : null);
        m.put("detected_encoding_b", meta != null ? meta.encodingB : null);
        m.put("used_disk_fallback", false);
        m.put("key_warning", job.keyWarning);
        m.put("ai_status", job.aiStatus);
        m.put("config", config);
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

    /** 结果分页行（b_cols 缺省时 equal 行回填 a_cols）。 */
    static List<Map<String, Object>> rowViews(List<com.textdiff.engine.RowDiff> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key);
            m.put("status", r.status);
            m.put("section", r.section);
            m.put("a_cols", r.aCols);
            m.put("b_cols", r.bCols != null ? r.bCols : r.aCols);
            m.put("diff_cols", r.diffCols);
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
