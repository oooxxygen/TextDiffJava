package com.textdiff.engine;

import java.util.Map;

/**
 * 主键索引 SPI：仅对 A 侧建索引；B 侧流式探查，命中即 markSeen，最后 unseen 得「仅 A 存在」。
 * 各实现对外接口一致，落盘（spill）在内部透明发生。对等 Python keyindex.AutoKeyIndex。
 */
public interface KeyIndex extends AutoCloseable {
    /** 建 A 索引；重复键后者覆盖，并计入 duplicateKeys。 */
    void put(String key, String[] cols);

    /** 探查；未命中返回 null。 */
    String[] get(String key);

    /** 标记键已被 B 命中（仅对已存在的键生效）。 */
    void markSeen(String key);

    /** 未被 markSeen 的 (key, cols)，即「仅 A 存在」。顺序不保证。 */
    Iterable<Map.Entry<String, String[]>> unseen();

    long size();

    long duplicateKeys();

    boolean spilled();

    @Override
    void close();
}
