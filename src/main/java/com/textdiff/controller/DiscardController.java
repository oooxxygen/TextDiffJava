package com.textdiff.controller;

import com.textdiff.export.BatchSummaryExcel;
import com.textdiff.export.BatchSummaryHtml;
import com.textdiff.export.Deviation;
import com.textdiff.export.DiscardColsService;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.DiscardExcel;
import com.textdiff.store.DiscardFieldStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.ResultFiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关注字段监测端点：
 * POST /api/discard-fields/import（multipart .xlsx 全量替换：文本名称/栏位/栏位名/含义）
 * GET  /api/discard-fields/status；DELETE /api/discard-fields（清空）
 * GET  /api/batches/{id}/discard-cols(.csv)（懒生成视图 / CSV 下载）
 * GET  /api/jobs/{id}/discard-cols（单作业小卡片）
 * GET  /api/batches/{id}/summary-report(.html/.xlsx)（批次情况总结：概览 + 全部文本偏离度双指标 + 监测表）
 */
@RestController
@RequestMapping("/api")
public class DiscardController {
    private final JobStore store;
    private final DiscardFieldStore discardFields;
    private final DiscardColsService discardService;

    public DiscardController(JobStore store, DiscardFieldStore discardFields,
                             DiscardColsService discardService) {
        this.store = store;
        this.discardFields = discardFields;
        this.discardService = discardService;
    }

    /** 导入（全量替换）：解析失败抛 400（旧数据不受影响），告警随摘要返回不阻断。 */
    @PostMapping("/discard-fields/import")
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("未选择 Excel 文件（.xlsx）");
        String name = file.getOriginalFilename() == null ? "DISCARD.xlsx" : file.getOriginalFilename();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
        DiscardExcel.Parsed parsed = DiscardExcel.parse(content, name);
        List<com.textdiff.store.DiscardField> saved =
                discardFields.importAll(parsed.fields(), Path.of(name).getFileName().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", saved.size());
        out.put("nickname_count", discardFields.nicknames().size());
        out.put("nicknames", discardFields.nicknames());
        out.put("source_file", name);
        out.put("imported_at", discardFields.lastImportedAt());
        out.put("db_available", discardFields.dbAvailable());
        if (!parsed.warnings().isEmpty()) out.put("warnings", parsed.warnings());
        return out;
    }

