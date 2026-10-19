package com.textdiff.engine;

import java.util.*;

/** 内存 HashMap 索引（KeyIndex 默认实现）。LinkedHashMap 保插入序，使 unseen 输出确定。 */
public final class InMemoryKeyIndex implements KeyIndex {
    private final LinkedHashMap<String, String[]> mem = new LinkedHashMap<>();
    private final HashSet<String> seenA = new HashSet<>();
    private final HashSet<String> seenB = new HashSet<>();
    private long dup = 0;

    @Override
    public void put(String key, String[] cols) {
        if (mem.containsKey(key)) dup++;
        mem.put(key, cols);
    }

    @Override
    public String[] get(String key) {
        return mem.get(key);
    }

    @Override
    public boolean markB(String key) {
        if (seenB.contains(key)) return true;
        seenB.add(key);
        return false;
    }

    @Override
    public void markSeen(String key) {
        if (mem.containsKey(key)) seenA.add(key);
    }

    @Override
    public Iterable<Map.Entry<String, String[]>> unseen() {
        // 惰性迭代：不物化整份 entry 列表
        return () -> mem.entrySet().stream().filter(e -> !seenA.contains(e.getKey())).iterator();
    }

    @Override
    public long size() {
        return mem.size();
    }

    @Override
    public long duplicateKeys() {
        return dup;
    }

    @Override
    public boolean spilled() {
        return false;
    }

    @Override
    public void close() {
        mem.clear();
        seenA.clear();
        seenB.clear();
    }
}
