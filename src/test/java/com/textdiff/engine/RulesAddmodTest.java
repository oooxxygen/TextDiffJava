package com.textdiff.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ADDMOD 增量过滤栏位解析与配置行回写。 */
class RulesAddmodTest {

    @Test
    void parseAndRoundTrip() {
        CompareConfig cfg = Rules.parseLegacy("N:*.txt:KEYSEQ=1:ADDMOD=2,5", " | ", "|||||");
        assertEquals(List.of(1, 4), cfg.incrFields); // 0-based
        String line = Rules.toLegacyLine(cfg, true);
        assertTrue(line.contains("ADDMOD=2,5"), line);
        // 回写可再解析（往返稳定）
        assertEquals(cfg.incrFields, Rules.parseLegacy(line, " | ", "|||||").incrFields);
    }

    @Test
    void keepInputOrderAndDeduplicate() {
        assertEquals(List.of(4, 1), Rules.parseAddmod("5,2,5"));
        assertEquals(List.of(0), Rules.parseAddmod(" 1 "));
    }

    @Test
    void invalidValuesRejected() {
        assertThrows(Exception.class, () -> Rules.parseLegacy("N:*.txt:ADDMOD=0", " | ", "|||||"));
        assertThrows(Exception.class, () -> Rules.parseLegacy("N:*.txt:ADDMOD=abc", " | ", "|||||"));
        assertThrows(Exception.class, () -> Rules.parseLegacy("N:*.txt:ADDMOD=", " | ", "|||||"));
    }

    @Test
    void defaultNoIncrFields() {
        CompareConfig cfg = Rules.parseLegacy("N:*.txt", " | ", "|||||");
        assertTrue(cfg.incrFields.isEmpty());
        assertFalse(Rules.toLegacyLine(cfg, true).contains("ADDMOD"));
    }
}
