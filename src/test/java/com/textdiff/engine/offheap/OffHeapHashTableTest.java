package com.textdiff.engine.offheap;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

class OffHeapHashTableTest {

    /** rowPointer = keys 列表下标；keyReader 据此回读 key。 */
    private static final class KeyStore {
        final List<String> keys = new ArrayList<>();
        long add(String k) { keys.add(k); return keys.size() - 1; }
        LongFunction<String> reader() { return p -> keys.get((int) p); }
    }

    @Test
    void putGetWithGrowth() {
        KeyStore ks = new KeyStore();
        OffHeapHashTable t = new OffHeapHashTable(16, ks.reader());
        for (int i = 0; i < 1000; i++) {                 // 远超初始容量，触发多次扩容
            assertTrue(t.put("key" + i, ks.add("key" + i)));
        }
        assertEquals(1000, t.size());
        for (int i = 0; i < 1000; i++) {
            long p = t.get("key" + i);
            assertTrue(p >= 0);
            assertEquals("key" + i, ks.keys.get((int) p));
        }
        assertEquals(-1, t.get("absent"));
    }

    @Test
    void duplicateOverwritesAndReturnsFalse() {
        KeyStore ks = new KeyStore();
        OffHeapHashTable t = new OffHeapHashTable(16, ks.reader());
        assertTrue(t.put("k", ks.add("k")));        // ptr 0
        long p2 = ks.add("k");                      // ptr 1（同 key 再存一份）
        assertFalse(t.put("k", p2));                // 覆盖
        assertEquals(p2, t.get("k"));
        assertEquals(1, t.size());
    }

    @Test
    void slotCollisionsHandledByProbingAndVerification() {
        KeyStore ks = new KeyStore();
        OffHeapHashTable t = new OffHeapHashTable(16, ks.reader());  // 小容量逼出 slot 冲突
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 12; i++) keys.add("k" + i);
        for (String k : keys) t.put(k, ks.add(k));
        for (String k : keys) {
            long p = t.get(k);
            assertEquals(k, ks.keys.get((int) p));   // 探查 + key 校验仍取回正确条目
        }
    }

    @Test
    void markSeenAndScanUnseen() {
        KeyStore ks = new KeyStore();
        OffHeapHashTable t = new OffHeapHashTable(16, ks.reader());
        for (int i = 0; i < 6; i++) t.put("k" + i, ks.add("k" + i));
        t.markSeen("k1");
        t.markSeen("k4");

        Set<String> unseen = new HashSet<>();
        t.scanUnseen(p -> unseen.add(ks.keys.get((int) p)));
        assertEquals(Set.of("k0", "k2", "k3", "k5"), unseen);
    }
}
