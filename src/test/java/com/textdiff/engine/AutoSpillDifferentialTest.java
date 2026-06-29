package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 核心不变量：内存路径与落盘路径对同一输入产出完全一致的行集与 Summary。 */
class AutoSpillDifferentialTest {

    private CompareConfig cfg() {
        CompareConfig c = new CompareConfig();
        c.keyColumns = new ArrayList<>(List.of(0, 1));   // 组合主键；<2 列 → malformed
        return c;
    }

    private void writeAb(Path a, Path b) throws Exception {
        Files.writeString(a, String.join("\n",
                "k1 | g | v1",
                "k2 | g | v2",
                "k3 | g | v3",
                "ka | g | onlyA",
                "malo",                       // 1 列 → malformed_a
                "|||||RecNum=5",
                "|||||SysID=AAA",
                "|||||Common=1"
        ), StandardCharsets.UTF_8);
        Files.writeString(b, String.join("\n",
                "k1 | g | v1",                // equal
                "k2 | g | V2DIFF",            // diff @col2
                "k3 | g | v3",                // equal
                "kb | g | onlyB",
                "malo2",                      // malformed_b
                "|||||RecNum=5",
                "|||||SysID=BBB",             // diff
                "|||||Common=1",
                "|||||OnlyBTrailer=z"         // unmatched_b（trailer）
        ), StandardCharsets.UTF_8);
    }

    private static List<String> rowKeys(List<RowDiff> rows) {
        List<String> out = new ArrayList<>();
        for (RowDiff r : rows) {
            out.add(r.section + "|" + r.key + "|" + r.status + "|"
                    + Arrays.toString(r.aCols) + "|" + Arrays.toString(r.bCols)
                    + "|" + Arrays.toString(r.diffCols));
        }
        Collections.sort(out);
        return out;
    }

    private static String summaryStr(Summary s) {
        return "eq=" + s.equal + " diff=" + s.diff + " oa=" + s.onlyA + " ob=" + s.onlyB
                + " ma=" + s.malformedA + " mb=" + s.malformedB
                + " dra=" + s.dataRowsA + " drb=" + s.dataRowsB
                + " tra=" + s.trailerRowsA + " trb=" + s.trailerRowsB
                + " ta=" + s.totalA + " tb=" + s.totalB
                + " rca=" + s.recnumCheckA + " rcb=" + s.recnumCheckB
                + " freq=" + new TreeMap<>(s.diffColFreq)
                + " tf=" + new TreeMap<>(s.trailerFields);
    }

    @Test
    void memoryAndSpillPathsIdentical(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.txt"), b = dir.resolve("b.txt");
        writeAb(a, b);

        ResultSink.ListSink memSink = new ResultSink.ListSink();
        CompareOutcome mem = Comparator.compareFiles(a, b, cfg(), memSink,
                Long.MAX_VALUE, dir);                       // 不落盘

        ResultSink.ListSink spillSink = new ResultSink.ListSink();
        CompareOutcome spill = Comparator.compareFiles(a, b, cfg(), spillSink,
                1, dir);                                    // 阈值=1，强制落盘

        assertFalse(mem.usedDiskFallback());
        assertTrue(spill.usedDiskFallback());

        // 行集（排序后）完全一致
        assertEquals(rowKeys(memSink.rows), rowKeys(spillSink.rows));
        // Summary 完全一致
        assertEquals(summaryStr(mem.summary()), summaryStr(spill.summary()));

        // 抽查关键计数，确认覆盖到各分支
        Summary s = mem.summary();
        assertEquals(2, s.equal);
        assertEquals(1, s.diff);
        assertEquals(1, s.onlyA);
        assertEquals(1, s.onlyB);
        assertEquals(1, s.malformedA);
        assertEquals(1, s.malformedB);
        assertEquals(Boolean.TRUE, s.recnumCheckA);
        assertEquals(Boolean.TRUE, s.recnumCheckB);
    }
}
