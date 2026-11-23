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

/** 配置端点：configs 目录下的 legacy 配置行文件（{nickname}.conf）CRUD、解析预览、结构 CSV（列名映射）导入、预解压设置。 */
@RestController
@RequestMapping("/api")
public class ConfigController {
    private final AppPaths paths;
    private final com.textdiff.store.FieldMapStore fieldMaps;
    private final com.textdiff.config.AppConfig appConfig;

    public ConfigController(AppPaths paths, com.textdiff.store.FieldMapStore fieldMaps,
                            com.textdiff.config.AppConfig appConfig) {
        this.paths = paths;
        this.fieldMaps = fieldMaps;
        this.appConfig = appConfig;
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

    /**
     * 配置管理列表（逐行展平 + 生效去重）：configs 目录全部文件按生效优先级排列
     * （单配置文件 → current.conf → default.conf，同 use_all），同一（昵称 + 通配名）只展示
     * 生效那条（来源 = 生效文件）。坏行计数不阻断。
     */
    @GetMapping("/configs/manage")
    public Map<String, Object> manage(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) String source) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int bad = 0;
        boolean bySource = source != null && !source.isBlank();
        boolean searching = q != null && !q.isBlank(); // 需求⑤：搜索覆盖全部配置文件，不去重（另存为文件的同名条目也可见）
        // 同（昵称+通配名）规则在多个文件共存的清单（需求⑤提示用：use_all 生效优先级=来源文件所列者）
        Map<String, java.util.List<String>> fileHits = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : readAllOrdered().entrySet()) {
            int lineNo = 0;
            for (String line : e.getValue().split("\\r?\\n")) {
                lineNo++;
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                try {
                    CompareConfig cfg = Rules.parseLegacy(t,
                            ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                    fileHits.computeIfAbsent(cfg.nickname + "\u0000" + cfg.fileGlob,
                            k -> new ArrayList<>()).add(e.getKey());
                    if (bySource && !source.equals(e.getKey())) continue;
                    if (q != null && !q.isBlank()
                            && !cfg.nickname.contains(q) && !cfg.fileGlob.contains(q)) continue;
                    // 指定 source = 逐文件展平（新增配置条目选择器：列出该文件全部条目，不做跨文件去重——
                    // 同名规则被独立文件遮蔽时仍可从 current.conf 带入）；搜索时同样展示全部匹配条目
                    if (!bySource && !searching && !seen.add(cfg.nickname + "\u0000" + cfg.fileGlob)) continue;
                    Map<String, Object> m = ApiPayloads.configMap(cfg, e.getKey());
                    m.put("line_no", lineNo);
                    out.add(m);
                } catch (RuntimeException ex) {
                    bad++;
                }
            }
        }
        List<Map<String, Object>> dups = new ArrayList<>();
        for (Map.Entry<String, java.util.List<String>> e : fileHits.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String[] k = e.getKey().split("\u0000", 2);
            Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("nickname", k[0]);
            d.put("file_glob", k.length > 1 ? k[1] : "*");
            d.put("files", e.getValue());
            dups.add(d);
        }
        return Map.of("configs", out, "bad_lines", bad, "dups", dups);
    }

    /** 删除配置：删 {昵称}.conf（如存在），并从 configs 目录全部配置文件中移除该昵称的行。 */
    @DeleteMapping("/configs")
    public Map<String, Object> delete(@RequestParam String nickname) throws IOException {
        if (nickname == null || nickname.isBlank()) throw new IllegalArgumentException("缺少 nickname");
        sanitize(nickname);
        String nick = nickname.strip();
        List<String> touched = new ArrayList<>();
        Path dir = paths.configsDir();
        if (Files.isDirectory(dir)) {
            List<Path> files;
            try (var s = Files.list(dir)) {
                files = s.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".conf") || n.endsWith(".txt") || n.endsWith(".ini");
                }).sorted().toList();
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.equals(nick + ".conf")) {
                    Files.delete(file);
                    touched.add(name);
                    continue;
                }
                List<String> kept = new ArrayList<>();
                boolean removed = false;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.strip().isEmpty() && !line.strip().startsWith("#")
                            && line.split(":", 2)[0].strip().equals(nick)) {
                        removed = true;
                        continue;
                    }
                    kept.add(line);
                }
                if (removed) {
                    Files.writeString(file, kept.isEmpty() ? "" : String.join(System.lineSeparator(), kept)
                            + System.lineSeparator(), StandardCharsets.UTF_8);
                    touched.add(name);
                }
            }
        }
        if (touched.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在: " + nick);
        return Map.of("deleted", true, "nickname", nick, "files", touched);
    }

    /**
     * 保存配置规则（需求⑤）：默认写入 <b>current.conf</b>（请求体 target 可指定其他配置文件，如
     * 配置管理页新建的独立文件）；目标文件内同（昵称+通配名）规则替换、否则追加，<b>不触碰其他文件</b>。
     * default.conf 为基线文件禁止作目标。column_names 显式传入时重编码 COLS（修复旧版忽略导致的陈旧）。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/configs")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) throws IOException {
        String nickname = str(body.get("nickname"));
        String glob = str(body.get("file_glob"));
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("缺少 nickname");
        }
        sanitize(nickname);
        String globKey = glob == null || glob.isBlank() ? "*" : glob.strip();
        String target = normalizeConfTarget(str(body.get("target")));
        StringBuilder sb = new StringBuilder();
        sb.append(nickname.strip()).append(':').append(globKey);
        String keySeq = str(body.get("key_seq"));
        String omitSeq = str(body.get("omit_seq"));
        if (keySeq != null && !keySeq.isBlank()) sb.append(":KEYSEQ=").append(keySeq.strip());
        if (omitSeq != null && !omitSeq.isBlank()) sb.append(":OMITSEQ=").append(omitSeq.strip());
        String delim = str(body.get("delimiter"));
        if (delim != null) sb.append(":DELIM=").append(delim);
        sb.append(":ENCA=").append(strOr(body.get("encoding_a")));
        sb.append(":ENCB=").append(strOr(body.get("encoding_b")));
        // 来源命名缺省 A/B（非 auto：SRCA=auto 会让结果页/导出的 A/B 命名显示成 "auto"）
        sb.append(":SRCA=").append(strOr(body.get("source_a"), "A"));
        sb.append(":SRCB=").append(strOr(body.get("source_b"), "B"));
        sb.append(":TRAILER=").append(body.get("trailer_prefix") == null ? "" : body.get("trailer_prefix"));
        String priSeq = str(body.get("pri_seq"));
        if (priSeq != null && !priSeq.isBlank()) sb.append(":PRISEQ=").append(priSeq.strip());
        String addmod = str(body.get("addmod"));
        if (addmod != null && !addmod.isBlank()) sb.append(":ADDMOD=").append(addmod.strip());
        // 现有同名配置保留其 ignore/replace 扩展字段；COLS/PRISEQ/ADDMOD 请求未带时沿用现值
        // （沿用优先取目标文件中的同规则，其次任意文件——跨文件同名规则互不覆盖，仅取值兜底）
        CompareConfig existing = findConfigIn(target, nickname.strip());
        if (existing == null) existing = findConfig(nickname);
        List<String> colNames = columnNamesOf(body);
        CompareConfig parsed = Rules.parseLegacy(sb.toString(),
                ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
        if (existing != null) {
            parsed.ignoreColumns = existing.ignoreColumns;
            parsed.replaceRules = existing.replaceRules;
            if (colNames == null || colNames.isEmpty()) parsed.columnNames = existing.columnNames;
            if ((priSeq == null || priSeq.isBlank()) && existing.priSeqSet) {
                parsed.priSeq = existing.priSeq;
                parsed.priSeqSet = true;
            }
            if ((addmod == null || addmod.isBlank()) && !existing.incrFields.isEmpty()) {
                parsed.incrFields = existing.incrFields;
            }
        }
        if (colNames != null && !colNames.isEmpty()) parsed.columnNames = colNames;
        String line = Rules.toLegacyLine(parsed, true);
        // 写目标文件：同（昵称+通配名）替换，否则追加；其他文件一律不动（Q14 口径：文件间互不影响）
        Path dir = paths.configsDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(target);
        List<String> kept = new ArrayList<>();
        boolean replaced = false;
        if (Files.isRegularFile(file)) {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = l.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    kept.add(l);
                    continue;
                }
                String[] parts = t.split(":", 3);
                String nick = parts[0].strip();
                String g = parts.length > 1 ? parts[1].strip() : "";
                if (!replaced && nick.equals(nickname.strip()) && g.equals(globKey)) {
                    kept.add(line);
                    replaced = true;
                } else {
                    kept.add(l);
                }
            }
        }
        if (!replaced) kept.add(line);
        Files.writeString(file, String.join(System.lineSeparator(), kept) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return Map.of("files", List.of(target), "nickname", nickname.strip(), "target", target);
    }

    /** 请求体 column_names（字符串列表）→ 非空列表，缺省 null（沿用现值）。 */
    private static List<String> columnNamesOf(Map<String, Object> body) {
        Object raw = body.get("column_names");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) out.add(o.toString());
        }
        return out.isEmpty() ? null : out;
    }

    /** 保存目标文件名规范化：sanitize + 缺省 current.conf + 补 .conf 后缀；default.conf 禁作目标。 */
    private String normalizeConfTarget(String target) {
        if (target == null || target.isBlank()) return "current.conf";
        sanitize(target);
        String t = target.strip();
        if (!t.toLowerCase().endsWith(".conf")) t = t + ".conf";
        if (t.equalsIgnoreCase("default.conf")) {
            throw new IllegalArgumentException("default.conf 为基线文件，不可作为保存目标");
        }
        return t;
    }

    /** 预解压设置读取（设置页）：临时解压目录 + 预解压数量。 */
    @GetMapping("/settings/extract")
    public Map<String, Object> extractSettings() {
        com.textdiff.config.EngineConfig e = appConfig.engine();
        return Map.of("extract_dir", e.extractDir(), "pre_extract_count", e.preExtractCount());
    }

    /** JVM 运行参数读取（设置页）：config.ini [jvm] jvm_opts + 内置缺省。 */
    @GetMapping("/settings/jvm")
    public Map<String, Object> jvmSettings() {
        return Map.of(
                "opts", appConfig.jvmOpts() == null ? "" : appConfig.jvmOpts(),
                "baked", List.of("-XX:MaxRAMPercentage=50.0", "-XX:+HeapDumpOnOutOfMemoryError"),
                "note", "由启动脚本在进程启动时读取 config.ini 注入；保存后需重启服务生效");
    }

    /** JVM 运行参数保存：写入 config.ini [jvm] jvm_opts（运行中 JVM 不可变更，重启后生效）。 */
    @PostMapping("/settings/jvm")
    public Map<String, Object> saveJvmSettings(@RequestBody Map<String, Object> body) {
        String opts = str(body.get("opts"));
        appConfig.updateJvmOpts(opts == null ? "" : opts);
        return Map.of("ok", true, "opts", appConfig.jvmOpts(),
                "note", "已写入 config.ini，重启服务后生效");
    }

    /** 预解压设置保存：改内存 + 写回 config.ini [engine]，保存后立即热生效。 */
    @PostMapping("/settings/extract")
    public Map<String, Object> saveExtractSettings(@RequestBody Map<String, Object> body) {
        String dir = str(body.get("extract_dir"));
        int count;
        try {
            count = Integer.parseInt(str(body.get("pre_extract_count")).strip());
        } catch (RuntimeException e) {
            count = appConfig.engine().preExtractCount();
        }
        appConfig.updateExtractSettings(dir == null ? "" : dir.strip(), count);
        com.textdiff.config.EngineConfig e = appConfig.engine();
        return Map.of("ok", true, "extract_dir", e.extractDir(), "pre_extract_count", e.preExtractCount());
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
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : readAll().entrySet()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("protected", e.getKey().equals("current.conf") || e.getKey().equals("default.conf"));
            // 前端 fmtTime 按 epoch 秒渲染——此处转秒（毫秒会把修改时间显示成 58861 年）；mtimeText 导出侧仍用毫秒
            m.put("mtime", fileMtime(e.getKey()) / 1000);
            int rules = 0;
            for (String line : e.getValue().split("\\r?\\n")) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) rules++;
            }
            m.put("rules", rules);
            out.add(m);
        }
        return Map.of("files", out);
    }

    /** 配置文件最后修改时间（毫秒；缺失返回 0）——设置页/管理页展示辅助信息（Q13）。 */
    private long fileMtime(String name) {
        try {
            return Files.getLastModifiedTime(paths.configsDir().resolve(name)).toMillis();
        } catch (IOException ignored) {
            return 0;
        }
    }

    /** 新建配置文件（需求⑤）：from=blank 空文件 / from=current 复制 current.conf 全部内容作起点（Q5=C 两入口之一）。 */
    @PostMapping("/config-files")
    public Map<String, Object> createConfigFile(@RequestBody Map<String, Object> body) throws IOException {
        String name = normalizeConfTarget(str(body.get("name")));
        if (name.equalsIgnoreCase("current.conf")) {
            throw new IllegalArgumentException("current.conf 为系统默认配置，不可新建同名文件");
        }
        Path dir = paths.configsDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        if (Files.exists(file)) throw new ResponseStatusException(HttpStatus.CONFLICT, "配置文件已存在: " + name);
        if ("current".equalsIgnoreCase(str(body.get("from")))) {
            Path cur = dir.resolve("current.conf");
            Files.writeString(file, Files.isRegularFile(cur)
                    ? Files.readString(cur, StandardCharsets.UTF_8) : "", StandardCharsets.UTF_8);
        } else {
            Files.writeString(file, "", StandardCharsets.UTF_8);
        }
        return Map.of("ok", true, "name", name);
    }

    /** 重命名配置文件（需求⑤）：current.conf / default.conf 受保护不可改名；目标重名拒绝。 */
    @PostMapping("/config-files/rename")
    public Map<String, Object> renameConfigFile(@RequestBody Map<String, Object> body) throws IOException {
        String from = str(body.get("from"));
        String to = str(body.get("to"));
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("缺少 from / to");
        }
        sanitize(from);
        sanitize(to);
        Path dir = paths.configsDir();
        Path src = dir.resolve(from.strip().toLowerCase().endsWith(".conf") ? from.strip() : from.strip() + ".conf");
        if (!Files.isRegularFile(src)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置文件不存在: " + from);
        String fromName = src.getFileName().toString();
        if (fromName.equals("current.conf") || fromName.equals("default.conf")) {
            throw new IllegalArgumentException("current.conf / default.conf 为系统文件，不可重命名");
        }
        String toName = normalizeConfTarget(to);
        Path dst = dir.resolve(toName);
        if (Files.exists(dst)) throw new ResponseStatusException(HttpStatus.CONFLICT, "目标文件已存在: " + toName);
        Files.move(src, dst);
        return Map.of("ok", true, "from", fromName, "to", toName);
    }

    /** 删除配置文件（需求⑤）：仅独立配置文件；current.conf / default.conf 受保护。 */
    @DeleteMapping("/config-files")
    public Map<String, Object> deleteConfigFile(@RequestParam String name) throws IOException {
        sanitize(name);
        Path dir = paths.configsDir();
        Path f = dir.resolve(name.strip().toLowerCase().endsWith(".conf") ? name.strip() : name.strip() + ".conf");
        if (!Files.isRegularFile(f)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置文件不存在: " + name);
        String n = f.getFileName().toString();
        if (n.equals("current.conf") || n.equals("default.conf")) {
            throw new IllegalArgumentException("current.conf / default.conf 为系统文件，不可删除");
        }
        Files.delete(f);
        return Map.of("ok", true, "deleted", n);
    }

    /** 指定文件内按昵称查找规则（保存沿用扩展字段时优先目标文件，跨文件同名不覆盖仅兜底）。 */
    private CompareConfig findConfigIn(String fileName, String nickname) throws IOException {
        Path f = paths.configsDir().resolve(fileName);
        if (!Files.isRegularFile(f)) return null;
        for (String line : readConfigLines(f)) {
            try {
                CompareConfig cfg = Rules.parseLegacy(line,
                        ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                if (nickname.equals(cfg.nickname)) return cfg;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
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

    /**
     * 基线导入：上传 .conf/.txt/.json 配置文件（每行一条 legacy 规则），按昵称合并写入 default.conf
     * （同名昵称以新内容替换），并同步生成 current.conf。返回写入条数与文件名。
     */
    @PostMapping("/configs/import-baseline")
    public Map<String, Object> importBaseline(@RequestParam("files") List<MultipartFile> files)
            throws IOException {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("未选择配置文件");
        // 现有基线 → 昵称 → 行内容（保留旧注释之外的规则）
        Path dir = paths.configsDir();
        Files.createDirectories(dir);
        Path baseline = dir.resolve("default.conf");
        Path current = dir.resolve("current.conf");
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        if (Files.isRegularFile(baseline)) {
            for (String line : readConfigLines(baseline)) {
                merged.putIfAbsent(nickOf(line), line);
            }
        }
        int imported = 0;
        List<String> nicknames = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (MultipartFile f : files) {
            for (String line : readConfigLines(Path.of(f.getOriginalFilename() == null ? "in.conf"
                    : f.getOriginalFilename()).getFileName().toString(),
                    new String(f.getBytes(), StandardCharsets.UTF_8))) {
                try {
                    Rules.parseLegacy(line, ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                } catch (RuntimeException e) {
                    bad.add(line.split(":", 2)[0] + "（" + e.getMessage() + "）");
                    continue;
                }
                String nick = nickOf(line);
                if (merged.containsKey(nick)) nicknames.remove(nick);
                merged.put(nick, line);
                nicknames.add(nick);
                imported++;
            }
        }
        if (imported == 0) {
            throw new IllegalArgumentException("未解析到有效配置行" + (bad.isEmpty() ? "" : "；非法行: " + bad));
        }
        String outText = String.join(System.lineSeparator(), merged.values()) + System.lineSeparator();
        Files.writeString(baseline, outText, StandardCharsets.UTF_8);
        Files.writeString(current, outText, StandardCharsets.UTF_8);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("imported", imported);
        out.put("file", baseline.getFileName().toString());
        out.put("nicknames", nicknames);
        if (!bad.isEmpty()) out.put("skipped", bad);
        return out;
    }

    /** 提取配置行的昵称段（冒号前）。 */
    private static String nickOf(String line) {
        return line.split(":", 2)[0].strip();
    }

    private static List<String> readConfigLines(Path file) throws IOException {
        return readConfigLines(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 从文本提取配置行：去空白、跳过 # 注释与空行。 */
    private static List<String> readConfigLines(String name, String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    /**
     * 配置导出（需求④，fmt 可选）：
     *  fmt=conf → 全部生效规则合并去重（legacy 行格式，可直接回导）；头部 # 注释携带导出时间与
     *             各来源文件修改时间（辅助信息 Q13，导入解析跳过 # 行不受影响）。
     *  fmt=xlsx → Sheet1 生效配置（结构化列 + 修改时间列 + 列名解码明文，可回导；REPLACE 不参与
     *             Excel 往返——完整保真请导出 conf）；Sheet2 基线对比（整行原文超长截断保护，
     *             修复几百字段时 POI 32767 上限崩溃）。
     */
    @GetMapping("/configs/export")
    public org.springframework.http.ResponseEntity<byte[]> exportConfigs(
            @RequestParam(defaultValue = "xlsx") String fmt) throws IOException {
        if ("conf".equalsIgnoreCase(fmt) || "txt".equalsIgnoreCase(fmt)) return exportConf();
        return exportExcel();
    }

    /** 合并去重后的生效规则：key(昵称\0通配) → {行, 来源文件}（与 use_all 生效优先级一致）。 */
    private java.util.LinkedHashMap<String, String[]> effectiveLines() throws IOException {
        java.util.LinkedHashMap<String, String[]> eff = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : readAllOrdered().entrySet()) {
            for (String line : readConfigLines(paths.configsDir().resolve(e.getKey()))) {
                String[] parts = line.split(":", 3);
                String key = parts[0].strip() + "\u0000" + (parts.length > 1 ? parts[1].strip() : "");
                eff.putIfAbsent(key, new String[]{line, e.getKey()});
            }
        }
        return eff;
    }

    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** conf 格式导出（可直接回导；头部 # 注释为辅助信息，解析侧自动跳过）。 */
    private org.springframework.http.ResponseEntity<byte[]> exportConf() throws IOException {
        Map<String, String> ordered = readAllOrdered();
        java.util.LinkedHashMap<String, String[]> eff = effectiveLines();
        StringBuilder sb = new StringBuilder();
        sb.append("# TextDiffJava 生效配置导出（合并去重，可直接回导）\n");
        sb.append("# 导出时间: ").append(java.time.LocalDateTime.now().format(TS_FMT)).append("\n");
        sb.append("# 来源文件修改时间:\n");
        for (String name : ordered.keySet()) {
            sb.append("#   ").append(name).append("  ").append(mtimeText(name)).append("\n");
        }
        for (String[] v : eff.values()) sb.append(v[0]).append("\n");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fname = "textdiff_configs_" + java.time.LocalDate.now() + ".conf";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + urlEncode(fname))
                .contentType(org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(body);
    }

    private String mtimeText(String name) {
        long ms = fileMtime(name);
        return ms == 0 ? "-" : java.time.LocalDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
                .format(TS_FMT);
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Excel 格式导出（结构化列 + 修改时间列，可回导；基线对比整行原文截断保护）。 */
    private org.springframework.http.ResponseEntity<byte[]> exportExcel() throws IOException {
        Map<String, String> all = readAll();
        Map<String, String> baseline = new java.util.LinkedHashMap<>();
        try {
            Path b = paths.configsDir().resolve("default.conf");
            if (Files.isRegularFile(b)) {
                for (String line : readConfigLines(b)) baseline.put(nickOf(line), line);
            }
        } catch (IOException ignored) {
        }

        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sh1 = wb.createSheet("生效配置");
            java.util.List<String> heads = java.util.List.of("配置文件", "昵称", "文件名通配", "主键栏位",
                    "跳过栏位", "忽略栏位", "分隔符", "编码A", "编码B", "来源A", "来源B",
                    "折叠行前缀", "优先级", "增量栏位", "列名字段", "修改时间");
            var r0 = sh1.createRow(0);
            for (int c = 0; c < heads.size(); c++) r0.createCell(c).setCellValue(heads.get(c));
            int r = 1;
            for (String[] v : effectiveLines().values()) {
                try {
                    CompareConfig cfg = Rules.parseLegacy(v[0],
                            ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                    var row = sh1.createRow(r++);
                    row.createCell(0).setCellValue(v[1]);
                    row.createCell(1).setCellValue(cfg.nickname);
                    row.createCell(2).setCellValue(cfg.fileGlob);
                    row.createCell(3).setCellValue(strOrEmpty(ApiPayloads.seq1based(cfg.keyColumns)));
                    row.createCell(4).setCellValue(strOrEmpty(ApiPayloads.seq1based(cfg.omitColumns)));
                    row.createCell(5).setCellValue(strOrEmpty(ApiPayloads.seq1based(cfg.ignoreColumns)));
                    row.createCell(6).setCellValue(cfg.delimiter == null ? "" : cfg.delimiter);
                    row.createCell(7).setCellValue(cfg.encodingA == null ? "auto" : cfg.encodingA);
                    row.createCell(8).setCellValue(cfg.encodingB == null ? "auto" : cfg.encodingB);
                    row.createCell(9).setCellValue(cfg.sourceA == null ? "A" : cfg.sourceA);
                    row.createCell(10).setCellValue(cfg.sourceB == null ? "B" : cfg.sourceB);
                    row.createCell(11).setCellValue(cfg.trailerPrefix == null ? "" : cfg.trailerPrefix);
                    row.createCell(12).setCellValue(cfg.priSeqSet ? String.valueOf(cfg.priSeq) : "");
                    row.createCell(13).setCellValue(cfg.incrFields.isEmpty() ? ""
                            : Rules.addmod1based(cfg.incrFields));
                    row.createCell(14).setCellValue(String.join("\n", cfg.columnNames));
                    row.createCell(15).setCellValue(mtimeText(v[1]));
                } catch (RuntimeException ignored) {
                }
            }
            for (int c = 0; c < heads.size(); c++) sh1.setColumnWidth(c, c == 14 ? 40 * 256 : 18 * 256);

            var sh2 = wb.createSheet("基线对比");
            java.util.List<String> h2 = java.util.List.of("昵称", "基线", "目录配置", "状态");
            var r20 = sh2.createRow(0);
            for (int c = 0; c < h2.size(); c++) r20.createCell(c).setCellValue(h2.get(c));
            int r2 = 1;
            var names = new java.util.TreeSet<String>();
            names.addAll(baseline.keySet());
            names.addAll(all.keySet().stream().map(f -> f.replaceAll("\\.\\w+$", "")).toList());
            for (String nick : names) {
                String bLine = baseline.get(nick);
                String dLine = all.get(nick + ".conf") != null ? all.get(nick + ".conf")
                        : all.values().stream().filter(v -> nickOf(v).equals(nick)).findFirst().orElse(null);
                var row = sh2.createRow(r2++);
                row.createCell(0).setCellValue(nick);
                // POI 单元格 32767 上限：整行原文（含 COLS= base64 数万字符）超限即抛异常 → 500，统一截断保护
                row.createCell(1).setCellValue(com.textdiff.export.BatchDetailExcel.capCell(
                        bLine == null ? "（基线无）" : bLine));
                row.createCell(2).setCellValue(com.textdiff.export.BatchDetailExcel.capCell(
                        dLine == null ? "（目录无）" : dLine));
                row.createCell(3).setCellValue(bLine == null ? "新增" : dLine == null ? "缺失"
                        : bLine.strip().equals(dLine.strip()) ? "一致" : "已修改");
            }
            for (int c = 0; c < h2.size(); c++) sh2.setColumnWidth(c, 42 * 256);

            var buf = new java.io.ByteArrayOutputStream();
            wb.write(buf);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"textdiff_configs.xlsx\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(buf.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("配置导出失败: " + e.getMessage(), e);
        }
    }

    private static String strOrEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Excel 回导（需求④闭环）：解析「生效配置」Sheet 结构化列 → 重建 legacy 行 →
     * 按（昵称+通配名）合并写入 current.conf（需求⑤：导入默认存 current.conf）；辅助列（修改时间等）忽略，
     * 替换规则(REPLACE) 不参与 Excel 往返（现有同名规则保留原值）。
     */
    @PostMapping("/configs/import-excel")
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        Path dir = paths.configsDir();
        Files.createDirectories(dir);
        Path current = dir.resolve("current.conf");
        // 现有 current.conf 规则（key 保序，替换原位、新规则追加）
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        if (Files.isRegularFile(current)) {
            for (String line : readConfigLines(current)) {
                merged.putIfAbsent(dedupeKey(line), line);
            }
        }
        int imported = 0;
        List<String> bad = new ArrayList<>();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(file.getInputStream())) {
            var sh = wb.getSheet("生效配置") != null ? wb.getSheet("生效配置") : wb.getSheetAt(0);
            Map<String, Integer> col = headerIndex(sh.getRow(0));
            for (int r = 1; r <= sh.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sh.getRow(r);
                if (row == null) continue;
                String nick = cellStr(row, col.get("昵称")).strip();
                if (nick.isEmpty()) continue;
                String glob = cellStr(row, col.get("文件名通配")).strip();
                try {
                    StringBuilder sb = new StringBuilder(nick).append(':')
                            .append(glob.isEmpty() ? "*" : glob);
                    String keySeq = cellStr(row, col.get("主键栏位")).strip();
                    if (!keySeq.isEmpty()) sb.append(":KEYSEQ=").append(keySeq);
                    String omitSeq = cellStr(row, col.get("跳过栏位")).strip();
                    if (!omitSeq.isEmpty()) sb.append(":OMITSEQ=").append(omitSeq);
                    String ignoreSeq = cellStr(row, col.get("忽略栏位")).strip();
                    if (!ignoreSeq.isEmpty()) sb.append(":IGNORESEQ=").append(ignoreSeq);
                    String delim = cellStr(row, col.get("分隔符")); // 分隔符内部空格有效，不 strip
                    if (!delim.isEmpty()) sb.append(":DELIM=").append(delim);
                    sb.append(":ENCA=").append(cellStr(row, col.get("编码A")).isEmpty()
                            ? "auto" : cellStr(row, col.get("编码A")).strip());
                    sb.append(":ENCB=").append(cellStr(row, col.get("编码B")).isEmpty()
                            ? "auto" : cellStr(row, col.get("编码B")).strip());
                    sb.append(":SRCA=").append(cellStr(row, col.get("来源A")).isEmpty()
                            ? "A" : cellStr(row, col.get("来源A")).strip());
                    sb.append(":SRCB=").append(cellStr(row, col.get("来源B")).isEmpty()
                            ? "B" : cellStr(row, col.get("来源B")).strip());
                    sb.append(":TRAILER=").append(cellStr(row, col.get("折叠行前缀")));
                    String pri = cellStr(row, col.get("优先级")).strip();
                    if (!pri.isEmpty()) sb.append(":PRISEQ=").append(pri);
                    String addmod = cellStr(row, col.get("增量栏位")).strip();
                    if (!addmod.isEmpty()) sb.append(":ADDMOD=").append(addmod);
                    String cols = cellStr(row, col.get("列名字段"));
                    if (!cols.isEmpty()) {
                        List<String> names = new ArrayList<>();
                        for (String n : cols.split("\n")) {
                            if (!n.strip().isEmpty()) names.add(n.strip());
                        }
                        if (!names.isEmpty()) sb.append(":COLS=").append(Rules.encodeStringList(names));
                    }
                    CompareConfig cfg = Rules.parseLegacy(sb.toString(),
                            ApiPayloads.JobDefaults.DELIM, ApiPayloads.JobDefaults.TRAILER);
                    merged.put(dedupeKey(Rules.toLegacyLine(cfg, true)), Rules.toLegacyLine(cfg, true));
                    imported++;
                } catch (RuntimeException ex) {
                    bad.add(nick + "（" + ex.getMessage() + "）");
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Excel 解析失败: " + e.getMessage());
        }
        if (imported == 0) {
            throw new IllegalArgumentException("未解析到有效配置行" + (bad.isEmpty() ? "" : "；非法行: " + bad));
        }
        Files.writeString(current, String.join(System.lineSeparator(), merged.values()) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("imported", imported);
        out.put("file", current.getFileName().toString());
        if (!bad.isEmpty()) out.put("skipped", bad);
        return out;
    }

    /** 规则去重键（昵称 + 通配名），与 use_all/保存语义一致。 */
    private static String dedupeKey(String line) {
        String[] parts = line.split(":", 3);
        return parts[0].strip() + "\u0000" + (parts.length > 1 ? parts[1].strip() : "");
    }

    /** 表头名 → 列下标（strip 后精确匹配）。 */
    private static Map<String, Integer> headerIndex(org.apache.poi.ss.usermodel.Row head) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        if (head == null) return m;
        for (int c = 0; c < head.getLastCellNum(); c++) {
            org.apache.poi.ss.usermodel.Cell cell = head.getCell(c);
            if (cell == null) continue;
            String v = new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(cell);
            if (v != null && !v.isBlank()) m.put(v.strip(), c);
        }
        return m;
    }

    /** 单元格字符串（原样不 strip——分隔符内部空格有效；空单元格返回 ""）。 */
    private static String cellStr(org.apache.poi.ss.usermodel.Row row, Integer idx) {
        if (idx == null || row == null) return "";
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(idx);
        if (cell == null) return "";
        String v = new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(cell);
        return v == null ? "" : v;
    }

    // ---- internals ----

    /** 文件名 → 内容（LinkedHashMap 保序）。 */
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

    /** 生效优先级排序读取：单配置文件（字母序）→ current.conf → default.conf（与 use_all 去重同序）。 */
    private Map<String, String> readAllOrdered() throws IOException {
        Map<String, String> all = readAll();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        all.forEach((name, content) -> {
            if (!name.equals("current.conf") && !name.equals("default.conf")) out.put(name, content);
        });
        if (all.containsKey("current.conf")) out.put("current.conf", all.get("current.conf"));
        if (all.containsKey("default.conf")) out.put("default.conf", all.get("default.conf"));
        return out;
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

    private static String strOr(Object o, String def) {
        String s = str(o);
        return s == null || s.isBlank() ? def : s.strip();
    }
}
