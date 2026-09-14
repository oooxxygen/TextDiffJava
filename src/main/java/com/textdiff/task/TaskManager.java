package com.textdiff.task;

import com.textdiff.config.AppPaths;
import com.textdiff.export.ExportAssembler;
import com.textdiff.store.Json;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.TaskRecord;
import com.textdiff.store.TaskStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 生成任务管理器（任务管理页后端）：跟踪作业完成后衍生的默认行为——差异 CSV 导出、文本模板 AI 分析。
 *
 * 作业 done 后经 JobManager.doneHook 注册两条任务（trigger=auto）并异步执行；
 * 任务页可重新生成（trigger=manual）/删除。生成路径可在设置页配置（store/task_settings.json，
 * 留空 = 默认：导出 → results/{batchId}/export，AI → 作业结果目录）。
 */
public final class TaskManager implements AutoCloseable {
    /** 生成路径设置（blank = 使用默认）。 */
    public record TaskDirs(String exportDir, String aiDir) {
        public static TaskDirs empty() {
            return new TaskDirs("", "");
        }
    }

    private final JobStore store;
    private final TaskStore tasks;
    private final com.textdiff.ai.AiAnalyzer ai; // 可空：无 AI 分析器时任务标记失败
    private final Path resultsRoot;
    private final Path settingsFile;
    private volatile TaskDirs dirs = TaskDirs.empty();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "task-runner");
        t.setDaemon(true);
        return t;
    });

    public TaskManager(JobStore store, TaskStore tasks, com.textdiff.ai.AiAnalyzer ai,
                       AppPaths paths, Path resultsRoot) {
        this.store = store;
        this.tasks = tasks;
        this.ai = ai;
        this.resultsRoot = resultsRoot;
        this.settingsFile = paths.baseDir().resolve("store").resolve("task_settings.json");
        loadSettings();
        backfill();
    }

    /** 全部任务记录（任务管理页列表）。 */
    public java.util.List<TaskRecord> listAll() {
        return tasks.listAll();
    }

    /** 设置页保存的路径即时生效（无需重启）。 */
    public synchronized void saveSettings(TaskDirs d) {
        this.dirs = d == null ? TaskDirs.empty() : d;
        try {
            Files.createDirectories(settingsFile.getParent());
            Map<String, String> out = new LinkedHashMap<>();
            out.put("export_dir", dirs.exportDir() == null ? "" : dirs.exportDir());
            out.put("ai_dir", dirs.aiDir() == null ? "" : dirs.aiDir());
            Files.writeString(settingsFile, Json.write(out), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[task] 生成路径设置落盘失败: " + e.getMessage());
        }
    }

    public TaskDirs settings() {
        return dirs;
    }

    private synchronized void loadSettings() {
        try {
            if (!Files.isRegularFile(settingsFile)) return;
            Map<String, Object> m = Json.read(Files.readString(settingsFile, StandardCharsets.UTF_8), Map.class);
            dirs = new TaskDirs(str(m.get("export_dir")), str(m.get("ai_dir")));
        } catch (Exception e) {
            System.err.println("[task] 生成路径设置加载失败（使用默认）: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 启动重放：DONE 作业补登记缺失任务；中断残留的 running/pending 重新入队（重启恢复）。 */
    private void backfill() {
        for (JobRecord job : store.listJobs(null)) {
            if (!JobRecord.DONE.equals(job.status)) continue;
            var existing = tasks.listForJob(job.id);
            if (existing.isEmpty()) {
                createDefaults(job);
                continue;
            }
            for (TaskRecord t : existing) {
                if (TaskRecord.RUNNING.equals(t.status) || TaskRecord.PENDING.equals(t.status)) {
                    t.status = TaskRecord.PENDING;
                    tasks.save(t);
                    enqueue(t);
                }
            }
        }
    }

    /** JobManager 完成回调：登记两条默认任务（差异 CSV 导出 + AI 分析）并执行。 */
    public void registerDefaults(JobRecord job) {
        if (!JobRecord.DONE.equals(job.status)) return;
        if (!tasks.listForJob(job.id).isEmpty()) return; // 重新触发时避免重复登记
        for (TaskRecord t : createDefaults(job)) enqueue(t);
    }

    private java.util.List<TaskRecord> createDefaults(JobRecord job) {
        java.util.List<TaskRecord> out = new java.util.ArrayList<>();
        for (String type : new String[]{TaskRecord.EXPORT_DIFF, TaskRecord.AI_ANALYSIS}) {
            TaskRecord t = new TaskRecord(newId(), job.batchId, job.id, type);
            tasks.save(t);
            out.add(t);
        }
        return out;
    }

    /** 任务管理页「重新生成」：pending → 重新入队（进行中的任务拒绝重复触发）。 */
    public synchronized boolean regenerate(String taskId) {
        TaskRecord t = tasks.get(taskId);
        if (t == null || TaskRecord.RUNNING.equals(t.status)) return false;
        t.status = TaskRecord.PENDING;
        t.trigger = TaskRecord.TRIGGER_MANUAL;
        t.error = "";
        t.startedAt = 0;
        t.finishedAt = 0;
        tasks.save(t);
        enqueue(t);
        return true;
    }

    /** 任务管理页「删除」：仅移除跟踪记录，不动生成产物文件。 */
    public void delete(String taskId) {
        tasks.delete(taskId);
    }

    private void enqueue(TaskRecord t) {
        pool.submit(() -> run(t.taskId));
    }

    void run(String taskId) {
        TaskRecord t = tasks.get(taskId);
        if (t == null || !TaskRecord.PENDING.equals(t.status)) return;
        JobRecord job = store.getJob(t.jobId);
        if (job == null) {
            t.status = TaskRecord.FAILED; // 作业已被删除：明确失败而非永久待开始
            t.error = "作业不存在（可能已删除）";
            t.finishedAt = System.currentTimeMillis() / 1000;
            tasks.save(t);
            return;
        }
        t.status = TaskRecord.RUNNING;
        t.startedAt = System.currentTimeMillis() / 1000;
        t.error = "";
        tasks.save(t);
        try {
            String output = TaskRecord.EXPORT_DIFF.equals(t.taskType) ? runExport(job) : runAi(job);
            t.outputPath = output;
            t.status = TaskRecord.DONE;
        } catch (Exception e) {
            t.status = TaskRecord.FAILED;
            t.error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        t.finishedAt = System.currentTimeMillis() / 1000;
        tasks.save(t);
    }

    /** 差异 CSV 导出：写配置目录（默认 results/{batchId}/export），同时保留全量 CSV 以维持既有行为。 */
    private String runExport(JobRecord job) throws Exception {
        String configured = dirs.exportDir();
        Path dir = configured == null || configured.isBlank()
                ? resultsRoot.resolve(job.batchId == null ? "standalone" : job.batchId).resolve("export")
                : Path.of(configured);
        Files.createDirectories(dir);
        ExportAssembler.writeFullCsv(dir, job, ExportAssembler.DATA);
        Path diff = ExportAssembler.writeDiffCsv(dir, job, ExportAssembler.DATA);
        return diff.toAbsolutePath().toString();
    }

    /** AI 分析（文本模板）：委托 AiAnalyzer（prompt.md 恒产出；产物 ai_analysis.json）。 */
    private String runAi(JobRecord job) throws Exception {
        if (ai == null) throw new IllegalStateException("AI 分析器不可用");
        ai.analyze(job.id); // 阻塞执行；内部已落盘 prompt.md / ai_analysis.json 并更新 aiStatus
        JobRecord fresh = store.getJob(job.id);
        String st = fresh == null ? "" : fresh.aiStatus;
        Path canonical = Path.of(job.resultDir);
        Path analysis = canonical.resolve("ai_analysis.json");
        boolean ok = "done".equals(st);
        boolean disabled = "disabled".equals(st); // AI 未启用：模板已产出，视为完成
        if (!ok && !disabled) {
            throw new IllegalStateException("AI 分析失败: " + (fresh == null ? "?" : fresh.error));
        }
        // 配置了生成目录时：产物复制到该目录（结果页读取的原件仍在 resultDir，UI 契约不变）
        String configured = dirs.aiDir();
        if (configured != null && !configured.isBlank()) {
            Path target = Path.of(configured);
            Files.createDirectories(target);
            Path dst = target.resolve(job.id + "_ai_analysis.json");
            Files.copy(analysis, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Path prompt = canonical.resolve("prompt.md");
            if (Files.isRegularFile(prompt)) {
                Files.copy(prompt, target.resolve(job.id + "_prompt.md"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return dst.toAbsolutePath().toString();
        }
        return (ok ? analysis : canonical.resolve("prompt.md")).toAbsolutePath().toString();
    }

    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            boolean busy = tasks.listAll().stream().anyMatch(t ->
                    TaskRecord.PENDING.equals(t.status) || TaskRecord.RUNNING.equals(t.status));
            if (!busy) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private static String newId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
