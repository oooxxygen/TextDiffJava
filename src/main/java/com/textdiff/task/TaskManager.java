package com.textdiff.task;

import com.textdiff.config.AppPaths;
import com.textdiff.export.ExportAssembler;
import com.textdiff.store.Json;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.ResultFiles;
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
 * 任务页可重新生成（trigger=manual）/批量重新生成（trigger=batch，可指定未来执行时间）/删除。
 * AI 调用并发受 aiConcurrency（config.ini [ai] max-concurrency）信号量限制，防止批量任务冲击服务方。
 * 导出路径可在设置页配置（store/task_settings.json，留空 = 默认 results/{batchId}/export）；
 * AI 分析结果（Markdown）与差异 CSV 同目录，文件名 [归属组]文件昵称_实际文件名.md，
 * 结果目录另存原件 ai_analysis.md 供结果页展示。
 */
public final class TaskManager implements AutoCloseable {
    /** 生成路径设置（blank = 使用默认导出目录；AI 分析结果随差异 CSV 同目录）。 */
    public record TaskDirs(String exportDir) {
        public static TaskDirs empty() {
            return new TaskDirs("");
        }
    }

    /** 批量重新生成的单条结果。 */
    public record BatchResult(String taskId, boolean ok, String reason) {}

    private final JobStore store;
    private final TaskStore tasks;
    private final com.textdiff.ai.AiAnalyzer ai; // 可空：无 AI 分析器时任务标记失败
    private final com.textdiff.store.FieldMapStore aiFieldMaps; // 可空：AI 产物归属组命名
    private final Path resultsRoot;
    private final Path settingsFile;
    private volatile TaskDirs dirs = TaskDirs.empty();
    private volatile int aiConcurrency;
    private volatile java.util.concurrent.Semaphore aiPermits;
    /** 已提交到执行池但尚未开始运行的任务（防止定时调度器重复入队）。 */
    private final java.util.Set<String> queued = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService pool;
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    public TaskManager(JobStore store, TaskStore tasks, com.textdiff.ai.AiAnalyzer ai,
                       com.textdiff.store.FieldMapStore aiFieldMaps,
                       AppPaths paths, Path resultsRoot) {
        this(store, tasks, ai, aiFieldMaps, paths, resultsRoot, 2);
    }

    public TaskManager(JobStore store, TaskStore tasks, com.textdiff.ai.AiAnalyzer ai,
                       com.textdiff.store.FieldMapStore aiFieldMaps,
                       AppPaths paths, Path resultsRoot, int aiConcurrency) {
        this.store = store;
        this.tasks = tasks;
        this.ai = ai;
        this.aiFieldMaps = aiFieldMaps;
        this.resultsRoot = resultsRoot;
        this.aiConcurrency = Math.max(1, aiConcurrency);
        this.aiPermits = new java.util.concurrent.Semaphore(this.aiConcurrency);
        this.pool = Executors.newFixedThreadPool(this.aiConcurrency, r -> {
            Thread t = new Thread(r, "task-runner");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "task-scheduler");
                    t.setDaemon(true);
                    return t;
                });
        this.settingsFile = paths.baseDir().resolve("store").resolve("task_settings.json");
        loadSettings();
        backfill();
        // 定时调度：周期扫描到点的 pending 任务（含重启恢复的未来定时任务）
        scheduler.scheduleWithFixedDelay(this::dispatchDue, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * AI 并发度热更新：信号量与执行池运行时重调（设置页保存即生效）。
     * 新信号量按旧信号量当前剩余许可初始化，已在执行中的任务不受影响。
     */
    public synchronized void resizeAiConcurrency(int n) {
        int next = Math.max(1, n);
        java.util.concurrent.Semaphore old = this.aiPermits;
        this.aiPermits = new java.util.concurrent.Semaphore(Math.max(0, old.availablePermits()));
        this.aiConcurrency = next;
        if (pool instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            tpe.setMaximumPoolSize(next);
            tpe.setCorePoolSize(next);
        }
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
            dirs = new TaskDirs(str(m.get("export_dir"))); // 旧文件中的 ai_dir 已废弃（AI 随导出目录）
        } catch (Exception e) {
            System.err.println("[task] 生成路径设置加载失败（使用默认）: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 启动重放：DONE 作业补登记缺失任务；中断残留的 running/pending 重新入队（未来定时任务留给调度器到点执行）。 */
    private void backfill() {
        long now = System.currentTimeMillis() / 1000;
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
                    if (t.scheduledAt > now) continue; // 未来定时：由调度器到点入队
                    enqueue(t);
                }
            }
        }
    }

