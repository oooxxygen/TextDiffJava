package com.textdiff.controller;

import com.textdiff.store.BatchRecord;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 批次端点：概览、标签、锁定、删除、增量条件补填/放弃（增量后置流程）。 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {
    private final JobStore store;
    private final com.textdiff.store.TaskStore taskStore;
    private final com.textdiff.task.ExtractService extractService;
    private final com.textdiff.task.JobManager jobs;
    private final com.textdiff.store.FieldMapStore fieldMaps;

    public BatchController(JobStore store, com.textdiff.store.TaskStore taskStore,
                           com.textdiff.task.ExtractService extractService,
                           com.textdiff.task.JobManager jobs,
                           com.textdiff.store.FieldMapStore fieldMaps) {
        this.store = store;
        this.taskStore = taskStore;
        this.extractService = extractService;
        this.jobs = jobs;
        this.fieldMaps = fieldMaps;
    }

    @GetMapping("/{id}")
    public Map<String, Object> overview(@PathVariable String id) {
        BatchRecord batch = store.getBatch(id);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        List<JobRecord> jobs = store.listJobs(id);
        Map<String, Object> resp = new LinkedHashMap<>(ApiViews.batchStats(batch, jobs));
        List<Map<String, Object>> children = new ArrayList<>();
        for (JobRecord j : jobs) children.add(ApiViews.jobBrief(j, store, true, fieldMaps::ownershipGroupOf));
        resp.put("children", children);
        resp.put("addmod_pending", ApiViews.addmodPending(jobs));
        return resp;
    }

    /**
     * 批次页补填增量条件（增量后置）：body {conds: {job_id: [条件值...]}}，仅受理本批次挂起作业；
     * 条件随作业落库后立即入队开跑。
     */
    @PostMapping("/{id}/incr-conds")
    public Map<String, Object> incrConds(@PathVariable String id, @RequestBody Map<String, Object> body) {
        require(id);
        Object raw = body.get("conds");
        Map<String, List<String>> conds = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                List<String> row = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) {
                    for (Object v : l) row.add(v == null ? "" : v.toString());
                }
                conds.put(e.getKey().toString(), row);
            }
        }
        return Map.of("ok", true, "applied", jobs.applyIncrConds(id, conds));
    }

    /** 放弃增量（Q12=C）：本批次全部挂起作业按普通对比立即开跑（不带条件）。 */
    @PostMapping("/{id}/incr-release")
    public Map<String, Object> incrRelease(@PathVariable String id) {
        require(id);
        return Map.of("ok", true, "released", jobs.releaseIncrHolds(id));
    }

    @PostMapping("/{id}/label")
    public Map<String, Object> label(@PathVariable String id, @RequestBody Map<String, Object> body) {
        BatchRecord batch = require(id);
        Object l = body.get("label");
        batch.label = l == null ? "" : l.toString();
        store.saveBatch(batch);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/lock")
    public Map<String, Object> lock(@PathVariable String id) {
        BatchRecord batch = require(id);
        batch.locked = true;
        store.saveBatch(batch);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/unlock")
    public Map<String, Object> unlock(@PathVariable String id) {
        BatchRecord batch = require(id);
        batch.locked = false;
        store.saveBatch(batch);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        BatchRecord batch = require(id);
        if (batch.locked) throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已锁定");
        List<String> jobIds = new ArrayList<>();
        for (JobRecord j : store.listJobs(id)) {
            ApiViews.deleteRecursively(java.nio.file.Path.of(j.resultDir));
            jobIds.add(j.id);
        }
        store.deleteBatch(id);
        taskStore.deleteForJobs(jobIds); // 级联清理任务管理中的跟踪记录
        extractService.cleanupBatch(id); // 级联清理解压临时目录残留
        return Map.of("ok", true);
    }

    private BatchRecord require(String id) {
        BatchRecord batch = store.getBatch(id);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        return batch;
    }
}
