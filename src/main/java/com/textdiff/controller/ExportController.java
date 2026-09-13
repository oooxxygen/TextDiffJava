package com.textdiff.controller;

import com.textdiff.engine.Status;
import com.textdiff.export.ExportAssembler;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.task.JobManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 导出端点（CSV，UTF-8 BOM）：
 * 全量=所有记录所有字段；差异=仅差异记录一条字段差异一行。批次导出打 ZIP。
 */
@RestController
@RequestMapping("/api")
public class ExportController {
    private final JobStore store;
    private final JobManager jobs;
    private final com.textdiff.config.AppPaths paths;

    public ExportController(JobStore store, JobManager jobs, com.textdiff.config.AppPaths paths) {
        this.store = store;
        this.jobs = jobs;
        this.paths = paths;
    }

    /** mode=full|diff；zone 过滤（trailer 仅在 full 模式有字段意义）。 */
    @GetMapping("/jobs/{id}/export")
    public ResponseEntity<byte[]> exportJob(@PathVariable String id,
                                            @RequestParam(defaultValue = "all") String zone,
                                            @RequestParam(defaultValue = "full") String mode) {
        JobRecord job = require(id);
        Predicate<com.textdiff.engine.RowDiff> filter = zoneFilter(zone);
        Path tmp = tmpDir().resolve("job_" + id + "_" + mode + "_" + System.nanoTime() + ".csv");
        if ("diff".equals(mode)) {
            ExportAssembler.writeDiffCsv(tmp.getParent(), job, filter);
        } else {
            ExportAssembler.writeFullCsv(tmp.getParent(), job, filter);
        }
        return csvDownload(tmp, tmp.getFileName().toString());
    }

    /** 单作业合并包：全量 + 差异。 */
    @GetMapping("/jobs/{id}/export-all")
    public ResponseEntity<byte[]> exportJobAll(@PathVariable String id) {
        JobRecord job = require(id);
        Path dir = tmpDir().resolve("joball_" + id + "_" + System.nanoTime());
        List<Path> files = List.of(
                ExportAssembler.writeFullCsv(dir, job, ExportAssembler.DATA),
                ExportAssembler.writeDiffCsv(dir, job, ExportAssembler.DATA));
        Path zip = dir.resolve("导出_" + job.id + ".zip");
        ExportAssembler.zip(zip, files);
        return zipDownload(zip);
    }

    /** 批次差异汇总 ZIP：每个子作业一份差异 CSV（group 过滤归属组）。 */
    @GetMapping("/batches/{id}/export")
    public ResponseEntity<byte[]> exportBatch(@PathVariable String id,
                                              @RequestParam(required = false) String group) {
        return batchZip(id, group, false);
    }

    /** 批次全量包：每个子作业全量+差异。 */
    @GetMapping("/batches/{id}/export-all")
    public ResponseEntity<byte[]> exportBatchAll(@PathVariable String id) {
        return batchZip(id, null, true);
    }

    private ResponseEntity<byte[]> batchZip(String id, String group, boolean includeFull) {
        var batch = store.getBatch(id);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        Path dir = tmpDir().resolve("batch_" + id + "_" + System.nanoTime());
        List<Path> files = new ArrayList<>();
        for (JobRecord j : store.listJobs(id)) {
            String nick = j.nickname;
            if (group != null && !group.isBlank() && !group.equals(nick)
                    && !(j.configLine != null && j.configLine.startsWith(group + ":"))) continue;
            if (includeFull) {
                files.add(ExportAssembler.writeFullCsv(dir, j, ExportAssembler.DATA));
            }
            files.add(ExportAssembler.writeDiffCsv(dir, j, ExportAssembler.DATA));
        }
        if (files.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无可导出的子作业");
        Path zip = dir.resolve("批次_" + id + ".zip");
        ExportAssembler.zip(zip, files);
        return zipDownload(zip);
    }

    static Predicate<com.textdiff.engine.RowDiff> zoneFilter(String zone) {
        if (zone == null || zone.isBlank() || "all".equals(zone)) return ExportAssembler.ALL;
        if ("unmatched".equals(zone)) {
            return r -> Status.UNMATCHED_A.equals(r.status) || Status.UNMATCHED_B.equals(r.status);
        }
        return r -> zone.equals(r.status);
    }

    private ResponseEntity<byte[]> csvDownload(Path file, String name) {
        try {
            byte[] body = Files.readAllBytes(file);
            delete(file.getParent());
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + urlEncode(name))
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .body(body);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出读取失败");
        }
    }

    private ResponseEntity<byte[]> zipDownload(Path file) {
        try {
            byte[] body = Files.readAllBytes(file);
            delete(file.getParent());
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''"
                            + urlEncode(file.getFileName().toString()))
                    .contentType(MediaType.parseMediaType("application/zip"))
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
        return job;
    }
}
