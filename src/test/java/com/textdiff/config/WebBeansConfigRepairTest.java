package com.textdiff.config;

import com.textdiff.store.BatchRecord;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 批次 createdAt 存量自愈：copy 漏拷时期漂移成保存时刻的批次时间，按最早作业创建时间回填。 */
class WebBeansConfigRepairTest {

    @Test
    void 漂移批次回填为最早作业时间(@TempDir Path dir) throws Exception {
        try (DualJobStore store = new DualJobStore(dir, false)) {
            JobRecord early = new JobRecord("j1", "b1", "n", "c", "/a/f1", "/b/f1",
                    dir.resolve("r1").toString());
            store.saveJob(early);
            BatchRecord b = new BatchRecord("b1", "标签", "/a", "/b", "cfg", 1);
            store.saveBatch(b);
            JobRecord late = new JobRecord("j2", "b1", "n", "c", "/a/f2", "/b/f2",
                    dir.resolve("r2").toString());
            store.saveJob(late);
            // 模拟漂移：批次时间被改写成晚于全部作业（bug 时期每次保存都发生）
            b.createdAt = late.createdAt + 500;
            store.saveBatch(b);

            WebBeansConfig.repairBatchCreatedAt(store);

            assertEquals(early.createdAt, store.getBatch("b1").createdAt);
        }
    }

    @Test
    void 正常批次与无作业批次不动(@TempDir Path dir) {
        try (DualJobStore store = new DualJobStore(dir, false)) {
            JobRecord j = new JobRecord("j1", "b1", "n", "c", "/a", "/b", dir.resolve("r").toString());
            store.saveJob(j);
            BatchRecord ok = new BatchRecord("b1", "正常", "/a", "/b", "cfg", 1);
            ok.createdAt = j.createdAt - 100; // 建档先于作业：合法
            store.saveBatch(ok);
            BatchRecord empty = new BatchRecord("b2", "无作业", "/a", "/b", "cfg", 0);
            empty.createdAt = 777777L;
            store.saveBatch(empty);

            WebBeansConfig.repairBatchCreatedAt(store);

            assertEquals(j.createdAt - 100, store.getBatch("b1").createdAt);
            assertEquals(777777L, store.getBatch("b2").createdAt);
        }
    }
}
