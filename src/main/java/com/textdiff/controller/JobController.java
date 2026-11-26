package com.textdiff.controller;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Summary;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.Json;
import com.textdiff.store.NoteRecord;
import com.textdiff.store.ResultFiles;
import com.textdiff.task.JobManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final com.textdiff.ai.AiAnalyzer analyzer;
    private final com.textdiff.store.TaskStore taskStore;
    private final com.textdiff.store.SplitJobStore splitJobs;
    private final com.textdiff.store.FieldMapStore fieldMaps;

    public JobController(JobManager jobs, JobStore store, com.textdiff.ai.AiAnalyzer analyzer,
                         com.textdiff.store.TaskStore taskStore,
                         com.textdiff.store.SplitJobStore splitJobs,
                         com.textdiff.store.FieldMapStore fieldMaps) {
        this.jobs = jobs;
        this.store = store;
        this.analyzer = analyzer;
        this.taskStore = taskStore;
        this.splitJobs = splitJobs;
        this.fieldMaps = fieldMaps;
    }

    @GetMapping("/joblist")
    public Map<String, Object> joblist() {
        // 单次全量扫描按批次分组（此前每批次全表扫描一次：批次数 × 作业数的深拷贝正是 OOM 高危路径）
        Map<String, List<JobRecord>> byBatch = new LinkedHashMap<>();
        List<JobRecord> standaloneRecs = new ArrayList<>();
        for (JobRecord j : store.listJobs(null)) {
            if (j.batchId == null) standaloneRecs.add(j);
            else byBatch.computeIfAbsent(j.batchId, k -> new ArrayList<>()).add(j);
        }
        List<Map<String, Object>> batches = new ArrayList<>();
        for (var b : store.listBatches()) {
            batches.add(ApiViews.batchStats(b, byBatch.getOrDefault(b.id, List.of()), true));
        }
        List<Map<String, Object>> standalone = new ArrayList<>();
        for (JobRecord j : standaloneRecs) standalone.add(ApiViews.jobBrief(j, store, false, fieldMaps::ownershipGroupOf));
        List<Map<String, Object>> splits = new ArrayList<>();
        for (com.textdiff.store.SplitJob s : splitJobs.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", s.jobId());
            m.put("label", s.label());
            m.put("status", s.status());
            m.put("created_at", s.createdAt());
            m.put("summary", s.summary());
            splits.add(m);
        }
        return Map.of("batches", batches, "standalone", standalone, "split_jobs", splits);
    }

    @GetMapping("/jobs/{id}/meta")
    public Map<String, Object> meta(@PathVariable String id) {
        JobRecord job = require(id);
        var meta = ResultFiles.readMeta(Path.of(job.resultDir));
        Summary summary = ResultFiles.readSummary(Path.of(job.resultDir));
        return ApiViews.metaResponse(job, meta, summary, fieldMaps::ownershipGroupOf);
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

        // 无过滤条件走 NO_FILTER 快路径（窗口外零解析）；评议键集合仅确需过滤时读取
        Predicate<RowDiff> filter = ResultFiles.NO_FILTER;
        if ((q != null && !q.isBlank()) || note != null) {
            var noted = store.notedKeys(job.id);
            filter = r -> {
                if (q != null && !q.isBlank() && !r.key.contains(q)) return false;
                if ("has".equals(note) && !noted.contains(r.key)) return false;
                if ("none".equals(note) && noted.contains(r.key)) return false;
                return true;
            };
        }
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
        job.clearStats(); // 旧结果目录已删除，列表统计同步失效
        store.saveJob(job);
        ApiViews.deleteRecursively(Path.of(job.resultDir)); // 旧结果清空后重跑
        jobs.submit(job);
        return Map.of("job_id", job.id);
    }

    /**
     * 修改配置后重跑「同昵称」（单文本界面发起，作用整个批次）：
     * <ul>
     *   <li>legacy（普通文件/CTIS，legacy configLine）：按 ComparePayload 重建 configLine（同
     *       /rerun），批次内解析昵称（Rules.parseLegacy）与重建后昵称一致的作业全部以新配置重跑；</li>
     *   <li>custom（JSON configLine）：body 可带 start/end/key/scene_sort → 校验
     *       （CustomFormat.of）后就地改写各作业 configLine 对应键；批次内 jobType=custom 且
     *       展示昵称（label 优先，回退剥「 · 」后缀的 nickname）相同的作业重跑；</li>
     *   <li>textline（JSON configLine）：body 可带 key_positions/encoding_a/encoding_b →
     *       校验（TextLineFormat.of）后批次内<b>全部</b> textline 作业就地改写对应键重跑
     *       （各作业 srca/srcb 来源命名保留）。</li>
     * </ul>
     * RUNNING 作业跳过（避免并发重跑）；PENDING 作业仅改写配置（已在队列，不重复入池）。
     * 请求体字段同各类型提交接口；无批次归属的作业 400。
     */
    @PostMapping("/jobs/{id}/rerun-nicknames")
    public Map<String, Object> rerunNicknames(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        return doRerunNicknames(id, body);
    }

    /** 同上（普通文本比对结果页入口：/api/textline-jobs/{id}/rerun-nicknames）。 */
    @PostMapping("/textline-jobs/{id}/rerun-nicknames")
    public Map<String, Object> rerunNicknamesTl(@PathVariable String id,
                                                @RequestBody Map<String, Object> body) {
        return doRerunNicknames(id, body);
    }

    /** 同上（自定义格式结果页入口：/api/custom-jobs/{id}/rerun-nicknames）。 */
    @PostMapping("/custom-jobs/{id}/rerun-nicknames")
    public Map<String, Object> rerunNicknamesCustom(@PathVariable String id,
                                                    @RequestBody Map<String, Object> body) {
        return doRerunNicknames(id, body);
    }

    private Map<String, Object> doRerunNicknames(String id, Map<String, Object> body) {
        JobRecord src = require(id);
        if (src.batchId == null || src.batchId.isBlank()) {
            throw new IllegalArgumentException("该作业不属于任何批次，无同昵称范围可重跑");
        }
        String type = src.jobType == null ? "file" : src.jobType;
        List<JobRecord> batchJobs = store.listJobs(src.batchId);

        // 预计算：新配置键值（各类型统一「先校验、后改写」，非法配置在改动任何作业前即失败）
        String newLegacyLine = null;
        String legacyNick = null;
        Map<String, String> jsonUpdates = null; // custom/textline 的 JSON 键改写（null 键 = 删除该键）
        String matchNick = null;                // custom 的同昵称匹配键

        if ("custom".equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, String> cur = Json.read(src.configLine, Map.class);
            // containsKey 区分「字段缺席（沿用原配置）」与「显式清空（end/key/scene_sort 可空合法）」
            String start = body.containsKey("start") ? nz(str(body.get("start"))) : g(cur, "start");
            String end = body.containsKey("end") ? nz(str(body.get("end"))) : g(cur, "end");
            String key = body.containsKey("key") ? nz(str(body.get("key"))) : g(cur, "key");
            String scene = body.containsKey("scene_sort") ? nz(str(body.get("scene_sort"))) : null;
            com.textdiff.custom.CustomFormat.of(start, end, key,
                    scene == null || scene.isBlank() ? null : scene); // 校验（非法正则 400）
            jsonUpdates = new LinkedHashMap<>();
            jsonUpdates.put("start", start);
            jsonUpdates.put("end", end);
            jsonUpdates.put("key", key);
            if (body.containsKey("scene_sort")) {
                jsonUpdates.put("scene_sort", scene == null || scene.isBlank() ? null : scene);
            }
            matchNick = com.textdiff.controller.ApiViews.jsonNick(src);
        } else if ("textline".equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, String> cur = Json.read(src.configLine, Map.class);
            String kp = body.containsKey("key_positions") ? nz(str(body.get("key_positions"))) : g(cur, "keypos");
            String ea = body.containsKey("encoding_a") ? nz(str(body.get("encoding_a"))) : g(cur, "enca");
            String eb = body.containsKey("encoding_b") ? nz(str(body.get("encoding_b"))) : g(cur, "encb");
            com.textdiff.textline.TextLineFormat.of(kp, ea, eb); // 校验（非法区间 400）
            jsonUpdates = new LinkedHashMap<>();
            jsonUpdates.put("keypos", kp);
            jsonUpdates.put("enca", ea);
            jsonUpdates.put("encb", eb);
        } else {
            ApiPayloads.ComparePayload p;
            try {
                p = Json.read(Json.write(body), ApiPayloads.ComparePayload.class);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("重跑参数无效：" + e.getMessage());
            }
            newLegacyLine = ApiPayloads.buildLegacyLine(p);
            legacyNick = com.textdiff.engine.Rules
                    .parseLegacy(newLegacyLine, com.textdiff.task.JobManager.DEFAULT_DELIM,
                            com.textdiff.task.JobManager.DEFAULT_TRAILER).nickname;
        }

        List<String> rerun = new ArrayList<>(), skipped = new ArrayList<>();
        for (JobRecord j : batchJobs) {
            String jt = j.jobType == null ? "file" : j.jobType;
            boolean inScope;
            if ("custom".equals(type)) {
                inScope = "custom".equals(jt) && matchNick.equals(com.textdiff.controller.ApiViews.jsonNick(j));
            } else if ("textline".equals(type)) {
                inScope = "textline".equals(jt); // 普通文本对比：重跑批次下全部文本对比任务
            } else {
                inScope = ("file".equals(jt) || "ctis".equals(jt)) && legacyNick.equals(legacyNickOf(j));
            }
            if (!inScope) continue;
            if (JobRecord.RUNNING.equals(j.status)) {
                skipped.add(j.id);
                continue;
            }
            if (newLegacyLine != null) {
                j.configLine = newLegacyLine;
            } else {
                applyJsonUpdates(j, jsonUpdates);
            }
            j.status = JobRecord.PENDING;
            j.error = null;
            j.aiStatus = "none";
            j.clearStats();
            store.saveJob(j);
            ApiViews.deleteRecursively(Path.of(j.resultDir)); // 旧结果清空后重跑
            jobs.submit(j); // 新置 PENDING 的作业重新入池（含此前已在队列中的：submit 幂等）
            rerun.add(j.id);
        }
        if (rerun.isEmpty() && skipped.isEmpty()) {
            throw new IllegalArgumentException("批次内没有匹配的同昵称作业");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch_id", src.batchId);
        out.put("rerun", rerun);
        out.put("skipped_running", skipped);
        out.put("count", rerun.size());
        return out;
    }

    /** legacy configLine 的解析昵称（解析失败回退首冒号段原文）。 */
    private static String legacyNickOf(JobRecord j) {
        try {
            return com.textdiff.engine.Rules.parseLegacy(j.configLine,
                    com.textdiff.task.JobManager.DEFAULT_DELIM,
                    com.textdiff.task.JobManager.DEFAULT_TRAILER).nickname;
        } catch (RuntimeException e) {
            String line = j.configLine == null ? "" : j.configLine;
            return line.split(":", 2)[0].strip();
        }
    }

    /** 就地改写 JSON configLine 的键（值为 null 删除该键；非 JSON 行原样不动并抛参数错误）。 */
    @SuppressWarnings("unchecked")
    private static void applyJsonUpdates(JobRecord j, Map<String, String> updates) {
        Map<String, Object> m = Json.read(j.configLine, Map.class);
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (e.getValue() == null) m.remove(e.getKey());
            else m.put(e.getKey(), e.getValue());
        }
        j.configLine = Json.write(m);
    }

    private static String g(Map<String, String> m, String k) {
        return m == null ? "" : (m.get(k) == null ? "" : m.get(k));
    }

    /** null → ""（Map 值显式清空语义统一）。 */
    private static String nz(String v) {
        return v == null ? "" : v;
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
        taskStore.deleteForJobs(List.of(id)); // 级联清理任务管理中的跟踪记录
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
        List<String> deletedBatchJobIds = new ArrayList<>();
        for (String bid : batchIds) {
            var batch = store.getBatch(bid);
            if (batch == null) continue;
            if (batch.locked) {
                locked.add(bid);
                continue;
            }
            for (JobRecord j : store.listJobs(bid)) {
                ApiViews.deleteRecursively(Path.of(j.resultDir));
                deletedBatchJobIds.add(j.id);
            }
            store.deleteBatch(bid);
            if (jobs.extractService != null) jobs.extractService.cleanupBatch(bid); // 级联清理解压临时目录
            deletedBatches++;
        }
        if (!deletedBatchJobIds.isEmpty()) taskStore.deleteForJobs(deletedBatchJobIds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted_jobs", deletedJobs);
        out.put("deleted_batches", deletedBatches);
        out.put("locked", locked);
        return out;
    }

    /** 手动触发 AI 归纳分析，返回 {content}（Markdown 全文）。 */
    @PostMapping("/jobs/{id}/analyze")
    public Map<String, Object> analyze(@PathVariable String id) {
        JobRecord job = require(id);
        java.nio.file.Path dir = java.nio.file.Path.of(job.resultDir);
        java.nio.file.Path md = dir.resolve("ai_analysis.md");
        java.nio.file.Path legacy = dir.resolve("ai_analysis.json"); // 旧版本产物兜底
        if (!java.nio.file.Files.isRegularFile(md) && !java.nio.file.Files.isRegularFile(legacy)) {
            analyzer.analyze(id); // 同步执行，前端等待结果
            job = require(id);
        }
        try {
            if (java.nio.file.Files.isRegularFile(md)) {
                return Map.of("content",
                        java.nio.file.Files.readString(md, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (java.nio.file.Files.isRegularFile(legacy)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = com.textdiff.store.Json.MAPPER.readValue(legacy.toFile(), Map.class);
                return Map.of("content", String.valueOf(json.getOrDefault("content", "")));
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 未启用或分析未产出（aiStatus=" + job.aiStatus + "）");
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 分析结果读取失败");
        }
    }

    /** 提示词 MD 预览/下载。 */
    @GetMapping(value = "/jobs/{id}/prompt", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> prompt(@PathVariable String id) {
        JobRecord job = require(id);
        java.nio.file.Path file = java.nio.file.Path.of(job.resultDir).resolve("prompt.md");
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "prompt.md 尚未生成（作业完成后自动产出）");
        }
        try {
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''"
                            + java.net.URLEncoder.encode("prompt_" + id + ".md",
                                    java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                    .body(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "prompt.md 读取失败");
        }
    }

    @PostMapping("/jobs/{id}/reanalyze")
    public Map<String, Object> reanalyze(@PathVariable String id) {
        require(id);
        analyzer.reanalyze(id);
        return Map.of("ok", true);
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
