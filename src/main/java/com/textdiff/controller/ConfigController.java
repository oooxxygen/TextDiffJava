package com.textdiff.controller;

import com.textdiff.config.AppPaths;
import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.Rules;
import com.textdiff.store.FieldMaps;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 配置端点：configs 目录下的 legacy 配置行文件（{nickname}.conf）CRUD、解析预览、结构 CSV（列名映射）导入。 */
@RestController
@RequestMapping("/api")
public class ConfigController {
    private final AppPaths paths;
    private final com.textdiff.store.FieldMapStore fieldMaps;

    public ConfigController(AppPaths paths, com.textdiff.store.FieldMapStore fieldMaps) {
        this.paths = paths;
        this.fieldMaps = fieldMaps;
    }

    @GetMapping("/configs")
    public Map<String, Object> list(@RequestParam(required = false) String q) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : readAll().entrySet()) {
            try {
                CompareConfig cfg = Rules.parseLegacy(e.getValue(),
                        ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                if (q != null && !q.isBlank()
                        && !cfg.nickname.contains(q) && !cfg.fileGlob.contains(q)) continue;
                out.add(ApiPayloads.configMap(cfg, e.getKey()));
            } catch (RuntimeException ignored) {
                // 单个配置文件损坏不影响列表
            }
        }
        return Map.of("configs", out);
    }

    @GetMapping("/configs/get")
    public Map<String, Object> get(@RequestParam String nickname) throws IOException {
        for (Map.Entry<String, String> e : readAll().entrySet()) {
            try {
                CompareConfig cfg = Rules.parseLegacy(e.getValue(),
                        ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                if (nickname.equals(cfg.nickname)) return ApiPayloads.configMap(cfg, e.getKey());
            } catch (RuntimeException ignored) {
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在: " + nickname);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/configs")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) throws IOException {
        String nickname = str(body.get("nickname"));
        String glob = str(body.get("file_glob"));
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("缺少 nickname");
        }
        sanitize(nickname);
        StringBuilder sb = new StringBuilder();
        sb.append(nickname.strip()).append(':').append(glob == null || glob.isBlank() ? "*" : glob.strip());
        String keySeq = str(body.get("key_seq"));
        String omitSeq = str(body.get("omit_seq"));
        if (keySeq != null && !keySeq.isBlank()) sb.append(":KEYSEQ=").append(keySeq.strip());
        if (omitSeq != null && !omitSeq.isBlank()) sb.append(":OMITSEQ=").append(omitSeq.strip());
        String delim = str(body.get("delimiter"));
        if (delim != null) sb.append(":DELIM=").append(delim);
        sb.append(":ENCA=").append(strOr(body.get("encoding_a")));
        sb.append(":ENCB=").append(strOr(body.get("encoding_b")));
        sb.append(":SRCA=").append(strOr(body.get("source_a")));
        sb.append(":SRCB=").append(strOr(body.get("source_b")));
        sb.append(":TRAILER=").append(body.get("trailer_prefix") == null ? "" : body.get("trailer_prefix"));
        // 现有同名配置保留其 ignore/replace/cols 扩展字段
        CompareConfig existing = findConfig(nickname);
        CompareConfig parsed = Rules.parseLegacy(sb.toString(),
                ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
        if (existing != null) {
            parsed.ignoreColumns = existing.ignoreColumns;
            parsed.replaceRules = existing.replaceRules;
            parsed.columnNames = existing.columnNames;
        }
        String line = Rules.toLegacyLine(parsed, true);
        Path dir = paths.configsDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(nickname.strip() + ".conf");
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8);
        return Map.of("files", List.of(file.getFileName().toString()), "nickname", nickname.strip());
    }

    @GetMapping("/configs/raw")
    public Map<String, Object> raw(@RequestParam String file) throws IOException {
        sanitize(file);
        Path f = paths.configsDir().resolve(file);
        if (!Files.isRegularFile(f)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        return Map.of("content", Files.readString(f, StandardCharsets.UTF_8));
    }

    @GetMapping("/config-files")
    public Map<String, Object> configFiles() throws IOException {
        return Map.of("files", listFiles());
    }

    @PostMapping("/parse-config")
    public Map<String, Object> parseConfig(@RequestBody Map<String, Object> body) {
        String text = str(body.get("config_text"));
        if (text == null || text.isBlank()) throw new IllegalArgumentException("缺少 config_text");
        CompareConfig cfg = Rules.parseLegacy(text, ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("nickname", cfg.nickname);
        out.put("file_glob", cfg.fileGlob);
        out.put("key_seq", ApiPayloads.seq1based(cfg.keyColumns));
        out.put("omit_seq", ApiPayloads.seq1based(cfg.omitColumns));
        return out;
    }

    /**
     * 列名映射导入（源系统字段配置）：上传多个 CSV，纯映射落库（不生成 .conf）。
     * bat_report_type_parm*.csv → 昵称(report_id) ↔ 文件名(report_file_name) ↔ 文件类型/归属组；
     * bat_report_conf_field*.csv → 昵称 ↔ 字段清单及类型（field_index 定序）。
     * 对比作业运行时按文件名自动注入列名（JobManager COLS 注入），归属组用于 AI 分析产物命名。
     */
    @PostMapping("/configs/import-structure")
    public Map<String, Object> importStructure(@RequestParam("files") List<MultipartFile> files)
            throws IOException {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("未选择 CSV 文件");
        List<FieldMaps.ReportType> types = new ArrayList<>();
        List<FieldMaps.ReportField> fields = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = Path.of(f.getOriginalFilename() == null ? "file.csv" : f.getOriginalFilename())
                    .getFileName().toString();
            byte[] content = f.getBytes();
            var kind = com.textdiff.store.StructureCsv.kindOf(name, content);
            switch (kind) {
                case TYPE_PARM -> types.addAll(com.textdiff.store.StructureCsv.parseTypes(content, name));
                case CONF_FIELD -> fields.addAll(com.textdiff.store.StructureCsv.parseFields(content, name));
                default -> skipped.add(name);
            }
            names.add(name);
        }
        if (types.isEmpty() && fields.isEmpty()) {
            throw new IllegalArgumentException(
                    "未识别到 bat_report_type_parm*.csv / bat_report_conf_field*.csv（跳过: " + skipped + "）");
        }
        FieldMaps.ImportSummary sum = fieldMaps.importAll(types, fields, names);

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("types", sum.types());
        out.put("fields", sum.fields());
        out.put("nicknames", sum.nicknames());
        if (!skipped.isEmpty()) out.put("skipped", skipped);
        return out;
    }

    /** 字段映射浏览：全部报表（昵称/文件名/类型/归属组/字段数）。 */
    @GetMapping("/fieldmaps")
    public Map<String, Object> fieldmaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FieldMaps.ReportType t : fieldMaps.allTypes()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("report_id", t.reportId());
            m.put("report_file_name", t.reportFileName());
            m.put("parm_report_type", t.parmReportType());
            m.put("ownership_group", t.ownershipGroup());
            m.put("field_count", fieldMaps.fieldNamesFor(t.reportId()).size());
            m.put("source_file", t.sourceFile());
            out.add(m);
        }
        return Map.of("reports", out, "db_available", fieldMaps.dbAvailable());
    }

    /** 指定报表的字段清单（0-based 列号 + 字段名/类型/长度，对比界面/导出所用的最终形态）。 */
    @GetMapping("/fieldmaps/get")
    public Map<String, Object> fieldmapGet(@RequestParam("report_id") String reportId) {
        FieldMaps.ReportType t = fieldMaps.typeByReport(reportId);
        if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无此报表映射: " + reportId);
        List<Map<String, Object>> cols = new ArrayList<>();
        List<FieldMaps.ReportField> fs = fieldMaps.fieldsFor(reportId);
        for (FieldMaps.ReportField f : fs) {
            Map<String, Object> c = new java.util.LinkedHashMap<>();
            c.put("col_index", f.colIndex());
            c.put("field_name", f.fieldName());
            c.put("field_format", f.fieldFormat());
            c.put("field_length", f.fieldLength());
            cols.add(c);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("report_id", reportId);
        out.put("report_file_name", t.reportFileName());
        out.put("parm_report_type", t.parmReportType());
        out.put("ownership_group", t.ownershipGroup());
        out.put("columns", cols);
        return out;
    }

    @GetMapping("/configs/export")
    public Map<String, Object> exportStub() {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Excel 导出未启用");
    }

    // ---- internals ----

    private Map<String, String> readAll() throws IOException {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Path dir = paths.configsDir();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                out.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private List<String> listFiles() throws IOException {
        return new ArrayList<>(readAll().keySet());
    }

    private CompareConfig findConfig(String nickname) throws IOException {
        for (String v : readAll().values()) {
            try {
                CompareConfig cfg = Rules.parseLegacy(v,
                        ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                if (nickname.strip().equals(cfg.nickname)) return cfg;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    /** 文件名安全：只允许字母数字下划线中划线点（防目录穿越）。 */
    private static void sanitize(String name) {
        if (!name.strip().matches("[\\w.\\-\\u4e00-\\u9fff]+")) {
            throw new IllegalArgumentException("非法文件名: " + name);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String strOr(Object o) {
        String s = str(o);
        return s == null || s.isBlank() ? "auto" : s.strip();
    }
}
