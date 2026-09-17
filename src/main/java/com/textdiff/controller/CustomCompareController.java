package com.textdiff.controller;

import com.textdiff.config.AppPaths;
import com.textdiff.custom.CustomCompareService;
import com.textdiff.custom.CustomFormat;
import com.textdiff.engine.RowDiff;
import com.textdiff.export.CustomDiffCsv;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.ResultFiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 【自定义格式对比】端点：非传统结构化文本（一段段报文，如 MT950）按用户指定的
 * 段起止匹配式 + 主键提取式做段解析与匹配的对比批次。
 * 提交 / 单作业摘要 / 段结果分页（section=segment，zone=all|equal|diff|unmatched_a|unmatched_b）/ 差异CSV。
 */
@RestController
@RequestMapping("/api")
public class CustomCompareController {
    private final JobStore store;
    private final CustomCompareService service;
    private final AppPaths paths;

    public CustomCompareController(JobStore store, CustomCompareService service, AppPaths paths) {
        this.store = store;
        this.service = service;
        this.paths = paths;
    }

    /**
     * 提交自定义格式对比批次：文本路径 A/B（目录按文件名配对，或单文件）
     * + 段起始/结束匹配式 + 主键提取式（均为正则；结束式与主键式可空）。
     */
    @PostMapping("/custom-compare")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) throws Exception {
        String pa = str(body.get("dir_a"));
        String pb = str(body.get("dir_b"));
        if (pa == null || pa.isBlank() || pb == null || pb.isBlank()) {
            throw new IllegalArgumentException("缺少 dir_a / dir_b");
        }
        CustomFormat cfg = CustomFormat.of(str(body.get("start_pattern")),
                str(body.get("end_pattern")), str(body.get("key_pattern")));
        var batch = service.submit(Path.of(pa), Path.of(pb), str(body.get("label")), cfg);
        List<String> ids = store.listJobs(batch.id).stream().map(j -> j.id).toList();
        return Map.of("batch_id", batch.id, "job_ids", ids);
    }

    /** 上传模式：A/B 两侧文本文件落盘 {@code uploads/customcmp/{id}/A|B}（保留原文件名配对）。 */
    @PostMapping("/custom-compare/upload")
    public Map<String, Object> upload(@RequestParam(value = "files_a", required = false) List<MultipartFile> filesA,
                                      @RequestParam(value = "files_b", required = false) List<MultipartFile> filesB)
            throws IOException {
        if (filesA == null || filesA.isEmpty() || filesB == null || filesB.isEmpty()) {
            throw new IllegalArgumentException("请上传 A / B 两侧文本文件");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path root = paths.baseDir().resolve("uploads").resolve("customcmp").resolve(id);
        Path dirA = save(filesA, root.resolve("A"));
        Path dirB = save(filesB, root.resolve("B"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir_a", dirA.toString());
        out.put("dir_b", dirB.toString());
        return out;
    }

    private static Path save(List<MultipartFile> files, Path dir) throws IOException {
        Files.createDirectories(dir);
        for (MultipartFile f : files) {
            String safe = Path.of(f.getOriginalFilename() == null ? "file" : f.getOriginalFilename())
                    .getFileName().toString();
            if (safe.isBlank()) safe = "file";
            try (var in = f.getInputStream()) {
                Files.copy(in, dir.resolve(safe), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir;
    }

    /** 单作业摘要（含 CustomSummary：段数/匹配统计/主键未命中/残行/配置回显）。 */
    @GetMapping("/custom-jobs/{id}/summary")
    public Map<String, Object> summary(@PathVariable String id) {
        Map<String, Object> out = service.summaryView(require(id).id);
        if (out == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在: " + id);
        return out;
    }

    /** 段结果分页：zone=all|equal|diff|unmatched_a|unmatched_b；q 按主键模糊过滤。 */
    @GetMapping("/custom-jobs/{id}/result")
    public Map<String, Object> result(@PathVariable String id,
                                      @RequestParam(required = false) String section,
                                      @RequestParam(required = false) String zone,
                                      @RequestParam(defaultValue = "0") long offset,
                                      @RequestParam(defaultValue = "50") long limit,
                                      @RequestParam(required = false) String q) {
        JobRecord job = require(id);
        if (limit > 500) limit = 500;
        Path file = Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL);
        Predicate<RowDiff> filter = r -> {
            if (section != null && !section.isBlank() && !section.equals(r.section)) return false;
            if (q != null && !q.isBlank() && !r.key.contains(q)) return false;
            return true;
        };
        ResultFiles.Page page = ResultFiles.readPage(file, zone, offset, limit, filter);
        return Map.of("rows", ApiViews.rowViews(page.rows()), "total", page.total());
    }

    /** 差异 CSV：仅有差异段（一条差异行一行）与单侧段（整段一条）。 */
    @GetMapping("/custom-jobs/{id}/export")
    public ResponseEntity<byte[]> exportJob(@PathVariable String id) {
        JobRecord job = require(id);
        Path dir = tmpDir().resolve("customjob_" + id + "_" + System.nanoTime());
        Path out = dir.resolve(exportBase(job) + "_段差异.csv");
        List<RowDiff> rows = new ArrayList<>();
        try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            stream.forEach(rows::add);
        }
        CustomDiffCsv.write(out, rows);
        return download(out, "text/csv;charset=UTF-8");
    }

    // ---- helpers ----

    private static String exportBase(JobRecord job) {
        String name = job.label != null && !job.label.isEmpty() ? job.label : job.nickname;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private ResponseEntity<byte[]> download(Path file, String mediaType) {
        try {
            byte[] body = Files.readAllBytes(file);
            delete(file.getParent());
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''"
                            + urlEncode(file.getFileName().toString()))
                    .contentType(MediaType.parseMediaType(mediaType))
                    .body(body);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出读取失败");
        }
    }

    private Path tmpDir() {
        try {
            return Files.createDirectories(paths.baseDir().resolve("tmp-export"));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "临时目录创建失败");
        }
    }

    private static void delete(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (java.io.IOException ignored) {
                }
            });
        } catch (java.io.IOException ignored) {
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JobRecord require(String id) {
        JobRecord job = store.getJob(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在: " + id);
        if (!CustomCompareService.JOB_TYPE.equals(job.jobType)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "非自定义格式对比作业: " + id);
        }
        return job;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
