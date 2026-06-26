package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    private static final String DELIM = " | ";
    private static final String TP = "|||||";

    @Test
    void parseSeqOneBasedToZeroBasedSortedDeduped() {
        assertEquals(List.of(2, 3, 4), Rules.parseSeq("3/4/5"));
        assertEquals(List.of(0, 1, 9), Rules.parseSeq("10/1/2/1"));
        assertThrows(IllegalArgumentException.class, () -> Rules.parseSeq("0"));
    }

    @Test
    void parseLegacyMinimal() {
        CompareConfig c = Rules.parseLegacy("nick:*.v01:KEYSEQ=1/2:OMITSEQ=5:IGNORESEQ=9", DELIM, TP);
        assertEquals("nick", c.nickname);
        assertEquals("*.v01", c.fileGlob);
        assertEquals(List.of(0, 1), c.keyColumns);
        assertEquals(List.of(4), c.omitColumns);
        assertEquals(List.of(8), c.ignoreColumns);
        assertEquals(" | ", c.delimiter);  // 默认
    }

    @Test
    void parseLegacyArrowPrefixStripped() {
        CompareConfig c = Rules.parseLegacy("=> nick:*:KEYSEQ=1", DELIM, TP);
        assertEquals("nick", c.nickname);
        assertEquals(List.of(0), c.keyColumns);
    }

    @Test
    void delimPreservesInnerSpaces() {
        CompareConfig c = Rules.parseLegacy("n:*:KEYSEQ=1:DELIM= | :ENCA=cp037:SRCA=Old", DELIM, TP);
        assertEquals(" | ", c.delimiter);   // 不被 strip
        assertEquals("cp037", c.encodingA);
        assertEquals("Old", c.sourceA);
    }

    @Test
    void roundTripFullLine() {
        CompareConfig c = new CompareConfig();
        c.nickname = "rep";
        c.fileGlob = "*.dat";
        c.keyColumns = new ArrayList<>(List.of(0, 1));
        c.omitColumns = new ArrayList<>(List.of(3));
        c.encodingA = "cp037";
        c.encodingB = "utf-8";
        c.sourceA = "Old";
        c.sourceB = "New";
        c.replaceRules = new LinkedHashMap<>();
        c.replaceRules.put(2, List.<String[]>of(new String[]{"\\d+", "#"}));

        String line = Rules.toLegacyLine(c, true);
        CompareConfig r = Rules.parseLegacy(line, DELIM, TP);

        assertEquals("rep", r.nickname);
        assertEquals("*.dat", r.fileGlob);
        assertEquals(List.of(0, 1), r.keyColumns);
        assertEquals(List.of(3), r.omitColumns);
        assertEquals("cp037", r.encodingA);
        assertEquals("New", r.sourceB);
        assertTrue(r.replaceRules.containsKey(2));
        assertArrayEquals(new String[]{"\\d+", "#"}, r.replaceRules.get(2).get(0));
    }

    @Test
    void ruleEngineKeyOfAndMalformed() {
        CompareConfig c = new CompareConfig();
        c.keyColumns = new ArrayList<>(List.of(0, 2));
        Rules.RuleEngine e = new Rules.RuleEngine(c);
        assertEquals("a" + Rules.KEY_SEP + "c", e.keyOf(new String[]{"a", "b", "c"}));
        assertNull(e.keyOf(new String[]{"a", "b"}));  // 列不足 -> malformed
    }

    @Test
    void ruleEngineNormalizeAndSkip() {
        CompareConfig c = new CompareConfig();
        c.omitColumns = new ArrayList<>(List.of(1));
        c.ignoreColumns = new ArrayList<>(List.of(4));
        c.replaceRules = new LinkedHashMap<>();
        c.replaceRules.put(0, List.<String[]>of(new String[]{"\\d+", "N"}));
        Rules.RuleEngine e = new Rules.RuleEngine(c);
        assertEquals("aN", e.normalize(0, "a123"));
        assertEquals("unchanged", e.normalize(3, "unchanged"));
        assertTrue(e.isSkipped(1));
        assertTrue(e.isSkipped(4));
        assertFalse(e.isSkipped(0));
    }

    @Test
    void matchGlobBasename() {
        assertTrue(Rules.matchGlob("/some/dir/01A.v01", "*.v01"));
        assertFalse(Rules.matchGlob("/some/dir/01A.v02", "*.v01"));
    }
}
