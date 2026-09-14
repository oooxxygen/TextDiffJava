package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.engine.Comparator;
import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.CompareOutcome;
import com.textdiff.engine.Rules;
import com.textdiff.engine.Summary;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.CommandRecord;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.Json;
import com.textdiff.store.ResultFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务管理器：批次=一次提交（目录对 + 多行配置），作业=单文件对。
 * 状态机 pending → running → done / failed / stopped；支持终止（尽力中断）与重试。
 * 启动时把中断残留的 running/pending 作业重新入队（重启恢复）。
 * 另起后台轮询消费 H2 命令信箱（外部运行期经 SQL 指挥：retry/cancel/create_batch）。
 */
public final class JobManager implements AutoCloseable {
    public static final String DEFAULT_DELIM = " | ";
    public static final String DEFAULT_TRAILER = "|||||";

    private static final long COMMAND_POLL_SECONDS = 2;

    private final JobStore store;
    private final Path resultsRoot;
    private final long maxInMemoryBytes;
    private final ExecutorService pool;
    private final ScheduledExecutorService commandPoller;
    private final ConcurrentMap<String, Future<?>> tasks = new ConcurrentHashMap<>();
    /** 字段名映射（可空：测试/未导入场景）。作业运行时按文件名自动填充列名。 */
    private final com.textdiff.store.FieldMapStore fieldMaps;
    /** 作业完成回调（任务管理器挂载点：登记差异 CSV 导出 + AI 分析两条默认生成任务）。 */
    public volatile java.util.function.Consumer<JobRecord> doneHook;

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine) {
        this(store, resultsRoot, engine, null);
    }

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine,
                      com.textdiff.store.FieldMapStore fieldMaps) {
        this.store = store;
        this.resultsRoot = resultsRoot;
        this.maxInMemoryBytes = engine.maxInMemoryBytes();
        this.fieldMaps = fieldMaps;
        this.pool = Executors.newFixedThreadPool(engine.maxThreads());
        this.commandPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "command-poller");
            t.setDaemon(true);
            return t;
        });
        this.commandPoller.scheduleWithFixedDelay(this::drainCommands,
                COMMAND_POLL_SECONDS, COMMAND_POLL_SECONDS, TimeUnit.SECONDS);
        requeueStuckJobs();
    }

    /** 目录模式批次：每行配置一条规则，glob 配对 dirA/dirB 同名文件 → 多作业。返回批次。 */
    public BatchRecord createBatch(Path dirA, Path dirB, List<String> configLines) throws IOException {
        record Planned(JobRecord job) {}

        String batchId = newId();
        BatchRecord batch = new BatchRecord(batchId,
                dirA.toAbsolutePath().normalize() + " vs " + dirB.toAbsolutePath().normalize(),
                dirA.toAbsolutePath().normalize().toString(),
                dirB.toAbsolutePath().normalize().toString(),
                configLines.size() + " 行配置", 0);

        List<JobRecord> jobs = new ArrayList<>();
        java.util.Set<String> matched = new java.util.HashSet<>();
        for (String line : configLines) {
            if (line.strip().isEmpty() || line.strip().startsWith("#")) continue;
            CompareConfig cfg = Rules.parseLegacy(line, DEFAULT_DELIM, DEFAULT_TRAILER);
            String configLine = Rules.toLegacyLine(cfg, true); // 规范化全量行：结果页展示与重跑依据
            for (String[] pair : Rules.pairFiles(dirA, dirB, cfg.fileGlob)) {
                String fileName = Path.of(pair[0]).getFileName().toString();
                matched.add(fileName);
                String jobId = newId();
                JobRecord job = new JobRecord(jobId, batchId,
                        cfg.nickname + " · " + fileName, configLine, pair[0], pair[1],
                        resultsRoot.resolve(jobId).toAbsolutePath().toString());
                jobs.add(job);
            }
        }
        batch.noRuleFiles = unmatchedFiles(dirA, matched);
        batch.jobCount = jobs.size();
        store.saveBatch(batch);
        for (JobRecord job : jobs) {
            store.saveJob(job);
            submit(job);
        }
        return batch;
    }

    /** 上传模式批次：调用方已确定文件对（M3 multipart 落盘后调用）。 */
    public BatchRecord createBatchFromPairs(String label, List<String[]> pairs) throws IOException {
        String batchId = newId();
        BatchRecord batch = new BatchRecord(batchId, label, null, null, "upload", pairs.size());
        store.saveBatch(batch);
        for (String[] p : pairs) {
            // p = {configLine, fileA, fileB, nickname}
            String jobId = newId();
            JobRecord job = new JobRecord(jobId, batchId, p[3], p[0], p[1], p[2],
                    resultsRoot.resolve(jobId).toAbsolutePath().toString());
            store.saveJob(job);
            submit(job);
        }
        return batch;
    }

    public synchronized void submit(JobRecord job) {
        if (tasks.containsKey(job.id)) return;
        tasks.put(job.id, pool.submit(() -> runJob(job.id)));
    }

    void runJob(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null) return;
        if (!JobRecord.PENDING.equals(job.status)) return; // 已停止/完成/被取消
        job.status = JobRecord.RUNNING;
        job.startedAt = System.currentTimeMillis() / 1000;
        job.error = null;
        store.saveJob(job);

        Path dir = Path.of(job.resultDir);
        try {
            CompareConfig cfg = Rules.parseLegacy(job.configLine, DEFAULT_DELIM, DEFAULT_TRAILER);
            // 字段名映射注入：配置未显式携带 COLS 时按文件名查源系统字段配置（需求：列名显示在对比界面与导出）
            if (cfg.columnNames.isEmpty() && fieldMaps != null) {
                String reportId = fieldMaps.reportIdForFile(job.fileA);
                if (reportId != null) {
                    List<String> names = fieldMaps.fieldNamesFor(reportId);
                    if (!names.isEmpty()) {
                        cfg.columnNames = names;
                        job.configLine = Rules.toLegacyLine(cfg, true); // 持久化列名：meta/导出/prompt 全链路可见
                    }
                }
            }
            ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(dir.resolve(ResultFiles.RESULT_JSONL));
            try (sink) {
                CompareOutcome outcome = Comparator.compareFiles(Path.of(job.fileA), Path.of(job.fileB),
                        cfg, sink, maxInMemoryBytes, dir.resolve("tmp"));
                Summary s = outcome.summary();
                job.keyWarning = s.keyDupA > 0 || s.keyDupB > 0; // 需求：主键配置错误醒目提示
                ResultFiles.writeSummary(dir, s);
                job.aiStatus = "pending"; // M5 AI 分析器消费 pending 状态
                JobMeta meta = ResultFiles.buildMeta(job,
                        outcome.detectedEncodingA(), outcome.detectedEncodingB(), s);
                meta.zoneEqual = sink.zoneEqual;
                meta.zoneDiff = sink.zoneDiff;
                meta.zoneUnmatched = sink.zoneUnmatched;
                meta.zoneTrailer = sink.zoneTrailer;
                ResultFiles.writeMeta(dir, meta);
            }
            job.status = JobRecord.DONE;
            job.finishedAt = System.currentTimeMillis() / 1000;
        } catch (Exception e) {
            Future<?> f = tasks.get(jobId);
            if (f != null && f.isCancelled()) {
                job.status = JobRecord.STOPPED; // 中断导致的异常归为用户终止
            } else {
                job.status = JobRecord.FAILED;
                job.error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            job.finishedAt = System.currentTimeMillis() / 1000;
        }
        store.saveJob(job);
        java.util.function.Consumer<JobRecord> hook = doneHook;
        if (hook != null && JobRecord.DONE.equals(job.status)) hook.accept(job); // 状态落盘后登记生成任务
        tasks.remove(jobId);
    }

    /** 终止作业：running 尽力中断，pending 直接置 stopped。返回是否产生了状态变更。 */
    public boolean cancel(String jobId) {
        Future<?> f = tasks.get(jobId);
        if (f != null) f.cancel(true);
        JobRecord job = store.getJob(jobId);
        if (job == null) return false;
        if (JobRecord.PENDING.equals(job.status) || JobRecord.RUNNING.equals(job.status)) {
            job.status = JobRecord.STOPPED;
            job.finishedAt = System.currentTimeMillis() / 1000;
            store.saveJob(job);
            return true;
        }
        return false;
    }

    /** 重试：failed/stopped 作业重置为 pending 重新入队。 */
    public void retry(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null) return;
        if (!JobRecord.FAILED.equals(job.status) && !JobRecord.STOPPED.equals(job.status)) return;
        job.status = JobRecord.PENDING;
        job.error = null;
        store.saveJob(job);
        submit(job);
    }

    public JobRecord getJob(String jobId) {
        return store.getJob(jobId);
    }

    public List<JobRecord> listJobs(String batchId) {
        return store.listJobs(batchId);
    }

    public BatchRecord getBatch(String batchId) {
        return store.getBatch(batchId);
    }

    public JobMeta jobMeta(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null) return null;
        JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir));
        if (meta != null) {
            meta.status = job.status; // 状态以任务层为准（meta 落盘后作业可能被重试）
            meta.aiStatus = job.aiStatus;
        }
        return meta;
    }

    private void requeueStuckJobs() {
        for (JobRecord job : store.listJobs(null)) {
            if (JobRecord.RUNNING.equals(job.status) || JobRecord.PENDING.equals(job.status)) {
                job.status = JobRecord.PENDING;
                store.saveJob(job);
                submit(job);
            }
        }
    }

    // ---- H2 命令信箱消费：外部（IDE/SQL）运行期插入 commands 行，此处轮询执行并回写结果 ----

    private void drainCommands() {
        try {
            if (!(store instanceof DualJobStore dual) || !dual.dbAvailable()) return;
            for (CommandRecord c : dual.pollPendingCommands()) {
                String result;
                boolean ok = true;
                try {
                    result = executeCommand(c);
                } catch (Exception e) {
                    ok = false;
                    result = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                try {
                    dual.completeCommand(c.id, ok, result);
                } catch (RuntimeException ignored) {
                    // 回写失败不终止调度；命令留在 running，重启时重放
                }
            }
        } catch (RuntimeException e) {
            System.err.println("[command] 轮询失败（下轮重试）: " + e.getMessage());
        }
    }

    /** 执行一条命令，返回写入命令表的结果描述。 */
    private String executeCommand(CommandRecord c) throws IOException {
        CommandRecord.Payload p = c.payload == null || c.payload.isBlank()
                ? new CommandRecord.Payload() : Json.read(c.payload, CommandRecord.Payload.class);
        return switch (c.type == null ? "" : c.type) {
            case "retry" -> {
                requireJob(p.jobId);
                if (!JobRecord.FAILED.equals(store.getJob(p.jobId).status)
                        && !JobRecord.STOPPED.equals(store.getJob(p.jobId).status)) {
                    throw new IllegalArgumentException("作业状态不可重试: " + store.getJob(p.jobId).status);
                }
                retry(p.jobId);
                yield "ok";
            }
            case "cancel" -> {
                requireJob(p.jobId);
                yield cancel(p.jobId) ? "stopped" : "no-op";
            }
            case "create_batch" -> {
                if (p.dirA == null || p.dirB == null || p.configLines == null) {
                    throw new IllegalArgumentException("payload 需要 dirA/dirB/configLines");
                }
                yield createBatch(Path.of(p.dirA), Path.of(p.dirB), p.configLines).id;
            }
            default -> throw new IllegalArgumentException("未知命令类型: " + c.type);
        };
    }

    private void requireJob(String jobId) {
        if (jobId == null || store.getJob(jobId) == null) {
            throw new IllegalArgumentException("作业不存在: " + jobId);
        }
    }

    /** dirA 中未被任何配置 glob 命中的普通文件（需求：无规则文件提示）。 */
    private static List<String> unmatchedFiles(Path dirA, java.util.Set<String> matched) {
        List<String> out = new ArrayList<>();
        try (var s = java.nio.file.Files.list(dirA)) {
            for (Path p : s.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                String name = p.getFileName().toString();
                if (!matched.contains(name)) out.add(name);
            }
        } catch (IOException e) {
            // 目录不可读时不阻断提交
        }
        return out;
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 测试/优雅关闭辅助：等待队列清空。 */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            boolean allDone = tasks.values().stream().allMatch(Future::isDone);
            if (allDone && tasks.values().stream().noneMatch(f -> !f.isDone())) {
                // 双重确认：store 状态无 pending/running
                boolean busy = store.listJobs(null).stream()
                        .anyMatch(j -> JobRecord.PENDING.equals(j.status) || JobRecord.RUNNING.equals(j.status));
                if (!busy) return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Override
    public void close() {
        commandPoller.shutdownNow();
        pool.shutdownNow();
    }
}
