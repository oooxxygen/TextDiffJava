package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** IncrFilter：AND 组合、栏位内多值任一命中、空条件不约束、trailer 原样保留、行数统计。 */
class IncrFilterTest {
    @TempDir Path dir;

    private static final String TEXT = """
            k1 | 20260701 | a
            k2 | 20260702 | b
            k3 | 20260702 | c
            k4 | 20260703 | d
            |||||
            RecNum=3
            """;

    private Path write(String name) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, TEXT, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void andAcrossFieldsAndMultiValueInField() throws Exception {
        Path in = write("a.txt");
        CompareConfig cfg = Rules.parseLegacy("N:*.txt:KEYSEQ=1:ADDMOD=2,3", " | ", "|||||");
        // 栏位2 ∈ {20260702,20260703} 且 栏位3 = c → 仅 k3
        IncrFilter.Result r = IncrFilter.apply(in, in, cfg, List.of("20260702,20260703", "c"), dir);
        assertEquals(4, r.aBefore());
        assertEquals(1, r.aAfter());
        String out = Files.readString(r.filteredA(), StandardCharsets.UTF_8);
        assertTrue(out.contains("k3 | 20260702 | c"));
        assertFalse(out.contains("k1 |"));
        // trailer 原样保留
        assertTrue(out.contains("|||||"));
        assertTrue(out.contains("RecNum=3"));
    }

    @Test
    void blankFieldUnconstrained() throws Exception {
        Path in = write("a.txt");
        CompareConfig cfg = Rules.parseLegacy("N:*.txt:ADDMOD=2,3", " | ", "|||||");
        // 栏位2 = 20260702，栏位3 未填 → 只按栏位2过滤 → k2,k3
        IncrFilter.Result r = IncrFilter.apply(in, in, cfg, List.of("20260702", ""), dir);
        assertEquals(2, r.aAfter());
    }

    @Test
    void emptyResultWhenNothingMatches() throws Exception {
        Path in = write("a.txt");
        CompareConfig cfg = Rules.parseLegacy("N:*.txt:ADDMOD=2", " | ", "|||||");
        IncrFilter.Result r = IncrFilter.apply(in, in, cfg, List.of("20991231"), dir);
        assertEquals(0, r.aAfter());
        // 数据区为空但 trailer 保留
        String out = Files.readString(r.filteredA(), StandardCharsets.UTF_8);
        assertTrue(out.contains("RecNum=3"));
        assertFalse(out.contains("k1 |"));
    }

    @Test
    void disabledWhenNoCondValues() {
        CompareConfig cfg = Rules.parseLegacy("N:*.txt:ADDMOD=2", " | ", "|||||");
        assertFalse(IncrFilter.enabled(cfg, List.of("", "")));
        assertFalse(IncrFilter.enabled(cfg, null));
        CompareConfig plain = Rules.parseLegacy("N:*.txt", " | ", "|||||");
        assertFalse(IncrFilter.enabled(plain, List.of("x")));
    }

    @Test
    void cleanupDeletesProducts() throws Exception {
        Path in = write("a.txt");
        CompareConfig cfg = Rules.parseLegacy("N:*.txt:ADDMOD=2", " | ", "|||||");
        IncrFilter.Result r = IncrFilter.apply(in, in, cfg, List.of("20260702"), dir);
        assertTrue(Files.exists(r.filteredA()));
        IncrFilter.cleanup(r);
        assertFalse(Files.exists(r.filteredA()));
    }
}
