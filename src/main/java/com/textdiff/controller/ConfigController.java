package com.textdiff.controller;

import com.textdiff.config.AppPaths;
import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.Rules;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 配置端点：configs 目录下的 legacy 配置行文件（{nickname}.conf）CRUD 与解析预览。 */
@RestController
@RequestMapping("/api")
public class ConfigController {
    private final AppPaths paths;

    public ConfigController(AppPaths paths) {
        this.paths = paths;
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

    /** M6+ 再评估：Excel 导入导出非本次需求范围。 */
    @PostMapping({"/configs/import-structure", "/configs/import-baseline"})
    public Map<String, Object> importStub() {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Excel 导入未启用（CSV 配置请直接写入 configs/）");
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
