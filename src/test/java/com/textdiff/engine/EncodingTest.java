package com.textdiff.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EncodingTest {
    private static final String DELIM = " | ";

    @Test
    void detectsPlainUtf8() {
        byte[] s = "a | b | c".getBytes(StandardCharsets.UTF_8);
        assertEquals("utf-8", Encoding.detect(s, DELIM));
    }

    @Test
    void detectsUtf8Bom() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        o.write("a | b".getBytes(StandardCharsets.UTF_8));
        assertEquals("utf-8-sig", Encoding.detect(o.toByteArray(), DELIM));
    }

    @Test
    void detectsUtf16LeAndBeByBom() throws Exception {
        ByteArrayOutputStream le = new ByteArrayOutputStream();
        le.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
        le.write("a | b".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("utf-16", Encoding.detect(le.toByteArray(), DELIM));

        ByteArrayOutputStream be = new ByteArrayOutputStream();
        be.write(new byte[]{(byte) 0xFE, (byte) 0xFF});
        be.write("a | b".getBytes(StandardCharsets.UTF_16BE));
        assertEquals("utf-16", Encoding.detect(be.toByteArray(), DELIM));
    }

    @Test
    void detectsEbcdicCp037ByDelimiterScoring() {
        // " | " 在 cp037 下解码回 " | "（计 2 次）；utf-8 严格解码失败；latin-1 解出乱码无分隔符
        byte[] s = "a | b | c".getBytes(Charset.forName("Cp037"));
        assertEquals("cp037", Encoding.detect(s, DELIM));
    }

    @Test
    void asciiSampleNeverDetectedAsEbcdic() {
        // 回归：纯 ASCII 报表（含 0x20 空格、CRLF）曾被误判为 cp037——EBCDIC 空格是 0x40，
        // 样本含 0x20 即排除 EBCDIC 候选，防止模板被解成乱码导致表头锚点失效
        String ascii = "BANK-DAILY-LIST\r\n        LIST\r\n  ACCT  NAME  AMT\r\n"
                + "  A0001  DEPOSIT  100.00\r\n  END|        3|\r\n";
        assertEquals("utf-8", Encoding.detect(ascii.getBytes(StandardCharsets.UTF_8), "|"));
        assertEquals("utf-8", Encoding.detect("hello world\nfoo bar\n".getBytes(StandardCharsets.UTF_8), "|"));
    }

    @Test
    void resolveEncodingBypassesDetectionForExplicit(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("x.txt");
        Files.write(f, "a | b".getBytes(StandardCharsets.UTF_8));
        assertEquals("cp037", Encoding.resolveEncoding(f, "cp037", DELIM));
        assertEquals("utf-8", Encoding.resolveEncoding(f, "auto", DELIM));
    }

    @Test
    void iterLinesUniversalNewlines(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nl.txt");
        Files.write(f, "x\r\ny\rz\n".getBytes(StandardCharsets.UTF_8));
        try (Stream<String> st = Encoding.iterLines(f, "utf-8", DELIM)) {
            assertEquals(List.of("x", "y", "z"), st.toList());
        }
    }

    @Test
    void iterLinesStripsUtf8Bom(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("bom.txt");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        o.write("héllo\nworld\n".getBytes(StandardCharsets.UTF_8));
        Files.write(f, o.toByteArray());
        try (Stream<String> st = Encoding.iterLines(f, "auto", DELIM)) {
            assertEquals(List.of("héllo", "world"), st.toList());
        }
    }

    @Test
    void iterLinesDecodesEbcdicRoundTrip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("e.txt");
        Files.write(f, "a | b | c\nd | e | f".getBytes(Charset.forName("Cp037")));
        try (Stream<String> st = Encoding.iterLines(f, "auto", DELIM)) {
            assertEquals(List.of("a | b | c", "d | e | f"), st.toList());
        }
    }
}
