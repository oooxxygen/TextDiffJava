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
            // 按 JVM 堆实际占用估而非字符数：String 对象/数组头开销 + UTF-16 2B/字符（紧凑字符串 ASCII 实际更小，此处从保守）
            long add = 16 + 8L * cols.length; // 行数组头 + 引用槽
            add += stringHeap(key);
            for (String c : cols) add += stringHeap(c);
            bytes += add;
            if (bytes > maxInMemoryBytes) spill();
        } else {
            off.put(key, cols);
        }
    }

    /** 单个字符串的堆占用估计：对象头+hash+数组头 ≈40B，字符按 2B/个（UTF-16 上限）。 */
    private static long stringHeap(String s) {
        return 40L + 2L * s.length();
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
