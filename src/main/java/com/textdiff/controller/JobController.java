package com.textdiff.controller;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Summary;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.NoteRecord;
import com.textdiff.store.ResultFiles;
import com.textdiff.task.JobManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 作业端点：列表/元数据/结果分页/评议/重跑/标签/锁定/收藏/删除/终止/重试。 */
@RestController
@RequestMapping("/api")
public class JobController {
    private final JobManager jobs;
    private final JobStore store;

    public JobController(JobManager jobs, JobStore store) {
        this.jobs = jobs;
        this.store = store;
    }

    @GetMapping("/joblist")
    public Map<String, Object> joblist() {
        List<Map<String, Object>> batches = new ArrayList<>();
        for (var b : store.listBatches()) {
            List<JobRecord> bj = store.listJobs(b.id);
            Map<String, Object> view = ApiViews.batchStats(b, bj);
            batches.add(view);
        }
        List<Map<String, Object>> standalone = new ArrayList<>();
        for (var j : store.listJobs(null)) {
            if (j.batchId == null) standalone.add(ApiViews.jobBrief(j, store));
        }
        return Map.of("batches", batches, "standalone", standalone, "split_jobs", List.of());
    }

    @GetMapping("/jobs/{id}/meta")
    public Map<String, Object> meta(@PathVariable String id) {
        JobRecord job = require(id);
        var meta = ResultFiles.readMeta(Path.of(job.resultDir));
        Summary summary = ResultFiles.readSummary(Path.of(job.resultDir));
        return ApiViews.metaResponse(job, meta, summary);
    }

    @GetMapping("/jobs/{id}/result")
    public Map<String, Object> result(@PathVariable String id,
                                      @RequestParam(required = false) String zone,
                                      @RequestParam(defaultValue = "0") long offset,
                                      @RequestParam(defaultValue = "50") long limit,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String note) {
        JobRecord job = require(id);
        if (limit > 500) limit = 500;
        Path file = Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL);

