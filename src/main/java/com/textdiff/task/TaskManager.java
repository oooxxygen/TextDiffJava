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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Path aiTemplateDir; // configs/analysis-template.md 覆盖目录（导出补产 prompt.md 用）
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
        this.aiTemplateDir = paths.baseDir().resolve("configs");
        loadSettings();
        backfill();
        // 定时调度：周期扫描到点的 pending 任务（含重启恢复的未来定时任务）
        scheduler.scheduleWithFixedDelay(this::dispatchDue, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * AI 并发度热更新：信号量与执行池运行时重调（设置页保存即生效）。
     * 新信号量按旧信号量当前剩余许可初始化，已在执行中的任务不受影响。缩容先降 core，扩容先升 max。
     */
    public synchronized void resizeAiConcurrency(int n) {
        int next = Math.max(1, n);
        java.util.concurrent.Semaphore old = this.aiPermits;
        this.aiPermits = new java.util.concurrent.Semaphore(Math.max(0, old.availablePermits()));
        this.aiConcurrency = next;
        if (pool instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            if (next < tpe.getCorePoolSize()) {
                tpe.setCorePoolSize(next);
                tpe.setMaximumPoolSize(next);
            } else {
                tpe.setMaximumPoolSize(next);
                tpe.setCorePoolSize(next);
            }
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
            if (TaskRecord.EXPORT_DIFF.equals(t.taskType)) {
                List<Path> parts = runExport(job);
                t.outputs = parts.stream().map(p -> p.toAbsolutePath().toString())
                        .collect(java.util.stream.Collectors.toList());
                t.outputPath = parts.isEmpty() ? "" : parts.get(parts.size() - 1).toAbsolutePath().toString();
            } else {
                t.outputPath = runAi(job);
            }
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

    /** 差异 CSV 导出：写导出目录（默认 results/{batchId}/export），同时保留全量 CSV 以维持既有行为；超 2 万条各自分片。
     *  差异采集表（21 列）：存在差异数据行才生成（无差异不产空表）；数据行超 5 万分片直接落盘本目录。 */
    private List<Path> runExport(JobRecord job) throws Exception {
        Path dir = exportDir(job);
        Files.createDirectories(dir);
        List<Path> out = new java.util.ArrayList<>(ExportAssembler.writeFullCsv(dir, job, ExportAssembler.DATA));
        out.addAll(ExportAssembler.writeDiffCsv(dir, job, ExportAssembler.DATA));
        long caseRows = (long) job.diffCount + job.onlyACount + job.onlyBCount;
        if (caseRows <= 0 && job.diffCount < 0) { // 旧记录无完成快照 → 回退 meta.json
            JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir == null ? "" : job.resultDir));
            caseRows = meta == null ? 0 : (long) meta.diff + meta.onlyA + meta.onlyB;
        }
        if (caseRows > 0) {
            var batch = job.batchId == null || job.batchId.isBlank() ? null : store.getBatch(job.batchId);
            var t = aiFieldMaps == null ? null
                    : aiFieldMaps.typeByReport(com.textdiff.export.DiscardColsService.monitorKey(job));
            String parmType = t == null || t.parmReportType() == null ? "" : t.parmReportType();
            String team = t == null || t.ownershipGroup() == null ? "" : t.ownershipGroup();
            out.addAll(com.textdiff.export.CollectCaseExcel.writeSharded(dir,
                    com.textdiff.export.CollectCaseExcel.collectBase(job), job, batch, parmType, team));
        }
        return out;
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
            // 需求①别名：报头/事实条用作业 configLine 的分区别名（SRCA/SRCB，缺省 A/B）
            var cfg = com.textdiff.export.ExportAssembler.compareConfig(job);
            Files.writeString(dst, com.textdiff.export.HtmlReportWriter.render(title, meta, md,
                            cfg.sourceA, cfg.sourceB),
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

    // ---- AI 待执行任务导出/导入（跨环境补跑）：无 AI 环境导出 prompt → 外部大模型执行 → 导入结果回填 ----

    /** AI 待执行判定：作业已完成且 aiStatus 未完成（pending/failed/disabled 等；running/done 不导出）。 */
    public boolean aiPending(JobRecord job) {
        return job != null && JobRecord.DONE.equals(job.status)
                && !"done".equals(job.aiStatus) && !"running".equals(job.aiStatus);
    }

    private static String zipSafe(String s) {
        String v = s == null || s.strip().isEmpty() ? "job" : s.strip();
        return v.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** prompt.md 读取；缺失时按完整预算现产（导出物自足，可直接投喂任何大模型）。 */
    private byte[] ensurePrompt(JobRecord job) throws Exception {
        Path dir = Path.of(job.resultDir == null ? "" : job.resultDir);
        Path p = dir.resolve("prompt.md");
        if (Files.isRegularFile(p)) return Files.readAllBytes(p);
        Path written = com.textdiff.ai.PromptRenderer.render(dir, job,
                ResultFiles.readMeta(dir), ResultFiles.readSummary(dir), aiTemplateDir, aiFieldMaps);
        return Files.readAllBytes(written);
    }

    private static void putZipEntry(java.util.zip.ZipOutputStream zos, String name, byte[] content)
            throws java.io.IOException {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        zos.write(content);
        zos.closeEntry();
    }

    /**
     * 导出待执行 AI 任务 zip：每个任务一个子目录 {@code {jobId}_{净化标签或昵称}/}（prompt.md + task.json）。
     * jobIds 非空 = 仅导出选中作业（任务页勾选限定），空集 = 全部待执行任务。
     * 无任何待执行任务返回 null（调用方 404）。
     */
    public Path exportPendingAi(Path zipFile, java.util.Collection<String> jobIds) throws Exception {
        java.util.Set<String> wanted = jobIds == null ? java.util.Set.of() : new java.util.HashSet<>(jobIds);
        Map<String, TaskRecord> aiTasks = new LinkedHashMap<>();
        for (TaskRecord t : tasks.listAll()) {
            if (!TaskRecord.AI_ANALYSIS.equals(t.taskType)) continue;
            if (!wanted.isEmpty() && !wanted.contains(t.jobId)) continue;
            aiTasks.putIfAbsent(t.jobId, t);
        }
        int exported = 0;
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, TaskRecord> e : aiTasks.entrySet()) {
                JobRecord job = store.getJob(e.getKey());
                if (!aiPending(job)) continue;
                String sub = e.getKey() + "_" + zipSafe(
                        job.label != null && !job.label.isEmpty() ? job.label : job.nickname);
                byte[] prompt;
                try {
                    prompt = ensurePrompt(job);
                } catch (RuntimeException | java.io.IOException ex) {
                    // 历史作业结果目录缺失/损坏：跳过该作业，不拖垮整个导出
                    System.getLogger(TaskManager.class.getName())
                            .log(System.Logger.Level.WARNING, "AI 导出跳过作业 " + e.getKey() + ": " + ex);
                    continue;
                }
                putZipEntry(zos, sub + "/prompt.md", prompt);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("task_id", e.getValue().taskId);
                meta.put("job_id", job.id);
                meta.put("nickname", job.nickname);
                meta.put("label", job.label);
                meta.put("file_a", job.fileA);
                meta.put("file_b", job.fileB);
                meta.put("ai_status", job.aiStatus);
                meta.put("exported_at", System.currentTimeMillis() / 1000);
                putZipEntry(zos, sub + "/task.json", Json.write(meta).getBytes(StandardCharsets.UTF_8));
                exported++;
            }
        }
        if (exported == 0) {
            Files.deleteIfExists(zipFile);
            return null;
        }
        return zipFile;
    }

    /** 导入单文件结果（逐文件反馈给任务页）。 */
    public record AiImportResult(String file, String jobId, boolean ok, String reason) {}

    /** 待导入文件：name 含扩展名；jobIdHint 来自 zip 内同目录 task.json（可空，文件名兜底识别）。 */
    public record AiImportFile(String name, byte[] content, String jobIdHint) {}

    /** zip 展开为待导入清单：收集 .md 内容（跳过 prompt.md——那是待执行输入不是结果），同目录 task.json 的 job_id 作匹配提示。 */
    public static List<AiImportFile> expandAiZip(String zipName, byte[] content) throws java.io.IOException {
        Map<String, String> jobByDir = new LinkedHashMap<>();
        Map<String, byte[]> mds = new LinkedHashMap<>();
        try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                byte[] data = zis.readAllBytes();
                if (n.endsWith("task.json")) {
                    try {
                        Map<?, ?> m = Json.read(new String(data, StandardCharsets.UTF_8), Map.class);
                        String jid = m == null ? null : (String) m.get("job_id");
                        if (jid != null && !jid.isBlank()) {
                            String dirName = n.contains("/") ? n.substring(0, n.lastIndexOf('/')) : "";
                            jobByDir.put(dirName, jid.strip());
                        }
                    } catch (RuntimeException ignored) {
                        // 损坏的 task.json 忽略，走文件名兜底
                    }
                } else if (n.toLowerCase().endsWith(".md")
                        && !"prompt.md".equalsIgnoreCase(Path.of(n).getFileName().toString())) {
                    mds.put(n, data);
                }
            }
        }
        List<AiImportFile> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : mds.entrySet()) {
            String n = e.getKey();
            String dirName = n.contains("/") ? n.substring(0, n.lastIndexOf('/')) : "";
            out.add(new AiImportFile(n, e.getValue(), jobByDir.get(dirName)));
        }
        return out;
    }

    /**
     * 导入 AI 分析结果：按 task.json 提示或文件名中的 8 位作业 id 匹配作业 →
     * 原样写 {@code results/{jobId}/ai_analysis.md}（缺一级标题自动补作业命名标题）→
     * 导出目录按「[归属组]文件昵称_实际文件名」命名生成 .md/.html（与原生 AI 产物同位同名）→
     * 作业 aiStatus=done、对应 AI 任务记录置 DONE。重复导入覆盖重渲染。逐文件返回结果。
     */
    public List<AiImportResult> importAiResults(List<AiImportFile> files) {
        List<AiImportResult> out = new ArrayList<>();
        java.util.regex.Pattern HEX8 = java.util.regex.Pattern.compile("[0-9a-f]{8}");
        for (AiImportFile f : files) {
            String jobId = f.jobIdHint();
            if (jobId == null || store.getJob(jobId) == null) {
                jobId = null;
                java.util.regex.Matcher m = HEX8.matcher(f.name() == null ? "" : f.name().toLowerCase());
                while (m.find()) {
                    String cand = m.group();
                    if (store.getJob(cand) != null) { jobId = cand; break; }
                }
            }
            if (jobId == null) {
                out.add(new AiImportResult(f.name(), null, false,
                        "无法识别作业（文件名需含 8 位作业 id，或 zip 内同目录附 task.json）"));
                continue;
            }
            try {
                applyAiResult(jobId, f);
                out.add(new AiImportResult(f.name(), jobId, true, "已导入"));
            } catch (Exception e) {
                out.add(new AiImportResult(f.name(), jobId, false,
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return out;
    }

    /** 单作业回填：写 ai_analysis.md + 导出目录 .md/.html + 作业/任务状态置完成。 */
    private void applyAiResult(String jobId, AiImportFile f) throws Exception {
        JobRecord job = store.getJob(jobId);
        if (job == null) throw new IllegalStateException("作业不存在");
        if (!JobRecord.DONE.equals(job.status)) {
            throw new IllegalStateException("作业未完成（status=" + job.status + "），仅已完成作业可导入 AI 结果");
        }
        if (com.textdiff.report.ReportCompareService.JOB_TYPE.equals(job.jobType)
                || com.textdiff.custom.CustomCompareService.JOB_TYPE.equals(job.jobType)) {
            throw new IllegalStateException("该作业类型（报表/自定义对比）不衍生 AI 分析任务");
        }
        Path dir = Path.of(job.resultDir == null ? "" : job.resultDir);
        Files.createDirectories(dir);
        String md = new String(f.content(), StandardCharsets.UTF_8);
        if (md.lines().noneMatch(l -> l.strip().startsWith("#"))) {
            md = "# " + com.textdiff.ai.AiReportNamer.title(job, aiFieldMaps) + "\n\n" + md;
        }
        Files.writeString(dir.resolve("ai_analysis.md"), md, StandardCharsets.UTF_8);
        Path exportTo = exportDir(job);
        Files.createDirectories(exportTo);
        String base = com.textdiff.ai.AiReportNamer.fileName(job, aiFieldMaps);
        Path mdOut = exportTo.resolve(base + ".md");
        Files.writeString(mdOut, md, StandardCharsets.UTF_8);
        Path htmlOut = writeHtmlReport(job, exportTo.resolve(base + ".html")); // 原样导入也走本地渲染（失败仅少 HTML）
        job.aiStatus = "done";
        if (job.error != null && job.error.startsWith("AI 分析失败")) job.error = null;
        store.saveJob(job);
        TaskRecord t = tasks.listForJob(jobId).stream()
                .filter(x -> TaskRecord.AI_ANALYSIS.equals(x.taskType))
                .reduce((a, b) -> a.createdAt >= b.createdAt ? a : b).orElse(null);
        if (t != null) {
            long now = System.currentTimeMillis() / 1000;
            t.status = TaskRecord.DONE;
            t.error = "";
            if (t.startedAt <= 0) t.startedAt = now;
            t.finishedAt = now;
            List<String> outs = new ArrayList<>();
            outs.add(mdOut.toAbsolutePath().toString());
            if (htmlOut != null) outs.add(htmlOut.toAbsolutePath().toString());
            t.outputs = outs;
            t.outputPath = htmlOut != null ? outs.get(1) : outs.get(0);
            tasks.save(t);
        }
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
