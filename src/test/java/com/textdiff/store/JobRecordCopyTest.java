package com.textdiff.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** JobRecord/BatchRecord 逐字段拷贝：不再走 JSON 往返（5000 作业 × 轮询的序列化开销是 OOM 诱因）。 */
class JobRecordCopyTest {

    @Test
    void jobRecordCopyPreservesAllFieldsAndIsolatesMutation() {
        JobRecord j = new JobRecord("id1", "b1", "昵称", "N:*.txt:KEYSEQ=1", "/a/f", "/b/f", "/r/id1");
        j.status = JobRecord.RUNNING;
        j.error = "err";
        j.keyWarning = true;
        j.aiStatus = "done";
        j.startedAt = 111;
        j.finishedAt = 222;
        j.label = "标签";
        j.locked = true;
        j.starred = true;
        j.jobType = "report";
        j.templateFile = "/t.md";
        j.diffCount = 3;
        j.totalA = 10;
        j.totalB = 11;
        j.equalCount = 7;
        j.onlyACount = 1;
        j.onlyBCount = 2;
        j.incrCond = "[\"v1\"]";
        j.incrState = "增量";

        JobRecord c = j.copy();
        assertEquals(j.id, c.id);
        assertEquals(j.batchId, c.batchId);
        assertEquals(j.nickname, c.nickname);
        assertEquals(j.configLine, c.configLine);
        assertEquals(j.fileA, c.fileA);
        assertEquals(j.fileB, c.fileB);
        assertEquals(j.resultDir, c.resultDir);
        assertEquals(j.status, c.status);
        assertEquals(j.error, c.error);
        assertTrue(c.keyWarning);
        assertEquals(j.aiStatus, c.aiStatus);
        assertEquals(j.createdAt, c.createdAt);
        assertEquals(j.startedAt, c.startedAt);
        assertEquals(j.finishedAt, c.finishedAt);
        assertEquals(j.label, c.label);
        assertTrue(c.locked);
        assertTrue(c.starred);
        assertEquals(j.jobType, c.jobType);
        assertEquals(j.templateFile, c.templateFile);
        assertEquals(j.diffCount, c.diffCount);
        assertEquals(j.totalA, c.totalA);
        assertEquals(j.totalB, c.totalB);
        assertEquals(j.equalCount, c.equalCount);
        assertEquals(j.onlyACount, c.onlyACount);
        assertEquals(j.onlyBCount, c.onlyBCount);
        assertEquals(j.incrCond, c.incrCond);
        assertEquals(j.incrState, c.incrState);

        // 修改拷贝不影响原件（store 内部记录防外泄改写）
        c.status = JobRecord.DONE;
        assertEquals(JobRecord.RUNNING, j.status);
        c.clearStats();
        assertEquals(3, j.diffCount);
    }

    @Test
    void batchRecordCopyPreservesFieldsAndCopiesLists() {
        BatchRecord b = new BatchRecord("b1", "标签", "/a", "/b", "3 行配置", 3);
        b.createdAt = 1600000000L; // 显式历史时间：copy 若漏拷会带成当下秒值（与构造值同秒时断言恒真假阳性）
        b.locked = true;
        b.batchType = "ctis";
        b.templateDir = "/tpl";
        b.prepareState = BatchRecord.PREPARING;
        b.prepareError = "boom";
        b.noRuleFiles = List.of("x.txt", "y.txt");
        b.missingFiles = List.of("NICK · z.txt");
        b.extractFailed = List.of("q.gz（损坏）");

        BatchRecord c = b.copy();
        assertEquals(b.id, c.id);
        assertEquals(b.label, c.label);
        assertEquals(b.dirA, c.dirA);
        assertEquals(b.dirB, c.dirB);
        assertEquals(b.configSource, c.configSource);
        assertEquals(b.jobCount, c.jobCount);
        assertEquals(b.createdAt, c.createdAt);
        assertTrue(c.locked);
        assertEquals(b.batchType, c.batchType);
        assertEquals(b.templateDir, c.templateDir);
        assertEquals(BatchRecord.PREPARING, c.prepareState);
        assertEquals("boom", c.prepareError);
        assertEquals(List.of("x.txt", "y.txt"), c.noRuleFiles);
        assertEquals(List.of("NICK · z.txt"), c.missingFiles);
        assertEquals(List.of("q.gz（损坏）"), c.extractFailed);

        // 列表防御性拷贝：改副本清单不影响原件
        c.noRuleFiles.add("w.txt");
        assertEquals(2, b.noRuleFiles.size());
    }
}
