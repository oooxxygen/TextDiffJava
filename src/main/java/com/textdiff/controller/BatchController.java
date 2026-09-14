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

/** 批次端点：概览、标签、锁定、删除。导出端点在 M4 启用。 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {
    private final JobStore store;
    private final com.textdiff.store.TaskStore taskStore;

    public BatchController(JobStore store, com.textdiff.store.TaskStore taskStore) {
        this.store = store;
        this.taskStore = taskStore;
    }

    @GetMapping("/{id}")
    public Map<String, Object> overview(@PathVariable String id) {
        BatchRecord batch = store.getBatch(id);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        List<JobRecord> jobs = store.listJobs(id);
        Map<String, Object> resp = new LinkedHashMap<>(ApiViews.batchStats(batch, jobs));
        List<Map<String, Object>> children = new ArrayList<>();
        for (JobRecord j : jobs) children.add(ApiViews.jobBrief(j, store));
        resp.put("children", children);
        return resp;
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
        return Map.of("ok", true);
    }

    private BatchRecord require(String id) {
        BatchRecord batch = store.getBatch(id);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        return batch;
    }
}
