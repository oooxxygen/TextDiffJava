package com.textdiff.export;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 批次情况总结 HTML：三段结构齐全、双指标偏离度、分区搜索（表名/归属组）、A/B 命名联动、HTML 转义。 */
class BatchSummaryHtmlTest {

    /** kv 交替 → LinkedHashMap（Map.of 上限 10 对，测试载荷更长）。 */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void rendersSectionsDualMetricsSearchAndEscapes() {
        Map<String, Object> p = map(
                "batch_id", "ab12cd34",
                "label", "夜批 <核对>",
                "status", "done",
                "total_files", 12,
                "diff_files", 3,
                "done", 12,
                "failed", 0,
                "no_rule_count", 1,
                "missing_count", 0,
                "src_a", "旧线",
                "src_b", "新线",
                "dir_a", "O:\\A",
                "dir_b", "O:\\B",
                "group_name", "日常对比配置",
                "started_at", 1770000000L,
                "finished_at", 1770000100L,
                "generated_at", 1770000200L,
                "deviations", List.of(map(
                        "nickname", "INCT0101",
                        "group", "卡中心",
                        "file_name", "01A3020D.v01",
                        "content_ratio", 0.25,
                        "content_grade", "务必关注",
                        "count_ratio", 0.5,
                        "count_grade", "严重问题",
                        "equal", 6, "diff", 2, "only_a", 0, "only_b", 0,
                        "total_a", 8, "total_b", 12)),
                "discard", map(
                        "hit", true,
                        "src_a", "旧线",
                        "src_b", "新线",
                        "status", "hit",
                        "rows", List.of(map(
                                "nickname", "INCT0101",
                                "group", "卡中心",
                                "file_name", "01A3020D.v01",
                                "col_seq", 27,
                                "field_name", "MERCH_COUNTRY_CODE",
                                "field_meaning", "国家码<script>",
                                "values_a", List.of("CN", "", "US"),
                                "values_b", List.of("CN", "US"),
                                "truncated_a", false, "truncated_b", false,
                                "file_missing", false,
                                "matched", true))));
        String html = BatchSummaryHtml.render(p);

        List<String> missing = new java.util.ArrayList<>(List.of(
                "批次情况总结", "批次概览", "文本偏离度（全部文本）", "内容偏离度", "条数偏离度",
                "对比范围", "分组「日常对比配置」（仅分组成员命中的文本）",
                "关注字段取值监测", "旧线的所有取值", "新线的所有取值", "CN、（空）、US",
                "是否匹配", "新线输出正常", "b-green",
                "25.0%", "50.0%", "务必关注", "严重问题", "微量偏移",
                "data-nick=\"INCT0101\"", "data-group=\"卡中心\"",
                "国家码&lt;script&gt;"));
        missing.removeIf(html::contains);
        assertTrue(missing.isEmpty(), "缺失内容: " + missing + "；html 长度 " + html.length());
        // 双指标徽标配色类
        assertTrue(html.contains("b-yellow"));
        assertTrue(html.contains("b-red"));
        assertTrue(html.contains("b-purple"));
        // 分区搜索：两分区各有 表名/归属组 输入 + 过滤脚本（离线可用）
        assertEquals(2, count(html, "data-role=\"nick\""));
        assertEquals(2, count(html, "data-role=\"group\""));
        assertTrue(html.contains("function tdFilter"));
        // HTML 转义：注入内容不裸露（内联过滤脚本自身的 script 标签仅一处）
        assertFalse(html.contains("国家码<script>"));
        assertEquals(1, count(html, "<script>"));
        // 自包含：内联样式、无外部资源引用
        assertTrue(html.contains("<style>"));
        assertFalse(html.contains("src=\"http"));
        assertFalse(html.contains("href=\"http"));
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) n++;
        return n;
    }

    @Test
    void emptyStatesRenderFriendlyText() {
        Map<String, Object> p = map(
                "batch_id", "x", "label", "", "status", "running",
                "total_files", 1, "diff_files", 0, "done", 0, "failed", 0,
                "no_rule_count", 0, "missing_count", 0,
                "src_a", "A", "src_b", "B", "dir_a", "", "dir_b", "",
                "generated_at", 0L,
                "deviations", List.of(),
                "discard", map("hit", false, "status", "pending"));
        String html = BatchSummaryHtml.render(p);
        assertTrue(html.contains("无已完成文本（或全部完全匹配无差异行）。"));
        assertTrue(html.contains("批次仍在执行中，完成后自动采集。"));
        assertTrue(html.contains("执行中"));
    }
}
