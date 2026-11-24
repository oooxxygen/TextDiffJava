package com.textdiff.textline;

import com.textdiff.engine.Encoding;
import com.textdiff.engine.RowDiff;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.Json;
import com.textdiff.store.ResultFiles;
import com.textdiff.store.TextLineSavedConfig;
import com.textdiff.task.ExtractService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * 普通文本比对服务（【普通文本比对】界面）：按目录比对方式指定文本存放——
 * 路径 A/B 各可为目录（目录下文本按统一方式比对：同名/同逻辑名配对，不限制具体文件名）
 * 或单个文件（按指定文件对比）。压缩文件（*.gz[.九位数字后缀]）剥 YYYYMMDD+一位增量标识
 * 后按逻辑名配对并解压参与对比。
 *
 * 对比主键：每行指定字符位置区间提取的字符串（未配置取整行），命中两侧整行逐字核对；
 * 结果行分区 section=line。不走 AI 分析链路（作业类型 textline 在任务管理器中显式跳过）。
 */
public final class TextLineCompareService implements AutoCloseable {
    public static final String JOB_TYPE = "textline";
    public static final String SUMMARY_JSON = "textline_summary.json";

    private final JobStore store;
    private final Path resultsRoot;
    private final ExecutorService pool;
    private final ConcurrentMap<String, Future<?>> tasks = new ConcurrentHashMap<>();

