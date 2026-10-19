package com.textdiff.controller;

import com.textdiff.engine.Status;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.FileJobStore;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.ResultFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 列表/批次视图统计优先取作业记录上的完成快照（免逐作业读 meta.json），无快照回退读盘。 */
class ApiViewsSnapshotTest {
    @TempDir Path dir;

    private JobRecord doneJob(String id, String resultDir, long diff, long totalA) {
        JobRecord j = new JobRecord(id, null, "N", "N:*.txt", "a", "b", resultDir);
        j.status = JobRecord.DONE;
        j.diffCount = diff;
        j.totalA = totalA;
        j.totalB = totalA - diff;
        j.equalCount = totalA - diff;
        j.onlyACount = 0;
        j.onlyBCount = 0;
        return j;
    }

    @Test
    @SuppressWarnings("unchecked")
    void jobBriefUsesSnapshotWithoutMetaRead() {
        Path res = dir.resolve("res-empty"); // 无 meta.json
        JobStore store = new FileJobStore(dir.resolve("store"));
        JobRecord j = doneJob("j1", res.toString(), 3, 10);
        Map<String, Object> m = ApiViews.jobBrief(j, store);
        Map<String, Object> s = (Map<String, Object>) m.get("summary");
        assertEquals(3L, s.get("diff"));
        assertEquals(10L, s.get("total_a"));
        assertEquals(7L, s.get("equal"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jobBriefFallsBackToMetaWhenNoSnapshot() throws Exception {
        Path res = dir.resolve("res-meta");
        JobMeta meta = new JobMeta();
        meta.diff = 7;
        meta.totalA = 20;
        ResultFiles.writeMeta(res, meta);
        JobStore store = new FileJobStore(dir.resolve("store"));
        JobRecord j = doneJob("j2", res.toString(), -1, -1); // 旧记录无快照
        Map<String, Object> s = (Map<String, Object>) ApiViews.jobBrief(j, store).get("summary");
        assertEquals(7L, s.get("diff"));
        assertEquals(20L, s.get("total_a"));
    }

    @Test
    void batchStatsCountsDiffFilesFromSnapshot() {
        BatchRecord batch = new BatchRecord("bt", "label", null, null, "upload", 2);
        JobRecord a = doneJob("ja", dir.resolve("ra").toString(), 1, 5);
        JobRecord b = doneJob("jb", dir.resolve("rb").toString(), 0, 5); // 无差异不计
        Map<String, Object> m = ApiViews.batchStats(batch, List.of(a, b));
        assertEquals(1L, m.get("diff_files"));
        assertEquals(2, ((Number) m.get("total_files")).intValue());
    }

    @Test
    void rowViewsBackfillsBColsOnlyForEqualRows() {
        var equal = com.textdiff.engine.RowDiff.equal("k1", com.textdiff.engine.Status.SECTION_DATA,
                new String[]{"a"});
        var onlyA = com.textdiff.engine.RowDiff.onlyA("k2", com.textdiff.engine.Status.SECTION_DATA,
                new String[]{"va"});
        var views = ApiViews.rowViews(List.of(equal, onlyA));
        // equal：B 隐含等于 A，回填供前端双栏展示
        assertArrayEquals(new String[]{"a"}, (String[]) views.get(0).get("b_cols"));
        // 仅A有：B 侧保持 null（BUG1——不得回填 A 内容冒充 B）
        assertNull(views.get(1).get("b_cols"));
    }
}
