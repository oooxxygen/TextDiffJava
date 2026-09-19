package com.textdiff.export;

import com.textdiff.store.JobMeta;
import org.commonmark.Extension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;

import java.util.List;

/**
 * AI 对比分析报告 HTML 渲染（默认导出产物，随 ai_analysis.md 同目录同名 .html）。
 *
 * 自包含单文件：内联样式、无外部资源、可离线打开与直接打印（@media print 适配）。
 * 版式「账页墨蓝」：冷白纸面 + 墨蓝正文 + 账目细线，宋体系标题对齐银行核对报告语境；
 * 报头为视觉重心——报告名、两侧文件、数据事实条（总数/匹配/差异/单侧）与总体评判；
 * 正文为 AI 分析 Markdown（结论事实清单、主键异常、差异栏位分析、尾部事实表）。
 */
public final class HtmlReportWriter {
    private HtmlReportWriter() {}

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    public static String render(String title, JobMeta meta, String markdown) {
        Node doc = PARSER.parse(markdown == null ? "" : markdown);
        String body = RENDERER.render(doc);
        DiffGrade.Grade grade = meta == null ? null : DiffGrade.of(meta.totalA, meta.totalB);
        return template(title, meta, grade, body);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String template(String title, JobMeta meta, DiffGrade.Grade grade, String body) {
        String ta = meta == null ? "—" : String.valueOf(meta.totalA);
        String tb = meta == null ? "—" : String.valueOf(meta.totalB);
        String eq = meta == null ? "—" : String.valueOf(meta.equal);
        String df = meta == null ? "—" : String.valueOf(meta.diff);
        String oa = meta == null ? "—" : String.valueOf(meta.onlyA);
        String ob = meta == null ? "—" : String.valueOf(meta.onlyB);
        String gradeCls = switch (grade == null ? "" : grade.label()) {
            case "严重问题" -> "severe";
            case "差异明显" -> "mark";
            case "保持关注" -> "watch";
            default -> "ok";
        };
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>%s</title>
<style>
:root {
  --paper: #FBFBF9;      /* 冷白纸面 */
  --ink: #16324F;        /* 墨蓝正文 */
  --ink-soft: #5B6B7C;
  --ledger: #2456A6;     /* 账目蓝 */
  --rule: #E3E1DA;       /* 账线 */
  --bad: #B42318;
  --warn: #B54708;
  --ok: #067647;
  --panel: #F3F2ED;
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--paper); color: var(--ink);
  font: 15px/1.85 "Source Han Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
}
.sheet { max-width: 78ch; margin: 0 auto; padding: 48px 32px 72px; }

/* 报头：报告的视觉重心——左侧账栏竖线 + 大号宋体报告名 */
header.masthead { border-left: 4px solid var(--ledger); padding-left: 22px; margin-bottom: 8px; }
header.masthead h1 {
  font-family: "Source Han Serif SC", "Noto Serif SC", "SimSun", serif;
  font-size: 27px; font-weight: 700; letter-spacing: .02em; margin: 0 0 8px;
}
header.masthead .files { color: var(--ink-soft); font-size: 13px; line-height: 1.7; }
header.masthead .files b { color: var(--ink); font-weight: 600; }

/* 数据事实条：账页式对齐，tabular-nums 保证数位对齐 */
section.facts {
  display: flex; flex-wrap: wrap; align-items: stretch; gap: 0;
  border-top: 1px solid var(--rule); border-bottom: 1px solid var(--rule);
  margin: 26px 0 34px;
}
.facts .cell { padding: 12px 20px 12px 0; margin-right: 20px; }
.facts .num {
  font-size: 24px; font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1.3;
}
.facts .lbl { font-size: 12px; color: var(--ink-soft); }
.facts .sep { border-left: 1px solid var(--rule); padding-left: 20px; }
.facts .grade { margin-left: auto; align-self: center; text-align: right; padding-right: 4px; }

.grade-badge { display: inline-block; font-size: 13px; font-weight: 700; padding: 3px 12px; border-radius: 3px; }
.grade-badge.ok { color: var(--ok); background: #EDF7F0; }
.grade-badge.watch { color: var(--warn); background: #FBF3E6; }
.grade-badge.mark { color: #932F10; background: #FAEBE0; }
.grade-badge.severe { color: var(--bad); background: #FBEAE8; }

/* 正文（AI Markdown 渲染）：结论清单 / 主键异常 / 表格 */
article h1, article h2, article h3 {
  font-family: "Source Han Serif SC", "Noto Serif SC", "SimSun", serif;
  font-weight: 700; line-height: 1.5; margin: 1.9em 0 .7em;
}
article h1 { font-size: 21px; }
article h2 { font-size: 19px; }
article h3 { font-size: 16.5px; }
article h1:first-child, article h2:first-child, article h3:first-child { margin-top: 0; }
article p, article li { max-width: 72ch; }
article ul, article ol { padding-left: 1.6em; margin: .8em 0; }
article li { margin: .3em 0; }
article li::marker { color: var(--ledger); }
article strong { font-weight: 700; }
article code {
  font-family: Consolas, "Courier New", monospace; font-size: .92em;
  background: var(--panel); padding: 1px 5px; border-radius: 3px;
}
article blockquote {
  margin: 1em 0; padding: 2px 0 2px 16px; border-left: 3px solid var(--rule);
  color: var(--ink-soft);
}
article hr { border: 0; border-top: 1px solid var(--rule); margin: 2.2em 0; }

/* 尾部事实表：账页表格（细线、隔行底、数位右对齐由内容自然呈现） */
article table { border-collapse: collapse; width: 100%%; margin: 1.2em 0; font-size: 13.5px; }
article th, article td { border: 1px solid var(--rule); padding: 7px 11px; text-align: left; vertical-align: top; }
article th { background: var(--panel); font-weight: 700; white-space: nowrap; }
article tbody tr:nth-child(even) { background: #F6F5F1; }

footer { margin-top: 48px; padding-top: 14px; border-top: 1px solid var(--rule);
         color: var(--ink-soft); font-size: 12px; }

@media print {
  body { background: #fff; font-size: 12.5px; }
  .sheet { max-width: none; padding: 0; }
  section.facts { break-inside: avoid; }
  article table { break-inside: auto; }
  article th, article td { border-color: #ccc; }
}
</style>
</head>
<body>
<div class="sheet">
  <header class="masthead">
    <h1>%s</h1>
    <div class="files">
      <div><b>A</b>：%s</div>
      <div><b>B</b>：%s</div>
    </div>
  </header>

  <section class="facts">
    <div class="cell"><div class="num">%s</div><div class="lbl">A 侧记录</div></div>
    <div class="cell sep"><div class="num">%s</div><div class="lbl">B 侧记录</div></div>
    <div class="cell sep"><div class="num">%s</div><div class="lbl">完全匹配</div></div>
    <div class="cell sep"><div class="num">%s</div><div class="lbl">有差异</div></div>
    <div class="cell sep"><div class="num">%s</div><div class="lbl">仅 A 存在</div></div>
    <div class="cell"><div class="num">%s</div><div class="lbl">仅 B 存在</div></div>
    <div class="cell grade"><div class="lbl">总体评判</div><span class="grade-badge %s">%s</span></div>
  </section>

  <article>
%s
  </article>

  <footer>TextDiffJava 对比分析报告 · 由 AI 生成的差异归纳，字段取值以对比结果为准</footer>
</div>
</body>
</html>
"""
                .formatted(esc(title), esc(title),
                        esc(meta == null ? "" : meta.fileA), esc(meta == null ? "" : meta.fileB),
                        ta, tb, eq, df, oa, ob,
                        gradeCls, esc(grade == null ? "—" : grade.label()),
                        body);
    }
}
