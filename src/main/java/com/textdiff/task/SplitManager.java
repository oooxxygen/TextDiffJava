package com.textdiff.task;

import com.textdiff.config.AppPaths;
import com.textdiff.engine.Encoding;
import com.textdiff.engine.Parser;
import com.textdiff.store.SplitConfig;
import com.textdiff.store.SplitJob;
import com.textdiff.store.SplitJobStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文本拆分执行器：A 区文件必填、B 区可选，A、B 按同一规则配置流式拆分。
 *
 * 模型（对齐前端拆分页）：规则 = 分组，按序求值、<b>首条命中即归属</b>，全部未命中归「其他」组；
 * 规则内多条件支持 且（all，默认）/ 或（any）。trailer 语义与 {@link Parser} 一致：
 * 命中尾部前缀后其余行全部计入 trailer，不参与分组；空行跳过。
 *
 * 输出：{@code results_split/{jobId}/{side}_{组名}.txt}（UTF-8，原始行原样保留）；
 * 输入经 {@link Encoding#iterLines} 按配置/自动检测编码读入——即统一转码为 UTF-8 落盘。
 */
public final class SplitManager implements AutoCloseable {
    public static final String OTHER_GROUP = "其他";

    private final SplitJobStore store;
    private final AppPaths paths;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "split-worker");
        t.setDaemon(true);
        return t;
    });

    public SplitManager(SplitJobStore store, AppPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    /** 提交拆分：校验 A 必填、B 可选、规则非空可编译、文件存在；落 PENDING 记录后排队执行。 */
    public SplitJob submit(String label, SplitConfig config, String pathA, String pathB) {
        List<CompiledRule> rules = compile(config == null ? null : config.rules()); // 提交即编译，坏正则/坏区间快速失败
        if (pathA == null || pathA.isBlank()) {
            throw new IllegalArgumentException("A 区文件必填（B 区可选）");
        }
        if (!Files.isRegularFile(Path.of(pathA))) {
            throw new IllegalArgumentException("A 区文件不存在: " + pathA);
        }
        String b = pathB == null ? "" : pathB.strip();
        if (!b.isEmpty() && !Files.isRegularFile(Path.of(b))) {
            throw new IllegalArgumentException("B 区文件不存在: " + b);
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        SplitJob job = new SplitJob(id, label == null ? "" : label.strip(), SplitJob.PENDING, null,
                config, new SplitJob.Inputs(Path.of(pathA).toAbsolutePath().toString(),
                        b.isEmpty() ? "" : Path.of(b).toAbsolutePath().toString()),
                null, paths.resultsSplitDir().resolve(id).toString(),
                System.currentTimeMillis() / 1000, 0);
        store.save(job);
        final List<CompiledRule> compiled = rules;
        pool.execute(() -> run(store.get(id), compiled));
        return job;
    }

    void run(SplitJob job, List<CompiledRule> rules) {
        if (job == null || !SplitJob.PENDING.equals(job.status())) return;
        save(job, SplitJob.RUNNING, null, null);
        try {
            Path dir = Path.of(job.resultDir());
            Files.createDirectories(dir);
            SplitJob.Summary s = split(job, dir, rules);
            save(job, SplitJob.DONE, null, s);
        } catch (Exception e) {
            save(job, SplitJob.ERROR, e.getMessage() == null ? e.toString() : e.getMessage(), null);
        }
    }

    private void save(SplitJob job, String status, String error, SplitJob.Summary summary) {
        store.save(new SplitJob(job.jobId(), job.label(), status, error, job.config(), job.inputs(),
                summary != null ? summary : job.summary(), job.resultDir(), job.createdAt(),
                SplitJob.DONE.equals(status) || SplitJob.ERROR.equals(status)
                        ? System.currentTimeMillis() / 1000 : job.finishedAt()));
    }

    private SplitJob.Summary split(SplitJob job, Path dir, List<CompiledRule> rules) throws IOException {
        SplitJob.Side a = splitSide("a", Path.of(job.inputs().a()), job.config(), rules, dir);
        SplitJob.Side b = job.inputs().b() == null || job.inputs().b().isEmpty()
                ? null
                : splitSide("b", Path.of(job.inputs().b()), job.config(), rules, dir);
        return new SplitJob.Summary(a, b);
    }

    /** 单侧拆分：逐行判组写出（行原样保留），trailer/空行不计入分组。 */
    private SplitJob.Side splitSide(String side, Path input, SplitConfig cfg,
                                    List<CompiledRule> rules, Path dir) throws IOException {
        String trailerPrefix = cfg.trailerPrefix();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CompiledRule r : rules) counts.put(r.name(), 0L);
        counts.put(OTHER_GROUP, 0L);
        Map<String, BufferedWriter> writers = new LinkedHashMap<>();
        long total = 0;
        long trailer = 0;
        try (var lines = Encoding.iterLines(input, cfg.encoding(), cfg.delimiter())) {
            boolean trailerStarted = false;
            for (String line : (Iterable<String>) lines::iterator) {
                if (trailerStarted) {
                    trailer++;
                    continue;
                }
                if (trailerPrefix != null && !trailerPrefix.isEmpty() && line.startsWith(trailerPrefix)) {
                    trailerStarted = true;
                    trailer++;
                    continue;
                }
                String data = blankToNull(line);
                if (data == null) continue; // 空行/纯空白/NUL 填充行（定宽提取空槽）跳过——写出后即「空行」
                String[] cols = Parser.splitColumns(data, cfg.delimiter());
                String group = OTHER_GROUP;
                for (CompiledRule r : rules) {
                    if (r.match(cols)) {
                        group = r.name();
                        break;
                    }
                }
                counts.merge(group, 1L, Long::sum);
                total++;
                BufferedWriter w = writers.computeIfAbsent(group,
                        g -> openWriter(dir, side + "_" + sanitize(g) + ".txt"));
                w.write(data);
                w.write("\n");
            }
        } finally {
            for (BufferedWriter w : writers.values()) {
                try {
                    w.close();
                } catch (IOException ignored) {
                }
            }
        }
        List<SplitJob.GroupCount> groups = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            groups.add(new SplitJob.GroupCount(e.getKey(), e.getValue()));
        }
        return new SplitJob.Side(total, trailer, groups);
    }

    private static BufferedWriter openWriter(Path dir, String fileName) {
        try {
            return Files.newBufferedWriter(dir.resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("拆分输出创建失败: " + fileName, e);
        }
    }

    /** 组名 → 安全文件名段（非法字符替换 _，空名回退 未命名）。 */
    public static String sanitize(String name) {
        String n = name == null || name.isBlank() ? "未命名" : name.strip();
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 数据行清洗：剥离行尾 \r（防御性）后判空。
     * 返回 null = 空行/纯空白行/NUL 填充行（定宽提取文件的空槽、UTF-16 填充残留）——
     * 这些行写出后在编辑器里渲染为「空行」，一律不计组、不产出。
     * 非 null = 去尾 \r 的原行（内容不做其他改写，保持拆分产物与原文一致）。
     */
    static String blankToNull(String line) {
        if (line == null) return null;
        String s = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\u0000' && ch != '\u3000' && !Character.isWhitespace(ch)) return s;
        }
        return null;
    }

    // ---- 条件编译与求值 ----

    /** 已编译规则：组内条件合成一个谓词（any=任一满足，否则全部满足）。 */
    private record CompiledRule(String name, boolean any, List<Predicate<String[]>> conds) {
        boolean match(String[] cols) {
            for (Predicate<String[]> c : conds) {
                boolean hit = c.test(cols);
                if (any && hit) return true;
                if (!any && !hit) return false;
            }
            return !any;
        }
    }

    /** 条件编译：op 分派为谓词；regex/range 在此预解析（坏正则/坏区间抛出，提交时即报错）。 */
    private static List<CompiledRule> compile(List<SplitConfig.Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("请至少配置一条拆分规则");
        }
        List<CompiledRule> out = new ArrayList<>();
        for (SplitConfig.Rule r : rules) {
            if (r.conditions() == null || r.conditions().isEmpty()) {
                throw new IllegalArgumentException("规则「" + r.name() + "」至少需要一个条件");
            }
            List<Predicate<String[]>> preds = new ArrayList<>();
            for (SplitConfig.Cond c : r.conditions()) {
                preds.add(compileCond(c));
            }
            String name = r.name() == null || r.name().isBlank() ? "组" + (out.size() + 1) : r.name().strip();
            out.add(new CompiledRule(name, r.any(), preds));
        }
        return out;
    }

    private static Predicate<String[]> compileCond(SplitConfig.Cond c) {
        if (c.col() < 1) throw new IllegalArgumentException("条件栏位序号须 ≥1（收到 " + c.col() + "）");
        int idx = c.col() - 1;
        String op = c.op() == null ? "" : c.op().strip().toLowerCase();
        String val = c.value() == null ? "" : c.value();
        return switch (op) {
            case "eq" -> {
                if (val.isEmpty()) throw new IllegalArgumentException("定值条件不能为空值");
                yield cols -> colAt(cols, idx).equals(val);
            }
            case "contains" -> {
                if (val.isEmpty()) throw new IllegalArgumentException("包含条件不能为空值");
                yield cols -> colAt(cols, idx).contains(val);
            }
            case "prefix" -> {
                if (val.isEmpty()) throw new IllegalArgumentException("前缀条件不能为空值");
                yield cols -> colAt(cols, idx).startsWith(val);
            }
            case "suffix" -> {
                if (val.isEmpty()) throw new IllegalArgumentException("后缀条件不能为空值");
                yield cols -> colAt(cols, idx).endsWith(val);
            }
            case "regex" -> {
                if (val.isEmpty()) throw new IllegalArgumentException("正则条件不能为空值");
                Pattern p;
                try {
                    p = Pattern.compile(val);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("正则无效: " + val + "（" + e.getDescription() + "）");
                }
                yield cols -> p.matcher(colAt(cols, idx)).find(); // search 语义（对齐 re.search）
            }
            case "range" -> {
                BigDecimal[] bounds = parseRange(val);
                BigDecimal lo = bounds[0];
                BigDecimal hi = bounds[1];
                yield cols -> inRange(colAt(cols, idx), lo, hi);
            }
            default -> throw new IllegalArgumentException("未知条件类型: " + op);
        };
    }

    /** 栏位取值：缺列/超界视为空串。 */
    private static String colAt(String[] cols, int idx) {
        return idx < cols.length && cols[idx] != null ? cols[idx] : "";
    }

    /** 范围解析：「起~止」（两侧可留空表示开区间）；两侧全空视为无效条件。 */
    private static BigDecimal[] parseRange(String val) {
        if (val == null || val.isBlank()) throw new IllegalArgumentException("范围条件不能为空值");
        String[] parts = val.split("~", -1);
        if (parts.length > 2) throw new IllegalArgumentException("范围条件格式应为 起~止: " + val);
        BigDecimal lo = parts[0].isBlank() ? null : num(parts[0], val);
        BigDecimal hi = parts.length < 2 || parts[1].isBlank() ? null : num(parts[1], val);
        if (lo == null && hi == null) throw new IllegalArgumentException("范围条件两侧不能同时为空: " + val);
        if (lo != null && hi != null && lo.compareTo(hi) > 0) {
            throw new IllegalArgumentException("范围条件起点不能大于止点: " + val);
        }
        return new BigDecimal[]{lo, hi};
    }

    private static BigDecimal num(String s, String raw) {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("范围条件需为数字: " + raw);
        }
    }

    /** 数值范围判定：值非数字 → 不命中；开区间只约束有界一侧。 */
    private static boolean inRange(String v, BigDecimal lo, BigDecimal hi) {
        if (v == null || v.isBlank()) return false;
        BigDecimal n;
        try {
            n = new BigDecimal(v.strip());
        } catch (NumberFormatException e) {
            return false;
        }
        return (lo == null || n.compareTo(lo) >= 0) && (hi == null || n.compareTo(hi) <= 0);
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
