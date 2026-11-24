package com.textdiff.textline;

import com.textdiff.store.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 普通文本比对配置：主键 = 每行指定字符位置区间（1-based，行首为 1）提取的字符串，
 * 未配置区间时整行作主键；配对后对整行数据逐字核对。
 *
 * 区间格式：{@code "3-5"}（第 3~5 位）、{@code "7"}（等价 7-7）、多区间逗号分隔
 * {@code "1-3,8-10"}（各段提取值用内部连接符拼接，展示时显示为冒号）。
 * 配置以 JSON 存于作业 configLine，跨重启可解析；命名配置经 TextLineConfigStore 复用。
 */
public record TextLineFormat(String keyPositions, String encodingA, String encodingB) {

    public TextLineFormat(String keyPositions, String encodingA, String encodingB) {
        this.keyPositions = keyPositions == null ? "" : keyPositions.strip();
        this.encodingA = enc(encodingA);
        this.encodingB = enc(encodingB);
    }

    private static String enc(String v) {
        return v == null || v.isBlank() ? "auto" : v.strip();
    }

    /** 主键区间（按需解析）：每项 {start, end}（1-based 含端点）；空数组 = 整行作键。 */
    public int[][] ranges() {
        return parseRanges(keyPositions);
    }

    /** 区间解析：空/全空白 = 整行作键（返回空数组）；语法非法抛 IllegalArgumentException。 */
    static int[][] parseRanges(String spec) {
        if (spec == null || spec.isBlank()) return new int[0][];
        List<int[]> out = new ArrayList<>();
        for (String part : spec.split("[,，]")) {
            String t = part.strip();
            if (t.isEmpty()) continue;
            String[] se = t.split("[-~]");
            try {
                int start = Integer.parseInt(se[0].strip());
                int end = se.length > 1 ? Integer.parseInt(se[1].strip()) : start;
                if (start < 1) throw new IllegalArgumentException("主键区间起始位须 ≥ 1：" + t);
                if (end < start) throw new IllegalArgumentException("主键区间止位须 ≥ 起始位：" + t);
                out.add(new int[]{start, end});
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("主键区间格式无效（应为 1-based 位置，如 3-5）：" + t);
            }
        }
        return out.toArray(new int[0][]);
    }

    /** 编译并校验（非法区间抛出）；返回自身便于链式构造。 */
    public TextLineFormat validated() {
        ranges(); // 触发区间语法校验
        return this;
    }

    /** 构造入口（Controller/前端提交用）：立即校验主键区间语法。 */
    public static TextLineFormat of(String keyPositions, String encodingA, String encodingB) {
        return new TextLineFormat(keyPositions, encodingA, encodingB).validated();
    }

    public boolean wholeLineKey() {
        return ranges().length == 0;
    }

    /** 行主键：按区间提取（区间越界部分截断、行过短时该段为空串）；未配置区间取整行。 */
    public String keyOf(String line) {
        int[][] ranges = ranges();
        if (ranges.length == 0) return line;
        StringBuilder sb = new StringBuilder();
        for (int[] r : ranges) {
            if (sb.length() > 0) sb.append(com.textdiff.engine.Rules.KEY_SEP);
            int from = Math.min(r[0] - 1, line.length());
            int to = Math.min(r[1], line.length());
            if (from < to) sb.append(line, from, to);
        }
        return sb.toString();
    }

    /** configLine 编解码（JSON，键 keypos/enca/encb；fromConfigLine 忽略 srca/srcb 等附加键）。 */
    public String toConfigLine() {
        return toConfigLine(null, null);
    }

    /** 带 A/B 来源命名的 configLine：值非空且非默认侧名才写入（供批次总结/结果页沿用命名）。 */
    public String toConfigLine(String srca, String srcb) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("keypos", keyPositions);
        m.put("enca", encodingA);
        m.put("encb", encodingB);
        if (srca != null && !srca.isBlank() && !srca.strip().equals("A")) m.put("srca", srca.strip());
        if (srcb != null && !srcb.isBlank() && !srcb.strip().equals("B")) m.put("srcb", srcb.strip());
        return Json.write(m);
    }

    public static TextLineFormat fromConfigLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = Json.read(line, Map.class);
            return new TextLineFormat(str(m, "keypos"), str(m, "enca"), str(m, "encb"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }
}
