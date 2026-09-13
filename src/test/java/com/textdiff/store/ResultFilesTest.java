package com.textdiff.store;

import com.textdiff.engine.Comparator;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.engine.Summary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultFilesTest {

    @Test
    void sinkWritePageReadRoundTrip(@TempDir Path dir) throws Exception {
        Path resultFile = dir.resolve("r").resolve(ResultFiles.RESULT_JSONL);
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(resultFile)) {
            sink.addRow(RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "v1"}));
            sink.addRow(RowDiff.diff("k2", Status.SECTION_DATA, new String[]{"k2", "a"}, new String[]{"k2", "b"}, new int[]{1}));
            sink.addRow(RowDiff.onlyA("k3", Status.SECTION_DATA, new String[]{"k3", "x"}));
            sink.addRow(RowDiff.onlyB("k4", Status.SECTION_DATA, new String[]{"k4", "y"}));
            sink.addRow(new RowDiff("T1", Status.DIFF, Status.SECTION_TRAILER,
                    new String[]{"T1", "10"}, new String[]{"T1", "20"}, new int[]{1}));
        }

        // 全量
        ResultFiles.Page all = ResultFiles.readPage(resultFile, "all", 0, 100);
        assertEquals(5, all.total());
        assertEquals(5, all.rows().size());

        // zone 过滤
        assertEquals(2, ResultFiles.readPage(resultFile, Status.DIFF, 0, 100).total()); // 数据 diff k2 + trailer diff T1
        assertEquals(1, ResultFiles.readPage(resultFile, Status.EQUAL, 0, 100).total());
        assertEquals(2, ResultFiles.readPage(resultFile, "unmatched", 0, 100).total());

        // 分页
        ResultFiles.Page page1 = ResultFiles.readPage(resultFile, "all", 0, 2);
        assertEquals(2, page1.rows().size());
        assertEquals("k1", page1.rows().get(0).key);
        ResultFiles.Page page3 = ResultFiles.readPage(resultFile, "all", 4, 2);
        assertEquals(1, page3.rows().size());
        assertEquals(Status.SECTION_TRAILER, page3.rows().get(0).section);

        // 字段内容保留（AI 特征提取依赖）
        RowDiff d = ResultFiles.readPage(resultFile, Status.DIFF, 0, 1).rows().get(0);
        assertArrayEquals(new String[]{"k2", "a"}, d.aCols);
        assertArrayEquals(new String[]{"k2", "b"}, d.bCols);
        assertArrayEquals(new int[]{1}, d.diffCols);
    }

    @Test
    void missingFileGivesEmptyPage(@TempDir Path dir) {
        ResultFiles.Page p = ResultFiles.readPage(dir.resolve("nope").resolve("result.jsonl"), "all", 0, 10);
        assertEquals(0, p.total());
        assertTrue(p.rows().isEmpty());
        assertEquals(0, ResultFiles.stream(dir.resolve("nope").resolve("result.jsonl")).count());
    }

    @Test
    void streamSkipsCorruptLine(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("result.jsonl");
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(f)) {
            sink.addRow(RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1"}));
        }
        Files.writeString(f, "{\"key\":\"k2\"", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, ResultFiles.stream(f).count());
    }

    @Test
    void summaryAndMetaRoundTrip(@TempDir Path dir) throws Exception {
        Summary s = new Summary();
        s.totalA = 10;
        s.diff = 3;
        s.keyDupA = 2;
        s.dupKeySamples.add("dup1");
        s.diffColFreq.put(9, 3L);
        s.trailerFields.put("RecNum", new Summary.TrailerField("10", "10", true));
        ResultFiles.writeSummary(dir, s);
        Summary loaded = ResultFiles.readSummary(dir);
        assertEquals(10, loaded.totalA);
        assertEquals(3, loaded.diff);
        assertEquals(2, loaded.keyDupA);
        assertEquals(List.of("dup1"), loaded.dupKeySamples);
        assertEquals(3L, loaded.diffColFreq.get(9));
        assertTrue(loaded.trailerFields.get("RecNum").equal());

        JobRecord job = new JobRecord("j1", "b1", "n", "cfg", "a.v01", "b.v01", dir.toString());
        job.status = JobRecord.DONE;
        job.keyWarning = true;
        JobMeta meta = ResultFiles.buildMeta(job, "cp037", "utf-8", s);
        ResultFiles.writeMeta(dir, meta);
        JobMeta rm = ResultFiles.readMeta(dir);
        assertEquals("j1", rm.jobId);
        assertEquals("cp037", rm.encodingA);
        assertTrue(rm.keyWarning);
        assertEquals(2, rm.keyDupA);
        assertEquals("10", rm.trailerFields.get("RecNum").a);
    }

    @Test
    void engineEndToEndThroughSink(@TempDir Path dir) throws Exception {
        // 引擎直写结果文件，验证 Comparator + JsonlSink 流水线
        Path fa = dir.resolve("a.txt");
        Path fb = dir.resolve("b.txt");
        Files.writeString(fa, "k1 | 1 | x\nk2 | 2 | y\n");
        Files.writeString(fb, "k1 | 1 | x\nk2 | 3 | y\n");
        var cfg = com.textdiff.engine.Rules.parseLegacy("NICK:*.txt:KEYSEQ=1", " | ", "|||||");
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(dir.resolve("r").resolve("result.jsonl"))) {
            Comparator.compareFiles(fa, fb, cfg, sink);
        }
        ResultFiles.Page p = ResultFiles.readPage(dir.resolve("r").resolve("result.jsonl"), "all", 0, 10);
        assertEquals(2, p.total());
        assertEquals(1, ResultFiles.readPage(dir.resolve("r").resolve("result.jsonl"), Status.DIFF, 0, 10).total());
    }
}
