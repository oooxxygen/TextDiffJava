package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PretransformTest {
    private static final String DELIM = " | ";

    @Test
    void transformedNameRules() {
        assertEquals("01A3020M.v01", Pretransform.transformedName("01A3020D.v01"));
        assertEquals("ABC_M.txt", Pretransform.transformedName("ABC.txt"));
        assertEquals("nameM", Pretransform.transformedName("nameD"));
        assertEquals("noext_M", Pretransform.transformedName("noext"));
    }

    @Test
    void transformFileReplacesColumnsKeepsTrailerUtf8(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src.txt");
        Files.write(src, "id1 | 2024-01-01 | x\nid2 | 2024-12-31 | y\n|||||RecNum=2"
                .getBytes(StandardCharsets.UTF_8));

        CompareConfig cfg = new CompareConfig();
        // 第 2 列（idx 1）把日期里的 '-' 去掉
        cfg.replaceRules.put(1, List.<String[]>of(new String[]{"-", ""}));

        Path dst = dir.resolve("out/dst.txt");
        Pretransform.transformFile(src, "utf-8", dst, cfg);

        List<String> out = Files.readAllLines(dst, StandardCharsets.UTF_8);
        assertEquals("id1 | 20240101 | x", out.get(0));
        assertEquals("id2 | 20241231 | y", out.get(1));
        assertEquals("|||||RecNum=2", out.get(2));   // trailer 原样透传
    }
}
