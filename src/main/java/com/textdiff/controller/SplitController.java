package com.textdiff.controller;

import com.textdiff.store.SplitConfig;
import com.textdiff.store.SplitJob;
import com.textdiff.store.SplitJobStore;
import com.textdiff.store.SplitConfigStore;
import com.textdiff.task.SplitManager;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文本拆分端点：
 * <ul>
 *   <li>规则配置 CRUD（配置管理界面维护）：GET/POST /api/split-configs、GET /api/split-configs/get、
 *       DELETE /api/split-configs/{nickname}；POST 按昵称 upsert。</li>
 *   <li>拆分作业：POST /api/split（A 必填、B 可选）→ {job_id}；GET /api/split-jobs/{id} → {meta}；
 *       GET /rows?side&amp;group 分页浏览原始行；GET /export?fmt=zip|xlsx&amp;bucket=matched|other
 *       下载 符合条件/不符合条件/各组文本 压缩包或汇总表；DELETE /api/split-jobs/{id}；
 *       POST /api/split-jobs/{id}/label。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class SplitController {
    private static final String[] OPS = {"eq", "contains", "prefix", "suffix", "regex", "range"};

    private final SplitConfigStore configs;
    private final SplitJobStore jobs;
    private final SplitManager manager;

    public SplitController(SplitConfigStore configs, SplitJobStore jobs, SplitManager manager) {
        this.configs = configs;
        this.jobs = jobs;
        this.manager = manager;
    }

    // ---- 规则配置 CRUD ----

    @GetMapping("/split-configs")
    public Map<String, Object> listConfigs() {
        return Map.of("configs", configs.all());
    }

    @GetMapping("/split-configs/get")
    public SplitConfig getConfig(@RequestParam String nickname) {
        SplitConfig c = configs.get(nickname);
        if (c == null) throw notFound("拆分配置不存在: " + nickname);
        return c;
    }

    @PostMapping("/split-configs")
    public SplitConfig saveConfig(@RequestBody SplitConfig c) {
        validate(c, true);
        return configs.save(c);
    }

    @DeleteMapping("/split-configs/{nickname}")
    public Map<String, Object> deleteConfig(@PathVariable String nickname) {
        if (!configs.delete(nickname)) throw notFound("拆分配置不存在: " + nickname);
        return Map.of("deleted", nickname);
    }

    // ---- 拆分作业 ----

    /** 提交拆分：inputs.a 必填、inputs.b 可选；config 内联完整规则（可从 /split-configs 载入）。 */
    @PostMapping("/split")
    public Map<String, Object> submit(@RequestBody Map<String, Object> payload) {
        SplitConfig cfg = com.textdiff.store.Json.read(
                com.textdiff.store.Json.write(payload.get("config")), SplitConfig.class);
        validate(cfg, false);
        Map<String, Object> inputs = castMap(payload.get("inputs"));
        if (inputs == null) throw bad("缺少输入文件（A 区必填）");
        String label = payload.get("label") == null ? "" : String.valueOf(payload.get("label"));
        try {
            SplitJob job = manager.submit(label, cfg,
                    str(inputs.get("a")), str(inputs.get("b")));
            return Map.of("job_id", job.jobId());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    @GetMapping("/split-jobs/{id}")
    public Map<String, Object> meta(@PathVariable String id) {
        SplitJob job = require(id);
        return Map.of("meta", job);
    }

    /** 组内原始行分页浏览（side=a|b，group=组名；行文本原样返回）。 */
    @GetMapping("/split-jobs/{id}/rows")
    public Map<String, Object> rows(@PathVariable String id,
                                    @RequestParam(defaultValue = "a") String side,
                                    @RequestParam String group,
                                    @RequestParam(defaultValue = "0") long offset,
                                    @RequestParam(defaultValue = "200") long limit) {
        SplitJob job = require(id);
        SplitJob.Side sideRec = sideRec(job, side);
        if (sideRec == null) throw bad("该侧无输入文件");
        long total = 0;
        for (SplitJob.GroupCount g : sideRec.groups()) {
            if (g.name().equals(group)) total = g.count();
        }
        Path file = Path.of(job.resultDir()).resolve(SplitManager.sanitize(side) + "_"
                + SplitManager.sanitize(group) + ".txt");
        List<String> rows = new ArrayList<>();
        if (total > 0 && Files.isRegularFile(file)) {
            try (var br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                long pos = 0;
                String line;
                while (pos < offset + limit && (line = br.readLine()) != null) {
                    if (pos++ >= offset) rows.add(line);
                }
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "拆分产物读取失败");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        return out;
    }

    /**
     * 导出：fmt=zip → 压缩包（bucket=matched 符合条件=全部规则组 / other 不符合条件=其他组 / 缺省=全部组）；
     * fmt=xlsx → 拆分汇总表（规则明细 + 各侧各组行数）。
     */
    @GetMapping("/split-jobs/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(defaultValue = "zip") String fmt,
                                         @RequestParam(required = false) String bucket) {
        SplitJob job = require(id);
        if ("xlsx".equalsIgnoreCase(fmt)) return xlsxDownload(job);
        if (!"zip".equalsIgnoreCase(fmt)) throw bad("fmt 仅支持 zip / xlsx");
        return zipDownload(job, bucket == null || bucket.isBlank() ? "all" : bucket);
    }

    @DeleteMapping("/split-jobs/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        SplitJob job = require(id);
        jobs.delete(id);
        deleteDir(Path.of(job.resultDir()));
        return Map.of("deleted", id);
    }

    @PostMapping("/split-jobs/{id}/label")
    public Map<String, Object> setLabel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        SplitJob job = require(id);
        String label = body.get("label") == null ? "" : String.valueOf(body.get("label"));
        jobs.save(new SplitJob(job.jobId(), label, job.status(), job.error(), job.config(), job.inputs(),
                job.summary(), job.resultDir(), job.createdAt(), job.finishedAt()));
        return Map.of("label", label);
    }

    // ---- 导出实现 ----

    /** bucket=matched → 规则组；other → 其他组；all → 全部。文件名 {侧}_{组名}.txt。 */
    private ResponseEntity<byte[]> zipDownload(SplitJob job, String bucket) {
        boolean matched = "matched".equals(bucket);
        boolean other = "other".equals(bucket);
        List<String> wanted = groupNames(job, matched, other);
        Path dir = Path.of(job.resultDir());
        List<Path> files = new ArrayList<>();
        for (String side : new String[]{"a", "b"}) {
            if (sideRec(job, side) == null) continue;
            for (String g : wanted) {
                Path f = dir.resolve(SplitManager.sanitize(side) + "_" + SplitManager.sanitize(g) + ".txt");
                if (Files.isRegularFile(f)) files.add(f);
            }
        }
        if (files.isEmpty()) throw notFound("拆分产物尚未生成或该侧无输入");
        Path zip;
        try {
            Path tmp = Files.createTempDirectory("split-export");
            String base = exportBase(job);
            String name = switch (bucket) {
                case "matched" -> base + "_符合条件.zip";
                case "other" -> base + "_不符合条件.zip";
                default -> base + "_各组文本.zip";
            };
            zip = tmp.resolve(name);
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip),
                    java.nio.charset.Charset.forName("UTF-8"))) {
                for (Path f : files) {
                    z.putNextEntry(new ZipEntry(f.getFileName().toString())); // {侧}_{组名}.txt
                    z.write(Files.readAllBytes(f));
                    z.closeEntry();
                }
            }
            byte[] body = Files.readAllBytes(zip);
            deleteDir(tmp);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''"
                            + urlEncode(zip.getFileName().toString()))
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(body);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "拆分导出失败");
        }
    }

    /** 组名清单（保持规则序，仅含有产出的组）：matched=规则组、other=其他、all=两者。 */
    private List<String> groupNames(SplitJob job, boolean matched, boolean other) {
        List<String> names = new ArrayList<>();
        SplitJob.Side side = sideRec(job, "a") != null ? sideRec(job, "a") : sideRec(job, "b");
        if (side == null) return names;
        for (SplitJob.GroupCount g : side.groups()) {
            boolean isOther = SplitManager.OTHER_GROUP.equals(g.name());
            if ((matched && !isOther) || (other && isOther) || (!matched && !other)) {
                names.add(g.name());
            }
        }
        return names;
    }

    /** 汇总 xlsx：Sheet「拆分汇总」= 规则明细 + 概览（各侧总行/尾部行/各组行数）。 */
    private ResponseEntity<byte[]> xlsxDownload(SplitJob job) {
        try {
            Path tmp = Files.createTempDirectory("split-xlsx");
            Path out = tmp.resolve(exportBase(job) + "_拆分汇总.xlsx");
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                Sheet sh = wb.createSheet("拆分汇总");
                int r = 0;
                Row head = sh.createRow(r++);
                head.createCell(0).setCellValue("侧");
                head.createCell(1).setCellValue("组名");
                head.createCell(2).setCellValue("行数");
                for (String side : new String[]{"a", "b"}) {
                    SplitJob.Side s = sideRec(job, side);
                    if (s == null) continue;
                    for (SplitJob.GroupCount g : s.groups()) {
                        Row row = sh.createRow(r++);
                        row.createCell(0).setCellValue(side.toUpperCase());
                        row.createCell(1).setCellValue(g.name());
                        row.createCell(2).setCellValue(g.count());
                    }
                    Row t = sh.createRow(r++);
                    t.createCell(0).setCellValue(side.toUpperCase());
                    t.createCell(1).setCellValue("（尾部区行数）");
                    t.createCell(2).setCellValue(s.trailer());
                }
                Row rules = sh.createRow(r++);
                rules.createCell(0).setCellValue("拆分规则");
                rules.createCell(1).setCellValue(ruleSummary(job));
                for (int c = 0; c < 3; c++) sh.setColumnWidth(c, c == 2 ? 10 * 256 : 40 * 256);
                try (var os = Files.newOutputStream(out)) {
                    wb.write(os);
                }
            }
            byte[] body = Files.readAllBytes(out);
            deleteDir(tmp);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''"
                            + urlEncode(out.getFileName().toString()))
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(body);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "汇总导出失败");
        }
    }

    /** 规则摘要文本（组名: 条件 且/或 条件；…；其他=未命中）。 */
    static String ruleSummary(SplitJob job) {
        StringBuilder sb = new StringBuilder();
        for (SplitConfig.Rule r : job.config().rules()) {
            if (sb.length() > 0) sb.append("；");
            sb.append(r.name() == null || r.name().isBlank() ? "组" : r.name()).append("：");
            String join = r.any() ? " 或 " : " 且 ";
            List<String> parts = new ArrayList<>();
            for (SplitConfig.Cond c : r.conditions()) {
                parts.add("列" + c.col() + " " + c.op() + " " + c.value());
            }
            sb.append(String.join(join, parts));
        }
        sb.append("；其他：未命中以上规则的记录");
        return sb.toString();
    }

    private static String exportBase(SplitJob job) {
        String name = job.label() != null && !job.label().isBlank() ? job.label() : "拆分_" + job.jobId();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ---- 校验与辅助 ----

    /** 配置校验：昵称（落库必填）、条件结构、op 枚举、正则可编译、range 数字区间。 */
    private static void validate(SplitConfig c, boolean requireNick) {
        if (c == null) throw bad("缺少拆分配置");
        if (requireNick && (c.nickname() == null || c.nickname().isBlank())) {
            throw bad("拆分配置昵称不能为空");
        }
        if (c.rules().isEmpty()) throw bad("请至少配置一条拆分规则");
        int ri = 0;
        for (SplitConfig.Rule r : c.rules()) {
            ri++;
            if (r.conditions() == null || r.conditions().isEmpty()) {
                throw bad("规则 " + ri + " 至少需要一个条件");
            }
            int ci = 0;
            for (SplitConfig.Cond cond : r.conditions()) {
                ci++;
                if (cond.col() < 1) throw bad("规则 " + ri + " 条件 " + ci + "：栏位序号须 ≥1");
                String op = cond.op() == null ? "" : cond.op().strip().toLowerCase();
                boolean known = false;
                for (String k : OPS) known |= k.equals(op);
                if (!known) throw bad("规则 " + ri + " 条件 " + ci + "：未知条件类型 " + cond.op());
                String val = cond.value() == null ? "" : cond.value();
                if (!"range".equals(op) && val.isBlank()) {
                    throw bad("规则 " + ri + " 条件 " + ci + "：条件值不能为空");
                }
                if ("regex".equals(op)) {
                    try {
                        java.util.regex.Pattern.compile(val);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        throw bad("规则 " + ri + " 条件 " + ci + "：正则无效（" + e.getDescription() + "）");
                    }
                }
                if ("range".equals(op)) {
                    String[] parts = val.split("~", -1);
                    if (parts.length > 2 || (parts[0].isBlank() && (parts.length < 2 || parts[1].isBlank()))) {
                        throw bad("规则 " + ri + " 条件 " + ci + "：范围格式应为 起~止（可留空一侧）");
                    }
                    try {
                        java.math.BigDecimal lo = parts[0].isBlank() ? null : new java.math.BigDecimal(parts[0].strip());
                        java.math.BigDecimal hi = parts.length < 2 || parts[1].isBlank()
                                ? null : new java.math.BigDecimal(parts[1].strip());
                        if (lo != null && hi != null && lo.compareTo(hi) > 0) {
                            throw bad("规则 " + ri + " 条件 " + ci + "：范围起点不能大于止点");
                        }
                    } catch (NumberFormatException e) {
                        throw bad("规则 " + ri + " 条件 " + ci + "：范围需为数字（起~止）");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static SplitJob.Side sideRec(SplitJob job, String side) {
        SplitJob.Summary s = job.summary();
        if (s == null) return null;
        return "b".equals(side) ? s.b() : s.a();
    }

    private SplitJob require(String id) {
        SplitJob job = jobs.get(id);
        if (job == null) throw notFound("拆分作业不存在: " + id);
        return job;
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static void deleteDir(Path dir) {
        if (dir == null || !Files.isRegularFile(dir) && !Files.isDirectory(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
