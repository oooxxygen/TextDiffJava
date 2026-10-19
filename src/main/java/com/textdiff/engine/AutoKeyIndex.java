package com.textdiff.engine;

import com.textdiff.engine.offheap.OffHeapKeyIndex;

import java.nio.file.Path;
import java.util.*;

/**
 * 自动落盘 KeyIndex：内存为主，累计字节超阈值时透明迁移到堆外（OffHeapKeyIndex）。
 * A 索引与 B 键集合合并记账（markB 的键也计入 bytes——B 独有键同样驻留，此前漏算曾是 OOM 诱因之一）。
 * 对等 Python keyindex.AutoKeyIndex（dict→SQLite），仅落盘介质换成 mmap 行存储 + 堆外表。
 */
public final class AutoKeyIndex implements KeyIndex {
    private final Path tmpDir;
    private final long maxInMemoryBytes;

    private LinkedHashMap<String, String[]> mem = new LinkedHashMap<>();
    private HashSet<String> seenA = new HashSet<>();
    private HashSet<String> seenB = new HashSet<>();
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
            // 按 JVM 堆实际占用估而非字符数：行数组头 + 引用槽 + LinkedHashMap 节点与 rehash 冗余 +
            // 字符串对象头 + 字符 2B/个（紧凑字符串 ASCII 实际更小，从保守；GB18030/中文解码后即 2B/字符）
            long add = 64 + 8L * cols.length; // 行数组 + 桶/节点均摊开销（低估曾是批量 OOM 主因之一）
            add += stringHeap(key);
            for (String c : cols) add += stringHeap(c);
            bytes += add;
            if (bytes > maxInMemoryBytes) spill();
        } else {
            off.put(key, cols);
        }
    }

    @Override
    public boolean markB(String key) {
        if (spilled) return off.markB(key);
        if (seenB.contains(key)) return true;
        seenB.add(key);
        bytes += 48 + stringHeap(key); // seenB 节点 + 键串驻留（B 独有键在此）
        if (bytes > maxInMemoryBytes) spill();
        return false;
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
            seenA.add(key);
            bytes += 48; // seenA HashSet 节点均摊开销（比对阶段全部命中键都会进 seenA）
        }
    }

    @Override
    public Iterable<Map.Entry<String, String[]>> unseen() {
        if (spilled) return off.unseen();
        // 惰性过滤迭代：不再为仅A有行物化整份 entry 列表（百万级行时本身又是数百 MB 堆）
        return () -> mem.entrySet().stream().filter(e -> !seenA.contains(e.getKey())).iterator();
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
        for (String k : seenA) off.markSeen(k);
        for (String k : seenB) off.markB(k);
        spilled = true;
        mem = null;
        seenA = null;
        seenB = null;
    }

    @Override
    public void close() {
        if (off != null) off.close();
        if (mem != null) mem.clear();
        if (seenA != null) seenA.clear();
        if (seenB != null) seenB.clear();
    }
}
