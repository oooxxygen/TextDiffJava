package com.textdiff.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PRISEQ 配置项：解析（1~5 校验、缺省 5）、写回（仅显式配置落行）。 */
class PriSeqRuleTest {

    @Test
    void 解析显式PRISEQ() {
        CompareConfig cfg = Rules.parseLegacy(
                "NICK:01A***0D.ew1:KEYSEQ=1:PRISEQ=2", " | ", "|||||");
        assertEquals(2, cfg.priSeq);
        assertTrue(cfg.priSeqSet);
    }

    @Test
    void 未配置缺省5且不落行() {
        CompareConfig cfg = Rules.parseLegacy("NICK:x.txt:KEYSEQ=1", " | ", "|||||");
        assertEquals(5, cfg.priSeq);
        assertFalse(cfg.priSeqSet);
        String line = Rules.toLegacyLine(cfg, true);
        assertFalse(line.contains("PRISEQ"), () -> line);
    }

    @Test
    void 写回仅显式配置出现() {
        CompareConfig cfg = Rules.parseLegacy(
                "NICK:x.txt:KEYSEQ=1:PRISEQ=1", " | ", "|||||");
        String line = Rules.toLegacyLine(cfg, true);
        assertTrue(line.contains("PRISEQ=1"), () -> line);
        // 往返一致
        CompareConfig back = Rules.parseLegacy(line, " | ", "|||||");
        assertEquals(1, back.priSeq);
        assertTrue(back.priSeqSet);
    }

    @Test
    void 越界值报错() {
        assertThrows(RuntimeException.class,
                () -> Rules.parseLegacy("NICK:x.txt:PRISEQ=0", " | ", "|||||"));
        assertThrows(RuntimeException.class,
                () -> Rules.parseLegacy("NICK:x.txt:PRISEQ=6", " | ", "|||||"));
    }

    @Test
    void 优先级语义_1最高5最低() {
        // 排序语义（JobManager 出队顺序）：PRISEQ 升序 + FIFO
        record T(int pri, long seq) implements Comparable<T> {
            public int compareTo(T o) {
                int c = Integer.compare(pri, o.pri);
                return c != 0 ? c : Long.compare(seq, o.seq);
            }
        }
        List<T> sorted = new java.util.ArrayList<>(java.util.List.of(
                new T(5, 0), new T(1, 1), new T(3, 2), new T(1, 3)));
        java.util.Collections.sort(sorted);
        assertEquals(1, sorted.get(0).pri());
        assertEquals(1, sorted.get(1).pri());
        assertTrue(sorted.get(0).seq() < sorted.get(1).seq()); // 同优先级 FIFO
        assertEquals(3, sorted.get(2).pri());
        assertEquals(5, sorted.get(3).pri());
    }
}
