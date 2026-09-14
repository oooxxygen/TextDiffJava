package com.textdiff.task;

import com.textdiff.config.EngineConfig;
import com.textdiff.store.DualJobStore;
import com.textdiff.store.JobRecord;
import com.textdiff.store.TaskRecord;
import com.textdiff.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 任务管理：作业完成登记两条默认任务、执行、重新生成、删除与生成路径设置。 */
class TaskManagerTest {

    /** AI 分析器不可用（null）：AI 任务失败、导出任务正常完成。 */
    @Test
    void jobDoneRegistersAndRunsDefaults(@TempDir Path dir) throws Exception {
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             TaskStore tasks = new TaskStore(dir.resolve("store2"), false);
             TaskManager mgr = new TaskManager(store, tasks, null,
                     new com.textdiff.config.AppPaths(dir, dir), dir.resolve("results"))) {
            JobRecord job = makeDoneJob(dir, store);
            mgr.registerDefaults(job);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));

            var list = tasks.listForJob(job.id);
            assertEquals(2, list.size());
            for (TaskRecord t : list) {
                if (TaskRecord.EXPORT_DIFF.equals(t.taskType)) {
                    assertEquals(TaskRecord.DONE, t.status, t.error);
                    assertTrue(t.outputPath.endsWith("_差异.csv"), t.outputPath);
                    assertTrue(Files.exists(Path.of(t.outputPath)));
                    assertEquals(TaskRecord.TRIGGER_AUTO, t.trigger);
                } else {
                    assertEquals(TaskRecord.FAILED, t.status); // ai=null
                    assertTrue(t.startedAt > 0, "失败任务应记录开始时间");
                    assertFalse(t.error.isBlank(), "失败任务应记录错误");
                }
            }
            // 再次触发不重复登记
            mgr.registerDefaults(job);
            assertTrue(mgr.awaitIdle(10, TimeUnit.SECONDS));
            assertEquals(2, tasks.listForJob(job.id).size());
        }
    }

    @Test
    void regenerateRerunsExportTask(@TempDir Path dir) throws Exception {
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             TaskStore tasks = new TaskStore(dir.resolve("store2"), false);
             TaskManager mgr = new TaskManager(store, tasks, null,
                     new com.textdiff.config.AppPaths(dir, dir), dir.resolve("results"))) {
            JobRecord job = makeDoneJob(dir, store);
            mgr.registerDefaults(job);
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            TaskRecord export = tasks.listForJob(job.id).stream()
                    .filter(t -> TaskRecord.EXPORT_DIFF.equals(t.taskType)).findFirst().orElseThrow();

            assertTrue(mgr.regenerate(export.taskId));
            assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
            TaskRecord after = tasks.get(export.taskId);
            assertEquals(TaskRecord.DONE, after.status, after.error);
            assertEquals(TaskRecord.TRIGGER_MANUAL, after.trigger); // 手动触发标记
            assertTrue(after.finishedAt >= export.finishedAt);

            // 删除：记录移除（产物文件保留）
            mgr.delete(export.taskId);
            assertNull(tasks.get(export.taskId));
            assertTrue(Files.exists(Path.of(after.outputPath)));
        }
    }

    @Test
    void settingsPersistAndAffectExportDir(@TempDir Path dir) throws Exception {
        Path custom = dir.resolve("custom-export");
        try (DualJobStore store = new DualJobStore(dir.resolve("store"), false);
             TaskStore tasks = new TaskStore(dir.resolve("store2"), false)) {
            JobRecord job = makeDoneJob(dir, store);
            // 先写设置再建 manager：验证启动加载
            TaskManager first = new TaskManager(store, tasks, null,
                    new com.textdiff.config.AppPaths(dir, dir), dir.resolve("results"));
            first.saveSettings(new TaskManager.TaskDirs(custom.toString(), ""));
            first.close();

            try (TaskManager mgr = new TaskManager(store, tasks, null,
                    new com.textdiff.config.AppPaths(dir, dir), dir.resolve("results"))) {
                assertEquals(custom.toString(), mgr.settings().exportDir());
                mgr.registerDefaults(job);
                assertTrue(mgr.awaitIdle(30, TimeUnit.SECONDS));
                TaskRecord export = tasks.listForJob(job.id).stream()
                        .filter(t -> TaskRecord.EXPORT_DIFF.equals(t.taskType)).findFirst().orElseThrow();
                assertEquals(TaskRecord.DONE, export.status, export.error);
                assertTrue(export.outputPath.startsWith(custom.toAbsolutePath().toString()),
                        "产物应落在配置目录: " + export.outputPath);
            }
        }
    }

    /** 造一个 DONE 作业（存入 store；结果目录含 result.jsonl，供差异导出读取）。 */
    private static JobRecord makeDoneJob(Path dir, DualJobStore store) throws Exception {
        Path rdir = dir.resolve("results").resolve("jobx");
        Files.createDirectories(rdir);
        Files.writeString(rdir.resolve("result.jsonl"),
                """
                        {"key":"k1","status":"diff","section":"data","a":["1","2"],"b":["1","9"],"diff_cols":[1]}
                        {"key":"k2","status":"equal","section":"data","a":["2","2"],"b":["2","2"],"diff_cols":[]}
                        """);
        JobRecord job = new JobRecord("jobx", "batchx", "NICK · f1.txt",
                "NICK:*.txt:KEYSEQ=1",
                dir.resolve("a.txt").toString(), dir.resolve("b.txt").toString(),
                rdir.toAbsolutePath().toString());
        job.status = JobRecord.DONE;
        store.saveJob(job);
        return job;
    }
}
