package com.textdiff.export;

import com.textdiff.engine.Encoding;
import com.textdiff.engine.Parser;
import com.textdiff.engine.Rules;
import com.textdiff.report.ReportCompareService;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.DiscardField;
import com.textdiff.store.DiscardFieldStore;
import com.textdiff.store.FieldMapStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 关注字段监测采集与 DISCARD_COLS 落盘（懒生成 + 缓存复用）。
 *
 * 批次完成后首次查看/下载时按昵称采集 A/B 两侧数据行监测栏位的去重取值
 * （复用各 runner 自己的解析原语：普通作业流式 Parser.dataRows；报表作业 ReportParser 分支），
 * 落盘 {@code results/{batchId}/export/DISCARD_COLS.csv}（UTF-8 BOM）+ DISCARD_COLS.json（视图缓存）。
 * 缓存失效：任一作业 finishedAt 晚于文件，或监测配置重新导入（imported_at 变化）；重跑自动重生成。
 */
public final class DiscardColsService {
    /** 每字段每侧去重取值上限（超出截断，防枚举外字段撑爆结果）。 */
    public static final int MAX_VALUES = 1000;
    public static final String CSV_NAME = "DISCARD_COLS.csv";
    public static final String CACHE_JSON = "DISCARD_COLS.json";
    /** 缓存结构版本：行内字段变化（如新增 matched）时递增，旧缓存自动失效重生成。 */
    static final int CACHE_SCHEMA = 2;

    private final JobStore store;
    private final DiscardFieldStore discardFields;
    private final FieldMapStore fieldMaps;
    private final ReportCompareService reportService; // 可空（未装配报表服务时报表作业跳过）
    private final Path resultsRoot;
    private final Object genLock = new Object();

    public DiscardColsService(JobStore store, DiscardFieldStore discardFields, FieldMapStore fieldMaps,
                              ReportCompareService reportService, Path resultsRoot) {
        this.store = store;
        this.discardFields = discardFields;
        this.fieldMaps = fieldMaps;
        this.reportService = reportService;
        this.resultsRoot = resultsRoot;
    }

    /** 批次视图（前端卡片/总结报告数据源）：pending（批次运行中）/ hit + rows / no_hit。 */
    public Map<String, Object> batchView(String batchId) {
        BatchRecord batch = store.getBatch(batchId);
        if (batch == null) return null;
        List<JobRecord> jobs = store.listJobs(batchId);
        boolean running = jobs.stream().anyMatch(j -> JobRecord.PENDING.equals(j.status)
                || JobRecord.RUNNING.equals(j.status));
        Map<String, Object> cached = loadCache(batchId, jobs);
        if (cached != null) return cached;
        if (running) {
            Map<String, Object> out = base("pending");
            out.put("batch_id", batchId);
            return out;
        }
        return generate(batchId, jobs);
    }

    /** 下载入口：确保缓存新鲜后返回 CSV 路径；无命中返回 null。 */
    public Path ensureCsv(String batchId) {
        BatchRecord batch = store.getBatch(batchId);
        if (batch == null) return null;
        List<JobRecord> jobs = store.listJobs(batchId);
        Map<String, Object> cached = loadCache(batchId, jobs);
        if (cached == null) {
            boolean running = jobs.stream().anyMatch(j -> JobRecord.PENDING.equals(j.status)
                    || JobRecord.RUNNING.equals(j.status));
            if (running) return null;
            Map<String, Object> view = generate(batchId, jobs);
            return Boolean.TRUE.equals(view.get("hit")) ? csvPath(batchId) : null;
        }
        return Boolean.TRUE.equals(cached.get("hit")) ? csvPath(batchId) : null;
    }

