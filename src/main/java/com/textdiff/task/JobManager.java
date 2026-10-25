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
    /** 批次建档单线程执行器：createBatchAsync 的配对/建档在后台串行完成，提交请求立即返回。 */
    private final java.util.concurrent.ExecutorService preparePool;
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
    /** CTIS 动态栏位配置（可空：测试/未导入场景）。作业运行时按文件名解析虚拟子列展开规格。 */
    private final com.textdiff.store.CtisDynFieldStore ctisDyn;

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine) {
        this(store, resultsRoot, engine, null, null, null);
    }

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine,
                      com.textdiff.store.FieldMapStore fieldMaps) {
        this(store, resultsRoot, engine, fieldMaps, null, null);
    }

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine,
                      com.textdiff.store.FieldMapStore fieldMaps,
                      com.textdiff.store.CharsetMapStore charsetMaps) {
        this(store, resultsRoot, engine, fieldMaps, charsetMaps, null);
    }

    public JobManager(JobStore store, Path resultsRoot, EngineConfig engine,
                      com.textdiff.store.FieldMapStore fieldMaps,
                      com.textdiff.store.CharsetMapStore charsetMaps,
                      com.textdiff.store.CtisDynFieldStore ctisDyn) {
        this.store = store;
        this.resultsRoot = resultsRoot;
        this.maxInMemoryBytes = engine.maxInMemoryBytes();
        this.fieldMaps = fieldMaps;
        this.charsetMaps = charsetMaps;
        this.ctisDyn = ctisDyn;
        this.pool = new java.util.concurrent.ThreadPoolExecutor(engine.maxThreads(), engine.maxThreads(),
                60L, TimeUnit.SECONDS, new java.util.concurrent.PriorityBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "compare-worker");
                    t.setDaemon(true);
                    return t;
                });
        this.preparePool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "batch-prepare");
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
    }

    /**
     * 启动恢复：把中断残留的 running/pending 作业重新入队。必须在装配完成（extractService/reportRunner/
     * customRunner 已挂载）后调用——构造器内恢复曾导致压缩作业在解压服务挂载前被直接对比 .gz 原文件。
     */
    public void start() {
        int resumed = 0;
        for (JobRecord job : store.listJobs(null)) {
            if (JobRecord.RUNNING.equals(job.status) || JobRecord.PENDING.equals(job.status)) {
                job.status = JobRecord.PENDING;
                store.saveJob(job);
                submit(job); // 报表作业经 submit 路由到 reportRunner
                resumed++;
            }
        }
        if (resumed > 0) System.out.println("[job] 重启恢复：重新入队 " + resumed + " 个中断作业");
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

    /** 提交前 dry-run 配对预览：与 {@link #materializeBatch} 完全相同的配对顺序，不建批次不派作业。
     *  返回项：nickname / file_name / file_a / file_b / addmod（1-based 栏位列表，未配置为空表）。
     *  每目录仅列举一次（万级文件 × 多配置行时列举是主要耗时），配对全程内存过滤。 */
    public List<Map<String, Object>> previewPairs(Path dirA, Path dirB, List<String> configLines) throws IOException {
        DirList a = DirList.of(dirA);
        DirList b = DirList.of(dirB);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : configLines) {
            if (line.strip().isEmpty() || line.strip().startsWith("#")) continue;
            CompareConfig cfg = Rules.parseLegacy(line, DEFAULT_DELIM, DEFAULT_TRAILER);
            java.nio.file.PathMatcher matcher = globMatcher(cfg.fileGlob);
            List<Integer> addmod = cfg.incrFields.stream().map(f -> f + 1).toList();
            for (String[] pair : pairFiles(a, b, matcher)) {
                out.add(pairView(cfg, Path.of(pair[0]).getFileName().toString(), pair[0], pair[1], addmod));
            }
            var gzA = gzipByLogical(a, matcher);
            var gzB = gzipByLogical(b, matcher);
            for (Map.Entry<String, List<Path>> entry : gzA.entrySet()) { // 逻辑名升序，与正式建档一致
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
     * 目录模式批次（压缩感知 + 增量条件），<b>异步建档</b>：先落批次记录（prepare_state=preparing）
     * 立即返回，配对与作业落盘在后台单线程执行——万级文本提交不再卡住 HTTP 请求；批次页轮询
     * 可看到 jobCount/children 动态增长，建档完成后 noRuleFiles/missingFiles 随终态落盘。
     * incrConds 与配对顺序一一对应（同 {@link #previewPairs} 顺序）。
     */
    public BatchRecord createBatchAsync(Path dirA, Path dirB, List<String> configLines, String kind,
                                        List<List<String>> incrConds) throws IOException {
        return createBatchAsync(dirA, dirB, configLines, kind, incrConds, null);
    }

    /** 分组圈定版：groupName 非空时记录对比范围（配置行已由调用方按分组过滤），供批次详情/总结展示。 */
    public BatchRecord createBatchAsync(Path dirA, Path dirB, List<String> configLines, String kind,
                                        List<List<String>> incrConds, String groupName) throws IOException {
        BatchRecord batch = newBatch(dirA, dirB, configLines, kind);
        if (groupName != null && !groupName.isBlank()) batch.groupName = groupName.strip();
        batch.prepareState = BatchRecord.PREPARING;
        store.saveBatch(batch);
        preparePool.submit(() -> {
            try {
                materializeBatch(batch, dirA, dirB, configLines, kind, incrConds);
            } catch (Exception e) {
                System.err.println("[batch] " + batch.id + " 建档失败: " + e);
                batch.prepareState = BatchRecord.PREPARE_FAILED;
                batch.prepareError = e.getMessage() != null ? e.getMessage() : e.toString();
                store.saveBatch(batch);
            }
        });
        return batch;
    }

    /** 目录模式批次（同步建档）：语义同 {@link #createBatchAsync}，供 H2 命令信箱与测试使用。 */
    public BatchRecord createBatch(Path dirA, Path dirB, List<String> configLines, String kind,
                                   List<List<String>> incrConds) throws IOException {
        return createBatch(dirA, dirB, configLines, kind, incrConds, null);
    }

    /** 分组圈定版（同步建档）。 */
    public BatchRecord createBatch(Path dirA, Path dirB, List<String> configLines, String kind,
                                   List<List<String>> incrConds, String groupName) throws IOException {
        BatchRecord batch = newBatch(dirA, dirB, configLines, kind);
        if (groupName != null && !groupName.isBlank()) batch.groupName = groupName.strip();
        materializeBatch(batch, dirA, dirB, configLines, kind, incrConds);
        return batch;
    }

    private BatchRecord newBatch(Path dirA, Path dirB, List<String> configLines, String kind) {
        String batchId = newId();
        BatchRecord batch = new BatchRecord(batchId,
                dirA.toAbsolutePath().normalize() + " vs " + dirB.toAbsolutePath().normalize(),
                dirA.toAbsolutePath().normalize().toString(),
                dirB.toAbsolutePath().normalize().toString(),
                configLines.size() + " 行配置", 0);
        if (kind != null && !kind.isBlank()) batch.batchType = kind;
        return batch;
    }

    /** 配对建档主体：previewPairs 与此共用全部配对逻辑（DirList 单次列举 + 预编译 glob + 逻辑名升序）。
     *  逐配对立即落盘并派发，每 100 对刷新一次批次 jobCount——批次页轮询可见任务数动态增长。 */
    private void materializeBatch(BatchRecord batch, Path dirA, Path dirB, List<String> configLines,
                                  String kind, List<List<String>> incrConds) throws IOException {
        DirList listA = DirList.of(dirA);
        DirList listB = DirList.of(dirB);
        int jobCount = 0;
        java.util.Set<String> matched = new java.util.HashSet<>();
        List<String> missingFiles = new ArrayList<>();
        int pairIdx = 0;
        for (String line : configLines) {
            if (line.strip().isEmpty() || line.strip().startsWith("#")) continue;
            CompareConfig cfg = Rules.parseLegacy(line, DEFAULT_DELIM, DEFAULT_TRAILER);
            String configLine = Rules.toLegacyLine(cfg, true); // 规范化全量行：结果页展示与重跑依据
            java.nio.file.PathMatcher matcher = globMatcher(cfg.fileGlob);
            int pairsForCfg = 0;
            // 普通文件配对（现状逻辑）
            for (String[] pair : pairFiles(listA, listB, matcher)) {
                String fileName = Path.of(pair[0]).getFileName().toString();
                matched.add(fileName);
                pairsForCfg++;
                JobRecord job = newJob(batch, cfg, configLine, kind,
                        cfg.nickname + " · " + fileName, pair[0], pair[1]);
                attachIncrCond(job, incrConds, pairIdx++);
                store.saveJob(job);
                submit(job);
                if (++jobCount % 100 == 0) saveProgress(batch, jobCount);
            }
            // 压缩文件配对：逻辑名匹配 glob；同逻辑名普通文件已存在时压缩版本忽略（普通优先）
            var gzA = gzipByLogical(listA, matcher);
            var gzB = gzipByLogical(listB, matcher);
            for (Map.Entry<String, List<Path>> entry : gzA.entrySet()) { // 逻辑名升序，与预览一致
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
                JobRecord job = newJob(batch, cfg, configLine, kind,
                        cfg.nickname + " · " + entry.getKey(), repA, repB);
                attachIncrCond(job, incrConds, pairIdx++);
                store.saveJob(job);
                submit(job);
                if (++jobCount % 100 == 0) saveProgress(batch, jobCount);
            }
            if (pairsForCfg == 0) missingFiles.add(cfg.nickname + " · " + cfg.fileGlob);
        }
        batch.noRuleFiles = unmatchedFiles(listA, matched);
        batch.missingFiles = missingFiles;
        batch.jobCount = jobCount;
        batch.prepareState = BatchRecord.READY;
        store.saveBatch(batch);
        System.out.println("[batch] " + batch.id + " 建档完成：" + jobCount + " 个作业");
    }

    /** 建档进度落盘（批次页动态刷新）；批次已被删除时静默停止派发。 */
    private void saveProgress(BatchRecord batch, int jobCount) {
        batch.jobCount = jobCount;
        try {
            store.saveBatch(batch);
        } catch (RuntimeException e) {
            // 进度快照失败不阻断建档，终态仍会尝试落盘
        }
    }

    private JobRecord newJob(BatchRecord batch, CompareConfig cfg, String configLine, String kind,
                             String nickname, String fileA, String fileB) {
        String jobId = newId();
        JobRecord job = new JobRecord(jobId, batch.id, nickname, configLine, fileA, fileB,
                resultsRoot.resolve(jobId).toAbsolutePath().toString());
        if (kind != null && !kind.isBlank()) job.jobType = kind;
        return job;
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

    /** 目录一次性列举（仅普通文件，文件名升序）。万级文件 × 多配置行的配对/建档以此消除重复目录遍历。 */
    private record DirList(java.util.List<Path> files) {
        static DirList of(Path dir) throws IOException {
            java.util.List<Path> out = new ArrayList<>();
            if (!Files.isDirectory(dir)) return new DirList(out);
            try (var ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (Files.isRegularFile(p)) out.add(p);
                }
            }
            out.sort(java.util.Comparator.comparing(x -> x.getFileName().toString()));
            return new DirList(out);
        }
    }

    private static java.nio.file.PathMatcher globMatcher(String glob) {
        return java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    /** 普通文件配对（预编译 matcher + 既有 DirList）：同名交集，文件名升序。与 Rules.pairFiles 语义一致。 */
    private static List<String[]> pairFiles(DirList a, DirList b, java.nio.file.PathMatcher matcher) {
        Map<String, Path> am = new java.util.LinkedHashMap<>();
        for (Path p : a.files()) {
            if (matcher.matches(p.getFileName())) am.put(p.getFileName().toString(), p);
        }
        Map<String, Path> bm = new java.util.LinkedHashMap<>();
        for (Path p : b.files()) {
            if (matcher.matches(p.getFileName())) bm.put(p.getFileName().toString(), p);
        }
        List<String> names = new ArrayList<>(am.keySet());
        names.retainAll(bm.keySet());
        java.util.Collections.sort(names);
        List<String[]> pairs = new ArrayList<>();
        for (String n : names) pairs.add(new String[]{am.get(n).toString(), bm.get(n).toString()});
        return pairs;
    }

    /**
     * 目录内 *.gz 按逻辑名分组（文件名升序），仅保留逻辑名匹配 matcher 且无同名普通文件的组；
     * 组按逻辑名升序（与配对预览顺序一致，增量条件按序对齐的前提）。
     */
    private static java.util.LinkedHashMap<String, List<Path>> gzipByLogical(DirList dir,
                                                                             java.nio.file.PathMatcher matcher) {
        java.util.Map<String, List<Path>> out = new java.util.TreeMap<>();
        for (Path p : dir.files()) {
            String n = p.getFileName().toString();
            if (!ExtractService.isGzipName(n)) continue;
            String logical = ExtractService.logicalName(n);
            if (!matcher.matches(Path.of(logical))) continue;
            out.computeIfAbsent(logical, k -> new ArrayList<>()).add(p); // DirList 已按文件名升序
        }
        java.util.LinkedHashMap<String, List<Path>> ordered = new java.util.LinkedHashMap<>();
        if (out.isEmpty()) return ordered;
        java.util.Set<String> plainNames = new java.util.HashSet<>();
        for (Path p : dir.files()) plainNames.add(p.getFileName().toString());
        for (Map.Entry<String, List<Path>> e : out.entrySet()) {
            if (plainNames.contains(e.getKey())) continue; // 普通文件存在 → 压缩版本忽略（普通优先）
            ordered.put(e.getKey(), e.getValue());
        }
        return ordered;
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
            // 字段名映射注入：配置未显式携带 COLS 时按文件名查源系统字段配置（需求：列名显示在对比界面与导出）。
            // CTIS 动态配置命中的报表优先（反向匹配，规避贪婪 glob 错配），其余走宽匹配（含 #占位符 模式）。
            String ctisReportId = ctisDynReportId(job.fileA);
            if (cfg.columnNames.isEmpty() && fieldMaps != null) {
                String reportId = ctisReportId != null ? ctisReportId
                        : fieldMaps.reportIdForFileWide(job.fileA);
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
                // CTIS 动态栏位：parm_report_type=CTS 且适用清单命中 → 父栏位对比时按 '~~' 编号展开虚拟子列
                List<String> dynWarnings = new ArrayList<>();
                List<com.textdiff.engine.DynExpand.Spec> dynSpecs =
                        resolveDynSpecs(job.fileA, ctisReportId, cfg, dynWarnings);
                if (!dynWarnings.isEmpty()) {
                    System.err.println("[job] " + jobId + " CTIS 动态栏位告警: " + dynWarnings);
                }
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
                            cfg, sink, inMemoryBudget(), dir.resolve("tmp"), transA, transB, dynSpecs);
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
                    if (!dynWarnings.isEmpty()) meta.dynWarnings = dynWarnings;
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
                boolean direct = t.getMessage() != null && t.getMessage().contains("direct buffer");
                job.error = "内存不足（OutOfMemoryError" + (direct ? "：直接内存" : "") + "）"
                        + (t.getMessage() != null ? "：" + t.getMessage() : "")
                        + "；建议降低对比并发度或内存阈值后重试"
                        + (direct ? "，或增大 JVM 直接内存上限（MaxDirectMemorySize）" : "");
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
     * 合并单表索引后每作业只持一个 AutoKeyIndex（A 侧行 + B 键标志），预算即作业合计驻留；
     * 超出部分由 AutoKeyIndex 透明落盘（行存储 + 堆外表，页缓存/直接内存不占堆）。
     * 注意：堆外表走直接内存（默认上限随 -XX:MaxDirectMemorySize，启动参数已设 2g）。
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

    /**
     * CTIS 动态栏位展开规格解析（反向匹配）：从动态配置适用清单出发，逐一验证
     * 「parm_report_type=CTS（忽略大小写）且该报表 report_file_name 模式命中文件」，
     * 命中即返回该 report_id（列名注入与规格解析共用）。反向匹配规避其他报表贪婪 glob
     * 对无扩展名文件 stem 的错配（如 01A***0* 抢先命中）。栏位定位：栏位名精确匹配 →
     * 数字作 1-based 序号；失败记告警（该栏位退化为普通比对，不阻断）。配置热导入后下一作业即生效。
     */
    private String ctisDynReportId(String fileA) {
        if (ctisDyn == null || fieldMaps == null || fileA == null || fileA.isBlank()) return null;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (var a : ctisDyn.allApplies()) {
            if (!seen.add(a.textName())) continue;
            var type = fieldMaps.typeByReport(a.textName());
            if (type == null || type.parmReportType() == null
                    || !"CTS".equalsIgnoreCase(type.parmReportType().strip())) continue;
            if (com.textdiff.store.FieldMapStore.fileMatchesPattern(fileA, type.reportFileName())) {
                return a.textName();
            }
        }
        return null;
    }

    private List<com.textdiff.engine.DynExpand.Spec> resolveDynSpecs(String fileA, String ctisReportId,
                                                                     CompareConfig cfg, List<String> warnings) {
        if (ctisDyn == null || fieldMaps == null || ctisReportId == null) return null;
        try {
            List<com.textdiff.store.CtisDynFields.Apply> applies = ctisDyn.appliesFor(ctisReportId);
            if (applies.isEmpty()) return null;
            List<com.textdiff.engine.DynExpand.Spec> out = new ArrayList<>();
            for (var a : applies) {
                Integer col = locateDynCol(a.colSeq(), cfg.columnNames);
                if (col == null) {
                    warnings.add("动态栏位 " + a.colSeq() + "（" + a.dynName() + "）无法定位：栏位名不匹配且非数字序号，按普通栏位比对");
                    continue;
                }
                Map<String, com.textdiff.engine.DynExpand.Item> items = new java.util.LinkedHashMap<>();
                ctisDyn.itemsFor(a.dynName()).forEach((code, it) -> items.put(code,
                        new com.textdiff.engine.DynExpand.Item(it.itemCode(), it.itemName(),
                                it.primaryKey(), it.typeLen(), it.padAlign(), it.description())));
                out.add(new com.textdiff.engine.DynExpand.Spec(col, a.colSeq(), a.dynName(), items));
            }
            return out.isEmpty() ? null : out;
        } catch (RuntimeException e) {
            warnings.add("动态栏位配置解析异常，按普通栏位比对: " + e.getMessage());
            return null;
        }
    }

    /** 动态栏位定位：先按栏位名与列名表精确匹配，再按整数作 1-based 序号；均失败返回 null。 */
    private static Integer locateDynCol(String colRef, List<String> columnNames) {
        if (colRef == null || colRef.isBlank()) return null;
        String ref = colRef.strip();
        if (columnNames != null) {
            for (int i = 0; i < columnNames.size(); i++) {
                if (ref.equals(columnNames.get(i))) return i;
            }
        }
        try {
            int n = Integer.parseInt(ref);
            return n >= 1 ? n - 1 : null;
        } catch (NumberFormatException ignored) {
            return null;
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
    private static List<String> unmatchedFiles(DirList dirA, java.util.Set<String> matched) {
        List<String> out = new ArrayList<>();
        for (Path p : dirA.files()) {
            String name = p.getFileName().toString();
            if (!matched.contains(name)) out.add(name);
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
        preparePool.shutdownNow();
        pool.shutdownNow();
        awaitTermination(pool);
        awaitTermination(preparePool);
        awaitTermination(commandPoller);
    }

    /**
     * 等待池内线程完全退出（含作业线程的 finally 收尾：KeyIndex/RowStore 关闭、状态落盘）。
     * 只 shutdownNow 不等待曾让测试的临时目录清理与线程收尾赛跑（CI 偶发 DirectoryNotEmpty），
     * 应用停机同理需要等资源真正释放。
     */
    private static void awaitTermination(java.util.concurrent.ExecutorService ex) {
        try {
            if (!ex.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.shutdownNow();
        }
    }
}
