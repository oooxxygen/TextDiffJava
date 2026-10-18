package com.textdiff.export;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CSV 2 万条分片切割：≤阈值单文件原名；超出按 _001/_002 序号切，各分片行数与表头一致。 */
class ExportAssemblerSplitTest {

    /** 造一个带 result.jsonl 的作业：rows 条记录（k{i}, diff 或 equal 交替）。 */
    private static JobRecord jobWithRows(Path dir, int rows) throws Exception {
        JobRecord job = new JobRecord("job1", "batch1", "样例表", null, "a.txt", "b.txt",
                dir.resolve("results").resolve("job1").toString());
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(
                Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            for (int i = 0; i < rows; i++) {
                String k = "k" + i;
                if (i % 2 == 0) {
                    sink.addRow(RowDiff.equal(k, Status.SECTION_DATA, new String[]{k, String.valueOf(i)}));
                } else {
                    sink.addRow(RowDiff.diff(k, Status.SECTION_DATA,
                            new String[]{k, String.valueOf(i)}, new String[]{k, String.valueOf(i + 1)},
                            new int[]{1}));
                }
            }
        }
        return job;
    }

    @Test
    void 小数据量_单文件保持原名(@TempDir Path dir) throws Exception {
        JobRecord job = jobWithRows(dir, 3);
        List<Path> full = ExportAssembler.writeFullCsv(dir.resolve("out"), job, ExportAssembler.DATA);
        List<Path> diff = ExportAssembler.writeDiffCsv(dir.resolve("out"), job, ExportAssembler.DATA);
        assertEquals(List.of("样例表_全量.csv"), full.stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(List.of("样例表_差异.csv"), diff.stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void 超阈值_按2万条切割且边界正确(@TempDir Path dir) throws Exception {
        int total = ExportAssembler.SPLIT_ROWS + 1; // 20001 条 → 2 片（20000 + 1）
        JobRecord job = jobWithRows(dir, total);
        List<Path> parts = ExportAssembler.writeFullCsv(dir.resolve("out"), job, ExportAssembler.DATA);
        assertEquals(2, parts.size());
        assertEquals("样例表_全量_001.csv", parts.get(0).getFileName().toString());
        assertEquals("样例表_全量_002.csv", parts.get(1).getFileName().toString());
        assertEquals(ExportAssembler.SPLIT_ROWS + 3, // 表头 ×2 + 数据 20001
                Files.readAllLines(parts.get(0), StandardCharsets.UTF_8).size()
                        + Files.readAllLines(parts.get(1), StandardCharsets.UTF_8).size());
        // 各分片表头一致（差异栏位列名），数据行不重叠：首片含 k0，末片含 k20000
        String head0 = Files.readAllLines(parts.get(0), StandardCharsets.UTF_8).get(0);
        String head1 = Files.readAllLines(parts.get(1), StandardCharsets.UTF_8).get(0);
        assertEquals(head0, head1);
        assertTrue(head0.replace("\ufeff", "").startsWith("键值,状态,差异栏位"));
        String lastFirst = Files.readAllLines(parts.get(0), StandardCharsets.UTF_8).get(1);
        String lastSecond = Files.readAllLines(parts.get(1), StandardCharsets.UTF_8).get(1);
        assertTrue(lastFirst.startsWith("k0,"));
        assertTrue(lastSecond.startsWith("k20000,"));
    }

    @Test
    void 差异CSV同样分片(@TempDir Path dir) throws Exception {
        int diffRows = ExportAssembler.SPLIT_ROWS + 5; // 20005 条差异 → 每条 1 个差异字段 → 2 片
        JobRecord job = jobWithRows(dir, 0);
        try (ResultFiles.JsonlSink sink = ResultFiles.JsonlSink.create(
                Path.of(job.resultDir).resolve(ResultFiles.RESULT_JSONL))) {
            for (int i = 0; i < diffRows; i++) {
                sink.addRow(RowDiff.diff("k" + i, Status.SECTION_DATA,
                        new String[]{"k" + i, "a"}, new String[]{"k" + i, "b"}, new int[]{1}));
            }
        }
        List<Path> parts = ExportAssembler.writeDiffCsv(dir.resolve("out"), job, ExportAssembler.DATA);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).getFileName().toString().startsWith("样例表_差异_001"));
        assertEquals(ExportAssembler.SPLIT_ROWS + 1, // 表头 + 20000 差异行
                Files.readAllLines(parts.get(0), StandardCharsets.UTF_8).size());
        assertEquals(6, Files.readAllLines(parts.get(1), StandardCharsets.UTF_8).size()); // 表头 + 5
    }
}
