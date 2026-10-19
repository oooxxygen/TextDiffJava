package com.textdiff.task;

import com.textdiff.config.AppConfig;
import com.textdiff.config.AppPaths;
import com.textdiff.config.EngineConfig;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 目录批次压缩感知集成：gz 逻辑名配对解压、最新版本生效、三个例外清单、产物清理。 */
class BatchGzipExtractTest {

    private static void gz(Path file, String content) throws Exception {
        try (var out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void 目录批次_压缩配对解压_最新生效_清单齐全(@TempDir Path dir) throws Exception {
        Path dirA = dir.resolve("A");
        Path dirB = dir.resolve("B");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        // A：同逻辑名两个版本（旧 1 行 / 新 2 行）+ 无规则文件；B：单版本
        gz(dirA.resolve("01A1010D.ew1.gz.202601011"), "K1,1\n");
        gz(dirA.resolve("01A1010D.ew1.gz.202601021"), "K1,1\nK2,2\n");
        Files.writeString(dirA.resolve("norule.txt"), "x\n");
        gz(dirB.resolve("01A1010D.ew1.gz.202601011"), "K1,1\nK2,2\n");

        List<String> configLines = List.of(
                "TEST:01A***0D.ew1:KEYSEQ=1:DELIM=,:PRISEQ=1",
                "GHOST:zzz*.txt:KEYSEQ=1");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"),
                     new EngineConfig(2, EngineConfig.DEFAULT_MAX_IN_MEMORY_BYTES, "./temp", 4));
             ExtractService svc = new ExtractService(store, mgr,
                     new AppPaths(dir, dir), fakeAppConfig())) {
            mgr.extractService = svc;
            BatchRecord batch = mgr.createBatch(dirA, dirB, configLines);
            boolean idle = mgr.awaitIdle(60, TimeUnit.SECONDS);
            if (!idle) {
                for (JobRecord j : mgr.listJobs(batch.id)) {
                    System.out.println("[dbg] job " + j.id + " status=" + j.status + " err=" + j.error
                            + " a=" + j.fileA + " b=" + j.fileB);
                }
                System.out.println("[dbg] temp dir exists=" + Files.exists(dir.resolve("temp")));
                try (var s = Files.walk(dir.resolve("temp"))) {
                    s.forEach(p -> System.out.println("[dbg] temp: " + p));
                } catch (Exception ignored) {
                }
            }
            assertTrue(idle, "作业未在时限内完成");

            // 一个 gz 配对作业（TEST），GHOST 双侧无文件 → missingFiles
            List<JobRecord> children = mgr.listJobs(batch.id);
            assertEquals(1, children.size());
            assertEquals(JobRecord.DONE, children.get(0).status, children.get(0).error);
            assertTrue(children.get(0).configLine.contains("PRISEQ=1"),
                    () -> "configLine=" + children.get(0).configLine);
            BatchRecord fresh = store.getBatch(batch.id);
            assertTrue(fresh.missingFiles.contains("GHOST · zzz*.txt"), fresh.missingFiles.toString());
            assertEquals(1, fresh.noRuleFiles.size());
            assertEquals("norule.txt", fresh.noRuleFiles.get(0));
            assertTrue(fresh.extractFailed == null || fresh.extractFailed.isEmpty());
            // 对比用了最新版本（A 侧 2 行）且解压产物已清理
            var meta = mgr.jobMeta(children.get(0).id);
            assertEquals(2, meta.totalA);
            assertFalse(Files.exists(dir.resolve("temp").resolve(batch.id)), "解压产物应随作业完成清理");
        }
    }

    @Test
    void 损坏gzip_解压失败_作业FAILED并计入清单(@TempDir Path dir) throws Exception {
        Path dirA = dir.resolve("A");
        Path dirB = dir.resolve("B");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);
        Files.writeString(dirA.resolve("bad.ew1.gz.202601011"), "这不是gzip内容");
        gz(dirB.resolve("bad.ew1.gz.202601011"), "K1,1\n");
        List<String> configLines = List.of("TEST:bad.ew1:KEYSEQ=1:DELIM=,");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"),
                     new EngineConfig(2, EngineConfig.DEFAULT_MAX_IN_MEMORY_BYTES, "./temp", 4));
             ExtractService svc = new ExtractService(store, mgr,
                     new AppPaths(dir, dir), fakeAppConfig())) {
            mgr.extractService = svc;
            BatchRecord batch = mgr.createBatch(dirA, dirB, configLines);
            boolean settled = mgr.awaitIdle(30, TimeUnit.SECONDS);
            // awaitIdle 以 store 无 pending/running 为准；失败作业为 failed 即视为空闲
            JobRecord job = mgr.listJobs(batch.id).get(0);
            assertTrue(settled || JobRecord.FAILED.equals(job.status));
            assertEquals(JobRecord.FAILED, job.status);
            assertTrue(job.error.startsWith("解压失败: "), job.error);
            // 清单与作业终态分两次落盘：轮询兜底等待批次清单可见
            BatchRecord fresh = store.getBatch(batch.id);
            for (int i = 0; i < 100 && (fresh.extractFailed == null || fresh.extractFailed.isEmpty()); i++) {
                Thread.sleep(50);
                fresh = store.getBatch(batch.id);
            }
            assertNotNull(fresh.extractFailed);
            assertEquals(1, fresh.extractFailed.size());
            assertTrue(fresh.extractFailed.get(0).startsWith("bad.ew1.gz.202601011"));
        }
    }

    private static AppConfig fakeAppConfig() {
        return new AppConfig(
                new com.textdiff.config.ServerConfig("127.0.0.1", 0),
                EngineConfig.defaults(),
                new com.textdiff.config.StoreConfig(false),
                new AppConfig.AiConfig(false, "", "", "", 30));
    }
}
