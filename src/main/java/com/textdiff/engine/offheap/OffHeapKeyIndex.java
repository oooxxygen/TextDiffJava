package com.textdiff.engine.offheap;

import com.textdiff.engine.KeyIndex;

import java.nio.file.Path;
import java.util.*;

/** 堆外 KeyIndex：RowStore（mmap 行存储） + OffHeapHashTable（堆外表）。命中行瞬态 materialize。 */
public final class OffHeapKeyIndex implements KeyIndex {
    private static final String[] EMPTY_COLS = new String[0];

    private final RowStore store;
    private final OffHeapHashTable table;
    private long dup = 0;

    public OffHeapKeyIndex(Path tmpDir) {
        this.store = new RowStore(tmpDir);
        this.table = new OffHeapHashTable(1 << 14, store::readKey);
    }

    @Override
    public void put(String key, String[] cols) {
        long ptr = store.append(key, cols);
        if (!table.put(key, ptr)) dup++;   // 覆盖既有键（旧行存储字节成为孤儿，可接受）
    }

    @Override
    public String[] get(String key) {
        long p = table.getRow(key);
        return p < 0 ? null : store.readCols(p);
    }

    @Override
    public boolean markB(String key) {
        if (table.contains(key)) return table.markBExisting(key);
        // B 独有键：RowStore 落 key-only 条目（所有槽都带可回读 key，匹配永远精确）
        long ptr = store.append(key, EMPTY_COLS);
        table.putBOnly(key, ptr);
        return false;
    }

    @Override
    public void markSeen(String key) {
        table.markSeen(key);
    }

    @Override
    public Iterable<Map.Entry<String, String[]>> unseen() {
        // 惰性逐条物化：仅A有的大量行不再一次性驻留堆上（消费方逐行写结果）
        return () -> new java.util.Iterator<>() {
            private final int[] cursor = {0};
            private Map.Entry<String, String[]> ready;

            private void advance() {
                while (ready == null) {
                    long p = table.nextUnseenPtr(cursor);
                    if (p < 0) return;
                    ready = Map.entry(store.readKey(p), store.readCols(p));
                }
            }

            @Override public boolean hasNext() { advance(); return ready != null; }
            @Override public Map.Entry<String, String[]> next() {
                advance();
                var e = ready;
                ready = null;
                return e;
            }
        };
    }

    @Override
    public long size() {
        return table.size();
    }

    @Override
    public long duplicateKeys() {
        return dup;
    }

    @Override
    public boolean spilled() {
        return true;   // 始终磁盘支撑
    }

    @Override
    public void close() {
        store.close();
    }
}
