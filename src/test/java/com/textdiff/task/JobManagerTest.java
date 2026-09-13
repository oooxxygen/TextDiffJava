package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.engine.Status;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JobManagerTest {

    private static Path makePair(@TempDir Path dir, String name, String aContent, String bContent) throws Exception {
        Path aDir = dir.resolve("a");
        Path bDir = dir.resolve("b");
        Files.createDirectories(aDir);
        Files.createDirectories(bDir);
        Files.writeString(aDir.resolve(name), aContent);
        Files.writeString(bDir.resolve(name), bContent);
        return dir;
    }

    @Test
    void batchRunsToEndWithResultFiles(@TempDir Path dir) throws Exception {
        makePair(dir, "f1.txt", "k1 | 1\nk2 | 2\n", "k1 | 1\nk2 | 9\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of("NICK:*.txt:KEYSEQ=1"));
            assertEquals(1, batch.jobCount);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));

            JobRecord job = mgr.listJobs(batch.id).get(0);
            assertEquals(JobRecord.DONE, job.status, job.error);
            assertFalse(job.keyWarning);
            assertEquals("pending", job.aiStatus);

            Path rdir = Path.of(job.resultDir);
            assertEquals(2, ResultFiles.readPage(rdir.resolve("result.jsonl"), "all", 0, 100).total());
            assertEquals(1, ResultFiles.readPage(rdir.resolve("result.jsonl"), Status.DIFF, 0, 100).total());
            assertNotNull(ResultFiles.readSummary(rdir));
            assertNotNull(ResultFiles.readMeta(rdir));
            assertEquals(1, ResultFiles.readSummary(rdir).diffColFreq.get(1)); // 1 个 diff 行包含列 1 差异
        }
    }

    @Test
    void failedJobThenRetry(@TempDir Path dir) throws Exception {
        makePair(dir, "f1.txt", "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of("NICK:*.txt:KEYSEQ=1"));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            assertEquals(JobRecord.DONE, job.status);

            // 人为置失败 → retry → 恢复
            job.status = JobRecord.FAILED;
            job.error = "模拟故障";
            store.saveJob(job);
            mgr.retry(job.id);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            assertEquals(JobRecord.DONE, mgr.getJob(job.id).status);
        }
    }

    @Test
    void cancelRunningJobMarksStopped(@TempDir Path dir) throws Exception {
        // 慢任务：大文件让 running 有窗口可取消；即使跑完也不算失败
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300000; i++) big.append("k").append(i).append(" | v").append(i).append("\n");
        makePair(dir, "big.txt", big.toString(), big.toString());
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of("NICK:*.txt:KEYSEQ=1"));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            boolean canceledOrDone = false;
            for (int i = 0; i < 100 && !canceledOrDone; i++) {
                JobRecord cur = mgr.getJob(job.id);
                if (JobRecord.RUNNING.equals(cur.status) || JobRecord.PENDING.equals(cur.status)) {
                    canceledOrDone = mgr.cancel(job.id);
                } else {
                    canceledOrDone = true; // 已完成
                }
            }
            assertTrue(canceledOrDone);
            String st = mgr.getJob(job.id).status;
            assertTrue(st.equals(JobRecord.STOPPED) || st.equals(JobRecord.DONE), st);
            assertTrue(mgr.awaitIdle(60, TimeUnit.SECONDS));
        }
    }

    @Test
    void keyWarningSetOnDuplicateKeys(@TempDir Path dir) throws Exception {
        makePair(dir, "f1.txt", "k1 | 1\nk1 | 2\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of("NICK:*.txt:KEYSEQ=1"));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(null).get(0);
            assertEquals(JobRecord.DONE, job.status, job.error);
            assertTrue(job.keyWarning);
            assertTrue(ResultFiles.readMeta(Path.of(job.resultDir)).keyWarning);
        }
    }

    @Test
    void restartRequeuesStuckJobs(@TempDir Path dir) throws Exception {
        makePair(dir, "f1.txt", "k1 | 1\n", "k1 | 1\n");
        JobRecord stuck;
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false)) {
            String batchId = "bx";
            var batch = new com.textdiff.store.BatchRecord(batchId, "l", "a", "b", "c", 1);
            store.saveBatch(batch);
            stuck = new JobRecord("jstuck", batchId, "n", "NICK:*.txt:KEYSEQ=1",
                    dir.resolve("a").resolve("f1.txt").toString(),
                    dir.resolve("b").resolve("f1.txt").toString(),
                    dir.resolve("results").resolve("jstuck").toAbsolutePath().toString());
            stuck.status = JobRecord.RUNNING; // 模拟进程中断残留
            store.saveJob(stuck);
        }
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            assertEquals(JobRecord.DONE, mgr.getJob("jstuck").status);
            assertEquals(1, ResultFiles.readPage(Path.of(mgr.getJob("jstuck").resultDir)
                    .resolve("result.jsonl"), "all", 0, 10).total());        }
    }
}
