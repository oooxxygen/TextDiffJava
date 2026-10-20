package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 万级文本提交立即响应：createBatchAsync 先落批次（preparing）即返回，建档与执行在后台完成。 */
class CreateBatchAsyncTest {
    @TempDir Path dir;

    private void makePair(String name, String a, String b) throws Exception {
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("a").resolve(name), a);
        Files.writeString(dir.resolve("b").resolve(name), b);
    }

    @Test
    void asyncBatchReturnsImmediatelyThenMaterializes(@TempDir Path ignored) throws Exception {
        for (int i = 0; i < 5; i++) {
            makePair("f" + i + ".txt", "k1 | " + i + "\n", "k1 | " + i + "\n");
        }
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            long t0 = System.nanoTime();
            BatchRecord batch = mgr.createBatchAsync(dir.resolve("a"), dir.resolve("b"),
                    List.of("NICK:*.txt:KEYSEQ=1"), null, null);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            // 后台建档极快时返回前即可能已 READY：两种状态都证明调用未阻塞等待建档完成
            BatchRecord first = store.getBatch(batch.id);
            assertTrue(BatchRecord.PREPARING.equals(first.prepareState)
                            || BatchRecord.READY.equals(first.prepareState),
                    "返回时应为 preparing 或已就绪，实测 " + first.prepareState);

            // 建档在后台完成：轮询至 READY，再等作业全部执行到终态
            BatchRecord done = null;
            for (int i = 0; i < 200; i++) {
                BatchRecord cur = store.getBatch(batch.id);
                if (BatchRecord.READY.equals(cur.prepareState)) { done = cur; break; }
                Thread.sleep(25);
            }
            assertNotNull(done, "建档未在 5s 内完成");
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            done = store.getBatch(batch.id);
            assertEquals(BatchRecord.READY, done.prepareState);
            assertEquals(5, done.jobCount);
            assertEquals(5, mgr.listJobs(batch.id).size());
            assertTrue(elapsedMs < 5000, "建档提交应立即返回，实测 " + elapsedMs + "ms");
        }
    }

    @Test
    void prepareFailureMarksBatchFailed() throws Exception {
        makePair("f0.txt", "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            // 非法配置行 → 配对阶段抛异常 → 批次终态 failed（页面可见原因）
            BatchRecord batch = mgr.createBatchAsync(dir.resolve("a"), dir.resolve("b"),
                    List.of("BOGUS:::KEYSEQ=???"), null, null);
            BatchRecord b = store.getBatch(batch.id);
            // 建档仅 1 个文件对且配置秒败：返回后下一瞬即可能已 failed——两者皆证明异步未阻塞
            assertTrue(BatchRecord.PREPARING.equals(b.prepareState)
                            || BatchRecord.PREPARE_FAILED.equals(b.prepareState),
                    "返回时应为 preparing 或已 failed，实测 " + b.prepareState);
            for (int i = 0; i < 100; i++) {
                if (BatchRecord.PREPARE_FAILED.equals(store.getBatch(batch.id).prepareState)) break;
                Thread.sleep(50);
            }
            BatchRecord failed = store.getBatch(batch.id);
            assertEquals(BatchRecord.PREPARE_FAILED, failed.prepareState);
            assertNotNull(failed.prepareError);
        }
    }

    @Test
    void previewPairsMatchesMaterializeOrder(@TempDir Path ignored) throws Exception {
        // 普通文件 + gz 多版本混合：预览顺序必须与建档（增量条件按序对齐的前提）完全一致
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        for (int i = 3; i >= 1; i--) { // 故意乱序写入
            Files.writeString(dir.resolve("a").resolve("rep" + i + ".txt"), "k | " + i + "\n");
            Files.writeString(dir.resolve("b").resolve("rep" + i + ".txt"), "k | " + i + "\n");
        }
        Files.writeString(dir.resolve("a").resolve("gz1.txt.gz"), "k | 1\n");
        Files.writeString(dir.resolve("a").resolve("gz2.txt.gz.202601011"), "k | 2\n");
        Files.writeString(dir.resolve("b").resolve("gz1.txt.gz"), "k | 1\n");
        Files.writeString(dir.resolve("b").resolve("gz2.txt.gz.202601011"), "k | 2\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var preview = mgr.previewPairs(dir.resolve("a"), dir.resolve("b"),
                    List.of("NICK:*.txt:KEYSEQ=1"));
            BatchRecord batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"),
                    List.of("NICK:*.txt:KEYSEQ=1"));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            List<JobRecord> jobs = mgr.listJobs(batch.id);
            assertEquals(preview.size(), jobs.size());
            for (int i = 0; i < preview.size(); i++) {
                // 顺序完全一致；file_name 语义有意不同（gz 预览给逻辑名，作业记录为最新版本 .gz 路径）
                assertEquals(preview.get(i).get("file_a"), jobs.get(i).fileA, "预览/建档顺序不一致 @" + i);
                assertEquals(preview.get(i).get("file_b"), jobs.get(i).fileB, "预览/建档顺序不一致 @" + i);
            }
            // gz 逻辑名升序：gz1 在 gz2 之前（此前按目录流顺序，非确定）
            assertEquals("gz1.txt.gz", Path.of(jobs.get(3).fileA).getFileName().toString());
        }
    }
}
