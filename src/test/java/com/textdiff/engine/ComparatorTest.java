package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComparatorTest {
    private static final String DELIM = " | ";
    private static final String TP = "|||||";

    private static Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static CompareConfig keyCfg(int... keyCols) {
        CompareConfig c = new CompareConfig();
        List<Integer> k = new ArrayList<>();
        for (int x : keyCols) k.add(x);
        c.keyColumns = k;
        return c;
    }

    private static RowDiff find(List<RowDiff> rows, String key, String section) {
        return rows.stream().filter(r -> r.key.equals(key) && r.section.equals(section))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + key + "/" + section));
    }

    @Test
    void endToEndDataAndTrailer(@TempDir Path dir) throws Exception {
        Path a = write(dir, "a.txt",
                "k1 | a | x\nk2 | b | y\nk3 | only | a\n|||||RecNum=3\n|||||SysID=AAA");
        Path b = write(dir, "b.txt",
                "k1 | a | x\nk2 | b | YYY\nk4 | only | b\n|||||RecNum=3\n|||||SysID=BBB");

        ResultSink.ListSink sink = new ResultSink.ListSink();
        CompareOutcome out = Comparator.compareFiles(a, b, keyCfg(0), sink);
        Summary s = out.summary();

        assertEquals(1, s.equal);
        assertEquals(1, s.diff);
        assertEquals(1, s.onlyA);
        assertEquals(1, s.onlyB);
        assertEquals(Map.of(2, 1L), s.diffColFreq);
        assertEquals(3, s.dataRowsA);
        assertEquals(3, s.dataRowsB);
        assertEquals(2, s.trailerRowsA);
        assertEquals(5, s.totalA);
        assertEquals(Boolean.TRUE, s.recnumCheckA);
        assertEquals(Boolean.TRUE, s.recnumCheckB);

        // 数据行
        assertEquals(Status.EQUAL, find(sink.rows, "k1", Status.SECTION_DATA).status);
        RowDiff d = find(sink.rows, "k2", Status.SECTION_DATA);
        assertEquals(Status.DIFF, d.status);
        assertArrayEquals(new int[]{2}, d.diffCols);
        assertEquals(Status.UNMATCHED_A, find(sink.rows, "k3", Status.SECTION_DATA).status);
        assertEquals(Status.UNMATCHED_B, find(sink.rows, "k4", Status.SECTION_DATA).status);

        // trailer 行
        assertEquals(Status.EQUAL, find(sink.rows, "RecNum", Status.SECTION_TRAILER).status);
        assertEquals(Status.DIFF, find(sink.rows, "SysID", Status.SECTION_TRAILER).status);
        // trailerFields 一致性
        assertTrue(s.trailerFields.get("RecNum").equal());
        assertFalse(s.trailerFields.get("SysID").equal());
        assertEquals("AAA", s.trailerFields.get("SysID").a());
        assertEquals("BBB", s.trailerFields.get("SysID").b());
    }

    @Test
    void omittedColumnNotMarkedDiff(@TempDir Path dir) throws Exception {
        Path a = write(dir, "a.txt", "k | a | x");
        Path b = write(dir, "b.txt", "k | a | DIFFERENT");
        CompareConfig c = keyCfg(0);
        c.omitColumns = new ArrayList<>(List.of(2));   // 跳过第 3 列
        ResultSink.ListSink sink = new ResultSink.ListSink();
        CompareOutcome out = Comparator.compareFiles(a, b, c, sink);
        assertEquals(1, out.summary().equal);
        assertEquals(0, out.summary().diff);
    }

    @Test
    void malformedRowsCounted(@TempDir Path dir) throws Exception {
        // keyColumns=[1]，但行只有 1 列 → keyOf 返回 null → malformed
        Path a = write(dir, "a.txt", "onlyonecol\ng1 | g2");
        Path b = write(dir, "b.txt", "g1 | g2");
        ResultSink.ListSink sink = new ResultSink.ListSink();
        CompareOutcome out = Comparator.compareFiles(a, b, keyCfg(1), sink);
        assertEquals(1, out.summary().malformedA);
        assertEquals(1, out.summary().equal);   // g1|g2 两侧键 "g2" 相等
    }

    @Test
    void duplicateKeysDetectedOnBothSides(@TempDir Path dir) throws Exception {
        // A: k1 重复 1 次；B: k2 重复 1 次 + k1 重复 1 次
        Path a = write(dir, "a.txt", "k1 | 1\nk1 | 2\nk2 | 3");
        Path b = write(dir, "b.txt", "k2 | 3\nk2 | 9\nk1 | 1\nk1 | 1");
        ResultSink.ListSink sink = new ResultSink.ListSink();
        CompareOutcome out = Comparator.compareFiles(a, b, keyCfg(0), sink);
        Summary s = out.summary();
        assertEquals(1, s.keyDupA);
        assertEquals(2, s.keyDupB);
        assertTrue(s.dupKeySamples.contains("k1"));
        assertTrue(s.dupKeySamples.contains("k2"));
    }

    @Test
    void detectedEncodingReported(@TempDir Path dir) throws Exception {
        Path a = write(dir, "a.txt", "k | v");
        Path b = write(dir, "b.txt", "k | v");
        CompareOutcome out = Comparator.compareFiles(a, b, keyCfg(0), new ResultSink.ListSink());
        assertEquals("utf-8", out.detectedEncodingA());
        assertEquals("utf-8", out.detectedEncodingB());
        assertFalse(out.usedDiskFallback());
    }
}
