package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.engine.Status;
import com.textdiff.store.CommandRecord;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.Json;
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

    /** 等待后台轮询器把命令消费到终态，返回终态命令。 */
    private static CommandRecord awaitCommand(DualJobStore store, String commandId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            CommandRecord c = store.getCommand(commandId);
            if (c != null && (CommandRecord.DONE.equals(c.status) || CommandRecord.ERROR.equals(c.status))) return c;
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    void retryCommandViaMailbox(@TempDir Path dir) throws Exception {
        makePair(dir, "f1.txt", "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), true);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of("NICK:*.txt:KEYSEQ=1"));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            job.status = JobRecord.FAILED;
            job.error = "模拟故障";
            store.saveJob(job);

            store.saveCommand(new CommandRecord("cmd-retry-1", "retry", "{\"jobId\":\"" + job.id + "\"}"));
            CommandRecord done = awaitCommand(store, "cmd-retry-1");
            assertNotNull(done, "命令未被轮询消费");
            assertEquals(CommandRecord.DONE, done.status, done.result);
            assertEquals("ok", done.result);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            assertEquals(JobRecord.DONE, mgr.getJob(job.id).status);
        }
    }

    @Test
    void createBatchCommandViaMailbox(@TempDir Path dir) throws Exception {
        makePair(dir, "f1.txt", "k1 | 1\n", "k1 | 2\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), true);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            String payload = Json.write(java.util.Map.of(
                    "dirA", dir.resolve("a").toString(),
                    "dirB", dir.resolve("b").toString(),
                    "configLines", List.of("NICK:*.txt:KEYSEQ=1")));
            store.saveCommand(new CommandRecord("cmd-batch-1", "create_batch", payload));

            CommandRecord done = awaitCommand(store, "cmd-batch-1");
            assertNotNull(done, "命令未被轮询消费");
            assertEquals(CommandRecord.DONE, done.status, done.result);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(done.result).get(0); // result = 新批次 id
            assertEquals(JobRecord.DONE, job.status, job.error);
        }
    }

    @Test
    void unknownCommandTypeReportsError(@TempDir Path dir) throws Exception {
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), true);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            store.saveCommand(new CommandRecord("cmd-bogus-1", "bogus", "{}"));
            CommandRecord done = awaitCommand(store, "cmd-bogus-1");
            assertNotNull(done, "命令未被轮询消费");
            assertEquals(CommandRecord.ERROR, done.status);
            assertTrue(done.result.contains("未知命令类型"), done.result);
        }
    }

    @Test
    void fieldMapInjectsColumnNamesIntoConfigLine(@TempDir Path dir) throws Exception {
        makePair(dir, "f1.txt", "k1 | 1\nk2 | 2\n", "k1 | 1\nk2 | 9\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             com.textdiff.store.FieldMapStore maps = new com.textdiff.store.FieldMapStore(
                     dir.resolve("fieldmaps"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"),
                     EngineConfig.defaults(), maps)) {
            maps.importAll(
                    List.of(new com.textdiff.store.FieldMaps.ReportType("R1", "f1.txt", "对公", "", "t.csv", 1L)),
                    List.of(new com.textdiff.store.FieldMaps.ReportField("R1", 0, "客户号", "CHAR", ""),
                            new com.textdiff.store.FieldMaps.ReportField("R1", 1, "余额", "DECIMAL", "")),
                    List.of("t.csv"));
            // 配置行本身无 COLS：作业运行时按文件名注入并持久化
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of("NICK:*.txt:KEYSEQ=1"));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            assertEquals(JobRecord.DONE, job.status, job.error);
            assertTrue(job.configLine.contains("COLS="), job.configLine);
            var cfg = com.textdiff.engine.Rules.parseLegacy(job.configLine, " | ", "|||||");
            assertEquals(List.of("客户号", "余额"), cfg.columnNames);
            // meta 携带同一配置行（前端 column_names 来源）
            var meta = ResultFiles.readMeta(Path.of(job.resultDir));
            assertTrue(meta.configLine.contains("COLS="), meta.configLine);
        }
    }
}
