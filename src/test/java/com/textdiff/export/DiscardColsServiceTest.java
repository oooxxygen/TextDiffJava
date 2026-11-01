package com.textdiff.export;

import com.textdiff.report.ReportCompareService;
import com.textdiff.store.BatchRecord;
import com.textdiff.store.DiscardField;
import com.textdiff.store.DiscardFieldStore;
import com.textdiff.store.FieldMapStore;
import com.textdiff.store.FileJobStore;
import com.textdiff.store.JobRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关注字段采集：普通文件作业（流式 Parser 语义：DELIM 切分 / trailer 剔除 / 短行计空 / 去重 / 上限截断）、
 * 报表作业（控制行版式经 ReportParser 分支）、SRCA/SRCB 命名联动、懒生成缓存与 rerun 失效重生成。
 */
class DiscardColsServiceTest {

    @TempDir Path dir;

    private Path write(String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row0(Map<String, Object> view) {
        return ((List<Map<String, Object>>) view.get("rows")).get(0);
    }

    @Test
    void fileJobDedupTrailerExcludedEmptyCountedSrcNamed() throws Exception {
        Path fa = write("data/A/TESTNICK.dat",
                "k1 | A1 | x\nk2 | A1 | y\nk3 | A2 | z\n|||||End=3\n");
        Path fb = write("data/B/TESTNICK.dat",
                "k1 | B1 | x\nk2 |  | y\nk3 | B2\n");   // 短行：第2栏缺失计空
        FileJobStore store = new FileJobStore(dir.resolve("jobs"));
        DiscardFieldStore fields = new DiscardFieldStore(dir.resolve("store"), false);
        fields.importAll(List.of(new DiscardField("TESTNICK", 2, "栏位2", "测试", 0, null, 0)), "DISCARD.xlsx");
        store.saveBatch(new BatchRecord("b1", "测试批次", "A", "B", "t", 1));
        JobRecord job = new JobRecord("j1", "b1", "TESTNICK",
                "TESTNICK:TESTNICK.dat:SRCA=旧线:SRCB=新线",
                fa.toString(), fb.toString(), dir.resolve("results/j1").toString());
        job.status = JobRecord.DONE;
        job.finishedAt = System.currentTimeMillis() / 1000;
        store.saveJob(job);
        DiscardColsService svc = new DiscardColsService(store, fields,
                new FieldMapStore(dir.resolve("fmx"), false), null, dir.resolve("results"));

        Map<String, Object> view = svc.batchView("b1");
        assertEquals(Boolean.TRUE, view.get("hit"));
        assertEquals("旧线", view.get("src_a"));
        assertEquals("新线", view.get("src_b"));
        Map<String, Object> r = row0(view);
        assertEquals("TESTNICK", r.get("nickname"));
        assertEquals("TESTNICK.dat", r.get("file_name"));
        assertEquals(List.of("A1", "A2"), r.get("values_a"));   // 去重 + trailer 行剔除
        assertEquals(List.of("B1", "", "B2"), r.get("values_b")); // 空值计入、短行计空
        assertFalse((Boolean) r.get("file_missing"));
        assertEquals(Boolean.FALSE, r.get("matched"));           // B 含 A 没有的 B1/B2 → 不为子集

        // CSV 落盘：BOM + A/B 命名表头 + 取值单元格 + 是否匹配列
        Path csv = dir.resolve("results/b1/export/DISCARD_COLS.csv");
        assertTrue(Files.isRegularFile(csv));
        String text = Files.readString(csv, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\ufeff"));
        assertTrue(text.contains("旧线的所有取值"));
        assertTrue(text.contains("新线的所有取值"));
        assertTrue(text.contains("A1、A2"));
        assertTrue(text.contains("B1、（空）、B2"));
        assertTrue(text.contains(",判定结果"));
        assertTrue(text.contains(",字段类型,"));
        assertTrue(text.contains("新线不为旧线子集"));

        // 缓存复用：同 finishedAt 不重生成（mtime 不变）
        long before = csv.toFile().lastModified();
        svc.batchView("b1");
        assertEquals(before, csv.toFile().lastModified());

        // rerun 失效：finishedAt 晚于缓存 mtime → 重生成新取值
        Thread.sleep(1100); // 文件 mtime 秒级粒度
        write("data/B/TESTNICK.dat", "k1 | B9 | x\n");
        job = store.getJob("j1");
        job.finishedAt = System.currentTimeMillis() / 1000;
        store.saveJob(job);
        Map<String, Object> fresh = svc.batchView("b1");
        assertEquals(List.of("B9"), row0(fresh).get("values_b"));

        store.close();
        fields.close();
    }

    @Test
    void cap1000MarksTruncated() throws Exception {
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < DiscardColsService.MAX_VALUES + 5; i++) {
            a.append("k").append(i).append(" | v").append(i).append("\n");
        }
        Path fa = write("data/A2/TESTNICK.dat", a.toString());
        Path fb = write("data/B2/TESTNICK.dat", "k | v1\n");
        FileJobStore store = new FileJobStore(dir.resolve("jobs2"));
        DiscardFieldStore fields = new DiscardFieldStore(dir.resolve("store2"), false);
        fields.importAll(List.of(new DiscardField("TESTNICK", 2, "F", "M", 0, null, 0)), "d.xlsx");
        store.saveBatch(new BatchRecord("b2", "t", "A", "B", "t", 1));
        JobRecord job = new JobRecord("j2", "b2", "TESTNICK", "TESTNICK:*.dat",
                fa.toString(), fb.toString(), dir.resolve("results2/j2").toString());
        job.status = JobRecord.DONE;
        job.finishedAt = System.currentTimeMillis() / 1000;
        store.saveJob(job);
        DiscardColsService svc = new DiscardColsService(store, fields,
                new FieldMapStore(dir.resolve("fmx2"), false), null, dir.resolve("results2"));
        Map<String, Object> r = row0(svc.batchView("b2"));
        assertEquals(DiscardColsService.MAX_VALUES, ((List<?>) r.get("values_a")).size());
        assertEquals(Boolean.TRUE, r.get("truncated_a"));
        assertEquals(Boolean.FALSE, r.get("truncated_b"));
        assertEquals(Boolean.TRUE, r.get("matched"));            // v1 ∈ A 侧取值 → B ⊆ A（截断不误判）
        String cell = DiscardColsService.valuesCell(r, true, "A");
        assertTrue(cell.endsWith("…（超出 " + DiscardColsService.MAX_VALUES + " 个已截断）"));
        store.close();
        fields.close();
    }

    @Test
    void reportJobUsesReportParserBranch() throws Exception {
        // 控制行版式：无需模板，D| 数据行按竖线切分（colSeq 3 → v 值）
        Path fa = write("rep/A/X.dat",
                "1@OD@|@T@|BANK-CODE:102|\nD|a|v1\nD|b|v1\n");
        Path fb = write("rep/B/X.dat",
                "1@OD@|@T@|BANK-CODE:102|\nD|a|v2\n");
        FileJobStore store = new FileJobStore(dir.resolve("jobs3"));
        ReportCompareService rs = new ReportCompareService(
                store, dir.resolve("res3"), dir.resolve("no-cfg"), null, 1);
        DiscardFieldStore fields = new DiscardFieldStore(dir.resolve("store3"), false);
        fields.importAll(List.of(new DiscardField("RPTNICK", 3, "F", "M", 0, null, 0)), "d.xlsx");
        store.saveBatch(new BatchRecord("b3", "报表", "A", "B", "t", 1));
        // 报表作业 nickname 为复合串「昵称 · 文件名」；监测匹配键 = configLine 首段裸昵称
        JobRecord job = new JobRecord("j3", "b3", "RPTNICK · X.dat", "RPTNICK:X.dat",
                fa.toString(), fb.toString(), dir.resolve("res3/j3").toString());
        job.jobType = "report";
        job.status = JobRecord.DONE;
        job.finishedAt = System.currentTimeMillis() / 1000;
        store.saveJob(job);
        DiscardColsService svc = new DiscardColsService(store, fields,
                new FieldMapStore(dir.resolve("fmx3"), false), rs, dir.resolve("res3"));
        Map<String, Object> view = svc.batchView("b3");
        assertEquals(Boolean.TRUE, view.get("hit"));
        Map<String, Object> r = row0(view);
        assertEquals("RPTNICK", r.get("nickname"), "复合昵称应按 configLine 裸昵称匹配");
        assertEquals(List.of("v1"), r.get("values_a"));
        assertEquals(List.of("v2"), r.get("values_b"));
        assertEquals(Boolean.FALSE, r.get("matched"));           // v2 ∉ {v1}

        store.close();
        rs.close();
        fields.close();
    }

    @Test
    void noHitWritesNothingAndFileMissingAnnotated() throws Exception {
        Path fa = write("data/A4/X.dat", "k | 1\n");
        FileJobStore store = new FileJobStore(dir.resolve("jobs4"));
        DiscardFieldStore fields = new DiscardFieldStore(dir.resolve("store4"), false);
        fields.importAll(List.of(new DiscardField("OTHER", 1, "F", "M", 0, null, 0)), "d.xlsx");
        store.saveBatch(new BatchRecord("b4", "t", "A", "B", "t", 1));
        JobRecord job = new JobRecord("j4", "b4", "TESTNICK", "TESTNICK:X.dat",
                fa.toString(), dir.resolve("data/B4/不存在.dat").toString(),
                dir.resolve("results4/j4").toString());
        job.status = JobRecord.DONE;
        job.finishedAt = System.currentTimeMillis() / 1000;
        store.saveJob(job);
        DiscardColsService svc = new DiscardColsService(store, fields,
                new FieldMapStore(dir.resolve("fmx4"), false), null, dir.resolve("results4"));
        Map<String, Object> view = svc.batchView("b4");
        // TESTNICK 不在监测表（只有 OTHER），作业昵称 TESTNICK → 无行 = 未命中，不落盘
        assertEquals(Boolean.FALSE, view.get("hit"));
        assertTrue(((List<?>) view.get("rows")).isEmpty());
        assertFalse(Files.exists(dir.resolve("results4/b4/export/DISCARD_COLS.csv")));

        // 加入命中昵称但 B 文件缺失 → 行保留并标注原文不可采集
        fields.importAll(List.of(new DiscardField("TESTNICK", 1, "F", "M", 0, null, 0)), "d.xlsx");
        Map<String, Object> view2 = svc.batchView("b4");
        assertEquals(Boolean.TRUE, view2.get("hit"));
        Map<String, Object> r = row0(view2);
        assertEquals(Boolean.TRUE, r.get("file_missing"));
        assertTrue(DiscardColsService.valuesCell(r, false, "B").contains("原文不可采集"));
        assertNull(r.get("matched"));                            // 采集失败无法判定
        assertEquals("—", DiscardColsService.matchText(r));

        store.close();
        fields.close();
    }

    /** 字段类型判定分派：2 功能未实施=集合一致；3 无法对比=不监控（值不展示、判定为 —）；1/4=子集。 */
    @Test
    void fieldTypeAwareCheckDispatch() throws Exception {
        Path fa = write("data/A5/T.dat", "k1 | A1 | T1 | S1\n");
        Path fb = write("data/B5/T.dat", "k1 | B1 | T1 | S2\n");
        FileJobStore store = new FileJobStore(dir.resolve("jobs5"));
        DiscardFieldStore fields = new DiscardFieldStore(dir.resolve("store5"), false);
        // 栏位2 类型1（废弃，B1∉{A1} → 不为子集）；栏位3 类型2（{T1}={T1} → 一致）；
        // 栏位4 类型3（无法对比，S1≠S2 也不监控）
        fields.importAll(List.of(
                new DiscardField("T", 2, "F1", "M", 1, null, 0),
                new DiscardField("T", 3, "F2", "M", 2, null, 0),
                new DiscardField("T", 4, "F3", "M", 3, null, 0)), "d.xlsx");
        store.saveBatch(new BatchRecord("b5", "t", "A", "B", "t", 1));
        JobRecord job = new JobRecord("j5", "b5", "T", "T:*.dat",
                fa.toString(), fb.toString(), dir.resolve("results5/j5").toString());
        job.status = JobRecord.DONE;
        job.finishedAt = System.currentTimeMillis() / 1000;
        store.saveJob(job);
        DiscardColsService svc = new DiscardColsService(store, fields,
                new FieldMapStore(dir.resolve("fmx5"), false), null, dir.resolve("results5"));
        List<Map<String, Object>> rows = rowsOf(svc.batchView("b5"));
        assertEquals(3, rows.size());
        Map<String, Object> f1 = rows.get(0);
        assertEquals(1, f1.get("field_type"));
        assertEquals("废弃字段", f1.get("type_label"));
        assertEquals(Boolean.FALSE, f1.get("matched"));
        assertEquals("新线不为旧线子集", DiscardColsService.matchText(f1));
        Map<String, Object> f2 = rows.get(1);
        assertEquals(Boolean.TRUE, f2.get("matched"));
        assertEquals("新旧线一致", DiscardColsService.matchText(f2));
        Map<String, Object> f3 = rows.get(2);
        assertNull(f3.get("matched"));
        assertEquals("不监控", DiscardColsService.matchText(f3));
        assertEquals("（不监控：无法对比，仅报表展示）", DiscardColsService.valuesCell(f3, true, "A"));
        assertEquals("", DiscardColsService.matchCls(f3));

        // CSV：字段类型列 + 类型 3 取值被抑制
        Path csv = dir.resolve("results5/b5/export/DISCARD_COLS.csv");
        assertTrue(Files.isRegularFile(csv));
        String text = Files.readString(csv, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains(",废弃字段,"));
        assertTrue(text.contains(",功能未实施,"));
        assertTrue(text.contains("（不监控：无法对比，仅报表展示）"));
        store.close();
        fields.close();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("rows");
    }
}
