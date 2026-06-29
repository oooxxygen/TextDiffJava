package com.textdiff.engine.offheap;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/**
 * 堆外开放寻址哈希表（direct ByteBuffer）。槽：[keyHash:long][rowPointer:long][flags:int] = 20B。
 * 线性探查，容量 2 的幂，装载因子 0.5 扩容 rehash。哈希冲突由 keyReader 回读 key 校验。
 */
public final class OffHeapHashTable {
    private static final int SLOT = 20;
    private static final int F_OCCUPIED = 1, F_SEEN = 2;

    private final LongFunction<String> keyReader;  // rowPointer -> key（碰撞校验）
    private ByteBuffer buf;
    private int capacity;
    private int mask;
    private int count;

    public OffHeapHashTable(int initialCapacity, LongFunction<String> keyReader) {
        this.keyReader = keyReader;
        this.capacity = tableSizeFor(initialCapacity);
        this.mask = capacity - 1;
        this.buf = ByteBuffer.allocateDirect(capacity * SLOT);
    }

    static long hash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    private static int tableSizeFor(int c) {
        int n = 16;
        while (n < c) n <<= 1;
        return n;
    }

    private boolean matches(int off, long h, String key) {
        return buf.getLong(off) == h && key.equals(keyReader.apply(buf.getLong(off + 8)));
    }

    /** 插入或覆盖；返回 true=新键，false=覆盖既有键（重复）。 */
    public boolean put(String key, long rowPointer) {
        if (count + 1 > capacity / 2) grow();
        long h = hash(key);
        int slot = (int) (h & mask);
        while ((buf.getInt(slot * SLOT + 16) & F_OCCUPIED) != 0) {
            int o = slot * SLOT;
            if (matches(o, h, key)) {
                buf.putLong(o + 8, rowPointer);  // 覆盖指针
                return false;
            }
            slot = (slot + 1) & mask;
        }
        int o = slot * SLOT;
        buf.putLong(o, h);
        buf.putLong(o + 8, rowPointer);
        buf.putInt(o + 16, F_OCCUPIED);
        count++;
        return true;
    }

    /** 命中返回 rowPointer，未命中返回 -1。 */
    public long get(String key) {
        long h = hash(key);
        int slot = (int) (h & mask);
        while ((buf.getInt(slot * SLOT + 16) & F_OCCUPIED) != 0) {
            int o = slot * SLOT;
            if (matches(o, h, key)) return buf.getLong(o + 8);
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    public void markSeen(String key) {
        long h = hash(key);
        int slot = (int) (h & mask);
        while ((buf.getInt(slot * SLOT + 16) & F_OCCUPIED) != 0) {
            int o = slot * SLOT;
            if (matches(o, h, key)) {
                buf.putInt(o + 16, buf.getInt(o + 16) | F_SEEN);
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** 遍历所有「已占用且未 seen」槽的 rowPointer。 */
    public void scanUnseen(LongConsumer ptrConsumer) {
        for (int slot = 0; slot < capacity; slot++) {
            int fl = buf.getInt(slot * SLOT + 16);
            if ((fl & F_OCCUPIED) != 0 && (fl & F_SEEN) == 0) {
                ptrConsumer.accept(buf.getLong(slot * SLOT + 8));
            }
        }
    }

    public int size() {
        return count;
    }

    private void grow() {
        int newCap = capacity << 1;
        ByteBuffer nb = ByteBuffer.allocateDirect(newCap * SLOT);
        int nmask = newCap - 1;
        for (int slot = 0; slot < capacity; slot++) {
            int o = slot * SLOT;
            if ((buf.getInt(o + 16) & F_OCCUPIED) == 0) continue;
            long h = buf.getLong(o);
            int ns = (int) (h & nmask);
            while ((nb.getInt(ns * SLOT + 16) & F_OCCUPIED) != 0) ns = (ns + 1) & nmask;
            int no = ns * SLOT;
            nb.putLong(no, h);
            nb.putLong(no + 8, buf.getLong(o + 8));
            nb.putInt(no + 16, buf.getInt(o + 16));
        }
        buf = nb;
        capacity = newCap;
        mask = nmask;
    }
}
