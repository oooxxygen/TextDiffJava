package com.textdiff.report;

import com.textdiff.engine.CompareConfig;
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
    private final Path configsDir; // conf 规则目录（主键/跳过栏位来源，可空 = 不启用）
    private final FieldMapStore fieldMaps; // 可空
    private final ExecutorService pool;
    private final ConcurrentMap<String, Future<?>> tasks = new ConcurrentHashMap<>();

    public ReportCompareService(JobStore store, Path resultsRoot, Path configsDir,
                                FieldMapStore fieldMaps, int threads) {
        this.store = store;
        this.resultsRoot = resultsRoot;
        this.configsDir = configsDir;
        this.fieldMaps = fieldMaps;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "report-runner");
            t.setDaemon(true);
            return t;
        });
        requeueStuckJobs();
    }

    /** 对比并发度热更新：报表线程池运行时重调大小（设置页保存即生效）。缩容先降 core，扩容先升 max。 */
    public void resizePool(int threads) {
        int n = Math.max(1, threads);
        if (pool instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            if (n < tpe.getCorePoolSize()) {
                tpe.setCorePoolSize(n);
                tpe.setMaximumPoolSize(n);
            } else {
                tpe.setMaximumPoolSize(n);
                tpe.setCorePoolSize(n);
            }
        }
    }

    /**
     * 提交批次：按文件名配对 dirA/dirB 的非 .header 文件。
     * 主键/跳过栏位不接收提交参数，一律按昵称从 conf 规则（configs/{nickname}.conf 的 KEYSEQ/OMITSEQ）读取；
     * conf 未命中或未配置主键 = 整行对比。
     */
    public BatchRecord submit(Path templateDir, Path dirA, Path dirB, String label) throws IOException {
        return submit(templateDir, dirA, dirB, label, null, null);
    }

    /**
     * 提交批次（可带 A/B 来源命名）：sourceA/sourceB 非空且非默认 A/B 时注入各作业
     * configLine SRCA/SRCB 令牌，供关注字段监测表头与批次总结报告沿用启动时命名。
     */
    public BatchRecord submit(Path templateDir, Path dirA, Path dirB, String label,
                              String sourceA, String sourceB) throws IOException {
        if (dirA == null || dirB == null || !Files.isDirectory(dirA) || !Files.isDirectory(dirB)) {
            throw new IllegalArgumentException("数据文本路径 A/B 必须是已存在的目录");
        }
        List<String> namesA = listDataFiles(dirA);
        Map<String, Path> bFiles = dataFiles(dirB);
        String absA = dirA.toAbsolutePath().normalize().toString();
        String absB = dirB.toAbsolutePath().normalize().toString();

        BatchRecord batch = new BatchRecord(newId(),
                label == null || label.isBlank() ? "报表对比" : label.strip(),
                absA, absB,
                "模板: " + (templateDir == null ? "（随数据目录）" : templateDir.toAbsolutePath().normalize())
                        + "；主键/跳过栏位取自 conf 规则（未命中按整行对比）",
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
            // conf 规则（按昵称精确匹配）取主键/跳过栏位令牌；未命中 = 空 = 整行对比
            String[] tokens = confKeyTokens(nick);
            String configLine = nick + ":" + name
                    + (tokens[0].isEmpty() ? "" : ":KEYSEQ=" + tokens[0])
                    + (tokens[1].isEmpty() ? "" : ":OMITSEQ=" + tokens[1])
                    + srcToken("SRCA", sourceA, "A")
                    + srcToken("SRCB", sourceB, "B");
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

    /** SRCA/SRCB 令牌：值非空且非默认侧名才注入。 */
    private static String srcToken(String key, String value, String dft) {
        if (value == null || value.isBlank() || value.strip().equals(dft)) return "";
        return ":" + key + "=" + value.strip();
    }

    /**
     * conf 规则（configs 目录，按昵称精确匹配）→ {KEYSEQ 令牌, OMITSEQ 令牌}（1-based，如 "3/4/5"）。
     * conf 目录不可用 / 规则不存在 / 解析失败 / 未配置主键 → {"", ""}（整行对比）。
     */    private String[] confKeyTokens(String nickname) {
        if (configsDir == null || nickname == null || nickname.isBlank()
                || !java.nio.file.Files.isDirectory(configsDir)) {
            return new String[]{"", ""};
        }
        try (Stream<Path> s = java.nio.file.Files.list(configsDir)) {
            for (Path p : s.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                String line = firstRule(java.nio.file.Files.readString(p));
                if (line == null) continue;
                try {
                    CompareConfig cfg = Rules.parseLegacy(line, "|", "|||||");
                    if (!nickname.strip().equals(cfg.nickname)) continue;
                    // 首个昵称命中的 conf 即生效；未配置 KEYSEQ = 整行对比
                    String key = cfg.keyColumns.isEmpty() ? "" : String.join("/",
                            cfg.keyColumns.stream().map(c -> String.valueOf(c + 1)).toList());
                    String omit = "";
                    java.util.Set<Integer> skip = cfg.skipSet();
                    if (!skip.isEmpty()) {
                        omit = String.join("/", skip.stream().sorted()
                                .map(c -> String.valueOf(c + 1)).toList());
                    }
                    return new String[]{key, omit};
                } catch (RuntimeException ignored) {
                    return new String[]{"", ""}; // 命中昵称但规则损坏 = 未配置
                }
            }
        } catch (IOException ignored) {
            // conf 目录读失败按未配置处理（整行对比）
        }
        return new String[]{"", ""};
    }

    /** conf 文本的首条有效规则行（跳过空行/注释）；无则 null。 */
    private static String firstRule(String text) {
        for (String line : text.split("\r?\n")) {
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#")) return line;
        }
        return null;
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
            ParsedPair parsed = parseReports(job, la, lb);
            pa = parsed.pa();
            pb = parsed.pb();
            fieldNames = parsed.fieldNames();
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

    /** 两侧报表解析结果（run 与关注字段采集共用同一分支决策）。 */
    record ParsedPair(ReportParser.ParsedReport pa, ReportParser.ParsedReport pb, List<String> fieldNames) {}

    /**
     * 按模板/控制行/兜底分支解析两侧报表；模板缺失抛 IllegalArgumentException。
     * 空行占位模板（表头骨架 + 5~10 连续空行报表体占位 + 表尾骨架）优先于控制行检测：
     * 用户指定按模板分区（控制行值两侧不同会导致控制行版式签名配对全失败）。
     */
    private ParsedPair parseReports(JobRecord job, List<String> la, List<String> lb) throws IOException {
        List<String> tpl = null;
        Path tplFile = Path.of(job.templateFile == null ? "" : job.templateFile);
        if (job.templateFile != null && !job.templateFile.isBlank() && Files.isRegularFile(tplFile)) {
            tpl = readLines(tplFile, Encoding.resolveEncoding(tplFile, "auto", "|"));
        }
        ReportParser.ParsedReport pa;
        ReportParser.ParsedReport pb;
        List<String> fieldNames;
        if (tpl != null && ReportParser.firstBlankRun(tpl, 5) != null) {
            pa = ReportParser.parse(tpl, la);
            pb = ReportParser.parse(tpl, lb);
            fieldNames = fieldNamesFor(job);
        } else if (ReportParser.hasControlLines(la) || ReportParser.hasControlLines(lb)) {
            // 控制行版式（1@OD@|...，含折行/分页报表）：自分区，模板不参与
            pa = ReportParser.parseControlFormat(la);
            pb = ReportParser.parseControlFormat(lb);
            fieldNames = !pa.columnNames().isEmpty() ? pa.columnNames() : pb.columnNames();
        } else {
            if (tpl == null) {
                throw new IllegalArgumentException("模板不存在：请在模板路径或数据目录下提供 "
                        + stem(fileName(job.fileA)) + ".header");
            }
            pa = ReportParser.parse(tpl, la);
            pb = ReportParser.parse(tpl, lb);
            fieldNames = fieldNamesFor(job);
        }
        return new ParsedPair(pa, pb, fieldNames);
    }

    /** 两侧业务行字段切分（关注字段采集用；折行记录已合并为单行业务行）。 */
    public record ReportSides(List<String[]> a, List<String[]> b) {}

    /** 关注字段采集：按与 run 相同分支重新解析两侧报表，返回业务行字段数组。 */
    public ReportSides monitorRows(JobRecord job) throws IOException {
        Path fa = Path.of(job.fileA);
        Path fb = Path.of(job.fileB);
        List<String> la = readLines(fa, Encoding.resolveEncoding(fa, "auto", "|"));
        List<String> lb = readLines(fb, Encoding.resolveEncoding(fb, "auto", "|"));
        ParsedPair p = parseReports(job, la, lb);
        List<String[]> a = new ArrayList<>();
        for (ReportParser.Row r : p.pa().rows()) a.add(r.fields());
        List<String[]> b = new ArrayList<>();
        for (ReportParser.Row r : p.pb().rows()) b.add(r.fields());
        return new ReportSides(a, b);
    }

    /** 单作业摘要（summary 端点返回体）。config = 本次对比配置（与普通对比同构）；conf = 当前 conf 规则状态。 */
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
        out.put("config", configView(job));
        return out;
    }

    /** 本次对比配置：昵称/文件名/主键跳过序列（来自提交时 conf 解析结果）+ conf 规则当前状态（重跑参考）。 */
    private Map<String, Object> configView(JobRecord job) {
        Map<String, Object> config = new LinkedHashMap<>();
        String nick = "";
        String keySeq = "";
        String omitSeq = "";
        try {
            CompareConfig cfg = Rules.parseLegacy(job.configLine, "|", "|||||");
            nick = cfg.nickname == null ? "" : cfg.nickname;
            keySeq = seq1based(cfg.keyColumns);
            omitSeq = skipSeq(cfg.skipSet());
        } catch (RuntimeException ignored) {
            // 旧作业 configLine 解析失败：保持空（默认为空）
        }
        if (nick.isEmpty()) nick = resolveNickname(fileName(job.fileA));
        String[] conf = confKeyTokens(nick);
        config.put("nickname", nick);
        config.put("file_name", fileName(job.fileA));
        config.put("key_seq", keySeq);
        config.put("omit_seq", omitSeq);
        config.put("conf_hit", !(conf[0].isEmpty() && conf[1].isEmpty()));
        config.put("conf_key_seq", conf[0]);
        config.put("conf_omit_seq", conf[1]);
        return config;
    }

    /** 0-based 主键列 → "3/5"（1-based 串）；空列 = 空串（配置未设置，整行对比）。 */
    private static String seq1based(List<Integer> cols) {
        if (cols == null || cols.isEmpty()) return "";
        return String.join("/", cols.stream().map(c -> String.valueOf(c + 1)).toList());
    }

    private static String skipSeq(java.util.Set<Integer> skip) {
        if (skip == null || skip.isEmpty()) return "";
        return String.join("/", skip.stream().sorted().map(c -> String.valueOf(c + 1)).toList());
    }

    /**
     * 就地重跑：清结果态重新执行（沿用 job_id，覆盖原结果文件）。
     * syncConf=true 时按昵称重查 conf 规则刷新 KEYSEQ/OMITSEQ（同步最新配置）；未命中 = 整行对比。
     */
    public synchronized void rerun(String jobId, boolean syncConf) {
        JobRecord job = store.getJob(jobId);
        if (job == null || !JOB_TYPE.equals(job.jobType)) {
            throw new IllegalArgumentException("报表对比作业不存在: " + jobId);
        }
        if (JobRecord.PENDING.equals(job.status) || JobRecord.RUNNING.equals(job.status)) {
            throw new IllegalStateException("作业进行中，不可重复触发");
        }
        if (syncConf) {
            String name = fileName(job.fileA);
            String nick = resolveNickname(name);
            String[] tokens = confKeyTokens(nick);
            job.configLine = nick + ":" + name
                    + (tokens[0].isEmpty() ? "" : ":KEYSEQ=" + tokens[0])
                    + (tokens[1].isEmpty() ? "" : ":OMITSEQ=" + tokens[1]);
        }
        job.status = JobRecord.PENDING;
        job.error = null;
        job.startedAt = 0;
        job.finishedAt = 0;
        store.saveJob(job);
        submit(job);
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