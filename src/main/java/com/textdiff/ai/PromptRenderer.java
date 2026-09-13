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
 * + 差异特征 → results/{jobId}/prompt.md 落盘（审计与跨环境移植载体）。
 */
public final class PromptRenderer {
    public static final String TEMPLATE_NAME = "analysis-template.md";
    public static final String TEMPLATE_VERSION = "v1";

    private PromptRenderer() {}

    /** 渲染并写入 prompt.md，返回文件路径。 */
    public static Path render(Path resultDir, JobRecord job, JobMeta meta, Summary summary,
                              Path overrideTemplateDir) {
        String template = loadTemplate(overrideTemplateDir);
        DiffFeatureExtractor.Features features = DiffFeatureExtractor.extract(
                resultDir.resolve(ResultFiles.RESULT_JSONL));
        String text = fill(template, placeholders(resultDir, job, meta, summary, features));
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
                                            DiffFeatureExtractor.Features features) {
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
        ph.put("delimiter", cfg.delimiter);

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
                ku.append("A/B 两侧主键均唯一，无需重检。");
            } else {
                ku.append("⚠ 主键存在重复：A 侧重复 ").append(summary.keyDupA)
                        .append(" 次，B 侧重复 ").append(summary.keyDupB).append(" 次。样例：")
                        .append(String.join("、", summary.dupKeySamples))
                        .append("。请提示用户：该对比配置需要重检。");
            }
            if (summary.malformedA > 0 || summary.malformedB > 0) {
                ku.append("（空键/解析异常行：A=").append(summary.malformedA).append("，B=").append(summary.malformedB).append("）");
            }
        }
        ph.put("keyUniqueness", ku.toString());

        StringBuilder ov = new StringBuilder();
        if (summary != null) {
            ov.append("- A 总行数 ").append(summary.totalA).append("，B 总行数 ").append(summary.totalB)
                    .append("；完全一致 ").append(summary.equal).append("，有差异 ").append(summary.diff)
                    .append("，仅 A ").append(summary.onlyA).append("，仅 B ").append(summary.onlyB).append("\n");
            if (summary.diffColFreq != null && !summary.diffColFreq.isEmpty()) {
                ov.append("\n| 差异列(0-based) | 差异行数 |\n|---|---|\n");
                summary.diffColFreq.forEach((c, n) -> {
                    String name = cfg.columnNames.size() > c ? cfg.columnNames.get(c) : "栏位" + (c + 1);
                    ov.append("| ").append(c).append("（").append(name).append("） | ").append(n).append(" |\n");
                });
            } else {
                ov.append("\n本次对比无任何差异列——动态分析部分省略。");
            }
        }
        ph.put("diffColOverview", ov.toString());

        StringBuilder cf = new StringBuilder();
        for (DiffFeatureExtractor.ColumnFeature f : features.columns.values()) {
            String name = cfg.columnNames.size() > f.col ? cfg.columnNames.get(f.col) : "栏位" + (f.col + 1);
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
                if (shown++ >= 10) {
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

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", "\\n").replace("\r", "\\r");
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
