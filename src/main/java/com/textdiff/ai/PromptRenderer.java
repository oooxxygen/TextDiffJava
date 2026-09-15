package com.textdiff.ai;

import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.Rules;
import com.textdiff.engine.Summary;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词渲染：内置模板 resources/prompts/analysis-template.md（configs/analysis-template.md 可覆盖）
 * + 差异特征 + 栏位属性（源系统字段配置的字段名/类型/长度）→ results/{jobId}/prompt.md 落盘
 * （审计与跨环境移植载体）。
 */
public final class PromptRenderer {
    public static final String TEMPLATE_NAME = "analysis-template.md";
    public static final String TEMPLATE_VERSION = "v2";

    /**
     * 提示词预算：适配小上下文窗口（≤256K）。
     * FULL 适合大窗口；COMPACT 压缩采样、聚焦 top 差异列、裁剪栏位属性表。
     */
    public record Budget(int maxSamplesPerColumn, int maxColumns, int maxAttrRows, String note) {
        public static final Budget FULL = new Budget(10, Integer.MAX_VALUE, 4096, "");
        public static final Budget COMPACT = new Budget(3, 20, 64,
                "\n> ⚠ 上下文预算压缩模式：仅保留差异行数最多的前 20 个差异列、每列 3 组采样与有限栏位属性；"
                        + "完整特征可查看作业结果目录下的 result.jsonl 与 prompt 原件。\n");
    }

    private PromptRenderer() {}

    /** 渲染并写入 prompt.md（全量预算），返回文件路径。fieldMaps 可空（无栏位属性段）。 */
    public static Path render(Path resultDir, JobRecord job, JobMeta meta, Summary summary,
                              Path overrideTemplateDir, com.textdiff.store.FieldMapStore fieldMaps) {
        return render(resultDir, job, meta, summary, overrideTemplateDir, fieldMaps, Budget.FULL);
    }

    /** 渲染并写入 prompt.md，返回文件路径。fieldMaps 可空（无栏位属性段）。 */
    public static Path render(Path resultDir, JobRecord job, JobMeta meta, Summary summary,
                              Path overrideTemplateDir, com.textdiff.store.FieldMapStore fieldMaps,
                              Budget budget) {
        String template = loadTemplate(overrideTemplateDir);
        DiffFeatureExtractor.Features features = DiffFeatureExtractor.extract(
                resultDir.resolve(ResultFiles.RESULT_JSONL));
        String text = fill(template, placeholders(resultDir, job, meta, summary, features, fieldMaps, budget));
        Path out = resultDir.resolve("prompt.md");
        try {
            Files.writeString(out, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompt.md 写入失败", e);
        }
        return out;
    }

    static String loadTemplate(Path overrideDir) {
        if (overrideDir != null) {
            Path p = overrideDir.resolve(TEMPLATE_NAME);
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            }
        }
        try (InputStream in = PromptRenderer.class.getResourceAsStream("/prompts/" + TEMPLATE_NAME)) {
            if (in == null) throw new IllegalStateException("内置提示词模板缺失");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("内置提示词模板读取失败", e);
        }
    }

