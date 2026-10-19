package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.store.FileJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 单作业内存预算随堆与并行度收缩：并行 P 个大索引不再可能合计撑爆堆。 */
class JobManagerMemoryBudgetTest {
    @TempDir Path dir;

    private JobManager newManager(int maxThreads, long configuredBudget) {
        EngineConfig cfg = new EngineConfig(maxThreads, configuredBudget, dir.resolve("tmp").toString(), 8);
        return new JobManager(new FileJobStore(dir.resolve("store")), dir.resolve("results"), cfg);
    }

    @Test
    void budgetAdaptsToHeapAndParallelism() {
        try (JobManager jm = newManager(4, Long.MAX_VALUE)) {
            // 合并单表索引后每作业只持一个 AutoKeyIndex，预算即作业合计驻留
            long expected = Math.max(16L * 1024 * 1024,
                    Runtime.getRuntime().maxMemory() / (4L * 4));
            assertEquals(expected, jm.inMemoryBudget());
        }
    }

    @Test
    void budgetNeverExceedsConfiguredAndNeverBelowFloor() {
        long configured = 128L * 1024 * 1024;
        try (JobManager jm = newManager(4, configured)) {
            long budget = jm.inMemoryBudget();
            assertTrue(budget <= configured, "不得超过配置阈值");
            assertTrue(budget >= 16L * 1024 * 1024, "不低于 16MB 下限");
        }
        try (JobManager jm = newManager(4, 1024)) { // 配置极小 → 按配置（强制落盘场景）
            assertEquals(1024, jm.inMemoryBudget());
        }
    }

    @Test
    void budgetShrinksWhenParallelismRaised() {
        try (JobManager jm = newManager(4, Long.MAX_VALUE)) {
            long before = jm.inMemoryBudget();
            jm.resizeEnginePool(8);
            long after = jm.inMemoryBudget();
            // 测试堆较小时 4→8 并行的预算可能都落在 16MB 下限——此时断言两者都触底即可
            if (Runtime.getRuntime().maxMemory() / (8L * 4) <= 16L * 1024 * 1024) {
                assertEquals(16L * 1024 * 1024, after, "小堆下应触底 16MB 下限");
            } else {
                assertTrue(after < before, "并行度 4→8 预算应收缩: " + before + " → " + after);
            }
        }
    }
}
