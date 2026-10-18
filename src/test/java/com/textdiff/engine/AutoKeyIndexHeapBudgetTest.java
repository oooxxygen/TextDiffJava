package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 内存预算按 JVM 堆实际占用估计（String 开销 + UTF-16），而非字符数——大索引提前落盘防 OOM。 */
class AutoKeyIndexHeapBudgetTest {
    @TempDir Path dir;

    @Test
    void spillsByHeapEstimateNotCharCount() {
        // 单行堆估计 = 16+8 + (40+2×1) + (40+2×10) = 126B；旧字符口径仅 19，阈值 200 永不触发
        try (AutoKeyIndex idx = new AutoKeyIndex(dir, 200)) {
            idx.put("k", new String[]{"0123456789"});
            assertFalse(idx.spilled(), "首行 126B ≤ 200B 应驻内存");
            idx.put("k2", new String[]{"0123456789"});
            assertTrue(idx.spilled(), "累计 254B > 200B 应落盘");
        }
    }

    @Test
    void spilledIndexStillServesRows() {
        try (AutoKeyIndex idx = new AutoKeyIndex(dir, 1)) { // 阈值=1 首行即落盘
            idx.put("k", new String[]{"v1", "v2"});
            assertArrayEquals(new String[]{"v1", "v2"}, idx.get("k"));
            assertEquals(1, idx.size());
        }
    }
}
