package com.textdiff.store;

import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** readPage 按需加载：免解析快路径/针串预过滤与全解析路径结果必须一致。 */
class ResultFilesReadPageFastPathTest {
    @TempDir Path dir;

    private Path writeJsonl() throws Exception {
        Path f = dir.resolve("result.jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            String key = "k" + i;
            RowDiff r = switch (i % 4) {
                case 0 -> RowDiff.equal(key, Status.SECTION_DATA, new String[]{"a" + i});
                case 1 -> RowDiff.diff(key, Status.SECTION_DATA, new String[]{"a" + i},
                        new String[]{"b" + i}, new int[]{0});
                case 2 -> RowDiff.onlyA(key, Status.SECTION_DATA, new String[]{"a" + i});
                default -> RowDiff.onlyB(key, Status.SECTION_DATA, new String[]{"b" + i});
            };
            sb.append(Json.write(ResultFiles.toDto(r))).append('\n');
        }
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return f;
    }

    /** RowDiff 未实现 equals，按业务字段降维比较。 */
    private static java.util.List<String> shape(java.util.List<RowDiff> rows) {
        return rows.stream().map(r -> r.key + "|" + r.status + "|" + r.section
                + java.util.Arrays.toString(r.aCols) + java.util.Arrays.toString(r.bCols)).toList();
    }

    @Test
    void fastPathMatchesParsePathOnEveryWindow() throws Exception {
        Path f = writeJsonl();
        for (long off : new long[]{0, 10, 50, 99, 100, 150}) {
            for (long lim : new long[]{1, 7, 50}) {
                ResultFiles.Page fast = ResultFiles.readPage(f, "all", off, lim);
                // 非共享谓词 → 强制全解析路径，作为基准
                ResultFiles.Page ref = ResultFiles.readPage(f, "all", off, lim, r -> true);
                assertEquals(ref.total(), fast.total(), "off=" + off);
                assertEquals(shape(ref.rows()), shape(fast.rows()), "off=" + off + " lim=" + lim);
            }
        }
        assertEquals(100, ResultFiles.readPage(f, "all", 0, 500).total());
    }

    @Test
    void zoneNeedleFilterMatchesParsePath() throws Exception {
        Path f = writeJsonl();
        for (String zone : new String[]{"diff", "equal", "unmatched", "unmatched_a", "unmatched_b"}) {
            ResultFiles.Page p = ResultFiles.readPage(f, zone, 0, 500);
            ResultFiles.Page ref = ResultFiles.readPage(f, "all", 0, 500,
                    r -> ResultFiles.zoneMatches(zone, r));
            assertEquals(ref.total(), p.total(), "zone=" + zone);
            assertEquals(shape(ref.rows()), shape(p.rows()), "zone=" + zone);
        }
        assertEquals(50, ResultFiles.readPage(f, "unmatched", 0, 500).total());
        assertEquals(25, ResultFiles.readPage(f, "diff", 0, 500).total());
    }

    @Test
    void missingFileReturnsEmptyPage() {
        ResultFiles.Page p = ResultFiles.readPage(dir.resolve("nope.jsonl"), "all", 0, 50);
        assertEquals(0, p.total());
        assertTrue(p.rows().isEmpty());
    }
}
