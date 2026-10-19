package com.textdiff.engine.offheap;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

/**
 * 堆外开放寻址哈希表（direct ByteBuffer）。槽 16B：[rowPointer:long][hash:int][flags:int]。
 * 线性探查，容量 2 的幂，装载因子 0.5 扩容 rehash；扩容后立即显式释放旧表
 * （等待 GC 曾让死表累积数百 MB 直接内存——大键量对比直接内存 OOM 的主因）。
 * 哈希冲突由 keyReader 回读 key 校验（32 位哈希仅作快速过滤）。
 */
public final class OffHeapHashTable {
    private static final int SLOT = 16;
    private static final int F_OCCUPIED = 1, F_SEEN_A = 2, F_SEEN_B = 4, F_B_ONLY = 8;

    // invokeCleaner：扩容后立即归还直接内存。Unsafe 经反射获取（getUnsafe 在应用类加载器下被拒）；
    // 获取失败（罕见）回退 null——旧表退回等 GC 回收的老行为。
    private static final sun.misc.Unsafe UNSAFE = unsafeOrNull();

    private static sun.misc.Unsafe unsafeOrNull() {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (RuntimeException | ReflectiveOperationException e) {
            System.err.println("[offheap] Unsafe 不可用，旧哈希表将等 GC 回收: " + e);
            return null;
        }
    }

    private final java.util.function.LongFunction<String> keyReader;  // rowPointer -> key（碰撞校验）
    private ByteBuffer buf;
    private int capacity;
    private int mask;
    private int count;

    public OffHeapHashTable(int initialCapacity, java.util.function.LongFunction<String> keyReader) {
        this.keyReader = keyReader;
        this.capacity = tableSizeFor(initialCapacity);
        this.mask = capacity - 1;
        this.buf = ByteBuffer.allocateDirect(capacity * SLOT);
    }

    /** 32 位哈希（槽内存储 + 匹配快速过滤；碰撞由 keyReader 兜底校验）。 */
    public int hash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        h ^= (h >>> 32);
        return (int) h;
    }

    private static int tableSizeFor(int c) {
        int n = 16;
        while (n < c) n <<= 1;
        return n;
    }

    private boolean matches(int off, int h, String key) {
        return buf.getInt(off + 8) == h && key.equals(keyReader.apply(buf.getLong(off)));
    }

    private int probe(int h, String key) {
        int slot = h & mask;
        while ((buf.getInt(slot * SLOT + 12) & F_OCCUPIED) != 0) {
            int o = slot * SLOT;
            if (matches(o, h, key)) return o;
            slot = (slot + 1) & mask;
        }
        return -(slot + 1); // 空槽位的补码编码
    }

    /** 插入或覆盖；返回 true=新键，false=覆盖既有键（重复）。 */
    public boolean put(String key, long rowPointer) {
        if (count + 1 > capacity / 2) grow();
        int h = hash(key);
        int o = probe(h, key);
        if (o >= 0) {
            buf.putLong(o, rowPointer);  // 覆盖指针
            return false;
        }
        o = (-o - 1) * SLOT; // probe 返回 -(空槽位号+1)，还原为字节偏移
        buf.putLong(o, rowPointer);
        buf.putInt(o + 8, h);
        buf.putInt(o + 12, F_OCCUPIED);
        count++;
        return true;
    }

    /** 命中返回 rowPointer，未命中返回 -1。 */
    public long get(String key) {
        int o = probe(hash(key), key);
        return o >= 0 ? buf.getLong(o) : -1;
    }

    /** A 侧行指针查询：跳过 B 独有键槽（B 侧 markB 先于 get 时不得把 B-only 条目当 A 行）。 */
    public long getRow(String key) {
        int o = probe(hash(key), key);
        if (o < 0) return -1;
        return (buf.getInt(o + 12) & F_B_ONLY) != 0 ? -1 : buf.getLong(o);
    }

    /**
     * B 侧登记（键已在表中）：置 SEEN_B；已置过返回 true=B 侧重复。
     * B 独有键由 OffHeapKeyIndex 先落 RowStore（key-only 条目）再经 {@link #putBOnly} 插入——
     * 所有槽都带可回读 key，匹配永远走 readKey 精确校验（32 位哈希仅快速过滤）。
     */
    public boolean markBExisting(String key) {
        int o = probe(hash(key), key);
        if (o < 0) throw new IllegalStateException("markBExisting: 槽不存在（调用序错误）");
        int fl = buf.getInt(o + 12);
        if ((fl & F_SEEN_B) != 0) return true;
        buf.putInt(o + 12, fl | F_SEEN_B);
        return false;
    }

    /** B 独有键插入：ptr 指向 RowStore key-only 条目；F_B_ONLY 使其不进入 unseen。 */
    public void putBOnly(String key, long rowPointer) {
        if (count + 1 > capacity / 2) grow();
        int h = hash(key);
        int o = probe(h, key);
        if (o >= 0) throw new IllegalStateException("putBOnly: 键已存在（调用序错误）");
        o = (-o - 1) * SLOT; // probe 返回 -(空槽位号+1)，还原为字节偏移
        buf.putLong(o, rowPointer);
        buf.putInt(o + 8, h);
        buf.putInt(o + 12, F_OCCUPIED | F_SEEN_B | F_B_ONLY);
        count++;
    }

    /** 键是否在表中（精确匹配）。 */
    public boolean contains(String key) {
        return probe(hash(key), key) >= 0;
    }

    /** 标记 A 侧行已被 B 命中（仅对已存在槽生效）。 */
    public void markSeen(String key) {
        int o = probe(hash(key), key);
        if (o >= 0) buf.putInt(o + 12, buf.getInt(o + 12) | F_SEEN_A);
    }

    /** 遍历所有「A 侧已占用、未被 B 命中」槽的 rowPointer（跳过 B 独有键槽）。 */
    public void scanUnseen(LongConsumer ptrConsumer) {
        for (int slot = 0; slot < capacity; slot++) {
            int fl = buf.getInt(slot * SLOT + 12);
            if ((fl & F_OCCUPIED) != 0 && (fl & F_SEEN_A) == 0 && (fl & F_B_ONLY) == 0) {
                ptrConsumer.accept(buf.getLong(slot * SLOT));
            }
        }
    }

    /** 自 cursor[0] 槽位起下一个「A 侧已占用、未被 B 命中」槽的 rowPointer；无则 -1。惰性遍历用。 */
    public long nextUnseenPtr(int[] cursor) {
        for (int s = cursor[0]; s < capacity; s++) {
            int fl = buf.getInt(s * SLOT + 12);
            if ((fl & F_OCCUPIED) != 0 && (fl & F_SEEN_A) == 0 && (fl & F_B_ONLY) == 0) {
                cursor[0] = s + 1;
                return buf.getLong(s * SLOT);
            }
        }
        return -1;
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
            if ((buf.getInt(o + 12) & F_OCCUPIED) == 0) continue;
            int h = buf.getInt(o + 8);
            int ns = h & nmask;
            while ((nb.getInt(ns * SLOT + 12) & F_OCCUPIED) != 0) ns = (ns + 1) & nmask;
            int no = ns * SLOT;
            nb.putLong(no, buf.getLong(o));
            nb.putInt(no + 8, h);
            nb.putInt(no + 12, buf.getInt(o + 12));
        }
        ByteBuffer old = buf;
        buf = nb;
        capacity = newCap;
        mask = nmask;
        if (UNSAFE != null) UNSAFE.invokeCleaner(old); // 立即归还旧表直接内存（等待 GC 曾累积数百 MB 死表）
    }
}
