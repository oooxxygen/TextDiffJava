package com.textdiff.report;

import com.textdiff.store.FileJobStore;
import com.textdiff.store.JobRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 报表对比主键/跳过栏位从 conf 规则读取：昵称命中注入 KEYSEQ/OMITSEQ 令牌，未命中 = 整行对比。 */
class ReportCompareConfKeyTest {

    @TempDir Path dir;

    private static void putFile(Path root, String name) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve(name), "a|1|x\nb|2|y\n");
    }

    @Test
    void confHitInjectsKeyseqOmitseq() throws Exception {
        Path configs = Files.createDirectories(dir.resolve("configs"));
        Files.writeString(configs.resolve("TESTNICK.conf"), "TESTNICK:*.dat:KEYSEQ=2:OMITSEQ=3\n");
        Path a = dir.resolve("A");
        Path b = dir.resolve("B");
        putFile(a, "TESTNICK.dat");
        putFile(b, "TESTNICK.dat");
        FileJobStore store = new FileJobStore(dir.resolve("jobs"));
        try (ReportCompareService svc = new ReportCompareService(
                store, dir.resolve("results"), configs, null, 1)) {
            var batch = svc.submit(null, a, b, "t");
            JobRecord job = store.listJobs(batch.id).get(0);
            assertEquals("TESTNICK:TESTNICK.dat:KEYSEQ=2:OMITSEQ=3", job.configLine);
        }
        store.close();
    }

    @Test
    void confMissFallsBackToWholeRow() throws Exception {
        Path a = dir.resolve("A");
        Path b = dir.resolve("B");
        putFile(a, "OTHERNICK.dat");
        putFile(b, "OTHERNICK.dat");
        // configs 目录不存在 = 未配置 → 整行对比
        FileJobStore store = new FileJobStore(dir.resolve("jobs2"));
        try (ReportCompareService svc = new ReportCompareService(
                store, dir.resolve("results2"), dir.resolve("no-such-configs"), null, 1)) {
            var batch = svc.submit(null, a, b, "t");
            JobRecord job = store.listJobs(batch.id).get(0);
            assertEquals("OTHERNICK:OTHERNICK.dat", job.configLine);
        }
        store.close();
    }
}
