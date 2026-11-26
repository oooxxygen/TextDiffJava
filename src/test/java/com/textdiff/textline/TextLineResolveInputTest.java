package com.textdiff.textline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 普通文本比对运行时输入解析：A/B 同逻辑名压缩件解压互不覆盖（同名配对场景）。 */
class TextLineResolveInputTest {

    @TempDir
    Path tmp;

    @Test
    void sameLogicalGzSidesDoNotOverwrite() throws Exception { // v0.9.18 验证发现：B 覆盖 A 致两侧误判全等
        Path dirA = Files.createDirectories(tmp.resolve("A"));
        Path dirB = Files.createDirectories(tmp.resolve("B"));
        Path fa = dirA.resolve("01A1020D.sx1.gz.202609271");
        Path fb = dirB.resolve("01A1020D.sx1.gz.202609272");
        try (GZIPOutputStream g = new GZIPOutputStream(Files.newOutputStream(fa))) {
            g.write("ABC\nDEF\nGHI\n".getBytes());
        }
        try (GZIPOutputStream g = new GZIPOutputStream(Files.newOutputStream(fb))) {
            g.write("ABC\nDEF\nXYZ\n".getBytes());
        }
        Path work = Files.createDirectories(tmp.resolve("job-tmp"));
        Path ra = TextLineCompareService.resolveInput(fa, work, "A");
        Path rb = TextLineCompareService.resolveInput(fb, work, "B");
        assertNotEquals(ra, rb, "A/B 解压产物必须落在不同目标文件");
        assertTrue(Files.readAllBytes(ra).length > 0);
        assertEquals("ABC\nDEF\nGHI", Files.readString(ra).stripTrailing());
        assertEquals("ABC\nDEF\nXYZ", Files.readString(rb).stripTrailing());
    }

    @Test
    void plainFileUnchanged() throws Exception {
        Path f = tmp.resolve("TRXDTA0.dat");
        Files.writeString(f, "hello\n");
        assertEquals(f, TextLineCompareService.resolveInput(f, tmp, "A"));
    }
}