        var noted = store.notedKeys(job.id);
        Predicate<RowDiff> filter = r -> {
            if (q != null && !q.isBlank() && !r.key.contains(q)) return false;
            if ("has".equals(note) && !noted.contains(r.key)) return false;
            if ("none".equals(note) && noted.contains(r.key)) return false;
            return true;
        };
        ResultFiles.Page page = ResultFiles.readPage(file, zone, offset, limit, filter);
        return Map.of("rows", ApiViews.rowViews(page.rows()), "total", page.total());
    }

    @GetMapping("/jobs/{id}/notes")
    public Map<String, Object> notes(@PathVariable String id) {
        require(id);
        Map<String, Object> notes = new LinkedHashMap<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        int diff = 0, unmatched = 0, equal = 0;
        for (NoteRecord n : store.getNotes(id)) {
            notes.put(n.key(), Map.of("note", n.note(), "zone", n.zone(), "updated_at", n.updatedAt()));
            switch (n.zone() == null ? "" : n.zone()) {
                case "diff" -> diff++;
                case "unmatched" -> unmatched++;
                case "equal" -> equal++;
                default -> {
                }
            }
        }
        counts.put("diff", diff);
        counts.put("unmatched", unmatched);
        counts.put("equal", equal);
        return Map.of("notes", notes, "zone_note_counts", counts);
    }

    @PostMapping("/jobs/{id}/notes")
    public Map<String, Object> putNote(@PathVariable String id, @RequestBody Map<String, Object> body) {
        require(id);
        String key = str(body.get("key"));
        String note = str(body.get("note"));
        String zone = str(body.get("zone"));
        if (key == null) throw new IllegalArgumentException("缺少 key");
        store.putNote(new NoteRecord(id, key, zone == null ? "" : zone,
                note == null ? "" : note, System.currentTimeMillis() / 1000));
        return Map.of("ok", true);
    }

    /** 全局搜索批量评议：q 命中键的全部 diff 行赋 note（空串清除）。 */
    @PostMapping("/jobs/{id}/notes/bulk")
    public Map<String, Object> bulkNotes(@PathVariable String id, @RequestBody Map<String, Object> body) {
        JobRecord job = require(id);
        String q = str(body.get("q"));
        String note = str(body.get("note"));
        if (q == null || q.isBlank()) throw new IllegalArgumentException("缺少搜索关键字 q");
        var noted = store.notedKeys(job.id);
        int count = 0;
        for (RowDiff r : (Iterable<RowDiff>) ResultFiles
                .stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))::iterator) {
            if (!r.key.contains(q) || noted.contains(r.key)) continue;
            store.putNote(new NoteRecord(id, r.key, "diff",
                    note == null ? "" : note, System.currentTimeMillis() / 1000));
            count++;
        }
        return Map.of("count", count);
    }

    @PostMapping("/jobs/{id}/rerun")
    public Map<String, Object> rerun(@PathVariable String id,
                                     @RequestBody ApiPayloads.ComparePayload p) {
        JobRecord job = require(id);
        String configLine = ApiPayloads.buildLegacyLine(p);
        job.configLine = configLine;
        job.status = JobRecord.PENDING;
        job.error = null;
        job.aiStatus = "none";
        store.saveJob(job);
        ApiViews.deleteRecursively(Path.of(job.resultDir)); // 旧结果清空后重跑
        jobs.submit(job);
        return Map.of("job_id", job.id);
    }

    @PostMapping("/jobs/{id}/label")
    public Map<String, Object> label(@PathVariable String id, @RequestBody Map<String, Object> body) {
        JobRecord job = require(id);
        job.label = str(body.get("label"));
        store.saveJob(job);
        return Map.of("ok", true);
    }

    @PostMapping("/jobs/{id}/star")
    public Map<String, Object> star(@PathVariable String id) {
        return setFlag(id, true, false);
    }

    @PostMapping("/jobs/{id}/unstar")
    public Map<String, Object> unstar(@PathVariable String id) {
        return setFlag(id, false, false);
    }

    @PostMapping("/jobs/{id}/lock")
    public Map<String, Object> lock(@PathVariable String id) {
        return setFlag(id, false, true);
    }

    @PostMapping("/jobs/{id}/unlock")
    public Map<String, Object> unlock(@PathVariable String id) {
        return setFlag(id, false, false);
    }

    private Map<String, Object> setFlag(String id, boolean starred, boolean locked) {
        JobRecord job = require(id);
        if (starred || locked) {
            if (starred) job.starred = true;
            if (locked) job.locked = true;
        } else {
            job.starred = false;
            job.locked = false;
        }
        store.saveJob(job);
        return Map.of("ok", true);
    }

    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        require(id);
        return Map.of("ok", jobs.cancel(id));
    }

    @PostMapping("/jobs/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        require(id);
        jobs.retry(id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/jobs/{id}")
    public Map<String, Object> deleteJob(@PathVariable String id) {
        JobRecord job = require(id);
        if (job.locked) throw new ResponseStatusException(HttpStatus.CONFLICT, "作业已锁定");
        store.deleteJob(id);
        ApiViews.deleteRecursively(Path.of(job.resultDir));
        return Map.of("ok", true);
    }

    @PostMapping("/jobs/bulk-delete")
    public Map<String, Object> bulkDelete(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> jobIds = (List<String>) body.getOrDefault("job_ids", List.of());
        @SuppressWarnings("unchecked")
        List<String> batchIds = (List<String>) body.getOrDefault("batch_ids", List.of());
        int deletedJobs = 0;
        List<String> locked = new ArrayList<>();
        for (String jid : jobIds) {
            JobRecord job = store.getJob(jid);
            if (job == null) continue;
            if (job.locked) {
                locked.add(jid);
                continue;
            }
            store.deleteJob(jid);
            ApiViews.deleteRecursively(Path.of(job.resultDir));
            deletedJobs++;
        }
        int deletedBatches = 0;
        for (String bid : batchIds) {
            var batch = store.getBatch(bid);
            if (batch == null) continue;
            if (batch.locked) {
                locked.add(bid);
                continue;
            }
            for (JobRecord j : store.listJobs(bid)) {
                ApiViews.deleteRecursively(Path.of(j.resultDir));
            }
            store.deleteBatch(bid);
            deletedBatches++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted_jobs", deletedJobs);
        out.put("deleted_batches", deletedBatches);
        out.put("locked", locked);
        return out;
    }

    /** M5 前占位：AI 归纳分析。 */
    @PostMapping("/jobs/{id}/analyze")
    public Map<String, Object> analyze(@PathVariable String id) {
        require(id);
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "AI 分析将在 M5 里程碑启用");
    }

    /** M5 前占位：提示词 MD 预览。 */
    @GetMapping("/jobs/{id}/prompt")
    public Map<String, Object> prompt(@PathVariable String id) {
        require(id);
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "提示词模板将在 M5 里程碑启用");
    }

    private JobRecord require(String id) {
        JobRecord job = store.getJob(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在: " + id);
        return job;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
