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

/** 提交类端点：编码枚举、上传、单对比、批次（目录对）。 */
@RestController
@RequestMapping("/api")
public class SubmitController {
    private final JobManager jobs;
    private final AppPaths paths;

    public SubmitController(JobManager jobs, AppPaths paths) {
        this.jobs = jobs;
        this.paths = paths;
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

    /** 单对/多对直连模式：每对 {a,b} 一个作业，共享同一配置行。 */
    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody ApiPayloads.ComparePayload p) throws IOException {
        if (p.pairs() == null || p.pairs().isEmpty()) {
            throw new IllegalArgumentException("缺少文件对 pairs");
        }
        String configLine = ApiPayloads.buildLegacyLine(p);
        List<String[]> rows = new ArrayList<>();
        for (ApiPayloads.ComparePayload.Pair pair : p.pairs()) {
            if (pair.a() == null || pair.b() == null) {
                throw new IllegalArgumentException("文件对缺少 a 或 b 路径");
            }
            String nick = configLine.split(":", 2)[0];
            rows.add(new String[]{configLine, pair.a(), pair.b(), nick});
        }
        BatchRecord batch = jobs.createBatchFromPairs(
                p.label() == null ? "直连对比" : p.label(), rows);
        List<String> ids = jobs.listJobs(batch.id).stream().map(j -> j.id).toList();
        return Map.of("batch_id", batch.id, "job_ids", ids);
    }

    /** 批次模式：目录对 + 配置（use_all / config_file / 内联三选一）。 */
    @PostMapping("/batch-compare")
    public Map<String, Object> batchCompare(@RequestBody Map<String, Object> body) throws IOException {
        String dirA = str(body.get("dir_a"));
        String dirB = str(body.get("dir_b"));
        if (dirA == null || dirB == null) throw new IllegalArgumentException("缺少 dir_a / dir_b");

        List<String> lines = new ArrayList<>();
        Boolean useAll = bool(body.get("use_all"));
        String configFile = str(body.get("config_file"));
        if (Boolean.TRUE.equals(useAll)) {
            for (String f : listConfigFiles()) {
                lines.addAll(readConfigLines(paths.configsDir().resolve(f)));
            }
        } else if (configFile != null) {
            lines.addAll(readConfigLines(paths.configsDir().resolve(configFile)));
        } else {
            // 内联单规则
            StringBuilder sb = new StringBuilder();
            sb.append(strOr(body.get("nickname"), "JOB")).append(':');
            sb.append(strOr(body.get("file_glob"), "*"));
            String keySeq = str(body.get("key_seq"));
            String omitSeq = str(body.get("omit_seq"));
            if (keySeq != null && !keySeq.isBlank()) sb.append(":KEYSEQ=").append(keySeq.strip());
            if (omitSeq != null && !omitSeq.isBlank()) sb.append(":OMITSEQ=").append(omitSeq.strip());
            String delim = str(body.get("delimiter"));
            if (delim != null) sb.append(":DELIM=").append(delim);
            sb.append(":ENCA=").append(strOr(body.get("encoding_a"), "auto"));
            sb.append(":ENCB=").append(strOr(body.get("encoding_b"), "auto"));
            sb.append(":SRCA=").append(strOr(body.get("source_a"), "A"));
            sb.append(":SRCB=").append(strOr(body.get("source_b"), "B"));
            lines.add(sb.toString());
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("无生效配置行");

        BatchRecord batch = jobs.createBatch(Path.of(dirA), Path.of(dirB), lines);
        return Map.of("batch_id", batch.id);
    }

    static List<String> readConfigLines(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    private List<String> listConfigFiles() throws IOException {
        Path dir = paths.configsDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<String> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".conf") || n.endsWith(".txt") || n.endsWith(".ini");
            }).map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        }
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
