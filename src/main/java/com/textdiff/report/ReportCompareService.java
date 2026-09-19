package com.textdiff.report;

import com.textdiff.engine.Encoding;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Rules;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.FieldMapStore;
import com.textdiff.store.FieldMaps;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 报表对比服务：基于 header 模板的报表核对批次（【报表对比】界面）。
 *
 * 提交：模板目录 + 数据文本目录 A/B，按文件名配对非 .header 文件；模板 = {@code <文件名去扩展名>.header}
 * （模板目录优先，其次数据目录旁）；昵称取列名映射（支持 {@code #BANKNO} 类占位符按通配匹配）。
 * 运行：解析分区 → 排序对比 → result.jsonl（表头/表尾/业务三分区 RowDiff 流）+ report_summary.json
 * + meta.json（复用作业状态机与批次页统计）。不走文件对比的 KEYSEQ 引擎与 AI 分析链路。
 */
public final class ReportCompareService implements AutoCloseable {
    public static final String JOB_TYPE = "report";
    public static final String SUMMARY_JSON = "report_summary.json";

    private final JobStore store;
    private final Path resultsRoot;
    private final FieldMapStore fieldMaps; // 可空
    private final ExecutorService pool;
    private final ConcurrentMap<String, Future<?>> tasks = new ConcurrentHashMap<>();

    public ReportCompareService(JobStore store, Path resultsRoot, FieldMapStore fieldMaps, int threads) {
        this.store = store;
        this.resultsRoot = resultsRoot;
        this.fieldMaps = fieldMaps;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "report-runner");
            t.setDaemon(true);
            return t;
        });
        requeueStuckJobs();
    }

    /**
     * 提交批次：按文件名配对 dirA/dirB 的非 .header 文件。
     * keySeq/omitSeq = 1-based 列序串（如 "3/4/5"，空串 = 不配置）；主键空 = 整行对比。
     */
    public BatchRecord submit(Path templateDir, Path dirA, Path dirB, String label,
                              String keySeq, String omitSeq) throws IOException {
        if (dirA == null || dirB == null || !Files.isDirectory(dirA) || !Files.isDirectory(dirB)) {
            throw new IllegalArgumentException("数据文本路径 A/B 必须是已存在的目录");
        }
        // 序号在提交时解析校验（非法列序直接拒绝），持久化为 KEYSEQ/OMITSEQ 配置令牌
        String keyToken = parseSeqToken(keySeq, "主键栏位");
        String omitToken = parseSeqToken(omitSeq, "跳过栏位");
        List<String> namesA = listDataFiles(dirA);
        Map<String, Path> bFiles = dataFiles(dirB);
        String absA = dirA.toAbsolutePath().normalize().toString();
        String absB = dirB.toAbsolutePath().normalize().toString();

        BatchRecord batch = new BatchRecord(newId(),
                label == null || label.isBlank() ? "报表对比" : label.strip(),
                absA, absB,
                "模板: " + (templateDir == null ? "（随数据目录）" : templateDir.toAbsolutePath().normalize()),
                0);
        batch.batchType = JOB_TYPE;
        batch.templateDir = templateDir == null ? ""
                : templateDir.toAbsolutePath().normalize().toString();

        List<JobRecord> jobs = new ArrayList<>();
        List<String> unpaired = new ArrayList<>();
        for (String name : namesA) {
            Path fb = bFiles.get(name);
            if (fb == null) {
                unpaired.add(name + "（B 侧无同名文件）");
                continue;
            }
            Path tpl = resolveTemplate(templateDir, dirA, name);
            String nick = resolveNickname(name);
            String configLine = nick + ":" + name
                    + (keyToken.isEmpty() ? "" : ":KEYSEQ=" + keyToken)
                    + (omitToken.isEmpty() ? "" : ":OMITSEQ=" + omitToken);
            JobRecord job = new JobRecord(newId(), batch.id, nick + " · " + name,
                    configLine,
                    Path.of(dirA.toString(), name).toAbsolutePath().normalize().toString(),
                    fb.toAbsolutePath().normalize().toString(),
                    resultsRoot.resolve(newId()).toAbsolutePath().toString());
            job.jobType = JOB_TYPE;
            job.templateFile = tpl == null ? "" : tpl.toAbsolutePath().normalize().toString();
            jobs.add(job);
        }
        batch.noRuleFiles = unpaired;
        batch.jobCount = jobs.size();
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("两目录无同名报表文件可配对（A 目录非 header 文件 "
                    + namesA.size() + " 个，均未在 B 目录找到同名文件）");
        }
        store.saveBatch(batch);
        for (JobRecord job : jobs) {
            store.saveJob(job);
            submit(job);
        }
        return batch;
    }

    /** "3/4/5" 序列校验并原样返回（1-based，供 configLine KEYSEQ/OMITSEQ 令牌）；空输入返回空串。 */
    private static String parseSeqToken(String seq, String what) {
        if (seq == null || seq.isBlank()) return "";
        Rules.parseSeq(seq); // 校验（非法抛 IllegalArgumentException）
        return seq.strip();
    }

    public synchronized void submit(JobRecord job) {
        if (job == null || !JOB_TYPE.equals(job.jobType)) return;
        tasks.putIfAbsent(job.id, pool.submit(() -> run(job.id)));
    }

    /** 作业执行入口（状态机 pending→running→done/failed；由 JobManager.submit 路由或本服务重启恢复调用）。 */
    public void run(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null || !JobRecord.PENDING.equals(job.status)) return;
        job.status = JobRecord.RUNNING;
        job.startedAt = System.currentTimeMillis() / 1000;
        job.error = null;
        store.saveJob(job);
        String encA = "auto", encB = "auto";
        try {
            Path fa = Path.of(job.fileA);
            Path fb = Path.of(job.fileB);
            encA = Encoding.resolveEncoding(fa, "auto", "|");
            encB = Encoding.resolveEncoding(fb, "auto", "|");
            List<String> la = readLines(fa, encA);
            List<String> lb = readLines(fb, encB);

            ReportParser.ParsedReport pa;
            ReportParser.ParsedReport pb;
            List<String> fieldNames;
            List<Integer> keyColumns = List.of();
            java.util.Set<Integer> omitColumns = java.util.Set.of();
            try {
                // 报表配置令牌（KEYSEQ/OMITSEQ）从 configLine 解析；主键未配置 = 整行对比
                com.textdiff.engine.CompareConfig cfg =
                        com.textdiff.engine.Rules.parseLegacy(job.configLine, "|", "|||||");
                keyColumns = cfg.keyColumns;
                omitColumns = cfg.skipSet();
            } catch (RuntimeException ignored) {
                // 旧作业 configLine 为 昵称:文件名，解析失败按缺省整行对比
            }
            if (ReportParser.hasControlLines(la) || ReportParser.hasControlLines(lb)) {
                // 控制行版式（1@OD@|...，含折行/分页报表）：自分区，模板不参与
                pa = ReportParser.parseControlFormat(la);
                pb = ReportParser.parseControlFormat(lb);
                fieldNames = !pa.columnNames().isEmpty() ? pa.columnNames() : pb.columnNames();
            } else {
                Path tplFile = Path.of(job.templateFile == null ? "" : job.templateFile);
                if (job.templateFile == null || job.templateFile.isBlank() || !Files.isRegularFile(tplFile)) {
                    throw new IllegalArgumentException("模板不存在：请在模板路径或数据目录下提供 "
                            + stem(fileName(job.fileA)) + ".header");
                }
                List<String> tpl = readLines(tplFile, Encoding.resolveEncoding(tplFile, "auto", "|"));
                pa = ReportParser.parse(tpl, la);
                pb = ReportParser.parse(tpl, lb);
                fieldNames = fieldNamesFor(job);
            }
            ReportComparator.Result result =
                    ReportComparator.compare(pa, pb, fieldNames, keyColumns, omitColumns);

            Path dir = Path.of(job.resultDir);
            ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(dir.resolve(ResultFiles.RESULT_JSONL));
            try (sink) {
                for (RowDiff r : result.headerRows()) sink.addRow(r);
                for (RowDiff r : result.footerRows()) sink.addRow(r);
                for (RowDiff r : result.dataRows()) sink.addRow(r);
            }
            ReportSummary s = result.summary();
            writeJson(dir.resolve(SUMMARY_JSON), s);
            JobMeta meta = ResultFiles.buildMeta(job, encA, encB, null);
            meta.totalA = s.rowCountA;
            meta.totalB = s.rowCountB;
            meta.equal = s.equal;
            meta.diff = s.partial;
            meta.onlyA = s.onlyA;
            meta.onlyB = s.onlyB;
            meta.zoneEqual = sink.zoneEqual;
            meta.zoneDiff = sink.zoneDiff;
            meta.zoneUnmatched = sink.zoneUnmatched;
            ResultFiles.writeMeta(dir, meta);

            JobRecord cur = store.getJob(jobId);
            if (cur != null && JobRecord.STOPPED.equals(cur.status)) return; // 用户终止优先
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
        ReportSummary s = readSummary(Path.of(job.resultDir));
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

    public ReportSummary readSummary(Path resultDir) {
        try {
            return Json.read(Files.readString(resultDir.resolve(SUMMARY_JSON)),
                    ReportSummary.class);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("报表摘要读取失败", e);
        }
    }

    // ---- 提交辅助 ----

    private static List<String> listDataFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.toLowerCase().endsWith(".header"))
                    .sorted().toList();
        }
    }

    private static Map<String, Path> dataFiles(Path dir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().toLowerCase().endsWith(".header"))
                    .forEach(p -> out.putIfAbsent(p.getFileName().toString(), p));
        }
        return out;
    }

    /**
     * 模板解析：模板路径可以是<b>目录</b>（取 {@code <stem>.header}，模板目录优先、数据目录 A 旁兜底），
     * 也可以是<b>单个 .header 文件</b>（按文件名茎与报表匹配才适用，避免错用模板）。
     */
    static Path resolveTemplate(Path templatePath, Path dirA, String fileName) {
        String stem = stem(fileName);
        if (templatePath != null && Files.isRegularFile(templatePath)) {
            String tplStem = stem(templatePath.getFileName().toString());
            return tplStem.equalsIgnoreCase(stem) ? templatePath : null;
        }
        List<Path> candidates = new ArrayList<>();
        if (templatePath != null) candidates.add(Path.of(templatePath.toString(), stem + ".header"));
        candidates.add(Path.of(dirA.toString(), stem + ".header"));
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    /** 昵称：列名映射（含 #占位符通配）优先，回落文件名去扩展名。 */
    private String resolveNickname(String fileName) {
        if (fieldMaps != null) {
            String id = reportIdForReportFile(fileName);
            if (id != null) return id;
        }
        return stem(fileName);
    }

    /** 列名映射查询：精确/茎匹配 → #占位符转通配（{@code 01.CORD900U.#BANKNO} → {@code 01.CORD900U.*}）。 */
    String reportIdForReportFile(String fileName) {
        String stem = stem(fileName);
        for (FieldMaps.ReportType t : fieldMaps.allTypes()) {
            if (t.reportFileName() != null && t.reportFileName().equalsIgnoreCase(fileName)) return t.reportId();
        }
        for (FieldMaps.ReportType t : fieldMaps.allTypes()) {
            if (t.reportFileName() != null
                    && stripExt(t.reportFileName()).equalsIgnoreCase(stem)) return t.reportId();
        }
        for (FieldMaps.ReportType t : fieldMaps.allTypes()) {
            String glob = toGlob(t.reportFileName());
            if (glob != null && (FieldMapStore.globMatches(glob, fileName)
                    || FieldMapStore.globMatches(glob, stem))) return t.reportId();
        }
        return null;
    }

    static String toGlob(String pattern) {
        return pattern == null || pattern.isBlank() ? null : pattern.replaceAll("#[A-Za-z0-9_]+", "*");
    }

    /** 业务行栏位名（铺底映射，供差异展示参考）：非空白、非 SPACE 填充列，按列序去重。 */
    private List<String> fieldNamesFor(JobRecord job) {
        if (fieldMaps == null) return List.of();
        String id = reportIdForReportFile(fileName(job.fileA));
        if (id == null) return List.of();
        List<String> out = new ArrayList<>();
        TreeSet<String> seen = new TreeSet<>();
        for (FieldMaps.ReportField f : fieldMaps.fieldsFor(id)) {
            String n = f.fieldName();
            if (n == null || n.isBlank()) continue;
            String up = n.toUpperCase();
            if (up.startsWith("SPACE")) continue;
            if (seen.add(n)) out.add(n);
        }
        return out;
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

    static String fileName(String path) {
        return Path.of(path == null ? "file" : path).getFileName().toString();
    }

    /** 文件名去最后一个扩展名：01.CORD900U.105 → 01.CORD900U。 */
    static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot <= 0 ? n : n.substring(0, dot);
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 测试/优雅关闭辅助：等待队列清空。 */
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