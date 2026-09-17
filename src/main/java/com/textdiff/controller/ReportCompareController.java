package com.textdiff.controller;

import com.textdiff.config.AppPaths;
import com.textdiff.engine.RowDiff;
import com.textdiff.export.ReportDetailExcel;
import com.textdiff.export.ReportDiffCsv;
import com.textdiff.report.ReportCompareService;
import com.textdiff.report.ReportSummary;
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
 * 【报表对比】端点：header 模板 + 数据文本目录 A/B 的报表核对批次。
 * 提交 / 单作业摘要 / 三分区结果分页（section=header|footer|data）/ 差异CSV / 批次详情 Excel。
 */
@RestController
@RequestMapping("/api")
public class ReportCompareController {
    private final JobStore store;
    private final ReportCompareService service;
    private final AppPaths paths;

    public ReportCompareController(JobStore store, ReportCompareService service, AppPaths paths) {
        this.store = store;
        this.service = service;
        this.paths = paths;
    }

    /** 提交报表对比批次：模板路径（目录或 .header 文件） + 数据文本路径 A/B。 */
    @PostMapping("/report-compare")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) throws Exception {
        String templatePath = str(body.get("template_path"));
        if (templatePath == null || templatePath.isBlank()) templatePath = str(body.get("template_dir"));
        String dirA = str(body.get("dir_a"));
        String dirB = str(body.get("dir_b"));
        if (dirA == null || dirB == null) throw new IllegalArgumentException("缺少 dir_a / dir_b");
        var batch = service.submit(templatePath == null || templatePath.isBlank() ? null : Path.of(templatePath),
                Path.of(dirA), Path.of(dirB), str(body.get("label")));
        List<String> ids = store.listJobs(batch.id).stream().map(j -> j.id).toList();
        return Map.of("batch_id", batch.id, "job_ids", ids);
    }

    /**
     * 上传模式：A/B 两侧报表文件（可选附 .header 模板）落盘
     * {@code uploads/reportcmp/{id}/A|B|tpl}（保留原文件名以便按名配对），返回目录路径供 /report-compare 引用。
     */
    @PostMapping("/report-compare/upload")
    public Map<String, Object> upload(@RequestParam(value = "files_a", required = false) List<MultipartFile> filesA,
                                      @RequestParam(value = "files_b", required = false) List<MultipartFile> filesB,
                                      @RequestParam(value = "files_tpl", required = false) List<MultipartFile> filesTpl)
            throws IOException {
        if ((filesA == null || filesA.isEmpty()) && (filesB == null || filesB.isEmpty())) {
            throw new IllegalArgumentException("请至少上传 A / B 两侧报表文件");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path root = paths.baseDir().resolve("uploads").resolve("reportcmp").resolve(id);
        Path dirA = save(filesA, root.resolve("A"));
        Path dirB = filesB == null || filesB.isEmpty() ? root.resolve("B") : save(filesB, root.resolve("B"));
        Path dirTpl = filesTpl == null || filesTpl.isEmpty() ? null : save(filesTpl, root.resolve("tpl"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir_a", dirA.toString());
        out.put("dir_b", dirB.toString());
        out.put("template_dir", dirTpl == null ? "" : dirTpl.toString());
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

    /** 单报表作业摘要（含 ReportSummary：条数/分区差异/匹配统计/条数核对）。 */
    @GetMapping("/report-jobs/{id}/summary")
    public Map<String, Object> summary(@PathVariable String id) {
        Map<String, Object> out = service.summaryView(require(id).id);
        if (out == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在: " + id);
        return out;
    }

    /**
     * 结果分页：section=header|footer|data（缺省全部）；zone=all|equal|diff|unmatched；
     * q 按行标识模糊过滤。
     */
    @GetMapping("/report-jobs/{id}/result")
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

    /** 单报表差异 CSV：仅单侧不匹配 + 行部分匹配（部分匹配一条差异栏位一行）。 */
    @GetMapping("/report-jobs/{id}/export")
    public ResponseEntity<byte[]> exportJob(@PathVariable String id) {
        JobRecord job = require(id);
        Path dir = tmpDir().resolve("reportjob_" + id + "_" + System.nanoTime());
        Path out = dir.resolve(exportBase(job) + "_报表差异.csv");
        List<RowDiff> rows = new ArrayList<>();
        try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            stream.forEach(rows::add);
        }
        ReportSummary s = service.readSummary(Path.of(job.resultDir));
        List<String> names = s == null || s.fieldNames == null ? List.of() : s.fieldNames;
        ReportDiffCsv.write(out, rows, names);
        return download(out, "text/csv;charset=UTF-8");
    }

    /** 批次详情 Excel：Sheet1 报表对比总览（昵称/文件名/总条数/差异统计），Sheet2 差异明细。 */
    @GetMapping("/report-batches/{id}/export-detail")
    public ResponseEntity<byte[]> exportBatchDetail(@PathVariable String id) {
        var batch = store.getBatch(id);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        List<JobRecord> jobs = store.listJobs(id);
        if (jobs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无可导出的子作业");
        Path dir = tmpDir().resolve("reportdetail_" + id + "_" + System.nanoTime());
        Path out = dir.resolve("报表批次明细_" + id + ".xlsx");
        ReportDetailExcel.write(out, jobs);
        return download(out, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
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
        if (!ReportCompareService.JOB_TYPE.equals(job.jobType)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "非报表对比作业: " + id);
        }
        return job;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
