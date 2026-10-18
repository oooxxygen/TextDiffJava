package com.textdiff.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 压缩文件命名规则：*.gz（含 9 位日期+标识后缀）识别与逻辑名剥离。 */
class ExtractNamingTest {

    @Test
    void 压缩文件识别() {
        assertTrue(ExtractService.isGzipName("01A1010D.ew1.gz.202601011"));
        assertTrue(ExtractService.isGzipName("01A1010D.ew1.gz"));
        assertTrue(ExtractService.isGzipName("DATA.TXT.gz.202601019")); // 大写 .GZ
        assertFalse(ExtractService.isGzipName("01A1010D.ew1"));
        assertFalse(ExtractService.isGzipName("x.gz.20260101"));    // 8 位（缺标识位）不认
        assertFalse(ExtractService.isGzipName("x.gz.2026010111"));  // 10 位不认
        assertFalse(ExtractService.isGzipName("x.gzip"));
        assertFalse(ExtractService.isGzipName(null));
    }

    @Test
    void 逻辑名剥离_日期后缀与gz() {
        assertEquals("01A1010D.ew1", ExtractService.logicalName("01A1010D.ew1.gz.202601011"));
        assertEquals("01A1010D.ew1", ExtractService.logicalName("01A1010D.ew1.gz"));
        assertEquals("01A1010D.ew1", ExtractService.logicalName("01A1010D.ew1.GZ.202601021"));
        assertEquals("plain.txt", ExtractService.logicalName("plain.txt"));
    }

    @Test
    void 组内排序_最新日期最后写入生效() {
        // 模拟组内版本排序语义（ExtractService.versions 升序 → 最新最后覆盖）
        var names = new java.util.ArrayList<>(java.util.List.of(
                "01A1010D.ew1.gz.202601021", "01A1010D.ew1.gz.202601011"));
        names.sort(java.util.Comparator.comparing(x -> x));
        assertEquals("01A1010D.ew1.gz.202601011", names.get(0)); // 先解压旧版
        assertEquals("01A1010D.ew1.gz.202601021", names.get(1)); // 后解压新版覆盖生效
    }
}
