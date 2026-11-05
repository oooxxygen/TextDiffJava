package com.textdiff.task;

import com.textdiff.config.AppPaths;
import com.textdiff.store.SplitConfig;
import com.textdiff.store.SplitJob;
import com.textdiff.store.SplitJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 文本拆分执行器：A 必填/B 可选、规则=分组首条命中、规则内且/或、trailer 剔除、UTF-8 产出。 */
class SplitManagerTest {

    @TempDir Path dir;

    private static SplitConfig config(List<SplitConfig.Rule> rules) {
        return new SplitConfig("测试拆分", " | ", "auto", "|||||", rules, "test", 0);
    }

    private SplitJob runSplit(SplitConfig cfg, String a, String b) throws Exception {
        try (SplitJobStore store = new SplitJobStore(dir.resolve("store"));
             SplitManager mgr = new SplitManager(store, new AppPaths(dir, dir))) {
            SplitJob job = mgr.submit("t", cfg, a, b);
            for (int i = 0; i < 100; i++) {
                SplitJob cur = store.get(job.jobId());
                if (cur != null && (SplitJob.DONE.equals(cur.status()) || SplitJob.ERROR.equals(cur.status()))) {
                    return cur;
                }
                Thread.sleep(100);
            }
            return store.get(job.jobId());
        }
    }

    /** 需求：启动拆分时 A 区必须有文件，B 区可选。 */
    @Test
    void a区文件必填b区可选() {
        try (SplitJobStore store = new SplitJobStore(dir.resolve("store"));
             SplitManager mgr = new SplitManager(store, new AppPaths(dir, dir))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> mgr.submit("t", config(List.of(
                            new SplitConfig.Rule("r", "all",
                                    List.of(new SplitConfig.Cond(1, "eq", "k1"))))), "", ""));
            assertTrue(e.getMessage().contains("A 区文件必填"));
            // 规则为空
            assertThrows(IllegalArgumentException.class, () -> mgr.submit("t",
                    new SplitConfig("n", " | ", "auto", "|||||", List.of(), "t", 0),
                    dir.resolve("a.txt").toString(), ""));
        }
    }

    /** 多规则分组 + 且/或组合 + 范围条件 + 首条命中优先 + trailer 剔除 + B 侧同规则拆分。 */
    @Test
    void 规则分组且或组合与汇总() throws Exception {
        Path a = dir.resolve("a.txt");
        Files.write(a, ("k1 | 养老 | 101\n"
                + "k2 | 健康 | 901\n"
                + "k3 | 医疗 | 50\n"
                + "k5 | 医疗 | 950\n"
                + "\n"
                + "|||||REC=4\n").getBytes(StandardCharsets.UTF_8));
        Path b = dir.resolve("b.txt");
        Files.write(b, "k4 | 养老 | 20\n".getBytes(StandardCharsets.UTF_8));

        // R1 养老：列2=养老；R2 健康（或）：列2=健康 或 列3 范围 800~1000（k5 命中范围）
        SplitConfig cfg = config(List.of(
                new SplitConfig.Rule("养老", "all",
                        List.of(new SplitConfig.Cond(2, "eq", "养老"))),
                new SplitConfig.Rule("健康", "any",
                        List.of(new SplitConfig.Cond(2, "eq", "健康"),
                                new SplitConfig.Cond(3, "range", "800~1000")))));
        SplitJob job = runSplit(cfg, a.toString(), b.toString());
        assertEquals(SplitJob.DONE, job.status(), job.error());

        SplitJob.Side sa = job.summary().a();
        assertEquals(4, sa.total());
        assertEquals(1, sa.trailer());
        assertEquals(3, sa.groups().size());
        assertEquals("养老", sa.groups().get(0).name());
        assertEquals(1, sa.groups().get(0).count());
        assertEquals("健康", sa.groups().get(1).name());
        assertEquals(2, sa.groups().get(1).count()); // k2（等值）+ k5（范围）
        assertEquals(SplitManager.OTHER_GROUP, sa.groups().get(2).name());
        assertEquals(1, sa.groups().get(2).count()); // k3

        SplitJob.Side sb = job.summary().b();
        assertNotNull(sb, "B 区可选：有输入时同规则拆分");
        assertEquals(1, sb.total());
        assertEquals(1, sb.groups().get(0).count());

        // 组文件行原样保留（UTF-8 产出）
        Path dirOut = Path.of(job.resultDir());
        assertEquals("k1 | 养老 | 101", Files.readString(dirOut.resolve("a_养老.txt"),
                StandardCharsets.UTF_8).strip());
        assertTrue(Files.readString(dirOut.resolve("a_健康.txt"), StandardCharsets.UTF_8)
                .contains("k5 | 医疗 | 950"));
        assertEquals("k3 | 医疗 | 50", Files.readString(dirOut.resolve("a_其他.txt"),
                StandardCharsets.UTF_8).strip());
        assertEquals("k4 | 养老 | 20", Files.readString(dirOut.resolve("b_养老.txt"),
                StandardCharsets.UTF_8).strip());
    }

    /** 首条命中即归属：两条规则都能命中时归第一条。 */
    @Test
    void 首条命中优先() throws Exception {
        Path a = dir.resolve("a2.txt");
        Files.write(a, "k1 | 养老 | 101\n".getBytes(StandardCharsets.UTF_8));
        SplitConfig cfg = config(List.of(
                new SplitConfig.Rule("先", "all", List.of(new SplitConfig.Cond(1, "prefix", "k"))),
                new SplitConfig.Rule("后", "all", List.of(new SplitConfig.Cond(2, "eq", "养老")))));
        SplitJob job = runSplit(cfg, a.toString(), "");
        assertEquals(SplitJob.DONE, job.status(), job.error());
        assertEquals("先", job.summary().a().groups().get(0).name());
        assertEquals(1, job.summary().a().groups().get(0).count());
        assertEquals(0, job.summary().a().groups().get(1).count());
        assertFalse(Files.exists(Path.of(job.resultDir()).resolve("a_后.txt")), "后组无产出不落文件");
    }

    /** 规则内「且」：全部条件满足才命中；坏正则在提交时即报错。 */
    @Test
    void 且组合与坏条件校验() throws Exception {
        Path a = dir.resolve("a3.txt");
        Files.write(a, "k1 | 养老 | 101\nk2 | 医疗 | 101\n".getBytes(StandardCharsets.UTF_8));
        SplitConfig cfg = config(List.of(new SplitConfig.Rule("双条件", "all",
                List.of(new SplitConfig.Cond(3, "range", "100~200"),
                        new SplitConfig.Cond(2, "eq", "养老")))));
        SplitJob job = runSplit(cfg, a.toString(), "");
        assertEquals(1, job.summary().a().groups().get(0).count()); // 仅 k1 同时满足
        assertEquals(1, job.summary().a().groups().get(1).count());

        try (SplitJobStore store = new SplitJobStore(dir.resolve("store"));
             SplitManager mgr = new SplitManager(store, new AppPaths(dir, dir))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> mgr.submit("t", config(List.of(new SplitConfig.Rule("r", "all",
                            List.of(new SplitConfig.Cond(1, "regex", "["))))), a.toString(), ""));
            assertTrue(e.getMessage().contains("正则无效"));
        }
    }
}
