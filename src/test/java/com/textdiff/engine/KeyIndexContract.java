package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** KeyIndex 契约：各实现（InMemory / OffHeap / Auto）共用。子类提供 newIndex()。 */
abstract class KeyIndexContract {

    abstract KeyIndex newIndex();

    @Test
    void putThenGet() {
        try (KeyIndex idx = newIndex()) {
            idx.put("k1", new String[]{"a", "b"});
            assertArrayEquals(new String[]{"a", "b"}, idx.get("k1"));
            assertNull(idx.get("missing"));
            assertEquals(1, idx.size());
        }
    }

    @Test
    void duplicateKeyOverwritesAndCounts() {
        try (KeyIndex idx = newIndex()) {
            idx.put("k", new String[]{"old"});
            idx.put("k", new String[]{"new"});
            assertArrayEquals(new String[]{"new"}, idx.get("k"));
            assertEquals(1, idx.size());
            assertEquals(1, idx.duplicateKeys());
        }
    }

    @Test
    void markSeenAndUnseenOrderIndependent() {
        try (KeyIndex idx = newIndex()) {
            idx.put("a", new String[]{"1"});
            idx.put("b", new String[]{"2"});
            idx.put("c", new String[]{"3"});
            idx.markSeen("b");

            Map<String, String[]> got = new HashMap<>();
            for (Map.Entry<String, String[]> e : idx.unseen()) got.put(e.getKey(), e.getValue());

            assertEquals(Set.of("a", "c"), got.keySet());
            assertArrayEquals(new String[]{"1"}, got.get("a"));
            assertArrayEquals(new String[]{"3"}, got.get("c"));
        }
    }

    @Test
    void handlesUnicodeEmptyColsAndManyKeys() {
        try (KeyIndex idx = newIndex()) {
            idx.put("中文", new String[]{"值", "", "x"});
            idx.put("", new String[]{""});
            for (int i = 0; i < 5000; i++) idx.put("k" + i, new String[]{"v" + i, "w" + i});

            assertArrayEquals(new String[]{"值", "", "x"}, idx.get("中文"));
            assertArrayEquals(new String[]{""}, idx.get(""));
            assertArrayEquals(new String[]{"v4999", "w4999"}, idx.get("k4999"));
            assertEquals(5002, idx.size());
        }
    }
}