    public TextLineCompareService(JobStore store, Path resultsRoot, int threads) {
        this.store = store;
        this.resultsRoot = resultsRoot;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "textline-runner");
            t.setDaemon(true);
            return t;
        });
        requeueStuckJobs();
    }

    /** 提交批次：路径 A/B 各可为目录（按逻辑名配对全部文本）或单个文件。 */
    public BatchRecord submit(Path pathA, Path pathB, String label, TextLineFormat cfg,
                              String sourceA, String sourceB) throws IOException {
        return submit(pathA, pathB, label, cfg, null, sourceA, sourceB);
    }

    /** 提交批次（带文件名正则筛选）：目录模式下仅逻辑名命中该式的文本纳入核对。 */
    public BatchRecord submit(Path pathA, Path pathB, String label, TextLineFormat cfg,
                              String fileRegex, String sourceA, String sourceB) throws IOException {
        if (pathA == null || pathB == null || !Files.exists(pathA) || !Files.exists(pathB)) {
            throw new IllegalArgumentException("文本路径 A/B 必须是已存在的目录或文件");
        }
        java.util.regex.Pattern filter = compileFilter(fileRegex);
        Pairing pairing = listPairs(pathA, pathB, n -> filter == null || filter.matcher(n).matches());
        if (pairing.pairs().isEmpty()) {
            throw new IllegalArgumentException("两路径下无可配对的同名文本"
                    + (filter != null ? "（文件名正则筛选后为空）" : ""));
        }
        String absA = normalize(pathA);
        String absB = normalize(pathB);
        BatchRecord batch = new BatchRecord(newId(),
                label == null || label.isBlank() ? "普通文本比对" : label.strip(),
                absA, absB, cfg.toConfigLine(), 0);
        batch.batchType = JOB_TYPE;
        batch.templateDir = cfg.toConfigLine();
        batch.noRuleFiles = pairing.unpaired();
        String configLine = cfg.toConfigLine(sourceA, sourceB);

        List<JobRecord> jobs = new ArrayList<>();
        for (String[] p : pairing.pairs()) { // p = {logicalName, fileA, fileB}
            JobRecord job = new JobRecord(newId(), batch.id,
                    p[0] + " · 整行比对", configLine, p[1], p[2],
                    resultsRoot.resolve(newId()).toAbsolutePath().toString());
            job.jobType = JOB_TYPE;
            jobs.add(job);
        }
        return finishBatch(batch, jobs);
    }

    /**
     * 按配置组提交：组内配置按落库顺序求值，文本逻辑名命中「首个文件名正则匹配（空式=全部）
     * 的配置」即按该配置建作业；两侧同名且命中同一配置才配对，未命中任何配置的文本
     * 计入批次「未纳入核对」清单（不报错、不建作业）。
     */
    public BatchRecord submitGroup(Path pathA, Path pathB, String label,
                                   List<TextLineSavedConfig> configs) throws IOException {
        if (pathA == null || pathB == null || !Files.exists(pathA) || !Files.exists(pathB)) {
            throw new IllegalArgumentException("文本路径 A/B 必须是已存在的目录或文件");
        }
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("配置组为空");
        }
        // 组内正则求值顺序 = 落库顺序（首条命中即归属）
        Map<String, TextLineSavedConfig> nameToCfg = new LinkedHashMap<>();
        java.util.Set<String> unmatched = new java.util.TreeSet<>();
        for (boolean isA : new boolean[]{true, false}) {
            for (Map.Entry<String, Path> e : logicalMap(isA ? pathA : pathB).entrySet()) {
                TextLineSavedConfig hit = null;
                for (TextLineSavedConfig c : configs) {
                    if (c.matches(e.getKey())) { hit = c; break; }
                }
                if (hit == null) {
                    unmatched.add((isA ? "仅A有: " : "仅B有: ") + e.getKey() + "（不匹配任何配置）");
                } else {
                    nameToCfg.putIfAbsent(e.getKey(), hit);
                }
            }
        }
        Map<String, Path> filesA = logicalMap(pathA);
        Map<String, Path> filesB = logicalMap(pathB);
        List<String[]> pairs = new ArrayList<>();
        List<String> unpaired = new ArrayList<>(unmatched);
        for (Map.Entry<String, TextLineSavedConfig> e : nameToCfg.entrySet()) {
            Path fa = filesA.get(e.getKey());
            Path fb = filesB.get(e.getKey());
            if (fa == null) {
                unpaired.add("仅B有: " + e.getKey() + "（配置 " + e.getValue().nickname() + "）");
            } else if (fb == null) {
                unpaired.add("仅A有: " + e.getKey() + "（配置 " + e.getValue().nickname() + "）");
            } else {
                pairs.add(new String[]{e.getKey(), fa.toAbsolutePath().normalize().toString(),
                        fb.toAbsolutePath().normalize().toString()});
            }
        }
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("配置组内无任何配置命中可配对的同名文本");
        }
        String groupName = configs.get(0).groupName();
        BatchRecord batch = new BatchRecord(newId(),
                label == null || label.isBlank() ? "普通文本比对 · 配置组" + groupName : label.strip(),
                normalize(pathA), normalize(pathB), "配置组: " + groupName, 0);
        batch.batchType = JOB_TYPE;
        batch.templateDir = "配置组: " + groupName;
        batch.noRuleFiles = unpaired;

        List<JobRecord> jobs = new ArrayList<>();
        for (String[] p : pairs) {
            TextLineSavedConfig c = nameToCfg.get(p[0]);
            TextLineFormat cfg = new TextLineFormat(c.keyPositions(), c.encodingA(), c.encodingB());
            String configLine = cfg.toConfigLine(c.sourceA(), c.sourceB());
            JobRecord job = new JobRecord(newId(), batch.id,
                    p[0] + " · 整行比对", configLine, p[1], p[2],
                    resultsRoot.resolve(newId()).toAbsolutePath().toString());
            job.jobType = JOB_TYPE;
            job.label = c.nickname();
            jobs.add(job);
        }
        return finishBatch(batch, jobs);
    }

    /** 批次建档 + 逐作业派发（单配置/配置组共用收尾）。 */
    private BatchRecord finishBatch(BatchRecord batch, List<JobRecord> jobs) throws IOException {
        batch.jobCount = jobs.size();
        store.saveBatch(batch);
        for (JobRecord job : jobs) {
            store.saveJob(job);
            submit(job);
        }
        return batch;
    }

    /** 文件名正则编译（空式 = 不过滤；非法式抛 400 级参数错误；委托命名配置的通配兜底逻辑）。 */
    public static java.util.regex.Pattern compileFilter(String fileRegex) {
        return com.textdiff.store.TextLineSavedConfig.compileFileFilter(fileRegex);
    }

    /** 配对结果：命中对（逻辑名/A路径/B路径）+ 未纳入核对清单（带侧别说明）。 */
    record Pairing(List<String[]> pairs, List<String> unpaired) {}

    /**
     * 配对：两侧均按逻辑名（压缩文件剥 .gz/.九位数字后缀）取交集；同一逻辑名同时存在
     * 普通文件与压缩版本时普通文件优先，多版本压缩件取文件名最大（最新）者。单文件路径只配该文件。
     * nameFilter 非空时仅逻辑名命中的文本参与（两侧同规则）；落选与单侧缺失计入未纳入核对清单。
     */
    private static Pairing listPairs(Path pathA, Path pathB, java.util.function.Predicate<String> nameFilter)
            throws IOException {
        Map<String, Path> filesA = logicalMap(pathA);
        Map<String, Path> filesB = logicalMap(pathB);
        List<String[]> out = new ArrayList<>();
        List<String> unpaired = new ArrayList<>();
        for (Map.Entry<String, Path> e : filesA.entrySet()) {
            String name = e.getKey();
            if (nameFilter != null && !nameFilter.test(name)) {
                unpaired.add("仅A有: " + name + "（不匹配筛选条件）");
                continue;
            }
            Path fb = filesB.get(name);
            if (fb != null) {
                out.add(new String[]{name, e.getValue().toAbsolutePath().normalize().toString(),
                        fb.toAbsolutePath().normalize().toString()});
            } else {
                unpaired.add("仅A有: " + name + "（B 侧无同名文本）");
            }
        }
        for (Map.Entry<String, Path> e : filesB.entrySet()) {
            String name = e.getKey();
            if (nameFilter != null && !nameFilter.test(name)) {
                unpaired.add("仅B有: " + name + "（不匹配筛选条件）");
            } else if (!filesA.containsKey(name)) {
                unpaired.add("仅B有: " + name + "（A 侧无同名文本）");
            }
        }
        return new Pairing(out, unpaired);
    }

    /** 逻辑名 → 实体路径（单文件：逻辑名=该文件逻辑名；目录：逐文件映射，普通优先、多版本取最新）。 */
    private static Map<String, Path> logicalMap(Path path) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (Files.isRegularFile(path)) {
            Path f = path.toAbsolutePath().normalize();
            out.put(logicalOf(f), f);
            return out;
        }
        try (Stream<Path> s = Files.list(path)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String logical = logicalOf(p);
                Path prev = out.get(logical);
                if (prev != null && !isGz(prev) && isGz(p)) continue; // 普通优先
                if (prev != null && isGz(prev) && isGz(p)
                        && p.getFileName().toString().compareTo(prev.getFileName().toString()) < 0) {
                    continue; // 多版本压缩件：文件名升序遍历，保留最大（最新）
                }
                out.put(logical, p);
            }
        }
        return out;
    }

    private static boolean isGz(Path p) {
        return ExtractService.isGzipName(p.getFileName().toString());
    }

    private static String logicalOf(Path p) {
        String n = p.getFileName().toString();
        return ExtractService.isGzipName(n) ? ExtractService.logicalName(n) : n;
    }

    public synchronized void submit(JobRecord job) {
        if (job == null || !JOB_TYPE.equals(job.jobType)) return;
        tasks.putIfAbsent(job.id, pool.submit(() -> run(job.id)));
    }

    /** 作业执行入口（状态机与自定义格式对比服务一致；不设 aiStatus，不触发任务衍生）。 */
    public void run(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null || !JobRecord.PENDING.equals(job.status)) return;
        job.status = JobRecord.RUNNING;
        job.startedAt = System.currentTimeMillis() / 1000;
        job.error = null;
        store.saveJob(job);
        String encA = "auto", encB = "auto";
        try {
            TextLineFormat cfg = TextLineFormat.fromConfigLine(job.configLine);
            if (cfg == null) throw new IllegalArgumentException("作业配置缺失或无效");
            Path dir = Path.of(job.resultDir);
            Path tmp = Files.createDirectories(dir.resolve("tmp"));
            Path fa = resolveInput(Path.of(job.fileA), tmp);
            Path fb = resolveInput(Path.of(job.fileB), tmp);
            encA = Encoding.resolveEncoding(fa, cfg.encodingA(), "|");
            encB = Encoding.resolveEncoding(fb, cfg.encodingB(), "|");
            List<String> la = readLines(fa, encA);
            List<String> lb = readLines(fb, encB);

            TextLineCompareEngine.Result result = TextLineCompareEngine.compare(la, lb, cfg);

            ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(dir.resolve(ResultFiles.RESULT_JSONL));
            try (sink) {
                for (RowDiff r : result.rows()) sink.addRow(r);
            }
            writeJson(dir.resolve(SUMMARY_JSON), result.summary());
            JobMeta meta = ResultFiles.buildMeta(job, encA, encB, null);
            meta.totalA = result.summary().totalA;
            meta.totalB = result.summary().totalB;
            meta.equal = result.summary().equal;
            meta.diff = result.summary().diff;
            meta.onlyA = result.summary().onlyA;
            meta.onlyB = result.summary().onlyB;
            meta.zoneEqual = sink.zoneEqual;
            meta.zoneDiff = sink.zoneDiff;
            meta.zoneUnmatched = sink.zoneUnmatched;
            ResultFiles.writeMeta(dir, meta);

            JobRecord cur = store.getJob(jobId);
            if (cur != null && JobRecord.STOPPED.equals(cur.status)) return;
            // 统计快照落作业记录：作业列表/批次页免逐作业读 meta（与通用对比一致；custom/report 未做）
            job.diffCount = result.summary().diff;
            job.equalCount = result.summary().equal;
            job.totalA = result.summary().totalA;
            job.totalB = result.summary().totalB;
            job.onlyACount = result.summary().onlyA;
            job.onlyBCount = result.summary().onlyB;
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

    /** 运行时输入解析：压缩文件解压到作业 tmp（解压后按解压产物读编码），普通文件原样返回。 */
    private static Path resolveInput(Path src, Path tmp) throws IOException {
        if (!isGz(src)) return src;
        Path target = tmp.resolve(logicalOf(src));
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
        return target;
    }

    /** 单作业摘要（summary 端点返回体）。 */
    public Map<String, Object> summaryView(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null) return null;
        TextLineSummary s = readSummary(Path.of(job.resultDir));
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
        // configLine（JSON）里的两侧命名（srca/srcb 附加键），缺省 A/B
        String[] src = srcNames(job.configLine);
        out.put("source_a", src[0]);
        out.put("source_b", src[1]);
        return out;
    }

    private static String[] srcNames(String configLine) {
        String a = "A", b = "B";
        try {
            Map<String, String> m = Json.read(configLine == null ? "{}" : configLine, Map.class);
            if (m != null) {
                String va = m.get("srca");
                String vb = m.get("srcb");
                if (va != null && !va.isBlank()) a = va.strip();
                if (vb != null && !vb.isBlank()) b = vb.strip();
            }
        } catch (RuntimeException ignored) {
            // 无命名信息按默认处理
        }
        return new String[]{a, b};
    }

    public TextLineSummary readSummary(Path resultDir) {
        try {
            return Json.read(Files.readString(resultDir.resolve(SUMMARY_JSON)), TextLineSummary.class);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("普通文本比对摘要读取失败", e);
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
