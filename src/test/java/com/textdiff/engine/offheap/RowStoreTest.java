package com.textdiff.engine.offheap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class RowStoreTest {

    @Test
    void appendReadRoundTrip(@TempDir Path dir) {
        long p1, p2, p3;
        Path f;
        try (RowStore rs = new RowStore(dir)) {
            f = rs.file();
            p1 = rs.append("k1", new String[]{"a", "b", "c"});
            p2 = rs.append("中文键", new String[]{"值", "", "x"});   // unicode + 空列
            p3 = rs.append("", new String[]{""});                     // 空 key + 单空列

            assertEquals("k1", rs.readKey(p1));
            assertArrayEquals(new String[]{"a", "b", "c"}, rs.readCols(p1));
            assertEquals("中文键", rs.readKey(p2));
            assertArrayEquals(new String[]{"值", "", "x"}, rs.readCols(p2));
            assertEquals("", rs.readKey(p3));
            assertArrayEquals(new String[]{""}, rs.readCols(p3));

            assertTrue(p2 > p1 && p3 > p2);   // 指针递增
            assertTrue(Files.exists(f));
        }
    }

    @Test
    void readBackManyEntries(@TempDir Path dir) {
        try (RowStore rs = new RowStore(dir)) {
            long[] ptrs = new long[2000];
            for (int i = 0; i < ptrs.length; i++) {
                ptrs[i] = rs.append("key" + i, new String[]{"v" + i, "w" + i});
            }
            for (int i = 0; i < ptrs.length; i++) {
                assertEquals("key" + i, rs.readKey(ptrs[i]));
                assertArrayEquals(new String[]{"v" + i, "w" + i}, rs.readCols(ptrs[i]));
            }
        }
    }

    @Test
    void closeDeletesTempFile(@TempDir Path dir) {
        RowStore rs = new RowStore(dir);
        Path f = rs.file();
        rs.append("k", new String[]{"v"});
        assertTrue(Files.exists(f));
        rs.close();
        assertFalse(Files.exists(f));   // FileChannel 非映射，Windows 上可删
    }
}
