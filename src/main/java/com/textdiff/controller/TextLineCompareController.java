package com.textdiff.controller;

import com.textdiff.config.AppPaths;
import com.textdiff.engine.RowDiff;
import com.textdiff.export.TextLineDiffCsv;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.Json;
import com.textdiff.store.ResultFiles;
import com.textdiff.store.TextLineConfigStore;
import com.textdiff.store.TextLineSavedConfig;
import com.textdiff.textline.TextLineCompareService;
import com.textdiff.textline.TextLineFormat;
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
 * 【普通文本比对】端点：目录/文件路径 A/B 指定文本存放，逐行按字符位置区间主键配对、
 * 整行逐字比对。提交 / 上传 / 命名配置 CRUD / 单作业摘要 / 结果分页（section=line，
 * zone=all|equal|diff|unmatched_a|unmatched_b）/ 差异 CSV。不触发 AI 分析。
 */
@RestController
@RequestMapping("/api")
public class TextLineCompareController {
    private final JobStore store;
    private final TextLineCompareService service;
    private final TextLineConfigStore configs;
    private final AppPaths paths;

    public TextLineCompareController(JobStore store, TextLineCompareService service,
                                     TextLineConfigStore configs, AppPaths paths) {
        this.store = store;
        this.service = service;
        this.configs = configs;
        this.paths = paths;
    }

    /** 命名配置列表（自定义名称保存、载入复用）。 */
    @GetMapping("/textline-configs")
    public Map<String, Object> listConfigs() {
        return Map.of("configs", configs.all());
    }

    /** 保存命名配置（同昵称覆盖；文件名正则非法拒绝）。 */
    @PostMapping("/textline-configs")
    public Map<String, Object> saveConfig(@RequestBody TextLineSavedConfig c) {
        if (c.nickname() == null || c.nickname().isBlank()) {
            throw new IllegalArgumentException("配置昵称不能为空");
        }
        TextLineFormat.of(c.keyPositions(), c.encodingA(), c.encodingB()); // 区间语法校验
        TextLineCompareService.compileFilter(c.fileRegex());               // 文件名正则校验
        return Map.of("ok", true, "config", configs.save(c));
    }

    /** 删除命名配置。 */
    @DeleteMapping("/textline-configs")
    public Map<String, Object> deleteConfig(@RequestParam String nickname) {
        return Map.of("ok", configs.delete(nickname));
    }

    /** 载入命名配置（按昵称取单条）。 */
    @GetMapping("/textline-configs/get")
    public Map<String, Object> getConfig(@RequestParam String nickname) {
        TextLineSavedConfig c = configs.get(nickname);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在: " + nickname);
        return Map.of("config", c);
    }

    /** 全部命名配置导出（JSON 附件，导入回灌用）。 */
    @GetMapping("/textline-configs/export")
    public ResponseEntity<byte[]> exportConfigs() throws IOException {
        String body = Json.write(Map.of("configs", configs.all()));
        String filename = "textline-configs-" + java.time.LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 导入命名配置（JSON 数组或 {configs:[...]}，同昵称覆盖；非法条目逐条报错行号）。 */
    @PostMapping("/textline-configs/import")
    public Map<String, Object> importConfigs(@RequestBody Map<String, Object> body) {
        Object raw = body.containsKey("configs") ? body.get("configs") : body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("导入内容缺少 configs 数组");
        }
        int saved = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                TextLineSavedConfig c = Json.read(Json.write(list.get(i)), TextLineSavedConfig.class);
                if (c.nickname() == null || c.nickname().isBlank()) {
                    throw new IllegalArgumentException("昵称为空");
                }
                TextLineFormat.of(c.keyPositions(), c.encodingA(), c.encodingB());
                TextLineCompareService.compileFilter(c.fileRegex());
                configs.save(c);
                saved++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 条: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", errors.isEmpty());
        out.put("saved", saved);
        out.put("errors", errors);
        return out;
    }