    /** 单作业视图（单文件/报表结果页小卡片）：免缓存直采。 */
    public Map<String, Object> jobView(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null) return null;
        List<DiscardField> fields = discardFields.forNickname(monitorKey(job));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job_id", job.id);
        out.put("nickname", monitorKey(job));
        if (fields.isEmpty()) {
            out.put("hit", false);
            return out;
        }
        out.put("hit", true);
        out.put("status", job.status);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (JobRecord.DONE.equals(job.status)) {
            collectJob(job, fields, rows);
        }
        out.put("rows", rows);
        return out;
    }

    // ---- 生成与缓存 ----

    private Map<String, Object> generate(String batchId, List<JobRecord> jobs) {
        synchronized (genLock) {
            List<Map<String, Object>> rows = new ArrayList<>();
            String srcA = null;
            String srcB = null;
            for (JobRecord job : jobs) {
                if (job.nickname == null || job.nickname.isBlank()) continue;
                if (srcA == null) srcA = srcOf(job, true);
                if (srcB == null) srcB = srcOf(job, false);
                List<DiscardField> fields = discardFields.forNickname(monitorKey(job));
                if (fields.isEmpty()) continue;
                if (JobRecord.DONE.equals(job.status)) {
                    collectJob(job, fields, rows);
                } else {
                    rows.addAll(coverageRows(job, fields, "作业未完成（状态: " + job.status + "）"));
                }
            }
            boolean hit = !rows.isEmpty();
            Map<String, Object> out = base(hit ? "hit" : "no_hit");
            out.put("batch_id", batchId);
            out.put("hit", hit);
            out.put("src_a", srcA == null ? "A" : srcA);
            out.put("src_b", srcB == null ? "B" : srcB);
            out.put("generated_at", System.currentTimeMillis() / 1000);
            out.put("rows", rows);
            Path dir = exportDir(batchId);
            if (hit) {
                writeCsv(dir.resolve(CSV_NAME), out);
                writeJsonCache(dir.resolve(CACHE_JSON), out, discardFields.lastImportedAt());
            } else {
                deleteQuietly(dir.resolve(CSV_NAME));
                deleteQuietly(dir.resolve(CACHE_JSON));
            }
            return out;
        }
    }

    /** 缓存读取：结构版本一致且未失效（作业 finishedAt / 监测配置 imported_at 均不晚于生成）才复用。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadCache(String batchId, List<JobRecord> jobs) {
        Path file = exportDir(batchId).resolve(CACHE_JSON);
        if (!Files.isRegularFile(file)) return null;
        try {
            Map<String, Object> cached = Json.read(Files.readString(file, StandardCharsets.UTF_8), Map.class);
            if (((Number) cached.getOrDefault("schema", 0)).intValue() != CACHE_SCHEMA) return null;
            long importedAt = ((Number) cached.getOrDefault("imported_at", 0L)).longValue();
            if (importedAt != discardFields.lastImportedAt()) return null;
            long maxFinished = jobs.stream().mapToLong(j -> j.finishedAt).max().orElse(0);
            if (maxFinished > 0 && file.toFile().lastModified() / 1000 < maxFinished) return null;
            return cached;
        } catch (RuntimeException | IOException e) {
            return null; // 缓存损坏按缺失处理，重生成
        }
    }

    private void writeJsonCache(Path file, Map<String, Object> view, long importedAt) {
        Map<String, Object> cached = new LinkedHashMap<>(view);
        cached.put("imported_at", importedAt);
        cached.put("schema", CACHE_SCHEMA);
        writeAtomicText(file, Json.write(cached));
    }

    private Path exportDir(String batchId) {
        return resultsRoot.resolve(batchId).resolve("export");
    }

    private Path csvPath(String batchId) {
        return exportDir(batchId).resolve(CSV_NAME);
    }

    // ---- 采集 ----

    /** 一个作业的监测行：按 runner 类型走对应解析原语，文件缺失/解析失败行保留并标注。 */
    private void collectJob(JobRecord job, List<DiscardField> fields, List<Map<String, Object>> rows) {
        String key = monitorKey(job);
        String group = groupOf(key);
        String fileName = fileName(job.fileA) != null ? fileName(job.fileA) : fileName(job.fileB);
        if ("custom".equals(job.jobType)) {
            rows.addAll(coverageRows(job, fields, "自定义格式段对比不参与栏位监测"));
            return;
        }
        Map<Integer, ValSet> valsA = new LinkedHashMap<>();
        Map<Integer, ValSet> valsB = new LinkedHashMap<>();
        for (DiscardField f : fields) {
            valsA.put(f.colSeq(), new ValSet());
            valsB.put(f.colSeq(), new ValSet());
        }
        String error = null;
        try {
            if ("report".equals(job.jobType)) {
                if (reportService == null) {
                    error = "报表服务未装配";
                } else {
                    ReportCompareService.ReportSides sides = reportService.monitorRows(job);
                    consumeRows(sides.a(), valsA);
                    consumeRows(sides.b(), valsB);
                }
            } else {
                consumeFile(job, true, valsA);
                consumeFile(job, false, valsB);
            }
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        for (DiscardField f : fields) {
            ValSet a = valsA.get(f.colSeq());
            ValSet b = valsB.get(f.colSeq());
            Map<String, Object> m = rowMap(key, f, group, fileName);
            m.put("values_a", a == null ? List.of() : a.values());
            m.put("values_b", b == null ? List.of() : b.values());
            m.put("truncated_a", a != null && a.truncated);
            m.put("truncated_b", b != null && b.truncated);
            m.put("file_missing", error != null);
            m.put("error", error);
            // 是否匹配：B 侧（新线）取值 ⊆ A 侧（旧线）取值（相等视为子集）；采集失败无法判定为 null
            m.put("matched", error != null ? null
                    : new java.util.HashSet<>(a == null ? List.of() : a.values())
                            .containsAll(b == null ? List.of() : b.values()));
            rows.add(m);
        }
    }

    private void consumeFile(JobRecord job, boolean sideA, Map<Integer, ValSet> vals) throws IOException {
        Path file = Path.of(sideA ? job.fileA : job.fileB);
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在: " + file);
        }
        com.textdiff.engine.CompareConfig cfg;
        try {
            cfg = Rules.parseLegacy(job.configLine == null ? "" : job.configLine, " | ", "|||||");
        } catch (RuntimeException e) {
            cfg = new com.textdiff.engine.CompareConfig();
        }
        String enc = Encoding.resolveEncoding(file, sideA ? cfg.encodingA : cfg.encodingB, cfg.delimiter);
        try (java.util.stream.Stream<String> lines = Encoding.iterLines(file, enc, cfg.delimiter)) {
            Parser.dataRows(lines, cfg.delimiter, cfg.trailerPrefix, new Parser.TrailerData())
                    .forEach(cols -> consumeCols(cols, vals));
        }
    }

    /** 报表/折行已切好的字段行流（rows = 每业务行 String[]）。 */
    private static void consumeRows(List<String[]> rows, Map<Integer, ValSet> vals) {
        for (String[] cols : rows) consumeCols(cols, vals);
    }

    private static void consumeCols(String[] cols, Map<Integer, ValSet> vals) {
        for (Map.Entry<Integer, ValSet> e : vals.entrySet()) {
            if (e.getValue().full()) continue;
            int idx = e.getKey() - 1;
            String v = idx < cols.length ? cols[idx].strip() : "";
            e.getValue().add(v);
        }
    }

    /** 作业未完成/不可采集时的占位行（保证监测覆盖可见）。 */
    private List<Map<String, Object>> coverageRows(JobRecord job, List<DiscardField> fields, String error) {
        String key = monitorKey(job);
        String group = groupOf(key);
        String fileName = fileName(job.fileA) != null ? fileName(job.fileA) : fileName(job.fileB);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DiscardField f : fields) {
            Map<String, Object> m = rowMap(key, f, group, fileName);
            m.put("values_a", List.of());
            m.put("values_b", List.of());
            m.put("truncated_a", false);
            m.put("truncated_b", false);
            m.put("file_missing", true);
            m.put("error", error);
            m.put("matched", null);
            rows.add(m);
        }
        return rows;
    }

    private Map<String, Object> rowMap(String nickname, DiscardField f, String group, String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nickname", nickname);
        m.put("group", group);
        m.put("file_name", fileName == null ? "" : fileName);
        m.put("col_seq", f.colSeq());
        m.put("field_name", f.fieldName() == null ? "" : f.fieldName());
        m.put("field_meaning", f.fieldMeaning() == null ? "" : f.fieldMeaning());
        return m;
    }

    /**
     * 监测匹配键 = configLine 首段裸昵称。报表作业 JobRecord.nickname 为
     * 「昵称 · 文件名」复合串，直接匹配会漏；custom 的 JSON configLine 解析失败回退原值。
     */
    public static String monitorKey(JobRecord job) {
        try {
            return Rules.parseLegacy(job.configLine == null ? "" : job.configLine, " | ", "|||||").nickname;
        } catch (RuntimeException e) {
            return job.nickname;
        }
    }

    /** 归属组：字段映射（源系统结构导入）按昵称关联；未导入为空串。 */
    public String groupOfNickname(String nickname) {
        return groupOf(nickname);
    }

    private String groupOf(String nickname) {
        var t = fieldMaps == null ? null : fieldMaps.typeByReport(nickname);
        return t == null || t.ownershipGroup() == null || t.ownershipGroup().isBlank()
                ? "" : t.ownershipGroup();
    }

    /** configLine 的 SRCA/SRCB：批次内首个非默认命名生效（默认 A/B 不覆盖）。custom 的 JSON 行读 srca/srcb 键。 */
    private String srcOf(JobRecord job, boolean sideA) {
        try {
            com.textdiff.engine.CompareConfig cfg =
                    Rules.parseLegacy(job.configLine == null ? "" : job.configLine, " | ", "|||||");
            String v = sideA ? cfg.sourceA : cfg.sourceB;
            if (v != null && !v.isBlank() && !v.equals(sideA ? "A" : "B")) return v.strip();
        } catch (RuntimeException ignored) {
            // custom 的 JSON configLine：读 srca/srcb 附加键
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> m = Json.read(job.configLine, Map.class);
                String v = m == null ? null : m.get(sideA ? "srca" : "srcb");
                if (v != null && !v.isBlank() && !v.equals(sideA ? "A" : "B")) return v.strip();
            } catch (RuntimeException ignored2) {
                // 无命名信息按默认处理
            }
        }
        return null;
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return null;
        return Path.of(path).getFileName().toString();
    }

    // ---- CSV 落盘 ----

    private void writeCsv(Path file, Map<String, Object> view) {
        String srcA = str(view.get("src_a"), "A");
        String srcB = str(view.get("src_b"), "B");
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write('\ufeff');
                w.write("昵称,归属组,实际文件名,字段序号,字段名,字段含义,"
                        + csv(srcA + "的所有取值") + "," + csv(srcB + "的所有取值") + ",是否匹配\r\n");
                for (Map<String, Object> row : rowsOf(view)) {
                    w.write(csv(str(row.get("nickname"), "")));
                    w.write(',' + csv(str(row.get("group"), "")));
                    w.write(',' + csv(str(row.get("file_name"), "")));
                    w.write(',' + csv(String.valueOf(row.getOrDefault("col_seq", ""))));
                    w.write(',' + csv(str(row.get("field_name"), "")));
                    w.write(',' + csv(str(row.get("field_meaning"), "")));
                    w.write(',' + csv(valuesCell(row, true, srcA)));
                    w.write(',' + csv(valuesCell(row, false, srcB)));
                    w.write(',' + csv(matchText(row)));
                    w.write("\r\n");
                }
            }
            moveAtomic(tmp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("DISCARD_COLS.csv 写入失败: " + file + " (" + e + ")", e);
        }
    }

    /**
     * 是否匹配结果文案（行级 matched 字段 → 展示文本，CSV/HTML/Excel/前端共用）：
     * true = B 侧（新线）取值 ⊆ A 侧（旧线）取值；false = 存在超出旧线的取值；null = 无法判定。
     */
    public static String matchText(Map<String, Object> row) {
        Object m = row.get("matched");
        if (m == null) return "—";
        return Boolean.TRUE.equals(m) ? "新线输出正常" : "新线不为旧线子集";
    }

    /** 取值单元格：空值显示 (空)、顿号连接、截断标注、文件缺失标注。 */
    @SuppressWarnings("unchecked")
    static String valuesCell(Map<String, Object> row, boolean sideA, String src) {
        if (Boolean.TRUE.equals(row.get("file_missing"))) {
            String err = str(row.get("error"), "");
            return "（" + src + " 原文不可采集" + (err.isBlank() ? "" : "：" + err) + "）";
        }
        List<String> values = (List<String>) row.get(sideA ? "values_a" : "values_b");
        StringBuilder sb = new StringBuilder();
        if (values == null || values.isEmpty()) {
            sb.append("（无数据行）");
        } else {
            for (String v : values) {
                if (sb.length() > 0) sb.append('、');
                sb.append(v == null || v.isEmpty() ? "（空）" : CsvExporter.excelSafe(v));
            }
        }
        if (Boolean.TRUE.equals(row.get(sideA ? "truncated_a" : "truncated_b"))) {
            sb.append("…（超出 ").append(MAX_VALUES).append(" 个已截断）");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> view) {
        Object rows = view.get("rows");
        return rows instanceof List ? (List<Map<String, Object>>) rows : List.of();
    }

    private static String csv(String v) {
        return CsvExporter.escape(v);
    }

    private static String str(Object v, String dft) {
        return v == null ? dft : String.valueOf(v);
    }

    private static void writeAtomicText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            moveAtomic(tmp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败: " + file, e);
        }
    }

    private static void moveAtomic(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static Map<String, Object> base(String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        return out;
    }

    /** 每字段每侧去重取值集合（首现顺序；超上限截断）。 */
    static final class ValSet {
        private final LinkedHashSet<String> set = new LinkedHashSet<>();
        boolean truncated;

        void add(String v) {
            if (truncated) return;
            if (set.contains(v)) return;
            if (set.size() >= MAX_VALUES) {
                truncated = true;
                return;
            }
            set.add(v);
        }

        boolean full() {
            return truncated;
        }

        List<String> values() {
            return new ArrayList<>(set);
        }
    }
}
