package com.textdiff.task;

import com.textdiff.config.AppConfig;
import com.textdiff.config.AppPaths;
import com.textdiff.engine.Rules;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * 压缩文件预解压服务（目录批量对比）：*.gz（可带 .YYYYMMDD+1位增全量标识 共 9 位数字后缀）
 * 复制到临时解压目录后 gunzip 得逻辑文件；解压前按逻辑名匹配配置，未匹配不解压。
 *
 * 预解压池：待解压作业按 (PRISEQ 升序, 逻辑名) 排队，动态补位维持
 * 「已解压待比」组数 ≤ pre-extract-count（设置页热生效）；组内源文件按文件名升序逐个
 * 解压覆盖（临时产物 + 原子改名，半文件不可见），最新日期版本最终生效。
 * 作业终态（done/failed）即删自己消费的解压产物并触发补位；
 * 解压失败 → 作业 FAILED（附具体文件名）并计入批次 extract_failed 清单。
 */
public final class ExtractService implements AutoCloseable {

    /** 压缩文件命名：*.gz 或 *.gz.九位数字（8 位日期 + 1 位增全量标识）。 */
    static final String GZ_SUFFIX_REGEX = "(?i).+\\.gz(\\.[0-9]{9})?";

    private final JobStore store;
    private final JobManager jobs;
    private final AppPaths paths;
    private final AppConfig cfg;

    /** 一个待解压作业：A/B 两侧各自的同名压缩源（文件名升序）与目标逻辑文件。 */
    record Group(int priSeq, String logicalName, JobRecord job,
                 List<Path> sourcesA, Path targetA,
                 List<Path> sourcesB, Path targetB) implements Comparable<Group> {
        @Override public int compareTo(Group o) {
            int c = Integer.compare(priSeq, o.priSeq);
            return c != 0 ? c : logicalName.compareTo(o.logicalName);
        }
    }