    /**
     * 提交普通文本比对批次：文本路径 A/B（目录按逻辑名配对全部文本，压缩文件解压后
     * 按逻辑名参与配对；或直接给单文件）+ 主键区间（可空 = 整行作键）+ A/B 编码。
     * 可选 file_regex：目录模式下仅逻辑名命中的文本纳入核对（未命中/单侧缺失计入未纳入核对）。
     * 可选 config_group：按配置组提交——组内配置按落库顺序求值，文本按「首个文件名正则命中的
     * 配置」建作业（此时 key_positions/编码/来源等单条参数被忽略）。
     */
    @PostMapping("/textline-compare")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) throws Exception {
        String pa = str(body.get("dir_a"));
        String pb = str(body.get("dir_b"));
        if (pa == null || pa.isBlank() || pb == null || pb.isBlank()) {
            throw new IllegalArgumentException("缺少 dir_a / dir_b");
        }
        String label = str(body.get("label"));
        String configGroup = str(body.get("config_group"));
        if (configGroup != null && !configGroup.isBlank()) {
            List<TextLineSavedConfig> group = new ArrayList<>();
            for (TextLineSavedConfig c : configs.all()) {
                if (configGroup.strip().equals(c.groupName())) group.add(c);
            }
            if (group.isEmpty()) {
                throw new IllegalArgumentException("配置组不存在或无成员配置: " + configGroup);
            }
            var batch = service.submitGroup(Path.of(pa), Path.of(pb), label, group);
            List<String> ids = store.listJobs(batch.id).stream().map(j -> j.id).toList();
            return Map.of("batch_id", batch.id, "job_ids", ids, "job_count", ids.size());
        }
        TextLineFormat cfg = TextLineFormat.of(str(body.get("key_positions")),
                str(body.get("encoding_a")), str(body.get("encoding_b")));
        var batch = service.submit(Path.of(pa), Path.of(pb), label, cfg,
                str(body.get("file_regex")), str(body.get("source_a")), str(body.get("source_b")));
        List<String> ids = store.listJobs(batch.id).stream().map(j -> j.id).toList();
        return Map.of("batch_id", batch.id, "job_ids", ids);
    }

    /** 上传模式：A/B 两侧文本文件落盘 {@code uploads/textlinecmp/{id}/A|B}（保留原文件名配对）。 */
    @PostMapping("/textline-compare/upload")
    public Map<String, Object> upload(@RequestParam(value = "files_a", required = false) List<MultipartFile> filesA,
                                      @RequestParam(value = "files_b", required = false) List<MultipartFile> filesB)
            throws IOException {
        if (filesA == null || filesA.isEmpty() || filesB == null || filesB.isEmpty()) {
            throw new IllegalArgumentException("请上传 A / B 两侧文本文件");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path root = paths.baseDir().resolve("uploads").resolve("textlinecmp").resolve(id);
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

    /** 单作业摘要（含 TextLineSummary：行数/匹配统计/区间越界行数/配置回显）。 */
    @GetMapping("/textline-jobs/{id}/summary")
    public Map<String, Object> summary(@PathVariable String id) {
        Map<String, Object> out = service.summaryView(require(id).id);
        if (out == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在: " + id);
        return out;
    }

    /** 结果分页：zone=all|equal|diff|unmatched_a|unmatched_b；q 按主键模糊过滤。 */
    @GetMapping("/textline-jobs/{id}/result")
    public Map<String, Object> result(@PathVariable String id,
                                      @RequestParam(required = false) String section,
                                      @RequestParam(required = false) String zone,
                                      @RequestParam(defaultValue = "0") long offset,
                                      @RequestParam(defaultValue = "50") long limit,
                                      @RequestParam(required = false) String q) {
        JobRecord job = require(id);
        if (limit > 500) limit = 500;
        Path file = Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL);
        Predicate<RowDiff> filter = ResultFiles.NO_FILTER;
        if ((section != null && !section.isBlank() && !"undefined".equals(section))
                || (q != null && !q.isBlank())) {
            filter = r -> {
                if (section != null && !section.isBlank() && !"undefined".equals(section)
                        && !section.equals(r.section)) return false;
                if (q != null && !q.isBlank() && !r.key.contains(q)) return false;
                return true;
            };
        }
        ResultFiles.Page page = ResultFiles.readPage(file, zone, offset, limit, filter);
        return Map.of("rows", ApiViews.rowViews(page.rows()), "total", page.total());
    }

    /** 差异 CSV：仅有差异行与单侧行（一条一行，整行原文）。 */
    @GetMapping("/textline-jobs/{id}/export")
    public ResponseEntity<byte[]> exportJob(@PathVariable String id) {
        JobRecord job = require(id);
        Path dir = tmpDir().resolve("textlinejob_" + id + "_" + System.nanoTime());
        Path out = dir.resolve(exportBase(job) + "_行差异.csv");
        List<RowDiff> rows = new ArrayList<>();
        try (var stream = ResultFiles.stream(Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            stream.forEach(rows::add);
        }
        TextLineDiffCsv.write(out, rows);
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
        if (!TextLineCompareService.JOB_TYPE.equals(job.jobType)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "非普通文本比对作业: " + id);
        }
        return job;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
