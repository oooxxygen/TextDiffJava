package com.textdiff.controller;

import com.textdiff.store.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求①A/B 别名修复单测：
 * 1) SubmitController.applySrcAliases——目录批次 use_all/config_file 分支的请求别名注入
 *    （此前只有内联分支读取 source_a/source_b，填了别名也不生效）；
 * 2) ApiViews.srcNamesOf——custom 的 JSON configLine 读 srca/srcb（parseLegacy 对 JSON 不抛错只回默认，
 *    必须前缀分流）。
 */
class SubmitAliasTest {

    @Test
    void 请求别名注入并剥除文件内既有令牌() {
        List<String> lines = List.of(
                "AARH:01A***0*.my1:DELIM= | :SRCA=旧系统:SRCB=B",
                "ABDC:01A***0*.cm1:KEYSEQ=1");
        List<String> out = SubmitController.applySrcAliases(lines, "主机", "信创");
        assertEquals("AARH:01A***0*.my1:DELIM= | :SRCA=主机:SRCB=信创", out.get(0));
        assertFalse(out.get(0).contains("旧系统"), "既有 SRCA 令牌应被剥除");
        assertEquals("ABDC:01A***0*.cm1:KEYSEQ=1:SRCA=主机:SRCB=信创", out.get(1));
    }

    @Test
    void 仅填一侧时另一侧独立保留() {
        List<String> out = SubmitController.applySrcAliases(
                List.of("N:X:SRCA=旧:SRCB=旧B"), "新A", null);
        assertFalse(out.get(0).contains("SRCA=旧:"), "A 侧被替换");
        assertTrue(out.get(0).contains(":SRCB=旧B"), "B 侧令牌保留");
        assertFalse(out.get(0).endsWith(":SRCB=新A"), "B 侧未被注入请求值");
    }

    @Test
    void 默认值与空白视为未填_原样返回() {
        List<String> lines = List.of("N:X:SRCA=旧:SRCB=旧B");
        assertSame(lines, SubmitController.applySrcAliases(lines, "A", "B"));
        assertSame(lines, SubmitController.applySrcAliases(lines, "", null));
        assertSame(lines, SubmitController.applySrcAliases(lines, null, " "));
    }

    @Test
    void JSON配置行读srca_srcb_缺省AB() {
        String line = Json.write(Map.of("nickname", "MT950", "srca", "SWIFT", "srcb", "信创"));
        assertArrayEquals(new String[]{"SWIFT", "信创"}, ApiViews.srcNamesOf(line));
        assertArrayEquals(new String[]{"A", "B"}, ApiViews.srcNamesOf("{\"nickname\":\"MT950\"}"));
    }

    @Test
    void legacy行读SRCA令牌() {
        assertArrayEquals(new String[]{"主机", "信创"},
                ApiViews.srcNamesOf("INCT0101:01A***0*.v01:SRCA=主机:SRCB=信创"));
        assertArrayEquals(new String[]{"A", "B"}, ApiViews.srcNamesOf("INCT0101:01A***0*.v01:KEYSEQ=1"));
        assertArrayEquals(new String[]{"A", "B"}, ApiViews.srcNamesOf(null));
    }
}
