package com.textdiff.export;

import java.util.List;
import java.util.Map;

/**
 * 批次情况总结报告（自包含 HTML，内联 CSS 无外部依赖，可离线打开/归档）。
 * 三段：批次概览指标 → 偏离度 Top 文本 → 关注字段监测表（DISCARD_COLS 同源数据）。
 */
public final class BatchSummaryHtml {
    private BatchSummaryHtml() {}

    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> p) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>批次情况总结 ").append(esc(str(p.get("batch_id")))).append("</title>\n");
        sb.append(css());
        sb.append("</head>\n<body>\n<div class=\"wrap\">\n");

        // 头部
        sb.append("<header class=\"head\">\n<h1>批次情况总结</h1>\n<div class=\"sub\">");
        sb.append(esc(str(p.get("label"))));
        sb.append(" <span class=\"bid\">批次 ").append(esc(str(p.get("batch_id")))).append("</span></div>\n");
        sb.append("<div class=\"sub2\">报告生成于 ").append(esc(fmtTime(p.get("generated_at"))));
        sb.append(" · A/B 命名：").append(esc(str(p.get("src_a"), "A")))
                .append(" / ").append(esc(str(p.get("src_b"), "B"))).append("</div>\n");
        sb.append("</header>\n");

        // 概览指标
        sb.append("<section>\n<h2>一、批次概览</h2>\n<div class=\"metrics\">\n");
        metric(sb, "状态", statusText(str(p.get("status"))), true);
        metric(sb, "文本总数", str(p.get("total_files"), "0"), false);
        metric(sb, "差异文本数", str(p.get("diff_files"), "0"), true);
        metric(sb, "已完成", str(p.get("done"), "0") + " / " + str(p.get("total_files"), "0"), false);
        metric(sb, "失败", str(p.get("failed"), "0"), Long.parseLong(str(p.get("failed"), "0")) > 0);
        metric(sb, "开始时间", fmtTime(p.get("started_at")), false);
        metric(sb, "完成时间", fmtTime(p.get("finished_at")), false);
        metric(sb, "无规则文件", str(p.get("no_rule_count"), "0"),
                Long.parseLong(str(p.get("no_rule_count"), "0")) > 0);
        metric(sb, "缺失文件", str(p.get("missing_count"), "0"),
                Long.parseLong(str(p.get("missing_count"), "0")) > 0);
        sb.append("</div>\n");
        kv(sb, "A 目录（" + str(p.get("src_a"), "A") + "）", str(p.get("dir_a")));
        kv(sb, "B 目录（" + str(p.get("src_b"), "B") + "）", str(p.get("dir_b")));
        sb.append("</section>\n");

        // 偏离度 Top
        sb.append("<section>\n<h2>二、偏离度高的文本（差异行占比 Top ")
                .append(str(p.get("top_n"), "10")).append("）</h2>\n");
        List<Map<String, Object>> top = (List<Map<String, Object>>) p.get("top_diff");
        if (top == null || top.isEmpty()) {
            sb.append("<p class=\"empty\">本批次全部文本完全匹配，无差异行。</p>\n");
        } else {
            sb.append("<table><thead><tr><th>#</th><th>昵称</th><th>文件名</th><th style=\"width:22%\">差异行占比</th>")
                    .append("<th>等级</th><th>完全匹配</th><th>有差异</th><th>仅A</th><th>仅B</th></tr></thead><tbody>\n");
            int i = 1;
            for (Map<String, Object> t : top) {
                double ratio = Double.parseDouble(str(t.get("ratio"), "0"));
                sb.append("<tr><td>").append(i++).append("</td><td>").append(esc(str(t.get("nickname")))).append("</td><td>")
                        .append(esc(str(t.get("file_name")))).append("</td>");
                sb.append("<td><div class=\"bar\"><div class=\"fill\" style=\"width:")
                        .append(Math.round(ratio * 100)).append("%\"></div><span>")
                        .append(String.format("%.1f", ratio * 100)).append("%</span></div></td>");
                sb.append("<td>").append(badge(str(t.get("grade"), "正常"))).append("</td><td>")
                        .append(str(t.get("equal"), "0")).append("</td><td><b>").append(str(t.get("diff"), "0"))
                        .append("</b></td><td>").append(str(t.get("only_a"), "0")).append("</td><td>")
                        .append(str(t.get("only_b"), "0")).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
            sb.append("<p class=\"note\">等级为记录数偏离评判（|A−B| / 均值：≥10% 保持关注、≥20% 差异明显、≥50% 严重问题）；")
                    .append("占比 =（有差异 + 仅A + 仅B）/ 全部匹配行。</p>\n");
        }
        sb.append("</section>\n");

        // 关注字段监测
        Map<String, Object> discard = (Map<String, Object>) p.getOrDefault("discard", Map.of());
        sb.append("<section>\n<h2>三、关注字段取值监测（DISCARD_COLS）</h2>\n");
        if (!Boolean.TRUE.equals(discard.get("hit"))) {
            String why = "pending".equals(str(discard.get("status")))
                    ? "批次仍在执行中，完成后自动采集。" : "本批次无可监测命中文本（未配置或昵称未命中监测表）。";
            sb.append("<p class=\"empty\">").append(esc(why)).append("</p>\n");
        } else {
            String srcA = str(discard.get("src_a"), str(p.get("src_a"), "A"));
            String srcB = str(discard.get("src_b"), str(p.get("src_b"), "B"));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) discard.get("rows");
            sb.append("<table><thead><tr><th>昵称</th><th>归属组</th><th>实际文件名</th><th>字段序号</th><th>字段名</th><th>字段含义</th><th>")
                    .append(esc(srcA)).append("的所有取值</th><th>")
                    .append(esc(srcB)).append("的所有取值</th></tr></thead><tbody>\n");
            for (Map<String, Object> r : rows) {
                sb.append("<tr><td>").append(esc(str(r.get("nickname")))).append("</td><td>")
                        .append(esc(str(r.get("group")))).append("</td><td>")
                        .append(esc(str(r.get("file_name")))).append("</td><td>")
                        .append(str(r.get("col_seq"))).append("</td><td>")
                        .append(esc(str(r.get("field_name")))).append("</td><td>")
                        .append(esc(str(r.get("field_meaning")))).append("</td><td class=\"vals\">")
                        .append(esc(valuesHtml(r, true, srcA))).append("</td><td class=\"vals\">")
                        .append(esc(valuesHtml(r, false, srcB))).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
            sb.append("<p class=\"note\">取值按首次出现顺序去重（每侧上限 ").append(DiscardColsService.MAX_VALUES)
                    .append(" 个）；（空）= 栏位存在但值为空；两侧取值范围最终应一致，不一致需人工核查。</p>\n");
        }
        sb.append("</section>\n");

        sb.append("<footer>TextDiffJava 批次情况总结 · 批次 ").append(esc(str(p.get("batch_id"))))
                .append(" · 生成于 ").append(esc(fmtTime(p.get("generated_at")))).append("</footer>\n");
        sb.append("</div>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static void metric(StringBuilder sb, String label, String value, boolean warn) {
        sb.append("<div class=\"metric").append(warn ? " warn" : "").append("\"><div class=\"mv\">")
                .append(esc(value)).append("</div><div class=\"ml\">").append(esc(label)).append("</div></div>\n");
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("<div class=\"kv\"><span>").append(esc(k)).append("</span><code>")
                .append(esc(v == null ? "" : v)).append("</code></div>\n");
    }

    private static String badge(String grade) {
        String cls = switch (grade) {
            case "严重问题" -> "b-red";
            case "差异明显" -> "b-orange";
            case "保持关注" -> "b-amber";
            default -> "b-green";
        };
        return "<span class=\"badge " + cls + "\">" + esc(grade) + "</span>";
    }

    private static String statusText(String s) {
        return switch (s == null ? "" : s) {
            case "done" -> "已完成";
            case "running" -> "执行中";
            case "preparing" -> "准备中";
            case "error" -> "异常";
            default -> s;
        };
    }

    /** 监测取值展示（HTML 转义前）：与 CSV 同语义（(空)/顿号连接/截断标注/原文缺失标注）。 */
    @SuppressWarnings("unchecked")
    static String valuesHtml(Map<String, Object> row, boolean sideA, String src) {
        if (Boolean.TRUE.equals(row.get("file_missing"))) {
            String err = str(row.get("error"), "");
            return "（" + src + " 原文不可采集" + (err.isBlank() ? "" : "：" + err) + "）";
        }
        List<String> values = (List<String>) row.get(sideA ? "values_a" : "values_b");
        StringBuilder sb = new StringBuilder();
        if (values == null || values.isEmpty()) {
            sb.append("（无数据行）");
        } else {
            for (String v : values) {
                if (sb.length() > 0) sb.append('、');
                sb.append(v == null || v.isEmpty() ? "（空）" : v);
            }
        }
        if (Boolean.TRUE.equals(row.get(sideA ? "truncated_a" : "truncated_b"))) {
            sb.append("…（超出 ").append(DiscardColsService.MAX_VALUES).append(" 个已截断）");
        }
        return sb.toString();
    }

    private static String fmtTime(Object epochSec) {
        long t = 0;
        try {
            t = Long.parseLong(str(epochSec, "0"));
        } catch (NumberFormatException ignored) {
        }
        if (t <= 0) return "—";
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochSecond(t));
    }

    private static String str(Object v, String dft) {
        return v == null ? dft : String.valueOf(v);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    private static String css() {
        return """
                <style>
                :root { color-scheme: light; }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
                       background: #f3f5f9; color: #1f2937; padding: 32px 16px; }
                .wrap { max-width: 1180px; margin: 0 auto; background: #fff; border-radius: 12px;
                        padding: 36px 40px 28px; box-shadow: 0 2px 16px rgba(15,23,42,.08); }
                .head h1 { font-size: 26px; letter-spacing: 1px; color: #0f172a; }
                .head .sub { margin-top: 8px; font-size: 16px; font-weight: 600; color: #334155; }
                .head .bid { color: #64748b; font-weight: 400; font-size: 13px; margin-left: 8px; }
                .head .sub2 { margin-top: 4px; font-size: 12.5px; color: #94a3b8; }
                section { margin-top: 30px; }
                h2 { font-size: 17px; color: #1e3a8a; border-left: 4px solid #2563eb;
                     padding-left: 10px; margin-bottom: 14px; }
                .metrics { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
                .metric { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 12px 14px; }
                .metric.warn { border-color: #fecaca; background: #fef2f2; }
                .metric .mv { font-size: 20px; font-weight: 700; color: #0f172a; word-break: break-all; }
                .metric.warn .mv { color: #b91c1c; }
                .metric .ml { font-size: 12px; color: #64748b; margin-top: 4px; }
                .kv { margin-top: 8px; font-size: 13px; color: #475569; display: flex; gap: 10px; align-items: baseline; }
                .kv span { flex: 0 0 170px; color: #64748b; }
                .kv code { font-family: Consolas, monospace; font-size: 12.5px; color: #334155; word-break: break-all; }
                table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 6px; }
                th { background: #1e3a8a; color: #fff; font-weight: 600; padding: 8px 10px; text-align: left;
                     white-space: nowrap; }
                td { border-bottom: 1px solid #e5e7eb; padding: 7px 10px; vertical-align: top; }
                tbody tr:nth-child(even) { background: #f8fafc; }
                td.vals { max-width: 280px; word-break: break-all; }
                .bar { position: relative; background: #e5e7eb; border-radius: 6px; height: 18px; min-width: 90px; }
                .bar .fill { position: absolute; inset: 0 auto 0 0; background: linear-gradient(90deg,#3b82f6,#1d4ed8);
                             border-radius: 6px; }
                .bar span { position: relative; display: block; text-align: center; line-height: 18px;
                            font-size: 12px; font-weight: 600; color: #0f172a; }
                .badge { display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 12px;
                         font-weight: 600; white-space: nowrap; }
                .b-green { background: #dcfce7; color: #15803d; }
                .b-amber { background: #fef9c3; color: #a16207; }
                .b-orange { background: #ffedd5; color: #c2410c; }
                .b-red { background: #fee2e2; color: #b91c1c; }
                .empty { color: #64748b; font-size: 13.5px; padding: 14px 4px; }
                .note { margin-top: 10px; font-size: 12px; color: #94a3b8; line-height: 1.7; }
                footer { margin-top: 34px; padding-top: 14px; border-top: 1px solid #e5e7eb;
                         font-size: 12px; color: #94a3b8; text-align: center; }
                @media print { body { background: #fff; padding: 0; } .wrap { box-shadow: none; } }
                </style>
                """;
    }
}