    /** JobManager 完成回调：登记两条默认任务（差异 CSV 导出 + AI 分析）并执行。 */
    public void registerDefaults(JobRecord job) {
        if (!JobRecord.DONE.equals(job.status)) return;
        if (com.textdiff.report.ReportCompareService.JOB_TYPE.equals(job.jobType)) return; // 报表对比不衍生 AI/CSV 任务
        if (com.textdiff.custom.CustomCompareService.JOB_TYPE.equals(job.jobType)) return; // 自定义格式对比同上
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
        t.scheduledAt = 0;
        tasks.save(t);
        enqueue(t);
        return true;
    }

    /**
     * 任务管理页「批量重新生成」：对选中任务（可由作业 id 展开）置为 pending，可指定未来执行时间。
     * runAtEpochSec <= now 立即入队；未来时间由调度器到点执行（scheduledAt 持久化，重启恢复）。
     */
    public synchronized java.util.List<BatchResult> regenerateBatch(java.util.List<String> taskIds, long runAtEpochSec) {
        java.util.List<BatchResult> out = new java.util.ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        for (String id : taskIds) {
            TaskRecord t = tasks.get(id);
            if (t == null) {
                out.add(new BatchResult(id, false, "任务不存在"));
                continue;
            }
            if (TaskRecord.RUNNING.equals(t.status)) {
                out.add(new BatchResult(id, false, "任务进行中"));
                continue;
            }
            t.status = TaskRecord.PENDING;
            t.trigger = TaskRecord.TRIGGER_BATCH;
            t.error = "";
            t.startedAt = 0;
            t.finishedAt = 0;
            t.scheduledAt = Math.max(0, runAtEpochSec);
            tasks.save(t);
            if (t.scheduledAt <= now) enqueue(t);
            out.add(new BatchResult(id, true, t.scheduledAt > now ? "scheduled" : "queued"));
        }
        return out;
    }

    /** 调度器周期调用：把到点的未来定时任务（仍 pending 且未入队）加入执行池。 */
    synchronized void dispatchDue() {
        long now = System.currentTimeMillis() / 1000;
        for (TaskRecord t : tasks.listAll()) {
            if (t.scheduledAt > now) continue;
            synchronized (stateLock) { // 与 run() 的状态迁移互斥，避免对已开跑任务的重复入队
                if (!TaskRecord.PENDING.equals(t.status)) continue;
                if (!queued.add(t.taskId)) continue; // 已在执行池队列
            }
            pool.submit(() -> run(t.taskId));
        }
    }

    /** 任务管理页「删除」：仅移除跟踪记录，不动生成产物文件。 */
    public void delete(String taskId) {
        tasks.delete(taskId);
    }

    private void enqueue(TaskRecord t) {
        if (!queued.add(t.taskId)) return;
        pool.submit(() -> run(t.taskId));
    }

    /** PENDING → RUNNING 原子迁移：与 dispatchDue（同样持锁检查）互斥，防止定时器与入队双跑同一任务。 */
    private final Object stateLock = new Object();

