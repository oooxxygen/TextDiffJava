package com.textdiff.engine;

import com.textdiff.engine.offheap.OffHeapKeyIndex;

import java.nio.file.Path;
import java.util.*;

/**
 * 自动落盘 KeyIndex：内存为主，累计字节超阈值时透明迁移到堆外（OffHeapKeyIndex）。
 * 对等 Python keyindex.AutoKeyIndex（dict→SQLite），仅落盘介质换成 mmap 行存储 + 堆外表。
 */
public final class AutoKeyIndex implements KeyIndex {
    private final Path tmpDir;
    private final long maxInMemoryBytes;

    private LinkedHashMap<String, String[]> mem = new LinkedHashMap<>();
    private HashSet<String> seen = new HashSet<>();
    private long memDup = 0;
    private long bytes = 0;

    private OffHeapKeyIndex off;     // null 直到落盘
    private boolean spilled = false;

    public AutoKeyIndex(Path tmpDir, long maxInMemoryBytes) {
        this.tmpDir = tmpDir;
        this.maxInMemoryBytes = maxInMemoryBytes;
    }

    @Override
    public void put(String key, String[] cols) {
        if (!spilled) {
            if (mem.containsKey(key)) memDup++;
            mem.put(key, cols);
            long add = key.length() + 8L * cols.length;
            for (String c : cols) add += c.length();
            bytes += add;
            if (bytes > maxInMemoryBytes) spill();
        } else {
            off.put(key, cols);
        }
    }

    @Override
    public String[] get(String key) {
        return spilled ? off.get(key) : mem.get(key);
    }

    @Override
    public void markSeen(String key) {
        if (spilled) {
            off.markSeen(key);
        } else if (mem.containsKey(key)) {
            seen.add(key);
        }
    }

    @Override
    public Iterable<Map.Entry<String, String[]>> unseen() {
        if (spilled) return off.unseen();
        List<Map.Entry<String, String[]>> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : mem.entrySet()) {
            if (!seen.contains(e.getKey())) out.add(Map.entry(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Override
    public long size() {
        return spilled ? off.size() : mem.size();
    }

    @Override
    public long duplicateKeys() {
        return memDup + (off != null ? off.duplicateKeys() : 0);
    }

    @Override
    public boolean spilled() {
        return spilled;
    }

    private void spill() {
        off = new OffHeapKeyIndex(tmpDir);
        for (Map.Entry<String, String[]> e : mem.entrySet()) off.put(e.getKey(), e.getValue());
        for (String k : seen) off.markSeen(k);
        spilled = true;
        mem = null;
        seen = null;
    }

    @Override
    public void close() {
        if (off != null) off.close();
        if (mem != null) mem.clear();
        if (seen != null) seen.clear();
    }
}
