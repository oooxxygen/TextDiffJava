package com.textdiff.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.Rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 前端提交载荷 → legacy 配置行 / 配置展示对象 的公共转换。 */
final class ApiPayloads {
    private ApiPayloads() {}

    /** /api/compare 与 /api/jobs/{id}/rerun 的请求体（snake_case，缺省字段为 null）。 */
    record ComparePayload(
            String config_text, String nickname, String file_glob,
            String delimiter, String trailer_prefix,
            String encoding_a, String encoding_b, String source_a, String source_b,
            String key_seq, String omit_seq, List<String> column_names,
            JsonNode replace_rules, String label, String group,
            List<Pair> pairs) {
        record Pair(String a, String b) {}
    }

    /** 拼装 legacy 行（1-based 序列），再经 parseLegacy+toLegacyLine 规范化。 */
    static String buildLegacyLine(ComparePayload p) {
        if (p.config_text() != null && !p.config_text().isBlank()) {
            return Rules.toLegacyLine(Rules.parseLegacy(p.config_text(),
                    JobDefaults.DELIM, JobDefaults.TRAILER), true);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(p.nickname() == null || p.nickname().isBlank() ? "JOB" : p.nickname().strip());
        sb.append(':');
        sb.append(p.file_glob() == null || p.file_glob().isBlank() ? "*" : p.file_glob().strip());
        if (p.key_seq() != null && !p.key_seq().isBlank()) sb.append(":KEYSEQ=").append(p.key_seq().strip());
        if (p.omit_seq() != null && !p.omit_seq().isBlank()) sb.append(":OMITSEQ=").append(p.omit_seq().strip());
        if (p.column_names() != null && !p.column_names().isEmpty()) {
            sb.append(":COLS=").append(Rules.encodeStringList(p.column_names()));
        }
        if (p.delimiter() != null) sb.append(":DELIM=").append(p.delimiter());
        sb.append(":ENCA=").append(p.encoding_a() == null || p.encoding_a().isBlank() ? "auto" : p.encoding_a().strip());
        sb.append(":ENCB=").append(p.encoding_b() == null || p.encoding_b().isBlank() ? "auto" : p.encoding_b().strip());
        sb.append(":SRCA=").append(p.source_a() == null || p.source_a().isBlank() ? "A" : p.source_a().strip());
        sb.append(":SRCB=").append(p.source_b() == null || p.source_b().isBlank() ? "B" : p.source_b().strip());
        sb.append(":TRAILER=").append(p.trailer_prefix() == null ? "" : p.trailer_prefix());
        return Rules.toLegacyLine(Rules.parseLegacy(sb.toString(),
                JobDefaults.DELIM, JobDefaults.TRAILER), true);
    }

    /** 配置对象 → 前端展示形态（meta.config / configs 列表项）。0-based 数组 + 1-based 序列串。 */
    static Map<String, Object> configMap(CompareConfig cfg, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nickname", cfg.nickname);
        m.put("file_glob", cfg.fileGlob);
        m.put("delimiter", cfg.delimiter);
        m.put("trailer_prefix", cfg.trailerPrefix);
        m.put("encoding_a", cfg.encodingA);
        m.put("encoding_b", cfg.encodingB);
        m.put("source_a", cfg.sourceA);
        m.put("source_b", cfg.sourceB);
        m.put("key_columns", cfg.keyColumns);
        m.put("omit_columns", cfg.omitColumns);
        m.put("ignore_columns", cfg.ignoreColumns);
        m.put("key_seq", seq1based(cfg.keyColumns));
        m.put("omit_seq", seq1based(cfg.omitColumns));
        m.put("column_names", cfg.columnNames);
        m.put("replace_rules", replaceRulesMap(cfg.replaceRules));
        m.put("group", cfg.group == null || cfg.group.isEmpty() ? cfg.nickname : cfg.group);
        m.put("_source", source);
        return m;
    }

    static String seq1based(List<Integer> cols) {
        if (cols == null || cols.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (int c : cols) out.add(String.valueOf(c + 1));
        return String.join("/", out);
    }

    /** {列号 -> [[pattern, repl], ...]}（0-based 键字符串）。 */
    static Map<String, Object> replaceRulesMap(Map<Integer, List<String[]>> rr) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String[]>> e : rr.entrySet()) {
            List<List<String>> rules = new ArrayList<>();
            for (String[] pair : e.getValue()) rules.add(List.of(pair[0], pair[1]));
            out.put(String.valueOf(e.getKey()), rules);
        }
        return out;
    }

    /** JobManager 与控制器共享的默认值。 */
    static final class JobDefaults {
        static final String DELIM = " | ";
        static final String TRAILER = "|||||";
        private JobDefaults() {}
    }
}