    void run(String taskId) {
        queued.remove(taskId);
        final TaskRecord t;
        final JobRecord job;
        synchronized (stateLock) {
            t = tasks.get(taskId);
            if (t == null || !TaskRecord.PENDING.equals(t.status)) return;
            job = store.getJob(t.jobId);
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
        }
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

    /** 导出目录：配置优先，默认 results/{batchId}/export（差异 CSV 与 AI 分析 Markdown 共用）。 */
    private Path exportDir(JobRecord job) {
        String configured = dirs.exportDir();
        return configured == null || configured.isBlank()
                ? resultsRoot.resolve(job.batchId == null ? "standalone" : job.batchId).resolve("export")
                : Path.of(configured);
    }

    /** 差异 CSV 导出：写导出目录（默认 results/{batchId}/export），同时保留全量 CSV 以维持既有行为。 */
    private String runExport(JobRecord job) throws Exception {
        Path dir = exportDir(job);
        Files.createDirectories(dir);
        ExportAssembler.writeFullCsv(dir, job, ExportAssembler.DATA);
        Path diff = ExportAssembler.writeDiffCsv(dir, job, ExportAssembler.DATA);
        return diff.toAbsolutePath().toString();
    }

    /**
     * AI 分析（文本模板）：委托 AiAnalyzer 阻塞执行（prompt.md 恒产出；ai_analysis.md 为原件），
     * 成功后将 Markdown 复制到差异 CSV 同目录，命名 [归属组]文件昵称_实际文件名.md；
     * 并默认渲染一份自包含 HTML 报告（同目录同名 .html，报头含数据事实条与总体评判）。
     * 并发受 aiPermits 信号量限制（config.ini [ai] max-concurrency），防止批量任务冲击服务方。
     */
    private String runAi(JobRecord job) throws Exception {
        if (ai == null) throw new IllegalStateException("AI 分析器不可用");
        aiPermits.acquire();
        try {
            ai.analyze(job.id); // 阻塞执行；内部已落盘 prompt.md / ai_analysis.md 并更新 aiStatus
        } finally {
            aiPermits.release();
        }
        JobRecord fresh = store.getJob(job.id);
        String st = fresh == null ? "" : fresh.aiStatus;
        Path canonical = Path.of(job.resultDir);
        boolean ok = "done".equals(st);
        boolean disabled = "disabled".equals(st); // AI 未启用：模板已产出，视为完成
        if (!ok && !disabled) {
            throw new IllegalStateException("AI 分析失败: " + (fresh == null ? "?" : fresh.error));
        }
        if (!ok) return canonical.resolve("prompt.md").toAbsolutePath().toString();
        Path dir = exportDir(job);
        Files.createDirectories(dir);
        String base = com.textdiff.ai.AiReportNamer.fileName(job, aiFieldMaps);
        Files.copy(canonical.resolve("ai_analysis.md"), dir.resolve(base + ".md"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path html = writeHtmlReport(job, dir.resolve(base + ".html"));
        return html != null ? html.toAbsolutePath().toString()
                : dir.resolve(base + ".md").toAbsolutePath().toString();
    }

    /** AI 分析 HTML 报告渲染（ai_analysis.md + meta → 自包含 .html）；无 meta 时跳过返回 null。 */
    private Path writeHtmlReport(JobRecord job, Path dst) {
        try {
            JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir));
            String md = Files.readString(Path.of(job.resultDir).resolve("ai_analysis.md"),
                    StandardCharsets.UTF_8);
            String title = job.label != null && !job.label.isEmpty() ? job.label : job.nickname;
            Files.writeString(dst, com.textdiff.export.HtmlReportWriter.render(title, meta, md),
                    StandardCharsets.UTF_8);
            return dst;
        } catch (Exception e) {
            System.err.println("[task] AI 分析 HTML 报告渲染失败（不影响 Markdown 产物）: " + e.getMessage());
            return null;
        }
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
        scheduler.shutdownNow();
        pool.shutdownNow();
    }

    private static String newId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