    static Map<String, String> placeholders(Path resultDir, JobRecord job, JobMeta meta, Summary summary,
                                            DiffFeatureExtractor.Features features,
                                            com.textdiff.store.FieldMapStore fieldMaps, Budget budget) {
        CompareConfig cfg = Rules.parseLegacy(job.configLine, " | ", "|||||");
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        ph.put("templateVersion", TEMPLATE_VERSION);
        ph.put("nickname", job.nickname);
        ph.put("fileA", job.fileA);
        ph.put("fileB", job.fileB);
        ph.put("encodingA", meta != null ? nvl(meta.encodingA) : "未知");
        ph.put("encodingB", meta != null ? nvl(meta.encodingB) : "未知");
        ph.put("keySeq", ApiSeq.seq1based(cfg.keyColumns));
        ph.put("keyColumns", intList(cfg.keyColumns));
        ph.put("omitSeq", ApiSeq.seq1based(cfg.omitColumns));
        ph.put("omitColumns", intList(cfg.omitColumns));
        java.util.Map<Integer, String> names = colNames(job, cfg, fieldMaps);
        ph.put("keyColumnsDesc", describeCols(cfg.keyColumns, names));
        ph.put("omitColumnsDesc", describeCols(cfg.omitColumns, names));
        ph.put("delimiter", cfg.delimiter);
        ph.put("fieldAttributes", fieldAttributeTable(job, fieldMaps, budget));

        StringBuilder tt = new StringBuilder();
        if (summary != null && summary.trailerFields != null && !summary.trailerFields.isEmpty()) {
            tt.append("| 字段 | A 值 | B 值 | 一致 |\n|---|---|---|---|\n");
            summary.trailerFields.forEach((k, v) -> tt.append("| ").append(k).append(" | ")
                    .append(nvl(v.a())).append(" | ").append(nvl(v.b())).append(" | ")
                    .append(v.equal() ? "✓" : "✗").append(" |\n"));
            tt.append("\nRecNum 校验：A=").append(summary.recnumCheckA).append("，B=").append(summary.recnumCheckB);
        } else {
            tt.append("（无 trailer 数据）");
        }
        ph.put("trailerTable", tt.toString());

        StringBuilder ku = new StringBuilder();
        if (summary != null) {
            if (summary.keyDupA == 0 && summary.keyDupB == 0) {
                ku.append("A/B 两侧主键均能唯一定位记录，无需重检。");
            } else {
                ku.append("<span style=\"color:red\">**🔴 主键存在重复，无法唯一定位记录：A 侧重复 ")
                        .append(summary.keyDupA).append(" 次，B 侧重复 ").append(summary.keyDupB)
                        .append(" 次（样例：").append(String.join("、", summary.dupKeySamples))
                        .append("），该对比配置需重检**</span>");
            }
            if (summary.malformedA > 0 || summary.malformedB > 0) {
                ku.append("（空键/解析异常行：A=").append(summary.malformedA).append("，B=").append(summary.malformedB).append("）");
            }
        }
        ph.put("keyUniqueness", ku.toString());

        StringBuilder ov = new StringBuilder();
        if (summary != null) {
            long dev = summary.totalB - summary.totalA;
            double devPct = summary.totalA == 0 ? 0 : Math.abs(dev) * 100.0 / summary.totalA;
            ov.append("- A（旧）总条数 ").append(summary.totalA).append("，B（新）总条数 ").append(summary.totalB)
                    .append("；B 相对 A 偏离 ").append(dev >= 0 ? "+" : "").append(dev).append(" 条（")
                    .append(String.format("%.2f", devPct)).append("%）\n");
            ov.append("- 完全一致 ").append(summary.equal).append(" 条，有差异 ").append(summary.diff)
                    .append(" 条，仅 A（B 中缺失）").append(summary.onlyA).append(" 条，仅 B（新增）")
                    .append(summary.onlyB).append(" 条\n");
            if (summary.diffColFreq != null && !summary.diffColFreq.isEmpty()) {
                ov.append("\n| 差异栏位 | 差异条数 | 占 B 总条数比 |\n|---|---|---|\n");
                summary.diffColFreq.forEach((c, n) -> {
                    String name = names.getOrDefault(c, "栏位" + (c + 1));
                    double pct = summary.totalB == 0 ? 0 : n * 100.0 / summary.totalB;
                    ov.append("| 第").append(c + 1).append("列 ").append(name)
                            .append(" | ").append(n)
                            .append(" | ").append(String.format("%.2f", pct)).append("% |\n");
                });
            } else {
                ov.append("\n本次对比无任何差异列——动态分析部分省略。");
            }
        }
        ph.put("diffColOverview", ov.toString());

        StringBuilder cf = new StringBuilder();
        // 预算：紧凑模式按差异行数取 top N 列
        java.util.List<DiffFeatureExtractor.ColumnFeature> cols =
                new java.util.ArrayList<>(features.columns.values());
        if (cols.size() > budget.maxColumns()) {
            cols.sort(java.util.Comparator.comparingLong((DiffFeatureExtractor.ColumnFeature f) -> f.count)
                    .reversed());
            cols = cols.subList(0, budget.maxColumns());
        }
        for (DiffFeatureExtractor.ColumnFeature f : cols) {
            String name = names.getOrDefault(f.col, "栏位" + (f.col + 1));
            cf.append("### 列 ").append(f.col).append("（").append(name).append("），差异 ").append(f.count).append(" 行\n");
            if (!f.commonPrefix.isEmpty() || !f.commonSuffix.isEmpty()) {
                cf.append("- 公共前缀：`").append(f.commonPrefix).append("`；公共后缀：`").append(f.commonSuffix).append("`\n");
            }
            if (f.numericDeltaMode != null) {
                cf.append("- 数值差恒定：B − A ≈ `").append(f.numericDeltaMode).append("`（覆盖 ").append(f.equalNumericDiff)
                        .append("/").append(f.numericPairs).append(" 个数值样本）\n");
            }
            if (f.dateShaped > 0) {
                cf.append("- 日期形态样本 ").append(f.dateShaped).append(" 个\n");
            }
            if (f.emptyA + f.emptyB > 0) {
                cf.append("- 空值样本：A ").append(f.emptyA).append("，B ").append(f.emptyB).append("\n");
            }
            if (f.lengthGrows + f.lengthShrinks > 0) {
                cf.append("- 长度变化：变长 ").append(f.lengthGrows).append("，变短 ").append(f.lengthShrinks).append("\n");
            }
            cf.append("- 采样值对（A → B）：\n");
            int shown = 0;
            for (String[] s : f.samples) {
                if (shown++ >= budget.maxSamplesPerColumn()) {
                    cf.append("  - …（其余 ").append(f.samples.size() - shown + 1).append(" 对省略）\n");
                    break;
                }
                cf.append("  - `").append(oneLine(s[0])).append("` → `").append(oneLine(s[1])).append("`\n");
            }
        }
        if (cf.isEmpty()) cf.append("（无差异列——动态分析部分省略）");
        ph.put("columnFeatures", cf.toString());

        ph.put("dynamicSection", features.columns.isEmpty()
                ? ""
                : "## 四、AI 动态分析要求\n\n请针对上方特征给出：数值分布规律、成因假设、改进意见（按输出格式要求）。");

        StringBuilder hints = new StringBuilder();
        for (com.textdiff.engine.RowDiff ignored : features.unmatchedSamples) {
            hints.append("（仅 A 或仅 B 的记录样例已计入差异概况，注意两侧文件完整性分析）\n");
            break;
        }
        ph.put("aiHints", hints.toString());
        return ph;
    }

