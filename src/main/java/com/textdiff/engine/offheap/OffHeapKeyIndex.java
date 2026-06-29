package com.textdiff.engine.offheap;

import com.textdiff.engine.KeyIndex;

import java.nio.file.Path;
import java.util.*;

/** 堆外 KeyIndex：RowStore（mmap 行存储） + OffHeapHashTable（堆外表）。命中行瞬态 materialize。 */
public final class OffHeapKeyIndex implements KeyIndex {
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
        long p = table.get(key);
        return p < 0 ? null : store.readCols(p);
    }

    @Override
    public void markSeen(String key) {
        table.markSeen(key);
    }

    @Override
    public Iterable<Map.Entry<String, String[]>> unseen() {
        List<Map.Entry<String, String[]>> out = new ArrayList<>();
        table.scanUnseen(ptr -> out.add(Map.entry(store.readKey(ptr), store.readCols(ptr))));
        return out;
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
