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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
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
    private final java.util.concurrent.ThreadPoolExecutor pool;
    private final ScheduledExecutorService commandPoller;
    private final ConcurrentMap<String, PrioritizedRunnable> tasks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong submitSeq = new java.util.concurrent.atomic.AtomicLong();
    /** 压缩文件预解压服务（装配时注入；null = 不启用，运行时兜底同步解压）。 */
    public volatile ExtractService extractService;
    /** 字段名映射（可空：测试/未导入场景）。作业运行时按文件名自动填充列名。 */
    private final com.textdiff.store.FieldMapStore fieldMaps;
    /** 作业完成回调（任务管理器挂载点：登记差异 CSV 导出 + AI 分析两条默认生成任务）。 */
    public volatile java.util.function.Consumer<JobRecord> doneHook;
    /** 报表对比作业执行器（ReportCompareService 挂载点；jobType=report 的作业路由到这里）。 */
    public volatile java.util.function.Consumer<String> reportRunner;
    /** 自定义格式对比作业执行器（CustomCompareService 挂载点；jobType=custom 的作业路由到这里）。 */
    public volatile java.util.function.Consumer<String> customRunner;
    /** 特殊编码字段映射（可空：测试/未导入场景）。作业运行时按文件名解析混合编码栏位转码。 */
    private final com.textdiff.store.CharsetMapStore charsetMaps;

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine) {
        this(store, resultsRoot, engine, null, null);
    }

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine,
                      com.textdiff.store.FieldMapStore fieldMaps) {
        this(store, resultsRoot, engine, fieldMaps, null);
    }

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine,
                      com.textdiff.store.FieldMapStore fieldMaps,
                      com.textdiff.store.CharsetMapStore charsetMaps) {
        this.store = store;
        this.resultsRoot = resultsRoot;
        this.maxInMemoryBytes = engine.maxInMemoryBytes();
        this.fieldMaps = fieldMaps;
        this.charsetMaps = charsetMaps;
        this.pool = new java.util.concurrent.ThreadPoolExecutor(engine.maxThreads(), engine.maxThreads(),
                60L, TimeUnit.SECONDS, new java.util.concurrent.PriorityBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "compare-worker");
                    t.setDaemon(true);
                    return t;
                });
        this.commandPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "command-poller");
            t.setDaemon(true);
            return t;
        });
        this.commandPoller.scheduleWithFixedDelay(this::drainCommands,
                COMMAND_POLL_SECONDS, COMMAND_POLL_SECONDS, TimeUnit.SECONDS);
        requeueStuckJobs();
    }

    /** 对比并发度热更新：主对比线程池运行时重调大小（设置页保存即生效）。缩容先降 core，扩容先升 max。 */
    public void resizeEnginePool(int maxThreads) {
        if (pool instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            if (maxThreads < tpe.getCorePoolSize()) {
                tpe.setCorePoolSize(maxThreads);
                tpe.setMaximumPoolSize(maxThreads);
            } else {
                tpe.setMaximumPoolSize(maxThreads);
                tpe.setCorePoolSize(maxThreads);
            }
        }
    }

    /** 目录模式批次：每行配置一条规则，glob 配对 dirA/dirB 同名文件 → 多作业。返回批次。 */
    public BatchRecord createBatch(Path dirA, Path dirB, List<String> configLines) throws IOException {
        return createBatch(dirA, dirB, configLines, null, null);
    }

    public BatchRecord createBatch(Path dirA, Path dirB, List<String> configLines, String kind) throws IOException {
        return createBatch(dirA, dirB, configLines, kind, null);
    }

    /** 提交前 dry-run 配对预览：与 {@link #createBatch} 完全相同的配对顺序，不建批次不派作业。
     *  返回项：nickname / file_name / file_a / file_b / addmod（1-based 栏位列表，未配置为空表）。 */
    public List<Map<String, Object>> previewPairs(Path dirA, Path dirB, List<String> configLines) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : configLines) {
            if (line.strip().isEmpty() || line.strip().startsWith("#")) continue;
            CompareConfig cfg = Rules.parseLegacy(line, DEFAULT_DELIM, DEFAULT_TRAILER);
            List<Integer> addmod = cfg.incrFields.stream().map(f -> f + 1).toList();
            for (String[] pair : Rules.pairFiles(dirA, dirB, cfg.fileGlob)) {
                out.add(pairView(cfg, Path.of(pair[0]).getFileName().toString(), pair[0], pair[1], addmod));
            }
            var gzA = gzipByLogical(dirA, cfg.fileGlob);
            var gzB = gzipByLogical(dirB, cfg.fileGlob);
            for (Map.Entry<String, List<Path>> entry : gzA.entrySet()) {
                List<Path> bs = gzB.get(entry.getKey());
                if (bs == null) continue;
                List<Path> as = entry.getValue();
                out.add(pairView(cfg, entry.getKey(), as.get(as.size() - 1).toString(),
                        bs.get(bs.size() - 1).toString(), addmod));
            }
        }
        return out;
    }

    private static Map<String, Object> pairView(CompareConfig cfg, String fileName,
                                                String fileA, String fileB, List<Integer> addmod) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("nickname", cfg.nickname);
        m.put("file_name", fileName);
        m.put("file_a", fileA);
        m.put("file_b", fileB);
        m.put("addmod", addmod);
        return m;
    }

    /**
     * 目录模式批次（压缩感知 + 增量条件）。kind 非空（如 "ctis"）时作为批次/作业类型标记，路由与展示用。
     * incrConds 与配对顺序一一对应（同 {@link #previewPairs} 顺序），逐文本增量条件值落入作业记录；
     * *.gz（含 .YYYYMMDD+1位标识 9 位数字后缀）按逻辑名参与 glob 匹配：匹配到的先解压后对比
     * （预解压池调度，同名多版本按文件名升序解压覆盖、最新生效）；未匹配的逻辑名计入 noRuleFiles；
     * 一条配置在两侧目录（普通文件 + 压缩逻辑名）都未配对出文件 → missingFiles（昵称 · 通配名）。
     */
    public BatchRecord createBatch(Path dirA, Path dirB, List<String> configLines, String kind,
                                   List<List<String>> incrConds) throws IOException {
        String batchId = newId();
        BatchRecord batch = new BatchRecord(batchId,
                dirA.toAbsolutePath().normalize() + " vs " + dirB.toAbsolutePath().normalize(),
                dirA.toAbsolutePath().normalize().toString(),
                dirB.toAbsolutePath().normalize().toString(),
                configLines.size() + " 行配置", 0);
        if (kind != null && !kind.isBlank()) batch.batchType = kind;

        List<JobRecord> jobs = new ArrayList<>();
        java.util.Set<String> matched = new java.util.HashSet<>();
        List<String> missingFiles = new ArrayList<>();
        int pairIdx = 0;
        for (String line : configLines) {
            if (line.strip().isEmpty() || line.strip().startsWith("#")) continue;
            CompareConfig cfg = Rules.parseLegacy(line, DEFAULT_DELIM, DEFAULT_TRAILER);
            String configLine = Rules.toLegacyLine(cfg, true); // 规范化全量行：结果页展示与重跑依据
            int pairsForCfg = 0;
            // 普通文件配对（现状逻辑）
            for (String[] pair : Rules.pairFiles(dirA, dirB, cfg.fileGlob)) {
                String fileName = Path.of(pair[0]).getFileName().toString();
                matched.add(fileName);
                pairsForCfg++;
                String jobId = newId();
                JobRecord job = new JobRecord(jobId, batchId,
                        cfg.nickname + " · " + fileName, configLine, pair[0], pair[1],
                        resultsRoot.resolve(jobId).toAbsolutePath().toString());
                if (kind != null && !kind.isBlank()) job.jobType = kind;
                attachIncrCond(job, incrConds, pairIdx++);
                jobs.add(job);
            }
            // 压缩文件配对：逻辑名匹配 glob；同逻辑名普通文件已存在时压缩版本忽略（普通优先）
            var gzA = gzipByLogical(dirA, cfg.fileGlob);
            var gzB = gzipByLogical(dirB, cfg.fileGlob);
            for (Map.Entry<String, List<Path>> entry : gzA.entrySet()) {
                List<Path> bs = gzB.get(entry.getKey());
                if (bs == null) continue;
                List<Path> as = entry.getValue();
                matched.add(entry.getKey());
                as.forEach(p -> matched.add(p.getFileName().toString()));
                bs.forEach(p -> matched.add(p.getFileName().toString()));
                pairsForCfg++;
                // 代表路径取文件名最大（最新日期）版本；解压时全版本按升序覆盖，最终即最新
                String repA = as.get(as.size() - 1).toString();
                String repB = bs.get(bs.size() - 1).toString();
                String jobId = newId();
                JobRecord job = new JobRecord(jobId, batchId,
                        cfg.nickname + " · " + entry.getKey(), configLine, repA, repB,
                        resultsRoot.resolve(jobId).toAbsolutePath().toString());
                if (kind != null && !kind.isBlank()) job.jobType = kind;
                attachIncrCond(job, incrConds, pairIdx++);
                jobs.add(job);
            }
            if (pairsForCfg == 0) missingFiles.add(cfg.nickname + " · " + cfg.fileGlob);
        }
        batch.noRuleFiles = unmatchedFiles(dirA, matched);
        batch.missingFiles = missingFiles;
        batch.jobCount = jobs.size();
        store.saveBatch(batch);
        for (JobRecord job : jobs) {
            store.saveJob(job);
            submit(job);
        }
        return batch;
    }

    /** 附加该文本的增量条件值（非空才落记录；对应规则未配 ADDMOD 时存储但过滤自然不启用）。 */
    private static void attachIncrCond(JobRecord job, List<List<String>> incrConds, int pairIdx) {
        if (incrConds == null || pairIdx >= incrConds.size()) return;
        List<String> cond = incrConds.get(pairIdx);
        if (cond == null || cond.isEmpty()) return;
        if (cond.stream().anyMatch(c -> c != null && !c.isBlank())) {
            job.incrCond = Json.write(cond);
        }
    }

    /** 目录内 *.gz 按逻辑名分组（文件名升序），仅保留逻辑名匹配 glob 且无同名普通文件的组。 */
    private static java.util.LinkedHashMap<String, List<Path>> gzipByLogical(Path dir, String glob)
            throws IOException {
        java.util.LinkedHashMap<String, List<Path>> out = new java.util.LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (var ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String n = p.getFileName().toString();
                if (!ExtractService.isGzipName(n)) continue;
                String logical = ExtractService.logicalName(n);
                if (!Rules.matchGlob(logical, glob)) continue;
                if (Files.exists(dir.resolve(logical))) continue; // 普通文件存在 → 压缩版本忽略
                out.computeIfAbsent(logical, k -> new ArrayList<>()).add(p);
            }
        }
        for (List<Path> l : out.values()) {
            l.sort(java.util.Comparator.comparing(x -> x.getFileName().toString()));
        }
        return out;
    }

    /** 上传模式批次：调用方已确定文件对（M3 multipart 落盘后调用）。 */
    public BatchRecord createBatchFromPairs(String label, List<String[]> pairs) throws IOException {
        return createBatchFromPairs(label, pairs, null, null);
    }

    /** 直连模式批次（kind 非空时作为批次/作业类型标记，如 "ctis"；incrConds 与 pairs 对齐）。 */
    public BatchRecord createBatchFromPairs(String label, List<String[]> pairs, String kind,
                                            List<List<String>> incrConds) throws IOException {
        String batchId = newId();
        BatchRecord batch = new BatchRecord(batchId, label, null, null, "upload", pairs.size());
        if (kind != null && !kind.isBlank()) batch.batchType = kind;
        store.saveBatch(batch);
        int pairIdx = 0;
        for (String[] p : pairs) {
            // p = {configLine, fileA, fileB, nickname}
            String jobId = newId();
            JobRecord job = new JobRecord(jobId, batchId, p[3], p[0], p[1], p[2],
                    resultsRoot.resolve(jobId).toAbsolutePath().toString());
            if (kind != null && !kind.isBlank()) job.jobType = kind;
            attachIncrCond(job, incrConds, pairIdx++);
            store.saveJob(job);
            submit(job);
        }
        return batch;
    }

    public synchronized void submit(JobRecord job) {
        if (job != null && com.textdiff.report.ReportCompareService.JOB_TYPE.equals(job.jobType)) {
            java.util.function.Consumer<String> rr = reportRunner;
            if (rr != null) rr.accept(job.id); // 报表作业由专用执行器消费
            return;
        }
        if (job != null && com.textdiff.custom.CustomCompareService.JOB_TYPE.equals(job.jobType)) {
            java.util.function.Consumer<String> cr = customRunner;
            if (cr != null) cr.accept(job.id); // 自定义格式对比作业由专用执行器消费
            return;
        }
        if (extractService != null && extractService.shouldExtract(job)) {
            extractService.enqueue(job); // 压缩文件：先解压（预解压池调度），完成后再入对比池
            return;
        }
        if (tasks.containsKey(job.id)) return;
        tasks.put(job.id, new PrioritizedRunnable(priSeqOf(job), submitSeq.getAndIncrement(),
                () -> runJob(job.id)));
        pool.execute(tasks.get(job.id));
    }

    /** 对比优先级：配置行 PRISEQ（缺省 5，解析失败同样回退 5）。 */
    private static int priSeqOf(JobRecord job) {
        try {
            return Rules.parseLegacy(job.configLine, DEFAULT_DELIM, DEFAULT_TRAILER).priSeq;
        } catch (RuntimeException e) {
            return 5;
        }
    }

    /** 优先级任务：PRISEQ 升序 + FIFO 出队；内嵌 FutureTask 提供取消/中断句柄。 */
    static final class PrioritizedRunnable implements Runnable, Comparable<PrioritizedRunnable> {
        private final int pri;
        private final long seq;
        private final java.util.concurrent.FutureTask<Void> task;

        PrioritizedRunnable(int pri, long seq, Runnable body) {
            this.pri = pri;
            this.seq = seq;
            this.task = new java.util.concurrent.FutureTask<>(body, null);
        }

        @Override public void run() {
            task.run();
        }

        void cancel(boolean mayInterruptIfRunning) {
            task.cancel(mayInterruptIfRunning);
        }

        boolean isCancelled() {
            return task.isCancelled();
        }

        boolean isDone() {
            return task.isDone();
        }

        @Override public int compareTo(PrioritizedRunnable o) {
            int c = Integer.compare(pri, o.pri);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
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
                Path inputA = resolveInput(job, job.fileA);
                Path inputB = resolveInput(job, job.fileB);
                com.textdiff.engine.FieldTranscoder transA = resolveTranscoder(inputA.toString(), cfg.encodingA, cfg.delimiter);
                com.textdiff.engine.FieldTranscoder transB = resolveTranscoder(inputB.toString(), cfg.encodingB, cfg.delimiter);
                // 增量过滤（ADDMOD）：解压后、正式对比前，按提交条件值重组源文本（转码器仍绑定原始路径解析）
                List<String> incrConds = parseIncrCond(job);
                com.textdiff.engine.IncrFilter.Result incr = null;
                if (com.textdiff.engine.IncrFilter.enabled(cfg, incrConds)) {
                    incr = com.textdiff.engine.IncrFilter.apply(inputA, inputB, cfg, incrConds, dir.resolve("tmp"));
                    job.incrState = incr.aAfter() == 0 && incr.bAfter() == 0 ? "增量-空文本" : "增量";
                }
                try {
                    CompareOutcome outcome = Comparator.compareFiles(
                            incr != null ? incr.filteredA() : inputA,
                            incr != null ? incr.filteredB() : inputB,
                            cfg, sink, inMemoryBudget(), dir.resolve("tmp"), transA, transB);
                    Summary s = outcome.summary();
                    // 统计快照落作业记录：列表/批次视图不再逐作业读 meta.json（前端轮询与批次页的主要磁盘开销）
                    job.diffCount = s.diff;
                    job.totalA = s.totalA;
                    job.totalB = s.totalB;
                    job.equalCount = s.equal;
                    job.onlyACount = s.onlyA;
                    job.onlyBCount = s.onlyB;
                    job.keyWarning = s.keyDupA > 0 || s.keyDupB > 0; // 需求：主键配置错误醒目提示
                    ResultFiles.writeSummary(dir, s);
                    job.aiStatus = "pending"; // M5 AI 分析器消费 pending 状态
                    JobMeta meta = ResultFiles.buildMeta(job,
                            outcome.detectedEncodingA(), outcome.detectedEncodingB(), s);
                    meta.zoneEqual = sink.zoneEqual;
                    meta.zoneDiff = sink.zoneDiff;
                    meta.zoneUnmatched = sink.zoneUnmatched;
                    meta.zoneTrailer = sink.zoneTrailer;
                    if (incr != null) {
                        meta.incr = new JobMeta.Incr(cfg.incrFields.stream().map(f -> f + 1).toList(),
                                incrConds, incr.aBefore(), incr.aAfter(), incr.bBefore(), incr.bAfter());
                    }
                    ResultFiles.writeMeta(dir, meta);
                } finally {
                    com.textdiff.engine.IncrFilter.cleanup(incr); // 过滤产物即用即删
                }
            }
            job.status = JobRecord.DONE;
            job.finishedAt = System.currentTimeMillis() / 1000;
        } catch (Throwable t) {
            // Throwable 而非 Exception：OutOfMemoryError 等 Error 也必须落终态，
            // 否则作业卡在 running、重启被重新入队 → 「启动即 OOM」死循环
            PrioritizedRunnable pr = tasks.get(jobId);
            if (pr != null && pr.isCancelled()) {
                job.status = JobRecord.STOPPED; // 中断导致的异常归为用户终止
            } else if (t instanceof OutOfMemoryError) {
                job.status = JobRecord.FAILED;
                job.error = "内存不足（OutOfMemoryError）" + (t.getMessage() != null ? "：" + t.getMessage() : "")
                        + "；建议降低对比并发度或内存阈值后重试";
                System.err.println("[job] " + jobId + " 内存不足终止: " + t);
            } else {
                job.status = JobRecord.FAILED;
                job.error = t.getMessage() != null ? t.getMessage() : t.toString();
                if (!(t instanceof Exception)) System.err.println("[job] " + jobId + " 异常终止: " + t);
            }
            job.finishedAt = System.currentTimeMillis() / 1000;
        }
        store.saveJob(job);
        java.util.function.Consumer<JobRecord> hook = doneHook;
        if (hook != null && JobRecord.DONE.equals(job.status)) hook.accept(job); // 状态落盘后登记生成任务
        if (extractService != null) extractService.onJobFinished(job); // 终态清理解压产物并补位
        tasks.remove(jobId);
    }

    /**
     * 单作业内存索引预算：min(配置阈值, max(16MB, 堆/(4×并行度)))。
     * maxInMemoryBytes 是单作业口径，与并行度无关——并行 P 个作业同时驻留大索引时
     * 实际堆占用可达 P×阈值，正是大文件批量对比 OOM 的主因；按当前并行度与最大堆动态收缩，
     * 超出部分由 AutoKeyIndex 透明落盘（mmap 行存储，页缓存不占堆）。
     */
    long inMemoryBudget() {
        int par = Math.max(1, pool.getMaximumPoolSize());
        long adaptive = Math.max(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / (4L * par));
        return Math.min(maxInMemoryBytes, adaptive); // 显式配置的小阈值（如强制落盘）优先于自适应下限
    }
    /** 运行输入解析：压缩文件侧替换为解压产物路径（无解压服务时按原路径）。 */
    private Path resolveInput(JobRecord job, String path) {
        if (extractService == null || path == null) return Path.of(path);
        return extractService.resolveInput(job, path);
    }

    /** 作业持久化的增量条件值（JSON 数组）；未设置返回 null。 */
    private static List<String> parseIncrCond(JobRecord job) {
        try {
            if (job.incrCond == null || job.incrCond.isBlank()) return null;
            return Json.read(job.incrCond, List.class);
        } catch (RuntimeException e) {
            return null; // 条件损坏按未启用处理，不阻断对比
        }
    }

    /** 终止作业：running 尽力中断，pending 直接置 stopped。返回是否产生了状态变更。 */
    public boolean cancel(String jobId) {
        PrioritizedRunnable pr = tasks.get(jobId);
        if (pr != null) pr.cancel(true);
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
        job.clearStats();
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
                submit(job); // 报表作业经 submit 路由到 reportRunner
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

    /**
     * 特殊编码字段转码器解析：文件名 → 数据表英文名 → 列字符集映射，与文件实际解析编码绑定。
     * 未导入映射 / 文件无对应表 / 文件编码非主机单字节字符集时返回 null（零开销路径）。
     */
    private com.textdiff.engine.FieldTranscoder resolveTranscoder(String filePath, String encoding,
                                                                  String delimiter) {
        if (charsetMaps == null || filePath == null || filePath.isBlank()) return null;
        try {
            String resolved = encoding == null || encoding.isBlank() || "auto".equals(encoding)
                    ? com.textdiff.engine.Encoding.detectFile(Path.of(filePath), delimiter)
                    : encoding;
            String table = charsetMaps.tableForFile(filePath, fieldMaps);
            if (table == null) return null;
            com.textdiff.engine.FieldTranscoder t =
                    new com.textdiff.engine.FieldTranscoder(resolved, charsetMaps.colCharsetsForTable(table));
            return t.enabled() ? t : null;
        } catch (Exception e) {
            return null; // 转码解析失败不阻断对比
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
            boolean allDone = tasks.values().stream().allMatch(PrioritizedRunnable::isDone);
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
