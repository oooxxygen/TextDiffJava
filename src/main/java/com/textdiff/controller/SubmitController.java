package com.textdiff.controller;

import com.textdiff.config.AppPaths;
import com.textdiff.engine.Encoding;
import com.textdiff.engine.Rules;
import com.textdiff.store.BatchRecord;
import com.textdiff.task.JobManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 提交类端点：编码枚举、上传、单对比、批次（目录对，可按对比配置分组圈定范围）。 */
@RestController
@RequestMapping("/api")
public class SubmitController {
    private final JobManager jobs;
    private final AppPaths paths;
    private final com.textdiff.store.CompareGroupStore compareGroups;

    public SubmitController(JobManager jobs, AppPaths paths,
                            com.textdiff.store.CompareGroupStore compareGroups) {
        this.jobs = jobs;
        this.paths = paths;
        this.compareGroups = compareGroups;
    }

    @GetMapping("/encodings")
    public Map<String, Object> encodings() {
        return Map.of("encodings", List.of("auto", "utf-8", "cp037", "cp500", "cp1047", "gb18030", "latin-1"));
    }

    /** 上传模式：文件落盘 uploads/，返回服务端路径供 /api/compare 的 pairs 引用。 */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("files") List<MultipartFile> files) throws IOException {
        Path dir = paths.baseDir().resolve("uploads");
        Files.createDirectories(dir);
        List<Map<String, String>> out = new ArrayList<>();
        for (MultipartFile f : files) {
            String safe = Path.of(f.getOriginalFilename() == null ? "file" : f.getOriginalFilename())
                    .getFileName().toString();
            if (safe.isBlank()) safe = "file";
            Path dest = dir.resolve(System.currentTimeMillis() + "_" + safe);
            try (var in = f.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            out.add(Map.of("path", dest.toAbsolutePath().toString()));
        }
        return Map.of("files", out);
    }

    /** 单对/多对直连模式：每对 {a,b} 一个作业，共享同一配置行；incr_conds 与 pairs 对齐（incr_cond 广播到全部）。 */
    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody ApiPayloads.ComparePayload p) throws IOException {
        if (p.pairs() == null || p.pairs().isEmpty()) {
            throw new IllegalArgumentException("缺少文件对 pairs");
        }
        String configLine = ApiPayloads.buildLegacyLine(p);
        List<List<String>> conds = condsFor(p.pairs().size(), p.incr_conds(), p.incr_cond());
        List<String[]> rows = new ArrayList<>();
        for (ApiPayloads.ComparePayload.Pair pair : p.pairs()) {
            if (pair.a() == null || pair.b() == null) {
                throw new IllegalArgumentException("文件对缺少 a 或 b 路径");
            }
            String nick = configLine.split(":", 2)[0];
            rows.add(new String[]{configLine, pair.a(), pair.b(), nick});
        }
        BatchRecord batch = jobs.createBatchFromPairs(
                p.label() == null ? "直连对比" : p.label(), rows, p.kind(), conds);
        List<String> ids = jobs.listJobs(batch.id).stream().map(j -> j.id).toList();
        return Map.of("batch_id", batch.id, "job_ids", ids);
    }

    /** 条件列表规范化：目标数量对齐；incr_conds 缺失时用 incr_cond 广播。全部为空返回 null（不过滤）。 */
    private static List<List<String>> condsFor(int n, List<List<String>> incrConds, List<String> incrCond) {
        List<List<String>> src = incrConds;
        if ((src == null || src.isEmpty()) && incrCond != null && !incrCond.isEmpty()) {
            src = new ArrayList<>();
            for (int i = 0; i < n; i++) src.add(incrCond);
        }
        if (src == null) return null;
        boolean any = false;
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> c = i < src.size() ? src.get(i) : null;
            out.add(c);
            if (c != null && c.stream().anyMatch(v -> v != null && !v.isBlank())) any = true;
        }
        return any ? out : null;
    }

    /** 提交前 dry-run 配对预览：请求体同 /api/batch-compare，返回文本项列表（不建批次不派作业）。 */
    @PostMapping("/batch-preview")
    public Map<String, Object> batchPreview(@RequestBody Map<String, Object> body) throws IOException {
        String dirA = str(body.get("dir_a"));
        String dirB = str(body.get("dir_b"));
        if (dirA == null || dirB == null) throw new IllegalArgumentException("缺少 dir_a / dir_b");
        List<String> lines = resolveConfigLines(body);
        return Map.of("pairs", jobs.previewPairs(Path.of(dirA), Path.of(dirB), lines));
    }

    /** 批次模式：目录对 + 配置（use_all / config_file / 内联三选一）；incr_conds 与配对顺序对齐。 */
    @PostMapping("/batch-compare")
    public Map<String, Object> batchCompare(@RequestBody Map<String, Object> body) throws IOException {
        String dirA = str(body.get("dir_a"));
        String dirB = str(body.get("dir_b"));
        if (dirA == null || dirB == null) throw new IllegalArgumentException("缺少 dir_a / dir_b");
        List<String> lines = resolveConfigLines(body);
        if (lines.isEmpty()) throw new IllegalArgumentException("无生效配置行");

        List<List<String>> conds = null;
        Object rawConds = body.get("incr_conds");
        if (rawConds instanceof List<?> l) {
            List<List<String>> parsed = new ArrayList<>();
            for (Object o : l) {
                List<String> row = new ArrayList<>();
                if (o instanceof List<?> rl) {
                    for (Object v : rl) row.add(v == null ? "" : v.toString());
                }
                parsed.add(row);
            }
            conds = condsFor(parsed.size(), parsed, null);
        }

        // 异步建档：立即返回批次，配对与作业落盘在后台进行（万级文本提交不再阻塞 HTTP 请求），
        // 批次页/任务页轮询可见 prepare_state=preparing → ready 与作业数动态增长
        BatchRecord batch = jobs.createBatchAsync(Path.of(dirA), Path.of(dirB), lines, str(body.get("kind")), conds);
        return Map.of("batch_id", batch.id);
    }

    /**
     * 批次配置行解析（use_all / config_file / 内联三选一），预览与正式提交共用。
     * 可选 {@code group}：对比配置分组名，仅保留分组成员（按配置昵称）内的行——
     * 目录对比圈定范围：不在分组名单内的文本不读取不对比；未指定分组则对比全部。
     */
    private List<String> resolveConfigLines(Map<String, Object> body) throws IOException {
        List<String> lines = new ArrayList<>();
        Boolean useAll = bool(body.get("use_all"));
        String configFile = str(body.get("config_file"));
        if (Boolean.TRUE.equals(useAll)) {
            for (String f : listConfigFiles()) {
                lines.addAll(readConfigLines(paths.configsDir().resolve(f)));
            }
            lines = dedupeLines(lines);
        } else if (configFile != null) {
            lines.addAll(readConfigLines(paths.configsDir().resolve(configFile)));
        } else {
            // 内联单规则
            StringBuilder sb = new StringBuilder();
            sb.append(strOr(body.get("nickname"), "JOB")).append(':');
            sb.append(strOr(body.get("file_glob"), "*"));
            String keySeq = str(body.get("key_seq"));
            String omitSeq = str(body.get("omit_seq"));
            String priSeq = str(body.get("pri_seq"));
            String addmod = str(body.get("addmod"));
            if (keySeq != null && !keySeq.isBlank()) sb.append(":KEYSEQ=").append(keySeq.strip());
            if (omitSeq != null && !omitSeq.isBlank()) sb.append(":OMITSEQ=").append(omitSeq.strip());
            if (priSeq != null && !priSeq.isBlank()) sb.append(":PRISEQ=").append(priSeq.strip());
            if (addmod != null && !addmod.isBlank()) sb.append(":ADDMOD=").append(addmod.strip());
            String delim = str(body.get("delimiter"));
            if (delim != null) sb.append(":DELIM=").append(delim);
            sb.append(":ENCA=").append(strOr(body.get("encoding_a"), "auto"));
            sb.append(":ENCB=").append(strOr(body.get("encoding_b"), "auto"));
            sb.append(":SRCA=").append(strOr(body.get("source_a"), "A"));
            sb.append(":SRCB=").append(strOr(body.get("source_b"), "B"));
            lines.add(sb.toString());
        }
        String group = str(body.get("group"));
        if (group != null && !group.isBlank()) lines = filterByGroup(group.strip(), lines);
        return lines;
    }

    /** 分组过滤：配置昵称（冒号前段）命中分组成员（忽略大小写）才保留；分组不存在抛 400。 */
    private List<String> filterByGroup(String groupName, List<String> lines) {
        java.util.Set<String> members = compareGroups.membersOf(groupName);
        if (compareGroups.group(groupName) == null) {
            throw new IllegalArgumentException("对比配置分组不存在: " + groupName);
        }
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String nick = line.split(":", 2)[0].strip();
            for (String m : members) {
                if (m.equalsIgnoreCase(nick)) {
                    out.add(line);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 全部配置去重：同一规则可能同时存在于 {昵称}.conf / current.conf / default.conf，
     * 按（昵称 + 通配名）去重保留先到者——单配置文件（UI 保存/编辑的最新内容）优先于
     * current/default 聚合文件，避免 use_all 批次对同一文本重复建作业。
     */
    private static List<String> dedupeLines(List<String> lines) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = t.split(":", 3);
            String key = parts[0].strip() + "\u0000" + (parts.length > 1 ? parts[1].strip() : "");
            out.putIfAbsent(key, t);
        }
        return new ArrayList<>(out.values());
    }

    static List<String> readConfigLines(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    /** 配置文件清单（use_all）：单配置文件（{昵称}.conf 等）在前、current.conf 次之、default.conf 最后——配合去重时最新编辑优先。 */
    private List<String> listConfigFiles() throws IOException {
        Path dir = paths.configsDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<String> singles = new ArrayList<>();
        String current = null;
        String baseline = null;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                String low = n.toLowerCase();
                if (!low.endsWith(".conf") && !low.endsWith(".txt") && !low.endsWith(".ini")) continue;
                if (n.equals("current.conf")) current = n;
                else if (n.equals("default.conf")) baseline = n;
                else singles.add(n);
            }
        }
        java.util.Collections.sort(singles);
        List<String> out = new ArrayList<>(singles);
        if (current != null) out.add(current);
        if (baseline != null) out.add(baseline);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String strOr(Object o, String def) {
        String s = str(o);
        return s == null || s.isBlank() ? def : s.strip();
    }

    private static Boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return null;
    }
}
