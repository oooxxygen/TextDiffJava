package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 增量对比作业流：条件附加、过滤生效、增量状态标注、meta 统计、重跑沿用条件。 */
class IncrementalJobFlowTest {

    private static final String CFG = "NICK:*.txt:KEYSEQ=1:ADDMOD=2";

    private static void makePair(@TempDir Path dir, String aContent, String bContent) throws Exception {
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("a").resolve("f1.txt"), aContent, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b").resolve("f1.txt"), bContent, StandardCharsets.UTF_8);
    }

    @Test
    void incrementalFilterAndBadge(@TempDir Path dir) throws Exception {
        makePair(dir,
                "k1 | 20260701\nk2 | 20260702\nk3 | 20260702\n|||||\nRecNum=3\n",
                "k2 | 20260702\nk3 | 20260709\nk4 | 20260702\n|||||\nRecNum=3\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            // 条件与预览顺序对齐：仅 1 个文本
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of(CFG), null,
                    List.of(List.of("20260702")));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            assertEquals(JobRecord.DONE, job.status, job.error);
            assertEquals("增量", job.incrState);

            JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir));
            assertNotNull(meta.incr);
            assertEquals(List.of(2), meta.incr.fields); // 1-based
            assertEquals(List.of("20260702"), meta.incr.values);
            assertEquals(3, meta.incr.aBefore);
            assertEquals(2, meta.incr.aAfter);
            assertEquals(3, meta.incr.bBefore);
            assertEquals(2, meta.incr.bAfter);
            // 过滤产物已清理
            try (var s = Files.list(Path.of(job.resultDir).resolve("tmp"))) {
                assertTrue(s.findAny().isEmpty(), "tmp 应无残留过滤产物");
            }
            // 仅过滤后的行参与对比：k2 equal、k3 仅A（B 的 k3 因栏位2=20260709 被过滤）、k4 仅B
            assertEquals(2, ResultFiles.readPage(Path.of(job.resultDir).resolve("result.jsonl"),
                    "unmatched", 0, 50).total()); // unmatched: k3(仅A) + k4(仅B)
            assertEquals(0, ResultFiles.readPage(Path.of(job.resultDir).resolve("result.jsonl"),
                    "diff", 0, 50).total());
        }
    }

    @Test
    void emptyAfterFilterMarkedAsEmptyText(@TempDir Path dir) throws Exception {
        makePair(dir, "k1 | 20260701\n", "k1 | 20260701\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of(CFG), null,
                    List.of(List.of("20991231")));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            assertEquals(JobRecord.DONE, job.status, job.error);
            assertEquals("增量-空文本", job.incrState);
            assertEquals(0, job.totalA);
        }
    }

    @Test
    void noCondMeansFullCompare(@TempDir Path dir) throws Exception {
        makePair(dir, "k1 | 1\n", "k1 | 1\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of(CFG), null, null);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            assertEquals(JobRecord.DONE, job.status, job.error);
            assertNull(job.incrState); // 未填条件 = 全量对比，无增量标注
            JobMeta meta = ResultFiles.readMeta(Path.of(job.resultDir));
            assertNull(meta.incr);
        }
    }

    @Test
    void rerunPreservesIncrCond(@TempDir Path dir) throws Exception {
        makePair(dir, "k1 | 20260701\nk2 | 20260702\n", "k1 | 20260701\nk2 | 20260702\n");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), List.of(CFG), null,
                    List.of(List.of("20260702")));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            JobRecord job = mgr.listJobs(batch.id).get(0);
            // 重试（重跑同路径）：条件沿用，增量状态重算
            mgr.retry(job.id);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            job = mgr.listJobs(batch.id).get(0);
            assertEquals(JobRecord.DONE, job.status, job.error);
            assertEquals("增量", job.incrState);
            assertNotNull(job.incrCond);
        }
    }

    @Test
    void previewPairsMatchesBatchJobOrder(@TempDir Path dir) throws Exception {
        makePair(dir, "k1 | 1\n", "k1 | 1\n");
        Files.writeString(dir.resolve("a").resolve("f2.txt"), "x | 1\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b").resolve("f2.txt"), "x | 1\n", StandardCharsets.UTF_8);
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            List<String> lines = List.of(CFG, "OTHER:*.txt:KEYSEQ=1");
            var preview = mgr.previewPairs(dir.resolve("a"), dir.resolve("b"), lines);
            assertEquals(4, preview.size()); // 2 配置 × 2 文件
            assertEquals(List.of(2), preview.get(0).get("addmod"));
            assertEquals("NICK", preview.get(0).get("nickname"));
            assertEquals("OTHER", preview.get(2).get("nickname"));
            // 预览顺序与实际批次作业顺序一致
            var batch = mgr.createBatch(dir.resolve("a"), dir.resolve("b"), lines);
            var jobs = mgr.listJobs(batch.id);
            for (int i = 0; i < jobs.size(); i++) {
                String previewFile = Path.of((String) preview.get(i).get("file_a")).getFileName().toString();
                assertTrue(jobs.get(i).fileA.endsWith(previewFile),
                        "顺序不一致: " + i + " " + previewFile + " vs " + jobs.get(i).fileA);
            }
        }
    }
}
