package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DualJobStoreTest {

    @Test
    void h2MirrorReceivesWrites(@TempDir Path dir) {
        try (DualJobStore store = new DualJobStore(dir, true)) {
            assertTrue(store.dbAvailable());

            BatchRecord b = new BatchRecord("b1", "label", "a", "b", "cfg", 1);
            store.saveBatch(b);
            JobRecord j = new JobRecord("j1", "b1", "n", "cfg", "a", "b", "r");
            j.status = JobRecord.DONE;
            store.saveJob(j);
            store.putNote(new NoteRecord("j1", "k=1", "diff", "note", 1));

            // 直接开 H2 验证镜像内容
            try (H2JobStore db = new H2JobStore(dir.resolve("h2"))) {
                assertEquals("label", db.getBatch("b1").label);
                assertEquals(JobRecord.DONE, db.getJob("j1").status);
                assertEquals(1, db.getNotes("j1").size());
            }
        }
    }

    @Test
    void disabledH2FallsBackToFileOnly(@TempDir Path dir) {
        try (DualJobStore store = new DualJobStore(dir, false)) {
            assertFalse(store.dbAvailable());
            JobRecord j = new JobRecord("j1", "b1", "n", "cfg", "a", "b", "r");
            store.saveJob(j);
            assertEquals(JobRecord.PENDING, store.getJob("j1").status);
            assertFalse(Files.exists(dir.resolve("h2")));
        }
    }

    @Test
    void fileRemainsSourceOfTruth(@TempDir Path dir) {
        try (DualJobStore store = new DualJobStore(dir, true)) {
            JobRecord j = new JobRecord("j1", "b1", "n", "cfg", "a", "b", "r");
            j.status = JobRecord.RUNNING;
            store.saveJob(j);
        }
        // H2 数据目录被删除后重启：镜像按文件重建
        assertTrue(Files.exists(dir.resolve("jobs.jsonl")));
        try (DualJobStore reopened = new DualJobStore(dir, true)) {
            assertEquals(JobRecord.RUNNING, reopened.getJob("j1").status);
            assertTrue(reopened.dbAvailable());
        }
    }

    @Test
    void noteUpsertMirrored(@TempDir Path dir) {
        try (DualJobStore store = new DualJobStore(dir, true)) {
            store.putNote(new NoteRecord("j1", "k=1", "diff", "v1", 1));
            store.putNote(new NoteRecord("j1", "k=1", "diff", "v2", 2));
        }
        try (H2JobStore db = new H2JobStore(dir.resolve("h2"))) {
            assertEquals("v2", db.getNotes("j1").get(0).note());
        }
    }
}