    static String fill(String template, Map<String, String> placeholders) {
        String out = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    /**
     * 栏位属性表（源系统字段配置 bat_report_conf_field：字段名/field_format 类型/field_length 长度），
     * 注入提示词辅助 AI 归纳分析；无映射时给出说明占位。行数按预算裁剪（compact 优先保留差异列）。
     */
    public static String fieldAttributeTable(JobRecord job, com.textdiff.store.FieldMapStore maps) {
        return fieldAttributeTable(job, maps, Budget.FULL);
    }

    public static String fieldAttributeTable(JobRecord job, com.textdiff.store.FieldMapStore maps, Budget budget) {
        if (maps == null || job.fileA == null || job.fileA.isBlank()) {
            return "（未导入源系统字段配置，无栏位属性）";
        }
        String reportId = maps.reportIdForFile(job.fileA);
        java.util.List<com.textdiff.store.FieldMaps.ReportField> fs =
                reportId == null ? java.util.List.of() : maps.fieldsFor(reportId);
        if (fs.isEmpty()) return "（该文件未匹配到源系统字段配置，无栏位属性）";
        java.util.Set<Integer> diffCols = summaryDiffCols(job);
        boolean filter = fs.size() > budget.maxAttrRows();
        if (filter) {
            java.util.List<com.textdiff.store.FieldMaps.ReportField> picked = new java.util.ArrayList<>();
            for (com.textdiff.store.FieldMaps.ReportField f : fs) {
                if (diffCols.contains(f.colIndex())) picked.add(f);
            }
            if (picked.isEmpty()) picked = fs.subList(0, budget.maxAttrRows());
            fs = picked.size() > budget.maxAttrRows()
                    ? picked.subList(0, budget.maxAttrRows()) : picked;
        }
        StringBuilder sb = new StringBuilder();
        if (filter) {
            sb.append("> 报表共 ").append(maps.fieldsFor(reportId).size())
                    .append(" 个字段，此处仅保留与差异列相关的栏位属性。\n\n");
        }
        sb.append("| 列(1-based) | 字段名 | 类型 field_format | 长度 field_length |\n");
        sb.append("|---|---|---|---|\n");
        for (com.textdiff.store.FieldMaps.ReportField f : fs) {
            sb.append("| ").append(f.colIndex() + 1)
                    .append(" | ").append(f.fieldName())
                    .append(" | ").append(f.fieldFormat().isEmpty() ? "—" : f.fieldFormat())
                    .append(" | ").append(f.fieldLength() == null || f.fieldLength().isEmpty()
                            ? "—" : f.fieldLength())
                    .append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 从 summary 提取差异列集合（0-based），供属性表裁剪时优先保留。读取失败返回空集。 */
    private static java.util.Set<Integer> summaryDiffCols(JobRecord job) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        try {
            Summary s = ResultFiles.readSummary(Path.of(job.resultDir));
            if (s != null && s.diffColFreq != null) out.addAll(s.diffColFreq.keySet());
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 0-based 列号 → 字段名。优先源系统字段配置（bat_report_conf_field），次选规则解析到的表头，
     * 均无时回落"栏位N"。供主键/跳过栏位"列数+字段名"描述与差异栏位表使用。
     */
    private static java.util.Map<Integer, String> colNames(JobRecord job, CompareConfig cfg,
                                                           com.textdiff.store.FieldMapStore maps) {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        for (int i = 0; i < cfg.columnNames.size(); i++) out.put(i, cfg.columnNames.get(i));
        if (maps != null && job.fileA != null && !job.fileA.isBlank()) {
            String reportId = maps.reportIdForFile(job.fileA);
            if (reportId != null) {
                for (com.textdiff.store.FieldMaps.ReportField f : maps.fieldsFor(reportId)) {
                    out.putIfAbsent(f.colIndex(), f.fieldName());
                }
            }
        }
        return out;
    }

    /** "共 N 列（第X列 字段A、第Y列 字段B…）"描述；空序列返回"无"。 */
    private static String describeCols(java.util.List<Integer> cols, java.util.Map<Integer, String> names) {
        if (cols == null || cols.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder("共 ").append(cols.size()).append(" 列（");
        for (int i = 0; i < cols.size(); i++) {
            int c = cols.get(i);
            if (i > 0) sb.append("、");
            sb.append("第").append(c + 1).append("列 ").append(names.getOrDefault(c, "栏位" + (c + 1)));
        }
        return sb.append("）").toString();
    }

    private static String intList(java.util.List<Integer> list) {
        if (list == null || list.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (int c : list) sb.append(c).append(" ");
        return sb.toString().trim();
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "未知" : s;
    }

    /** 1-based 序列串工具（供模板占位符）。 */
    static final class ApiSeq {
        static String seq1based(java.util.List<Integer> cols) {
            if (cols == null || cols.isEmpty()) return "无";
            StringBuilder sb = new StringBuilder();
            for (int c : cols) sb.append(c + 1).append("/");
            return sb.substring(0, sb.length() - 1);
        }
    }
}
