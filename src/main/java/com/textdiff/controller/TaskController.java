package com.textdiff.controller;

import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.TaskRecord;
import com.textdiff.task.TaskManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务管理端点：默认生成任务（差异 CSV 导出 / AI 分析）的跟踪、重新生成、删除与生成路径设置。 */
@RestController
@RequestMapping("/api")
public class TaskController {
    private final JobStore store;
    private final TaskManager tasks;

    public TaskController(JobStore store, TaskManager tasks) {
        this.store = store;
        this.tasks = tasks;
    }

    /** 任务列表（可按批次/作业过滤），附带作业昵称与文件名供层级展示。 */
    @GetMapping("/tasks")
    public Map<String, Object> list(@RequestParam(required = false) String batch_id,
                                    @RequestParam(required = false) String job_id) {
        List<TaskRecord> src = batch_id != null ? tasks.listAll().stream()
                .filter(t -> batch_id.equals(t.batchId)).toList() : tasks.listAll();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskRecord t : src) {
            if (job_id != null && !job_id.equals(t.jobId)) continue;
            out.add(taskMap(t));
        }
        return Map.of("tasks", out);
    }

    @PostMapping("/tasks/{id}/regenerate")
    public Map<String, Object> regenerate(@PathVariable String id) {
        TaskRecord t = require(id);
        if (!tasks.regenerate(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务进行中，不可重复触发");
        }
        return Map.of("ok", true, "task_id", t.taskId, "status", TaskRecord.PENDING);
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        require(id);
        tasks.delete(id);
        return Map.of("ok", true);
    }

    /** 生成路径设置（配置管理界面）：空 = 默认 results/{batchId}/export；AI 分析结果随该目录。 */
    @GetMapping("/settings/tasks")
    public Map<String, Object> taskSettings() {
        TaskManager.TaskDirs d = tasks.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("export_dir", d.exportDir());
        return out;
    }

    @PostMapping("/settings/tasks")
    public Map<String, Object> saveTaskSettings(@RequestBody Map<String, Object> body) {
        String exportDir = str(body.get("export_dir"));
        tasks.saveSettings(new TaskManager.TaskDirs(exportDir));
        return Map.of("ok", true, "export_dir", exportDir);
    }

    private Map<String, Object> taskMap(TaskRecord t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", t.taskId);
        m.put("batch_id", t.batchId);
        m.put("job_id", t.jobId);
        m.put("task_type", t.taskType);
        m.put("status", t.status);
        m.put("trigger", t.trigger);
        m.put("output_path", t.outputPath);
        m.put("error", t.error);
        m.put("created_at", t.createdAt);
        m.put("started_at", t.startedAt);
        m.put("finished_at", t.finishedAt);
        JobRecord job = store.getJob(t.jobId);
        if (job != null) {
            m.put("nickname", job.nickname);
            m.put("file_a", job.fileA);
        }
        return m;
    }

    private TaskRecord require(String id) {
        TaskRecord t = tasks.listAll().stream()
                .filter(x -> x.taskId.equals(id)).findFirst().orElse(null);
        if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + id);
        return t;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }
}
