package com.textdiff.engine;

import java.util.Map;

/**
 * 主键索引 SPI：对 A 侧建索引；B 侧流式探查——命中即 markSeen，B 侧每次出现经 markB 登记
 * （第二次出现返回 true，即 B 侧重复键）；最后 unseen 得「仅 A 存在」。
 * A 索引与 B 键集合用同一张表（B 侧仅占一个标志位），表内存较双表方案减半。
 * 各实现对外接口一致，落盘（spill）在内部透明发生。对等 Python keyindex.AutoKeyIndex。
 */
public interface KeyIndex extends AutoCloseable {
    /** 建 A 索引；重复键后者覆盖，并计入 duplicateKeys。 */
    void put(String key, String[] cols);

    /** 探查 A 侧行；未命中返回 null。 */
    String[] get(String key);

    /** B 侧登记一次出现；返回 true = 该键此前已在 B 侧出现过（B 侧重复键）。 */
    boolean markB(String key);

    /** 标记键已被 B 命中（仅对 A 侧已存在的键生效）。 */
    void markSeen(String key);

    /** 未被 markSeen 的 (key, cols)，即「仅 A 存在」。顺序不保证。惰性迭代，不整表物化。 */
    Iterable<Map.Entry<String, String[]>> unseen();

    long size();

    long duplicateKeys();

    boolean spilled();

    @Override
    void close();
}
