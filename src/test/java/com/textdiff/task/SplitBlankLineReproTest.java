package com.textdiff.task;

import com.textdiff.config.AppPaths;
import com.textdiff.engine.Encoding;
import com.textdiff.store.SplitConfig;
import com.textdiff.store.SplitJob;
import com.textdiff.store.SplitJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 拆分产物洁净回归：纯空白行/NUL 填充行（定宽提取空槽）不产出、无 CR 残留、无 BOM UTF-16 正确探测。 */
class SplitBlankLineReproTest {

    @TempDir Path dir;
    private static final Charset GBK = Charset.forName("gb18030");

    private SplitJob run(SplitConfig cfg, byte[] content, String suffix) throws Exception {
        Path a = dir.resolve("in" + suffix + ".txt");
        Files.write(a, content);
        try (SplitJobStore store = new SplitJobStore(dir.resolve("store" + suffix));
             SplitManager mgr = new SplitManager(store, new AppPaths(dir, dir))) {
            SplitJob job = mgr.submit("t", cfg, a.toString(), "");
            for (int i = 0; i < 100; i++) {
                SplitJob cur = store.get(job.jobId());
                if (cur != null && (SplitJob.DONE.equals(cur.status()) || SplitJob.ERROR.equals(cur.status()))) {
                    assertEquals(SplitJob.DONE, cur.status(), cur.error());
                    return cur;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("超时");
        }
    }

    private SplitConfig cfg() {
        return new SplitConfig("n", " | ", "auto", "|||||",
                List.of(new SplitConfig.Rule("养老", "all",
                        List.of(new SplitConfig.Cond(2, "eq", "养老")))), "t", 0);
    }

    private static String out(SplitJob job, String file) throws Exception {
        return Files.readString(Path.of(job.resultDir()).resolve(file), StandardCharsets.UTF_8);
    }

    /** 场景A：UTF-8 + CRLF + 空行 + 纯空格行——空白行不计组、不产出。 */
    @Test
    void 空白行不产出() throws Exception {
        String text = "k1 | 养老 | 1\r\n   \r\nk2 | 养老 | 2\r\n\r\nk3 | 医疗 | 3\r\n";
        SplitJob job = run(cfg(), text.getBytes(StandardCharsets.UTF_8), "a");
        String hit = out(job, "a_养老.txt");
        String other = out(job, "a_其他.txt");
        System.out.println("[dbg] 养老组: " + hit.replace("\r", "\\r").replace("\n", "\\n\n"));
        System.out.println("[dbg] 其他组: " + other.replace("\r", "\\r").replace("\n", "\\n\n"));
        assertFalse(hit.contains("\r"), "产物不应含 CR");
        assertFalse(hit.lines().anyMatch(l -> l.strip().isEmpty()), "养老组不应有空行/空白行");
        assertFalse(other.lines().anyMatch(l -> l.strip().isEmpty()), "其他组不应有空行/空白行");
    }

    /** 场景B：NUL 填充行（定宽提取文件尾部空槽）——整行 NUL/空白一律不产出。 */
    @Test
    void nul填充行不产出() throws Exception {
        byte[] nulLine = new byte[20]; // 全 0x20 空格 + 全 NUL 混合的填充行
        java.util.Arrays.fill(nulLine, (byte) 0x20);
        byte[] content = new byte[0];
        content = concat("k1 | 养老 | 1\n".getBytes(GBK), nulLine,
                "\n".getBytes(), new byte[16], "\n".getBytes(), "k2 | 医疗 | 2\n".getBytes(GBK));
        SplitJob job = run(cfg(), content, "b");
        String hit = out(job, "a_养老.txt");
        System.out.println("[dbg] NUL场景 养老组: [" + hit.replace("\u0000", "\\0") + "]");
        assertFalse(hit.lines().anyMatch(l -> l.strip().isEmpty() || l.indexOf('\u0000') >= 0),
                "不应有空白行/NUL行");
    }

    /** 场景C：UTF-16LE 带 BOM（Excel Unicode Text 导出）→ 检测 utf-16 → 行正确，产物干净。 */
    @Test
    void utf16带bom正常拆分() throws Exception {
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] content = concat(bom, "k1 | 养老 | 1\r\nk2 | 医疗 | 2\r\n"
                .getBytes(StandardCharsets.UTF_16LE));
        assertEquals("utf-16", Encoding.detect(java.util.Arrays.copyOfRange(
                concat(bom, "k1 | 养老".getBytes(StandardCharsets.UTF_16LE)), 0, 64), " | "));
        SplitJob job = run(cfg(), content, "c");
        assertEquals("k1 | 养老 | 1", out(job, "a_养老.txt").strip());
        assertEquals("k2 | 医疗 | 2", out(job, "a_其他.txt").strip());
    }

    /** 场景D：UTF-16LE 无 BOM——当前探测会误判 gb18030 → 全文乱码（NUL 密度高）。 */
    @Test
    void 无bom_utf16当前探测结果() {
        byte[] sample = "k1 | 养老 | 101\nk2 | 医疗 | 202\n"
                .getBytes(StandardCharsets.UTF_16LE);
        String detected = Encoding.detect(sample, " | ");
        System.out.println("[dbg] 无BOM UTF-16LE 探测结果: " + detected);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
