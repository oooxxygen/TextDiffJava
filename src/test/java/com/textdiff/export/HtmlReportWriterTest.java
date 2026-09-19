package com.textdiff.export;

import com.textdiff.store.JobMeta;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** AI 分析 HTML 报告渲染：自包含、报头事实条与总体评判、GFM 表格转换、HTML 转义。 */
class HtmlReportWriterTest {

    private static JobMeta meta(long ta, long tb, long eq, long df, long oa, long ob) {
        JobMeta m = new JobMeta();
        m.fileA = "O:/data/01.XREF.102";
        m.fileB = "O:/data/01.XREF.103";
        m.totalA = ta;
        m.totalB = tb;
        m.equal = eq;
        m.diff = df;
        m.onlyA = oa;
        m.onlyB = ob;
        return m;
    }

    @Test
    void 报头事实条与总体评判() {
        String html = HtmlReportWriter.render("XREF 对比分析", meta(1000, 1700, 980, 10, 10, 0),
                "## 结论\n- 记录数不一致。");
        assertTrue(html.contains("账页墨蓝") || html.contains("masthead"));
        assertTrue(html.contains("XREF 对比分析"));
        assertTrue(html.contains("O:/data/01.XREF.102"));
        assertTrue(html.contains(">严重问题<"), "偏离 51.9% 应判严重问题");
        assertTrue(html.contains("仅 A 存在"));
        assertTrue(html.contains("@media print"), "打印适配");
        assertFalse(html.contains("http://") || html.contains("https://"),
                "自包含：无外部资源引用");
    }

    @Test
    void Markdown表格与列表转换() {
        String md = "### 差异栏位\n\n| 栏位 | A值 | B值 |\n|---|---|---|\n| AMT | 1 | 2 |\n\n- 事实一";
        String html = HtmlReportWriter.render("t", meta(1, 1, 1, 0, 0, 0), md);
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<li>事实一</li>"));
        assertTrue(html.contains("<h3>差异栏位</h3>"));
    }

    @Test
    void 标题内容HTML转义() {
        String html = HtmlReportWriter.render("<script>x</script>", meta(1, 1, 1, 0, 0, 0), "正文");
        assertFalse(html.contains("<script>x</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void 端到端_写入文件可预览(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String html = HtmlReportWriter.render("端到端报告", meta(100, 115, 98, 1, 1, 1),
                "## 结论\n1. 总数偏离约 14%，需保持关注。\n\n| 栏位 | 说明 |\n|---|---|\n| QTY | 数量差 15 |");
        Path f = dir.resolve("report.html");
        Files.writeString(f, html);
        assertTrue(Files.size(f) > 2000);
        assertEquals("保持关注", html.replaceAll("(?s).*grade-badge [a-z]+\">([^<]+)<.*", "$1"));
    }
}