    /** 当前监测配置概览（设置页状态行）。 */
    @GetMapping("/discard-fields/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", discardFields.all().size());
        out.put("nickname_count", discardFields.nicknames().size());
        out.put("nicknames", discardFields.nicknames());
        out.put("source_file", discardFields.lastSourceFile());
        out.put("imported_at", discardFields.lastImportedAt());
        out.put("db_available", discardFields.dbAvailable());
        return out;
    }

    /** 清空监测配置。 */
    @DeleteMapping("/discard-fields")
    public Map<String, Object> clear() {
        discardFields.clear();
        return Map.of("count", 0);
    }

    /** 批次监测视图（懒生成；批次运行中返回 pending）。 */
    @GetMapping("/batches/{id}/discard-cols")
    public Map<String, Object> batchView(@PathVariable String id) {
        Map<String, Object> view = discardService.batchView(id);
        if (view == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        return view;
    }

    /** DISCARD_COLS.csv 下载（懒生成；无命中 404）。 */
    @GetMapping("/batches/{id}/discard-cols.csv")
    public ResponseEntity<byte[]> csv(@PathVariable String id) {
        Path file = discardService.ensureCsv(id);
        if (file == null || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "本批次无监测命中文本或尚未完成: " + id);
        }
        try {
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''DISCARD_COLS.csv")
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .body(Files.readAllBytes(file));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DISCARD_COLS.csv 读取失败");
        }
    }

    /** 单作业监测视图（单文件/报表结果页小卡片）。 */
    @GetMapping("/jobs/{id}/discard-cols")
    public Map<String, Object> jobView(@PathVariable String id) {
        Map<String, Object> view = discardService.jobView(id);
        if (view == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在: " + id);
        return view;
    }

    /** 批次情况总结（JSON：概览 + 全部文本偏离度双指标 + 监测表）。 */
    @GetMapping("/batches/{id}/summary-report")
    public Map<String, Object> summaryReport(@PathVariable String id) {
        Map<String, Object> out = summaryPayload(id);
        if (out == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        return out;
    }

    /** 批次情况总结（自包含 HTML 下载：全量内容 + 各分区按表名/归属组搜索）。 */
    @GetMapping("/batches/{id}/summary-report.html")
    public ResponseEntity<byte[]> summaryReportHtml(@PathVariable String id) {
        Map<String, Object> out = summaryPayload(id);
        if (out == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        byte[] body = BatchSummaryHtml.render(out).getBytes(StandardCharsets.UTF_8);
        String name = "批次情况总结_" + id + ".html";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + urlEncode(name))
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(body);
    }

    /** 批次情况总结 Excel（一个分区一个 Sheet：概览 / 偏离度 / 关注字段监测）。 */
    @GetMapping("/batches/{id}/summary-report.xlsx")
    public ResponseEntity<byte[]> summaryReportExcel(@PathVariable String id) {
        Map<String, Object> out = summaryPayload(id);
        if (out == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        byte[] body = BatchSummaryExcel.render(out);
        String name = "批次情况总结_" + id + ".xlsx";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + urlEncode(name))
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    // ---- internals ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryPayload(String id) {
        BatchRecord batch = store.getBatch(id);
        if (batch == null) return null;
        List<JobRecord> jobs = store.listJobs(id);
        Map<String, Object> stats = ApiViews.batchStats(batch, jobs);
        Map<String, Object> b = (Map<String, Object>) stats.get("batch");
        Map<String, Object> discard = discardService.batchView(id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch_id", id);
        out.put("label", b.get("label"));
        out.put("batch_type", b.get("batch_type"));
        out.put("dir_a", b.get("dir_a"));
        out.put("dir_b", b.get("dir_b"));
        out.put("status", stats.get("status"));
        out.put("created_at", b.get("created_at"));
        out.put("started_at", b.get("started_at"));
        out.put("finished_at", b.get("finished_at"));
        out.put("total_files", stats.get("total_files"));
        out.put("diff_files", stats.get("diff_files"));
        out.put("done", stats.get("done"));
        out.put("failed", jobs.stream().filter(j -> JobRecord.FAILED.equals(j.status)).count());
        out.put("no_rule_count", stats.get("no_rule_count"));
        out.put("missing_count", stats.get("missing_count"));
        out.put("src_a", discard == null ? "A" : discard.getOrDefault("src_a", "A"));
        out.put("src_b", discard == null ? "B" : discard.getOrDefault("src_b", "B"));
        out.put("deviations", deviations(jobs));
        out.put("discard", discard);
        out.put("generated_at", System.currentTimeMillis() / 1000);
        return out;
    }

    /**
     * 全部文本偏离度（双指标）：
     *  内容偏离度 =（有差异 + 仅A + 仅B）/ A 总条数；条数偏离度 = |B − A| / A 总条数；
     *  分级 ≥50% 严重问题 / ≥20% 务必关注 / ≥10% 微量偏移，按两指标较大者主排（同值按差异条数）。
     */
    private List<Map<String, Object>> deviations(List<JobRecord> jobs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobRecord j : jobs) {
            if (!JobRecord.DONE.equals(j.status)) continue;
            long equal, diff, onlyA, onlyB, totalA, totalB;
            if (j.diffCount >= 0) {
                equal = j.equalCount;
                diff = j.diffCount;
                onlyA = j.onlyACount;
                onlyB = j.onlyBCount;
                totalA = j.totalA;
                totalB = j.totalB;
            } else {
                com.textdiff.store.JobMeta meta = ResultFiles.readMeta(Path.of(j.resultDir == null ? "" : j.resultDir));
                if (meta == null) continue;
                equal = meta.equal;
                diff = meta.diff;
                onlyA = meta.onlyA;
                onlyB = meta.onlyB;
                totalA = meta.totalA;
                totalB = meta.totalB;
            }
            String nickname = DiscardColsService.monitorKey(j);
            Deviation.Metric content = Deviation.content(diff, onlyA, onlyB, totalA);
            Deviation.Metric count = Deviation.count(totalA, totalB);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nickname", nickname);
            m.put("group", discardService.groupOfNickname(nickname));
            m.put("file_name", fileName(j.fileA) == null ? fileName(j.fileB) : fileName(j.fileA));
            m.put("content_ratio", round4(content.ratio()));
            m.put("content_grade", content.grade());
            m.put("count_ratio", round4(count.ratio()));
            m.put("count_grade", count.grade());
            m.put("equal", equal);
            m.put("diff", diff);
            m.put("only_a", onlyA);
            m.put("only_b", onlyB);
            m.put("total_a", totalA);
            m.put("total_b", totalB);
            out.add(m);
        }
        out.sort((x, y) -> {
            int c = Double.compare(Math.max(((Number) x.get("content_ratio")).doubleValue(),
                            ((Number) x.get("count_ratio")).doubleValue()),
                    Math.max(((Number) y.get("content_ratio")).doubleValue(),
                            ((Number) y.get("count_ratio")).doubleValue()));
            if (c != 0) return -c;
            return Long.compare(((Number) y.get("diff")).longValue(), ((Number) x.get("diff")).longValue());
        });
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return null;
        return Path.of(path).getFileName().toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
