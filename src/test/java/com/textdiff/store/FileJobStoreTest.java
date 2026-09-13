package com.textdiff.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileJobStoreTest {

    @Test
    void roundTripAcrossReopen(@TempDir Path dir) {
        BatchRecord batch = new BatchRecord();
        batch.id = "b1";
        batch.label = "第一批";
        batch.dirA = "O:/data/a";
        batch.dirB = "O:/data/b";
        batch.configSource = "INCT0101:01A***0*.v01:KEYSEQ=3/4/5";
        batch.createdAt = 1000;
        batch.jobCount = 1;

        JobRecord job = new JobRecord("j1", "b1", "样例", "NICK:GLOB:KEYSEQ=3/4/5",
                "a.v01", "b.v01", "results/j1");
        job.status = JobRecord.DONE;
        job.keyWarning = true;

        try (FileJobStore store = new FileJobStore(dir)) {
            store.saveBatch(batch);
            store.saveJob(job);
            store.putNote(new NoteRecord("j1", "k=1", "diff", "差异备注", 42));
        }

        try (FileJobStore reopened = new FileJobStore(dir)) {
            BatchRecord rb = reopened.getBatch("b1");
            assertNotNull(rb);
            assertEquals("第一批", rb.label);
            assertEquals(1, rb.jobCount);

            JobRecord rj = reopened.getJob("j1");
            assertNotNull(rj);
            assertEquals(JobRecord.DONE, rj.status);
            assertTrue(rj.keyWarning);

            List<JobRecord> all = reopened.listJobs("b1");
            assertEquals(1, all.size());
            assertTrue(reopened.listJobs("nope").isEmpty());

            List<NoteRecord> rn = reopened.getNotes("j1");
            assertEquals(1, rn.size());
            assertEquals("差异备注", rn.get(0).note());
        }
    }

    @Test
    void lastWriteWins(@TempDir Path dir) {
        try (FileJobStore store = new FileJobStore(dir)) {
            JobRecord j = new JobRecord("j1", "b1", "n", "cfg", "a", "b", "r");
            store.saveJob(j);
            j.status = JobRecord.RUNNING;
            store.saveJob(j);
            j.status = JobRecord.FAILED;
            j.error = "boom";
            store.saveJob(j);
        }
        try (FileJobStore store = new FileJobStore(dir)) {
            JobRecord j = store.getJob("j1");
            assertEquals(JobRecord.FAILED, j.status);
            assertEquals("boom", j.error);
        }
    }

    @Test
    void noteUpsertByKeyJobZone(@TempDir Path dir) {
        try (FileJobStore store = new FileJobStore(dir)) {
            store.putNote(new NoteRecord("j1", "k=1", "diff", "v1", 1));
            store.putNote(new NoteRecord("j1", "k=1", "diff", "v2", 2));
            store.putNote(new NoteRecord("j1", "k=2", "diff", "other", 3));
        }
        try (FileJobStore store = new FileJobStore(dir)) {
            List<NoteRecord> notes = store.getNotes("j1");
            assertEquals(2, notes.size());
            NoteRecord first = notes.stream().filter(n -> n.key().equals("k=1")).findFirst().orElseThrow();
            assertEquals("v2", first.note());
            assertEquals(2, first.updatedAt());
        }
    }

    @Test
    void toleratesCorruptTailLine(@TempDir Path dir) throws Exception {
        Path jobs = dir.resolve("jobs.jsonl");
        Files.writeString(jobs, "{\"id\":\"j1\",\"batchId\":\"b1\",\"status\":\"done\"}\n"
                + "{\"id\":\"j1\",\"stat"); // 崩溃残留半行
        try (FileJobStore store = new FileJobStore(dir)) {
            JobRecord j = store.getJob("j1");
            assertNotNull(j);
            assertEquals("done", j.status);
        }
    }

    @Test
    void copiesAreDefensive(@TempDir Path dir) {
        try (FileJobStore store = new FileJobStore(dir)) {
            JobRecord j = new JobRecord("j1", "b1", "n", "cfg", "a", "b", "r");
            store.saveJob(j);
            j.status = JobRecord.RUNNING; // 修改调用方对象不影响库内快照
            assertEquals(JobRecord.PENDING, store.getJob("j1").status);
            store.getJob("j1").status = JobRecord.DONE; // 修改取出的副本也不影响
            assertEquals(JobRecord.PENDING, store.getJob("j1").status);
        }
    }
}
