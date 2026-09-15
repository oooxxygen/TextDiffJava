package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskStoreTest {

    private static TaskRecord rec(String id, String jobId, String type) {
        return new TaskRecord(id, "b1", jobId, type);
    }

    @Test
    void saveListAndReloadFromJsonl(@TempDir Path dir) {
        try (TaskStore store = new TaskStore(dir, true)) {
            assertTrue(store.dbAvailable());
            TaskRecord t1 = rec("t1", "j1", TaskRecord.EXPORT_DIFF);
            store.save(t1);
            TaskRecord t2 = rec("t2", "j1", TaskRecord.AI_ANALYSIS);
            t2.status = TaskRecord.RUNNING;
            store.save(t2);
            store.save(rec("t3", "j2", TaskRecord.EXPORT_DIFF));

            assertEquals(3, store.listAll().size());
            assertEquals(2, store.listForJob("j1").size());
            assertEquals(3, store.listForBatch("b1").size()); // 三条记录同属批次 b1
            assertEquals(TaskRecord.RUNNING, store.get("t2").status);
        }
        // 重载：JSONL last-wins
        try (TaskStore reopened = new TaskStore(dir, true)) {
            assertEquals(3, reopened.listAll().size());
            assertEquals(TaskRecord.RUNNING, reopened.get("t2").status);
        }
    }

    @Test
    void statusUpdatePersistsLastWin(@TempDir Path dir) {
        try (TaskStore store = new TaskStore(dir, false)) {
            TaskRecord t = rec("t1", "j1", TaskRecord.EXPORT_DIFF);
            store.save(t);
            t.status = TaskRecord.DONE;
            t.outputPath = "/x/差异.csv";
            t.finishedAt = 42;
            store.save(t);
            assertEquals(TaskRecord.DONE, store.get("t1").status);
            assertEquals("/x/差异.csv", store.get("t1").outputPath);
        }
        try (TaskStore reopened = new TaskStore(dir, false)) {
            assertEquals(TaskRecord.DONE, reopened.get("t1").status);
            assertEquals(42, reopened.get("t1").finishedAt);
        }
    }

    @Test
    void deleteRemovesRecordAndDoesNotResurrect(@TempDir Path dir) throws Exception {
        try (TaskStore store = new TaskStore(dir, true)) {
            store.save(rec("t1", "j1", TaskRecord.EXPORT_DIFF));
            store.save(rec("t2", "j1", TaskRecord.AI_ANALYSIS));
            store.delete("t1");
            assertNull(store.get("t1"));
            assertEquals(1, store.listAll().size());
        }
        // 删除后重载不复活（整文件重写而非墓碑）
        try (TaskStore reopened = new TaskStore(dir, true)) {
            assertNull(reopened.get("t1"));
            assertEquals(1, reopened.listAll().size());
        }
        // H2 镜像同样只有一条
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:" + dir.resolve("h2").resolve("textdiff").toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM task_record")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void deleteForJobsCascades(@TempDir Path dir) {
        try (TaskStore store = new TaskStore(dir, false)) {
            store.save(rec("t1", "j1", TaskRecord.EXPORT_DIFF));
            store.save(rec("t2", "j1", TaskRecord.AI_ANALYSIS));
            store.save(rec("t3", "j2", TaskRecord.EXPORT_DIFF));
            store.deleteForJobs(List.of("j1"));
            assertEquals(1, store.listAll().size());
            assertNotNull(store.get("t3"));
        }
    }

    @Test
    void scheduledAtPersistsJsonlAndH2(@TempDir Path dir) throws Exception {
        try (TaskStore store = new TaskStore(dir, true)) {
            TaskRecord t = rec("t1", "j1", TaskRecord.AI_ANALYSIS);
            t.scheduledAt = 1799999999L;
            store.save(t);
            assertEquals(1799999999L, store.get("t1").scheduledAt);
        }
        // JSONL 重载
        try (TaskStore reopened = new TaskStore(dir, false)) {
            assertEquals(1799999999L, reopened.get("t1").scheduledAt);
        }
        // H2 镜像列
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:" + dir.resolve("h2").resolve("textdiff").toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT scheduled_at FROM task_record WHERE task_id='t1'")) {
            assertTrue(rs.next());
            assertEquals(1799999999L, rs.getLong(1));
        }
    }

    @Test
    void h2MirrorReceivesSaves(@TempDir Path dir) throws Exception {
        try (TaskStore store = new TaskStore(dir, true)) {
            TaskRecord t = rec("t1", "j1", TaskRecord.AI_ANALYSIS);
            t.status = TaskRecord.FAILED;
            t.error = "boom";
            store.save(t);
        }
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:" + dir.resolve("h2").resolve("textdiff").toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status, error FROM task_record WHERE task_id='t1'")) {
            assertTrue(rs.next());
            assertEquals(TaskRecord.FAILED, rs.getString(1));
            assertEquals("boom", rs.getString(2));
        }
    }

    @Test
    void reloadAfterH2Deleted(@TempDir Path dir) throws Exception {
        try (TaskStore store = new TaskStore(dir, true)) {
            store.save(rec("t1", "j1", TaskRecord.EXPORT_DIFF));
        }
        try (var s = Files.walk(dir.resolve("h2"))) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (Exception ignored) {
                }
            });
        }
        try (TaskStore reopened = new TaskStore(dir, true)) {
            assertTrue(reopened.dbAvailable()); // 镜像自愈重建
            assertEquals(1, reopened.listAll().size());
        }
    }
}