    private final PriorityBlockingQueue<Group> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<String, Boolean> queued = new ConcurrentHashMap<>();   // 在等待队列/解压中
    private final ConcurrentHashMap<String, Boolean> extracted = new ConcurrentHashMap<>(); // 已解压待比
    private final AtomicInteger inFlight = new AtomicInteger(); // 正在解压（已出队未完成）
    private final Object batchLock = new Object();              // extractFailed 清单读改写互斥
    private final ExecutorService extractPool;
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    public ExtractService(JobStore store, JobManager jobs, AppPaths paths, AppConfig cfg) {
        this.store = store;
        this.jobs = jobs;
        this.paths = paths;
        this.cfg = cfg;
        this.extractPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "extractor");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "extract-scheduler");
            t.setDaemon(true);
            return t;
        });
        // 周期补位：设置热生效（数量/目录）后下一轮即按新值运行
        scheduler.scheduleWithFixedDelay(this::refill, 500, 500, TimeUnit.MILLISECONDS);
    }

    // ---- 命名规则 ----

    /** 是否压缩文件：*.gz 或 *.gz.九位数字。 */
    public static boolean isGzipName(String name) {
        return name != null && name.matches(GZ_SUFFIX_REGEX);
    }

    /** 逻辑名：剥离 9 位数字后缀与 .gz（01A1010D.ew1.gz.202601011 → 01A1010D.ew1）。 */
    public static String logicalName(String name) {
        String n = name;
        if (n.matches("(?i).+\\.gz\\.[0-9]{9}")) n = n.substring(0, n.length() - 10);
        if (n.toLowerCase().endsWith(".gz")) n = n.substring(0, n.length() - 3);
        return n;
    }

    /** 作业是否需要走解压队列（PENDING、任一侧为压缩文件、尚未解压完成）。 */
    public boolean shouldExtract(JobRecord job) {
        if (job == null || !JobRecord.PENDING.equals(job.status)) return false;
        if (extracted.containsKey(job.id)) return false;
        return isGzipName(base(job.fileA)) || isGzipName(base(job.fileB));
    }

    /** 入队待解压（幂等）。 */
    public void enqueue(JobRecord job) {
        if (queued.putIfAbsent(job.id, Boolean.TRUE) != null) return;
        queue.offer(groupOf(job));
        refill();
    }

    /** 运行时输入解析：压缩侧返回解压产物路径（缺失时兜底同步解压），普通侧原样返回。 */
    public Path resolveInput(JobRecord job, String path) {
        if (path == null) return null;
        if (!isGzipName(base(path))) return Path.of(path);
        Path target = targetFor(job, path);
        if (!Files.isRegularFile(target)) {
            try {
                extractOne(Path.of(path), target);
            } catch (IOException e) {
                throw new UncheckedIOException("解压失败: " + base(path) + " (" + e.getMessage() + ")", e);
            }
        }
        return target;
    }

    /** 作业终态回调：清理解压产物（连带空的批次/侧目录）并触发补位。 */
    public void onJobFinished(JobRecord job) {
        if (extracted.remove(job.id) == null) return;
        Path a = targetFor(job, job.fileA);
        Path b = targetFor(job, job.fileB);
        deleteQuietly(a);
        deleteQuietly(b);
        pruneEmptyParents(a);
        pruneEmptyParents(b);
        refill();
    }

    /** 自底向上删除解压根目录内的空目录（批次收尾后目录树自动消失）。 */
    private void pruneEmptyParents(Path file) {
        Path root = extractRoot();
        Path dir = file == null ? null : file.getParent();
        while (dir != null && dir.startsWith(root) && !dir.equals(root)) {
            try {
                if (!Files.deleteIfExists(dir)) break; // 非空：其他作业仍在用
            } catch (IOException e) {
                break;
            }
            dir = dir.getParent();
        }
    }

    /** 批次收尾兜底：清理该批次解压目录残留（作业删除/中断等遗漏）。 */
    public void cleanupBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) return;
        deleteRecursively(extractRoot().resolve(batchId));
    }

    // ---- internals ----

    private Group groupOf(JobRecord job) {
        int pri = 5;
        try {
            pri = Rules.parseLegacy(job.configLine, " | ", "|||||").priSeq;
        } catch (RuntimeException ignored) {
        }
        String la = base(job.fileA), lb = base(job.fileB);
        String logical = isGzipName(la) ? logicalName(la)
                : isGzipName(lb) ? logicalName(lb) : la;
        return new Group(pri, logical, job,
                isGzipName(la) ? versions(job.fileA) : List.of(),
                targetFor(job, job.fileA),
                isGzipName(lb) ? versions(job.fileB) : List.of(),
                targetFor(job, job.fileB));
    }

    /** 同逻辑名全部版本（同目录，文件名升序——最新版本最后解压覆盖生效）。 */
    private List<Path> versions(String anySource) {
        Path p = Path.of(anySource);
        String logical = logicalName(p.getFileName().toString());
        List<Path> out = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(p.getParent())) {
            for (Path f : ds) {
                String n = f.getFileName().toString();
                if (Files.isRegularFile(f) && isGzipName(n) && logicalName(n).equals(logical)) out.add(f);
            }
        } catch (IOException ignored) {
        }
        out.sort(java.util.Comparator.comparing(x -> x.getFileName().toString()));
        return out;
    }

    /** 动态补位：维持 已解压待比 + 解压中 < 预解压数量。 */
    private void refill() {
        int limit = Math.max(1, cfg.engine().preExtractCount());
        while ((int) extracted.mappingCount() + inFlight.get() < limit) {
            Group g = queue.poll();
            if (g == null) return;
            queued.remove(g.job().id);
            if (!JobRecord.PENDING.equals(g.job().status)) continue; // 已被终止/删除
            inFlight.incrementAndGet();
            extractPool.submit(() -> doExtract(g));
        }
    }

    private void doExtract(Group g) {
        try {
            extractSide(g.sourcesA(), g.targetA());
            extractSide(g.sourcesB(), g.targetB());
            extracted.put(g.job().id, Boolean.TRUE);
            jobs.submit(g.job()); // 已解压：submit 经 extracted 表放行进入对比池
        } catch (Exception e) {
            failGroup(g, e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 逐版本解压覆盖：gunzip 到临时产物，原子改名到逻辑名。 */
    private void extractSide(List<Path> sources, Path target) throws IOException {
        if (sources == null || sources.isEmpty()) return;
        Files.createDirectories(target.getParent());
        for (Path src : sources) {
            extractOne(src, target);
        }
    }

    private void extractOne(Path src, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmpOut = target.resolveSibling(target.getFileName() + ".extracting");
        try (var in = new GZIPInputStream(Files.newInputStream(src));
             var fos = Files.newOutputStream(tmpOut, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        try {
            Files.move(tmpOut, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmpOut, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 解压失败：作业 FAILED（附具体文件名），计入批次 extract_failed 清单，触发补位。 */
    private void failGroup(Group g, Exception e) {
        JobRecord job = g.job();
        String src = !g.sourcesA().isEmpty() ? base(job.fileA)
                : !g.sourcesB().isEmpty() ? base(job.fileB) : g.logicalName();
        String reason = e.getMessage() == null ? e.toString() : e.getMessage();
        synchronized (store) {
            JobRecord fresh = store.getJob(job.id);
            if (fresh != null && JobRecord.PENDING.equals(fresh.status)) {
                fresh.status = JobRecord.FAILED;
                fresh.error = "解压失败: " + src + " (" + reason + ")";
                fresh.finishedAt = System.currentTimeMillis() / 1000;
                store.saveJob(fresh);
            }
        }
        if (job.batchId != null) {
            synchronized (batchLock) {
                BatchRecord latest = store.getBatch(job.batchId);
                if (latest != null) {
                    if (latest.extractFailed == null) latest.extractFailed = new ArrayList<>();
                    String entry = src + "（" + reason + "）";
                    if (!latest.extractFailed.contains(entry)) latest.extractFailed.add(entry);
                    store.saveBatch(latest);
                }
            }
        }
        refill();
    }

    private Path extractRoot() {
        String dir = cfg.engine().extractDir();
        Path p = Path.of(dir);
        return p.isAbsolute() ? p : paths.baseDir().resolve(p);
    }

    private Path targetFor(JobRecord job, String path) {
        String side = path != null && path.equals(job.fileB) ? "B" : "A";
        String name = base(path);
        String logical = isGzipName(name) ? logicalName(name) : name;
        return extractRoot().resolve(job.batchId == null ? "standalone" : job.batchId)
                .resolve(side).resolve(logical);
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String base(String path) {
        return path == null ? "" : Path.of(path).getFileName().toString();
    }

    @Override public void close() {
        scheduler.shutdownNow();
        extractPool.shutdownNow();
    }
}
