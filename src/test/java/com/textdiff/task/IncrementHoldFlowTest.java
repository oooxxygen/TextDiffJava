package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量后置流程（v0.9.15 需求①）：规则含 ADDMOD 且提交未带条件 → 作业挂起（awaitingCond）不入队，
 * 其余文本正常对比；批次页可按 job_id 补填条件（applyIncrConds）或放弃增量（releaseIncrHolds）开跑；
 * 重启恢复保持挂起不误跑。附带 conf_file 溯源与直连模式「内联」标记断言。
 */
class IncrementHoldFlowTest {

    private static void makeFiles(Path dir, String name, String aContent, String bContent) throws Exception {
        Path aDir = dir.resolve("a");
        Path bDir = dir.resolve("b");
        Files.createDirectories(aDir);
        Files.createDirectories(bDir);
        Files.writeString(aDir.resolve(name), aContent);
        Files.writeString(bDir.resolve(name), bContent);
    }

    /** 轮询等待某昵称作业到达目标状态（每次经 store 重查，挂起流程不能 awaitIdle——被挂起作业永远 pending）。 */
    private static void waitForStatus(Supplier<List<JobRecord>> jobs, String nick, String status, long seconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            boolean hit = jobs.get().stream()
                    .anyMatch(j -> j.configLine.startsWith(nick + ":") && status.equals(j.status));
            if (hit) return;
            Thread.sleep(50);
        }
        fail("等待超时: " + nick + " → " + status + "；当前: " + jobs.get());
    }

    /** 轮询等待作业数到位（异步建档在后台线程逐个落盘）。 */
    private static void waitForJobs(Supplier<List<JobRecord>> jobs, int n, long seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (jobs.get().size() >= n) return;
            Thread.sleep(50);
        }
        fail("等待作业数超时: 期望 " + n + "，当前 " + jobs.get().size());
    }

    /** 轮询等待批次建档完成（prepare_state=ready）——异步建档线程收尾后再安全关闭管理器。 */
    private static void waitForReady(com.textdiff.store.JobStore store, String batchId, long seconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            var b = store.getBatch(batchId);
            if (b != null && com.textdiff.store.BatchRecord.READY.equals(b.prepareState)) return;
            Thread.sleep(50);
        }
        fail("等待建档超时: " + batchId);
    }

    private static JobRecord byNick(List<JobRecord> jobs, String nick) {
        return jobs.stream().filter(j -> j.configLine.startsWith(nick + ":")).findFirst().orElseThrow();
    }

    @Test
    void addmodJobHoldsOthersRunThenApplyConds(@TempDir Path dir) throws Exception {
        makeFiles(dir, "aa.txt", "k1 | 1\nk2 | 2\n", "k1 | 1\nk2 | 9\n");
        makeFiles(dir, "bb.txt", "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            List<String> lines = List.of("NICKA:aa.txt:KEYSEQ=1:ADDMOD=2", "NICKB:bb.txt:KEYSEQ=1");
            // 异步批次链路（HTTP 提交语义）：ADDMOD 无条件 → 挂起待批次页补填
            var batch = mgr.createBatchAsync(dir.resolve("a"), dir.resolve("b"), lines, null, null,
                    null, null, null, List.of("current.conf", "DPTM_BATCH.conf"));
            waitForReady(store, batch.id, 15);

            waitForJobs(() -> mgr.listJobs(batch.id), 2, 10);
            List<JobRecord> jobs = mgr.listJobs(batch.id);
            // conf_file 溯源与配置行一一对应
            assertEquals("current.conf", byNick(jobs, "NICKA").confFile);
            assertEquals("DPTM_BATCH.conf", byNick(jobs, "NICKB").confFile);

            // 无 ADDMOD 的文本正常开跑；含 ADDMOD 未带条件 → 挂起（pending + awaitingCond）
            waitForStatus(() -> mgr.listJobs(batch.id), "NICKB", JobRecord.DONE, 15);
            JobRecord held = byNick(mgr.listJobs(batch.id), "NICKA");
            assertEquals(JobRecord.PENDING, held.status);
            assertTrue(held.awaitingCond);
            assertNull(held.incrCond);
            assertEquals(-1, held.diffCount);

            // 批次页补填条件（按 job_id）→ 入队执行且条件随作业落库
            int n = mgr.applyIncrConds(batch.id, Map.of(held.id, List.of("k1")));
            assertEquals(1, n);
            waitForStatus(() -> mgr.listJobs(batch.id), "NICKA", JobRecord.DONE, 15);
            JobRecord done = byNick(mgr.listJobs(batch.id), "NICKA");
            assertFalse(done.awaitingCond);
            assertEquals("[\"k1\"]", done.incrCond);
            assertNotNull(ResultFiles.readMeta(Path.of(done.resultDir)));
        }
    }

    @Test
    void releaseIncrHoldsRunsAllWithoutConds(@TempDir Path dir) throws Exception {
        makeFiles(dir, "aa.txt", "k1 | 1\n", "k1 | 9\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatchAsync(dir.resolve("a"), dir.resolve("b"),
                    List.of("NICKA:aa.txt:KEYSEQ=1:ADDMOD=2"), null, null, null, null, null, null);
            waitForReady(store, batch.id, 15);
            waitForJobs(() -> mgr.listJobs(batch.id), 1, 10);
            JobRecord held = byNick(mgr.listJobs(batch.id), "NICKA");
            assertTrue(held.awaitingCond);

            int n = mgr.releaseIncrHolds(batch.id);
            assertEquals(1, n);
            waitForStatus(() -> mgr.listJobs(batch.id), "NICKA", JobRecord.DONE, 15);
            JobRecord done = byNick(mgr.listJobs(batch.id), "NICKA");
            assertFalse(done.awaitingCond);
            assertNull(done.incrCond); // 放弃增量 = 无条件全量对比
        }
    }

    @Test
    void restartKeepsHeldJobsHeld(@TempDir Path dir) throws Exception {
        makeFiles(dir, "aa.txt", "k1 | 1\n", "k1 | 1\n");
        Path storeDir = dir.resolve("store");
        String batchId;
        String heldId;
        try (DualJobStore store = new DualJobStore(storeDir, false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatchAsync(dir.resolve("a"), dir.resolve("b"),
                    List.of("NICKA:aa.txt:KEYSEQ=1:ADDMOD=2"), null, null, null, null, null, null);
            waitForReady(store, batch.id, 15);
            waitForJobs(() -> mgr.listJobs(batch.id), 1, 10);
            batchId = batch.id;
            heldId = byNick(mgr.listJobs(batchId), "NICKA").id;
        }
        // 重启恢复：挂起作业保持挂起（不重新入队误跑）
        try (DualJobStore store = new DualJobStore(storeDir, false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            mgr.start();
            JobRecord held = mgr.getJob(heldId);
            assertEquals(JobRecord.PENDING, held.status);
            assertTrue(held.awaitingCond);
            assertEquals(-1, held.diffCount);
            // 重启后补填条件仍可开跑
            assertEquals(1, mgr.applyIncrConds(batchId, Map.of(heldId, List.of("k1"))));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            assertEquals(JobRecord.DONE, mgr.getJob(heldId).status);
        }
    }

    @Test
    void syncPathKeepsLegacyFullCompare(@TempDir Path dir) throws Exception {
        makeFiles(dir, "aa.txt", "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            // 同步建档（H2 命令信箱/旧流程语义）：ADDMOD + 无条件 = 全量对比直接跑，不挂起
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"),
                    List.of("NICKA:aa.txt:KEYSEQ=1:ADDMOD=2"), null, null, null, null, null, null);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = byNick(mgr.listJobs(batch.id), "NICKA");
            assertEquals(JobRecord.DONE, job.status);
            assertFalse(job.awaitingCond);
        }
    }

    @Test
    void condsProvidedUpfrontSkipHold(@TempDir Path dir) throws Exception {
        makeFiles(dir, "aa.txt", "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            // 提交时已带条件（直连/旧流程）→ 不挂起直接跑
            List<List<String>> conds = List.of(List.of("k1"));
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"),
                    List.of("NICKA:aa.txt:KEYSEQ=1:ADDMOD=2"), null, conds, null, null, null, null);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = byNick(mgr.listJobs(batch.id), "NICKA");
            assertEquals(JobRecord.DONE, job.status);
            assertFalse(job.awaitingCond);
        }
    }

    @Test
    void directPairsMarkInlineConfFile(@TempDir Path dir) throws Exception {
        makeFiles(dir, "f1.txt", "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatchFromPairs("直连对比",
                    List.<String[]>of(new String[]{"NICK:*.txt:KEYSEQ=1", dir.resolve("a").resolve("f1.txt").toString(),
                            dir.resolve("b").resolve("f1.txt").toString(), "NICK"}));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            assertEquals("内联", mgr.listJobs(batch.id).get(0).confFile);
        }
    }
}
