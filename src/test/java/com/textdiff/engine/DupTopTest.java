package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 主键重复 TOP10：分侧统计键值与次数，封顶 10 个键。 */
class DupTopTest {
    @TempDir Path dir;

    private CompareOutcome compare(String name, String a, String b) throws Exception {
        Path fa = dir.resolve(name + "_a.txt");
        Path fb = dir.resolve(name + "_b.txt");
        Files.writeString(fa, a, StandardCharsets.UTF_8);
        Files.writeString(fb, b, StandardCharsets.UTF_8);
        CompareConfig cfg = new CompareConfig();
        cfg.nickname = "N";
        cfg.fileGlob = "*";
        cfg.keyColumns = List.of(0);
        return Comparator.compareFiles(fa, fb, cfg, new ResultSink.ListSink(), Long.MAX_VALUE, dir);
    }

    @Test
    void perSideTopWithCounts() throws Exception {
        // A: k1 重复 1 次（共 2 行）、k2 重复 2 次（共 3 行）；B: k9 重复 1 次
        CompareOutcome r = compare("t1",
                "k1 | 1\nk1 | 2\nk2 | 3\nk2 | 4\nk2 | 5\nk3 | 6\n",
                "k1 | 1\nk9 | 2\nk9 | 3\n");
        assertEquals(3, r.summary().keyDupA); // 1+2
        assertEquals(2, r.summary().dupTopA.size()); // k1 与 k2 两个键
        // 插入序：k1 先出现
        assertEquals("k1", r.summary().dupTopA.get(0).key());
        assertEquals(1L, r.summary().dupTopA.get(0).count());
        assertEquals("k2", r.summary().dupTopA.get(1).key());
        assertEquals(2L, r.summary().dupTopA.get(1).count());
        assertEquals(1, r.summary().dupTopB.size());
        assertEquals("k9", r.summary().dupTopB.get(0).key());
        assertEquals(1L, r.summary().dupTopB.get(0).count());
    }

    @Test
    void cappedAtTenKeys() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("d").append(i).append(" | ").append(i).append("\n").append("d").append(i).append(" | ").append(i).append("\n");
        sb.append("u0 | 0\nu1 | 1\n"); // 两个不重复键，不计入 TOP
        CompareOutcome r = compare("t2", sb.toString(), "x | 0\n");
        assertEquals(10, r.summary().dupTopA.size());
        assertEquals("d0", r.summary().dupTopA.get(0).key()); // 先出现的键保留
        assertTrue(r.summary().dupTopA.stream().noneMatch(k -> k.key().startsWith("u")));
    }

    @Test
    void noDupMeansEmptyTop() throws Exception {
        CompareOutcome r = compare("t3", "k1 | 1\nk2 | 2\n", "k1 | 1\nk2 | 2\n");
        assertTrue(r.summary().dupTopA.isEmpty());
        assertTrue(r.summary().dupTopB.isEmpty());
        assertFalse(r.summary().keyDupA > 0);
    }
}
