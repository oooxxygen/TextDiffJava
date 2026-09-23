package com.textdiff.custom;

import com.textdiff.engine.Encoding;
import com.textdiff.engine.RowDiff;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.Json;
import com.textdiff.store.ResultFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 自定义格式对比服务（【自定义格式对比】界面）：非传统结构化文本（一段段报文，如 MT950）
 * 由用户指定段起止匹配式与主键提取式，按段解析、按主键匹配。
 *
 * 提交：路径 A/B（目录按文件名配对，或直接给单文件）+ 格式配置；运行：段解析 → 主键配对逐行对比
 * → result.jsonl（segment 分区 RowDiff 流）+ custom_summary.json + meta.json。不走 AI 分析链路。
 */
public final class CustomCompareService implements AutoCloseable {
    public static final String JOB_TYPE = "custom";
    public static final String SUMMARY_JSON = "custom_summary.json";

    private final JobStore store;
    private final Path resultsRoot;
    private final ExecutorService pool;
    private final ConcurrentMap<String, Future<?>> tasks = new ConcurrentHashMap<>();

    public CustomCompareService(JobStore store, Path resultsRoot, int threads) {
        this.store = store;
        this.resultsRoot = resultsRoot;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "custom-runner");
            t.setDaemon(true);
            return t;
        });
        requeueStuckJobs();
    }

    /** 对比并发度热更新：自定义格式线程池运行时重调大小（设置页保存即生效）。 */
    public void resizePool(int threads) {
        if (pool instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            tpe.setMaximumPoolSize(Math.max(1, threads));
            tpe.setCorePoolSize(Math.max(1, threads));
        }
    }

    /** 提交批次：路径 A/B 各可为目录（按文件名配对全部文件）或单个文件。 */
    public BatchRecord submit(Path pathA, Path pathB, String label, CustomFormat cfg) throws IOException {
        if (pathA == null || pathB == null || !Files.exists(pathA) || !Files.exists(pathB)) {
            throw new IllegalArgumentException("文本路径 A/B 必须是已存在的目录或文件");
        }
        List<String[]> pairs = listPairs(pathA, pathB);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("两路径下无可配对的同名文件");
        }
        String absA = normalize(pathA);
        String absB = normalize(pathB);
        BatchRecord batch = new BatchRecord(newId(),
                label == null || label.isBlank() ? "自定义格式对比" : label.strip(),
                absA, absB, "格式: 起始 " + cfg.startPattern(), 0);
        batch.batchType = JOB_TYPE;
        batch.templateDir = cfg.toConfigLine();
        String configLine = cfg.toConfigLine();

        List<JobRecord> jobs = new ArrayList<>();
        for (String[] p : pairs) { // p = {fileName, fileA, fileB}
            JobRecord job = new JobRecord(newId(), batch.id,
                    p[0] + " · 段对比", configLine, p[1], p[2],
                    resultsRoot.resolve(newId()).toAbsolutePath().toString());
            job.jobType = JOB_TYPE;
            jobs.add(job);
        }
        batch.jobCount = jobs.size();
        store.saveBatch(batch);
        for (JobRecord job : jobs) {
            store.saveJob(job);
            submit(job);
        }
        return batch;
    }

    /** 目录按文件名配对；单文件则只配该文件名。 */
    private static List<String[]> listPairs(Path pathA, Path pathB) throws IOException {
        List<String> namesA;
        Path dirA, dirB;
        if (Files.isRegularFile(pathA)) {
            namesA = List.of(fileName(pathA));
            dirA = pathA.getParent();
        } else {
            namesA = listFiles(pathA);
            dirA = pathA;
        }
        if (Files.isRegularFile(pathB)) {
            dirB = pathB.getParent();
        } else {
            dirB = pathB;
        }
        Map<String, Path> filesB = new LinkedHashMap<>();
        if (Files.isRegularFile(pathB)) {
            filesB.put(fileName(pathB), pathB);
        } else {
            for (String n : listFiles(pathB)) filesB.put(n, pathB.resolve(n));
        }
        List<String[]> out = new ArrayList<>();
        for (String n : namesA) {
            Path fb = filesB.get(n);
            if (fb != null) out.add(new String[]{n, dirA.resolve(n).toAbsolutePath().normalize().toString(),
                    fb.toAbsolutePath().normalize().toString()});
        }
        return out;
    }

    private static List<String> listFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted().toList();
        }
    }

    public synchronized void submit(JobRecord job) {
        if (job == null || !JOB_TYPE.equals(job.jobType)) return;
        tasks.putIfAbsent(job.id, pool.submit(() -> run(job.id)));
    }

    /** 作业执行入口（状态机与报表对比服务一致）。 */
    public void run(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null || !JobRecord.PENDING.equals(job.status)) return;
        job.status = JobRecord.RUNNING;
        job.startedAt = System.currentTimeMillis() / 1000;
        job.error = null;
        store.saveJob(job);
        String encA = "auto", encB = "auto";
        try {
            CustomFormat cfg = CustomFormat.fromConfigLine(job.configLine);
            if (cfg == null) throw new IllegalArgumentException("作业格式配置缺失或无效");
            Path fa = Path.of(job.fileA);
            Path fb = Path.of(job.fileB);
            encA = Encoding.resolveEncoding(fa, "auto", "|");
            encB = Encoding.resolveEncoding(fb, "auto", "|");
            List<String> la = readLines(fa, encA);
            List<String> lb = readLines(fb, encB);

            CustomCompareEngine.Result result = CustomCompareEngine.compare(la, lb, cfg);

            Path dir = Path.of(job.resultDir);
            ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(dir.resolve(ResultFiles.RESULT_JSONL));
            try (sink) {
                for (RowDiff r : result.rows()) sink.addRow(r);
            }
            CustomSummary s = result.summary();
            writeJson(dir.resolve(SUMMARY_JSON), s);
            JobMeta meta = ResultFiles.buildMeta(job, encA, encB, null);
            meta.totalA = s.segmentsA;
            meta.totalB = s.segmentsB;
            meta.equal = s.equal;
            meta.diff = s.diff;
            meta.onlyA = s.onlyA;
            meta.onlyB = s.onlyB;
            meta.zoneEqual = sink.zoneEqual;
            meta.zoneDiff = sink.zoneDiff;
            meta.zoneUnmatched = sink.zoneUnmatched;
            ResultFiles.writeMeta(dir, meta);

            JobRecord cur = store.getJob(jobId);
            if (cur != null && JobRecord.STOPPED.equals(cur.status)) return;
            job.status = JobRecord.DONE;
            job.finishedAt = System.currentTimeMillis() / 1000;
        } catch (Exception e) {
            job.status = JobRecord.FAILED;
            job.error = e.getMessage() != null ? e.getMessage() : e.toString();
            job.finishedAt = System.currentTimeMillis() / 1000;
        }
        store.saveJob(job);
        tasks.remove(jobId);
    }

    /** 单作业摘要（summary 端点返回体）。 */
    public Map<String, Object> summaryView(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null) return null;
        CustomSummary s = readSummary(Path.of(job.resultDir));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job_id", job.id);
        out.put("batch_id", job.batchId);
        out.put("status", JobRecord.FAILED.equals(job.status) ? "error" : job.status);
        out.put("error", job.error);
        out.put("nickname", job.nickname);
        out.put("file_a", job.fileA);
        out.put("file_b", job.fileB);
        out.put("label", job.label == null || job.label.isEmpty() ? job.nickname : job.label);
        out.put("finished_at", job.finishedAt);
        out.put("summary", s == null ? Map.of() : s);
        return out;
    }

    public CustomSummary readSummary(Path resultDir) {
        try {
            return Json.read(Files.readString(resultDir.resolve(SUMMARY_JSON)), CustomSummary.class);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("自定义格式对比摘要读取失败", e);
        }
    }

    private void requeueStuckJobs() {
        for (JobRecord job : store.listJobs(null)) {
            if (JOB_TYPE.equals(job.jobType)
                    && (JobRecord.PENDING.equals(job.status) || JobRecord.RUNNING.equals(job.status))) {
                job.status = JobRecord.PENDING;
                store.saveJob(job);
                submit(job);
            }
        }
    }

    private static List<String> readLines(Path p, String enc) throws IOException {
        try (Stream<String> s = Encoding.iterLines(p, enc, "|")) {
            return s.toList();
        }
    }

    private static void writeJson(Path file, Object o) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(o));
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败: " + file, e);
        }
    }

    private static String normalize(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    private static String fileName(Path p) {
        return p.getFileName().toString();
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 测试辅助：等待队列清空。 */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (tasks.values().stream().allMatch(Future::isDone)) {
                boolean busy = store.listJobs(null).stream().anyMatch(j -> JOB_TYPE.equals(j.jobType)
                        && (JobRecord.PENDING.equals(j.status) || JobRecord.RUNNING.equals(j.status)));
                if (!busy) return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
