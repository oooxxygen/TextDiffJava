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
 * 全量=所有记录所有字段；差异=一条差异记录一行（全部差异栏位整合格式 + 关注栏位 [X-YYYY @N] 前缀）；
 * 仅差异栏位=一条字段差异一行。批次导出打 ZIP。
 */
@RestController
@RequestMapping("/api")
public class ExportController {
    private final JobStore store;
    private final JobManager jobs;
    private final com.textdiff.config.AppPaths paths;
    private final com.textdiff.store.FieldMapStore fieldMaps; // 批次明细 Excel 的列名解析
    private final com.textdiff.store.DiscardFieldStore discardFields; // 差异导出 [X-YYYY] 前缀的监测类型

    public ExportController(JobStore store, JobManager jobs, com.textdiff.config.AppPaths paths,
                            com.textdiff.store.FieldMapStore fieldMaps,
                            com.textdiff.store.DiscardFieldStore discardFields) {
        this.store = store;
        this.jobs = jobs;
        this.paths = paths;
        this.fieldMaps = fieldMaps;
        this.discardFields = discardFields;
    }

    /**
     * mode=full|diff|fields|collect；zone 过滤仅对 CSV 模式有意义（trailer 仅在 full 有字段意义）。
     * full 超 2 万条分片，多文件自动打 zip。
     * diff（默认差异导出）= 恢复的差异 CSV（整合格式）：一条差异记录一行，全部差异栏位合并进
     * 「差异明细」（[栏位不一致 @N] A:'..' B:'..'；关注栏位 [X-YYYY @N] 前缀；单方存在写存在侧主键）。
     * fields = 仅差异栏位 CSV：一条字段差异一行（键值/栏位号/栏位名/A 值/B 值）。
     * collect = 差异采集对比案例 xlsx（CollectComparisonCaseTemplate 20 列）。
     */
    @GetMapping("/jobs/{id}/export")
    public ResponseEntity<byte[]> exportJob(@PathVariable String id,
                                            @RequestParam(defaultValue = "all") String zone,
                                            @RequestParam(defaultValue = "full") String mode) {
        JobRecord job = require(id);
        Predicate<com.textdiff.engine.RowDiff> filter = zoneFilter(zone);
        Path dir = tmpDir().resolve("job_" + id + "_" + System.nanoTime());
        if ("diff".equals(mode)) {
            List<Path> parts = ExportAssembler.writeDiffCsv(dir, job, filter, monitorMap(job));
            return csvOrZip(dir, parts);
        }
        if ("fields".equals(mode)) {
            List<Path> parts = ExportAssembler.writeDiffFieldsCsv(dir, job, filter);
            return csvOrZip(dir, parts);
        }
        if ("collect".equals(mode)) {
            var batch = job.batchId == null || job.batchId.isBlank() ? null : store.getBatch(job.batchId);
            var t = fieldMaps == null ? null
                    : fieldMaps.typeByReport(com.textdiff.export.DiscardColsService.monitorKey(job));
            String parmType = t == null || t.parmReportType() == null ? "" : t.parmReportType();
            String team = t == null || t.ownershipGroup() == null ? "" : t.ownershipGroup();
            Path out = com.textdiff.export.CollectCaseExcel.write(dir.resolve(ExportAssembler.base(job) + "_差异.xlsx"),
                    job, batch, parmType, team);
            return fileDownload(out, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        List<Path> parts = ExportAssembler.writeFullCsv(dir, job, filter);
        return csvOrZip(dir, parts);
    }

    /** 单片直接返回 CSV，多片打 ZIP（共享临时目录，下载后统一清理）。 */
    private ResponseEntity<byte[]> csvOrZip(Path dir, List<Path> parts) {
        if (parts.size() == 1) return csvDownload(parts.get(0), parts.get(0).getFileName().toString());
        Path zip = dir.resolve(parts.get(0).getFileName().toString().replace(".csv", "") + "等"
                + parts.size() + "个文件.zip");
        ExportAssembler.zip(zip, parts);
        return zipDownload(zip);
    }

    /** 监测栏位类型表（1-based 栏位序号 → 配置条目）：未导入/未命中返回 null（差异明细全部 [栏位不一致 @N]）。 */
    private java.util.Map<Integer, com.textdiff.store.DiscardField> monitorMap(JobRecord job) {
        if (discardFields == null || discardFields.isEmpty()) return null;
        var fields = discardFields.forNickname(com.textdiff.export.DiscardColsService.monitorKey(job));
        if (fields.isEmpty()) return null;
        java.util.Map<Integer, com.textdiff.store.DiscardField> m = new java.util.LinkedHashMap<>();
        for (var f : fields) m.put(f.colSeq(), f);
        return m;
    }

    /** 单作业合并包：全量 + 差异（各含分片）。 */
    @GetMapping("/jobs/{id}/export-all")
    public ResponseEntity<byte[]> exportJobAll(@PathVariable String id) {
        JobRecord job = require(id);
        Path dir = tmpDir().resolve("joball_" + id + "_" + System.nanoTime());
        List<Path> files = new ArrayList<>(ExportAssembler.writeFullCsv(dir, job, ExportAssembler.DATA));
        files.addAll(ExportAssembler.writeDiffCsv(dir, job, ExportAssembler.DATA, monitorMap(job)));
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

    /**
     * 批次明细 Excel：Sheet1 对比总览（每表新旧总数/完全匹配/键值匹配有差异/未匹配 + 对比配置列号+名称），
     * Sheet2 差异栏位（表名昵称/列号/列名/备注）。group 过滤归属组（可选）。
     */
    @GetMapping("/batches/{id}/export-detail")
    public ResponseEntity<byte[]> exportBatchDetail(@PathVariable String id,
                                                    @RequestParam(required = false) String group) {
        var batch = store.getBatch(id);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        List<JobRecord> jobs = new ArrayList<>();
        for (JobRecord j : store.listJobs(id)) {
            String nick = j.nickname;
            if (group != null && !group.isBlank() && !group.equals(nick)
                    && !(j.configLine != null && j.configLine.startsWith(group + ":"))) continue;
            jobs.add(j);
        }
        if (jobs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无可导出的子作业");
        Path dir = tmpDir().resolve("batchdetail_" + id + "_" + System.nanoTime());
        Path out = dir.resolve("批次明细_" + id + ".xlsx");
        com.textdiff.export.BatchDetailExcel.write(out, jobs, fieldMaps);
        return fileDownload(out, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
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
                files.addAll(ExportAssembler.writeFullCsv(dir, j, ExportAssembler.DATA));
            }
            files.addAll(ExportAssembler.writeDiffCsv(dir, j, ExportAssembler.DATA, monitorMap(j)));
        }
        if (files.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无可导出的子作业");
        Path zip = dir.resolve("批次_" + id + ".zip");
        ExportAssembler.zip(zip, files);
        return zipDownload(zip);
    }

    /** zone 过滤与结果页分区一致：trailer=尾部区全量（任意状态）；equal/diff/unmatched 不含尾部行。 */
    static Predicate<com.textdiff.engine.RowDiff> zoneFilter(String zone) {
        if (zone == null || zone.isBlank() || "all".equals(zone)) return ExportAssembler.ALL;
        Predicate<com.textdiff.engine.RowDiff> trailer =
                r -> com.textdiff.engine.Status.SECTION_TRAILER.equals(r.section);
        if ("trailer".equals(zone)) return trailer;
        if ("unmatched".equals(zone)) {
            return trailer.negate().and(r -> Status.UNMATCHED_A.equals(r.status)
                    || Status.UNMATCHED_B.equals(r.status));
        }
        return trailer.negate().and(r -> zone.equals(r.status));
    }

    private ResponseEntity<byte[]> csvDownload(Path file, String name) {
        return fileDownload(file, "text/csv;charset=UTF-8");
    }

    private ResponseEntity<byte[]> zipDownload(Path file) {
        return fileDownload(file, "application/zip");
    }

    /** 附件下载：读取文件、Content-Disposition（RFC 5987 中文文件名）、读完清理临时目录。 */
    private ResponseEntity<byte[]> fileDownload(Path file, String mediaType) {
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
        return job;
    }
}
