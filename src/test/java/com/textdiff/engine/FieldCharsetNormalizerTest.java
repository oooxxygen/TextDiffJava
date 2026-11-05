package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 特殊编码栏位预转码：GBK 文件内嵌 UTF-16 定宽栏位按字节切列逐值解码；对侧正常文本逐值保护；无 BOM 整文件 UTF-16 探测。 */
class FieldCharsetNormalizerTest {

    private static final Charset GBK = Charset.forName("gb18030");
    private static final Map<Integer, Integer> LEN10 = Map.of(1, 10);

    @TempDir Path dir;

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

    /** 定宽 UTF-16 栏位：值填充 NUL 至声明长度（真实下传按 2×声明长度字节存储）。 */
    private static byte[] utf16Fixed(String v, int declaredLen, boolean le) {
        StringBuilder sb = new StringBuilder();
        sb.append(v);
        for (int i = v.length(); i < declaredLen; i++) sb.append('\u0000');
        return sb.toString().getBytes(le ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
    }

    /** 需求主场景：文件主体 GBK，栏位以 UTF-16BE 定宽（2×10 字节）存储 → 转出可读文本，其余栏位原样。 */
    @Test
    void gbk内嵌utf16be栏位转出可读文本() throws Exception {
        byte[] line = concat("K1 | ".getBytes(GBK), utf16Fixed("张三", 10, false),
                " | 20260101\n".getBytes(GBK));
        Path in = dir.resolve("a.txt");
        Files.write(in, line);
        Path out = FieldCharsetNormalizer.normalize(in, "gb18030", " | ",
                Map.of(1, "UTF16"), LEN10, dir, "a");
        assertNotEquals(in, out, "应产出预转码临时文件");
        assertEquals("K1 | 张三 | 20260101\n", Files.readString(out, GBK));
    }

    /** UTF-16LE 定宽存储（低字节序）：BE 解码含未配对代理失败 → LE 择优解出可读文本。 */
    @Test
    void utf16le定宽栏位按字节序择优解码() throws Exception {
        byte[] line = concat("K1 | ".getBytes(GBK), utf16Fixed("李四", 10, true), "\n".getBytes());
        Path in = dir.resolve("b.txt");
        Files.write(in, line);
        String text = Files.readString(FieldCharsetNormalizer.normalize(
                in, "gb18030", " | ", Map.of(1, "UTF-16"), LEN10, dir, "b"), GBK);
        assertTrue(text.contains("李四"), text);
    }

    /** 对侧正常文本（同栏位为可读 GBK 值）：宽度不符定宽信号 + 主编码解码洁净 → 原样保留。 */
    @Test
    void 正常文本栏位逐值保护不被误转() throws Exception {
        Path in = dir.resolve("c.txt");
        Files.write(in, concat("K1 | ".getBytes(GBK), "张三 | 20260101\n".getBytes(GBK)));
        String text = Files.readString(FieldCharsetNormalizer.normalize(
                in, "gb18030", " | ", Map.of(1, "UTF16"), LEN10, dir, "c"), GBK);
        assertEquals("K1 | 张三 | 20260101\n", text);
    }

    /** 无 BOM 整文件 UTF-16：NUL 交叠密度 + UTF-8 严格解码失败 + 字节序严格成功 → 整行解码重写。 */
    @Test
    void 无bom整文件utf16按NUL密度与严格解码探测() throws Exception {
        Path in = dir.resolve("d.txt");
        Files.write(in, "K1 | 张三\nK2 | 李四\n".getBytes(StandardCharsets.UTF_16BE));
        String text = Files.readString(FieldCharsetNormalizer.normalize(
                in, "gb18030", " | ", Map.of(1, "UTF16"), LEN10, dir, "d"), GBK);
        assertTrue(text.contains("张三") && text.contains("李四"), text);
    }

    /** 编码本身是 UTF-16 族（BOM 由 charset 消费）→ 行读取已正确解码，不处理。 */
    @Test
    void utf16族编码与主机编码不处理() throws Exception {
        Path in = dir.resolve("e.txt");
        Files.writeString(in, "x\n");
        assertEquals(in, FieldCharsetNormalizer.normalize(in, "utf-16", " | ",
                Map.of(0, "UTF16"), Map.of(), dir, "e"));
        assertTrue(FieldCharsetNormalizer.isHostEncodingName("cp037"));
        assertFalse(FieldCharsetNormalizer.isHostEncodingName("gb18030"));
        assertTrue(FieldCharsetNormalizer.isUtf16Name("UTF16"));
        assertTrue(FieldCharsetNormalizer.isUtf16Name("utf-16be"));
        assertTrue(FieldCharsetNormalizer.isUtf16Name("UCS-2"));
        assertFalse(FieldCharsetNormalizer.isUtf16Name("E"));
        assertTrue(FieldCharsetNormalizer.hasUtf16Field(Map.of(0, "E", 1, "UTF16")));
        assertFalse(FieldCharsetNormalizer.hasUtf16Field(Map.of(0, "E")));
    }

    /** 逐值探测单元：定宽命中转码、宽度不符/主编码洁净时原样、填充剥离。 */
    @Test
    void 逐值探测语义() {
        // 定宽命中（2×10）：BE/LE 均解出 张三 并剥离 NUL 填充
        assertEquals("张三", FieldCharsetNormalizer.probeUtf16(utf16Fixed("张三", 10, false), GBK, 10));
        assertEquals("张三", FieldCharsetNormalizer.probeUtf16(utf16Fixed("张三", 10, true), GBK, 10));
        // 宽度不符：主编码（GBK）解码洁净 → 原样（对侧正常文本保护）
        assertEquals("张三", FieldCharsetNormalizer.probeUtf16("张三".getBytes(GBK), GBK, 10));
        // 未声明长度：主编码解码出现控制符（UTF-16BE 误解码 "_N\t"）→ 择优转出
        assertEquals("张三", FieldCharsetNormalizer.probeUtf16(
                "张三".getBytes(StandardCharsets.UTF_16BE), GBK, 0));
        // 未声明长度：主编码解码洁净（GBK 张三）→ 原样（保守策略）
        assertEquals("张三", FieldCharsetNormalizer.probeUtf16("张三".getBytes(GBK), GBK, 0));
        // 空
        assertEquals("", FieldCharsetNormalizer.probeUtf16(new byte[0], GBK, 0));
    }
}
