package com.textdiff.engine;

import java.util.*;

/** 内存 HashMap 索引（KeyIndex 默认实现）。LinkedHashMap 保插入序，使 unseen 输出确定。 */
public final class InMemoryKeyIndex implements KeyIndex {
    private final LinkedHashMap<String, String[]> mem = new LinkedHashMap<>();
    private final HashSet<String> seen = new HashSet<>();
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
    public void markSeen(String key) {
        if (mem.containsKey(key)) seen.add(key);
    }

    @Override
    public Iterable<Map.Entry<String, String[]>> unseen() {
        List<Map.Entry<String, String[]>> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : mem.entrySet()) {
            if (!seen.contains(e.getKey())) out.add(Map.entry(e.getKey(), e.getValue()));
        }
        return out;
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
        seen.clear();
    }
}
