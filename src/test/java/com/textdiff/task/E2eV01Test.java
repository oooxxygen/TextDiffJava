package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实数据端到端（O:\CodeRepos\v01，bocso vs bocsoxc，配置.txt）。
 * 数据不存在时（如 CI 环境）自动跳过。
 */
class E2eV01Test {
    private static com.textdiff.config.AppConfig fakeAppConfig() {
        return new com.textdiff.config.AppConfig(
                new com.textdiff.config.ServerConfig("127.0.0.1", 0),
                EngineConfig.defaults(),
                new com.textdiff.config.StoreConfig(false),
                new com.textdiff.config.AppConfig.AiConfig(false, "", "", "", 30));
    }

    private static final Path V01 = Path.of("O:\\CodeRepos\\v01");

    @Test
    void fullFlowOnRealData(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(V01), "v01 样例数据不存在，跳过");
        Path config = V01.resolve("配置.txt");
        Assumptions.assumeTrue(Files.isRegularFile(config), "配置.txt 不存在，跳过");
        List<String> configLines = Files.readAllLines(config, StandardCharsets.UTF_8);
        configLines.removeIf(l -> l.strip().isEmpty() || l.strip().startsWith("#"));
        Assumptions.assumeTrue(!configLines.isEmpty(), "配置为空，跳过");

        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             JobManager mgr = new JobManager(store, dir.resolve("results"), EngineConfig.defaults())) {
            // 挂载 AI 分析器（AI 未启用 → prompt.md 仍产出，aiStatus=disabled）
            new com.textdiff.ai.AiAnalyzer(store, fakeAppConfig(), new com.textdiff.config.AppPaths(dir, dir), mgr);
            var batch = mgr.createBatch(V01.resolve("bocso"), V01.resolve("bocsoxc"), configLines);
            assertTrue(batch.jobCount >= 1, "应至少配对一个文件对，实际 " + batch.jobCount);
            assertTrue(mgr.awaitIdle(120, TimeUnit.SECONDS));

            for (JobRecord job : mgr.listJobs(batch.id)) {
                assertEquals(JobRecord.DONE, job.status, job.error);
                var meta = ResultFiles.readMeta(Path.of(job.resultDir));
                assertNotNull(meta);
                assertTrue(meta.totalA > 0, "A 文件应有数据行");
                // 结果文件可分页读取
                var page = ResultFiles.readPage(Path.of(job.resultDir).resolve("result.jsonl"),
                        "all", 0, 10);
                assertEquals(Math.min(10, meta.zoneEqual + meta.zoneDiff + meta.zoneUnmatched + meta.zoneTrailer),
                        page.rows().size());
                // prompt.md 自动产出（AI 异步执行，轮询等待；AI 未启用也应生成）
                Path promptFile = Path.of(job.resultDir).resolve("prompt.md");
                boolean promptReady = false;
                for (int i = 0; i < 150 && !promptReady; i++) {
                    promptReady = Files.exists(promptFile);
                    if (!promptReady) Thread.sleep(200);
                }
                Path rdir = Path.of(job.resultDir);
                String dbg = "summary=" + Files.readString(rdir.resolve(ResultFiles.SUMMARY_JSON),
                        StandardCharsets.UTF_8);
                assertTrue(promptReady, "prompt.md 应自动产出（aiStatus=" + store.getJob(job.id).aiStatus
                        + ", err=" + store.getJob(job.id).error + "） " + dbg);
                // 自动导出 CSV 产出
                Path exportFull = dir.resolve("results").resolve(batch.id).resolve("export")
                        .resolve((job.label == null ? job.nickname : job.label).replaceAll("[\\\\/:*?\"<>|]", "_") + "_全量.csv");
                assertTrue(Files.exists(exportFull), "自动导出全量 CSV: " + exportFull);
                System.out.println("[e2e] " + job.nickname + " 编码=" + meta.encodingA + "/" + meta.encodingB
                        + " totalA=" + meta.totalA + " totalB=" + meta.totalB
                        + " diff=" + meta.diff + " onlyA=" + meta.onlyA + " onlyB=" + meta.onlyB
                        + " keyWarn=" + job.keyWarning);
            }
        }
    }
}
