package com.textdiff.engine;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * 编码检测与流式解码。对等 Python encoding.py 的行为（非库对等）。
 *
 * 检测：BOM 优先（UTF-8-SIG / UTF-16），否则在候选编码上按
 * 「解码后分隔符出现次数 + 可打印字符比例」打分择优（设计 §3）。
 * 不引 charset-normalizer；用结构化打分覆盖 UTF-8 / EBCDIC(Cp037/500/1047) / GB18030 / latin-1。
 */
public final class Encoding {
    private Encoding() {}

    static final int SAMPLE_BYTES = 64 * 1024;
    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16_LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF16_BE = {(byte) 0xFE, (byte) 0xFF};

    private static final String[] CANDIDATES = {"utf-8", "cp037", "cp500", "cp1047", "gb18030", "latin-1"};

    /** 从字节样本检测编码名（返回小写 token，可交 toCharset / iterLines 使用）。 */
    public static String detect(byte[] sample, String delimiter) {
        if (startsWith(sample, BOM_UTF8)) return "utf-8-sig";
        if (startsWith(sample, BOM_UTF16_LE) || startsWith(sample, BOM_UTF16_BE)) return "utf-16";

        String bestEnc = "utf-8";
        long bestDelim = 0;
        double bestRatio = 0.0;
        boolean any = false;
        for (String enc : CANDIDATES) {
            String text = tryDecodeStrict(sample, enc);
            if (text == null) continue;            // 解码失败
            double ratio = printableRatio(text);
            if (ratio < 0.5) continue;             // 可打印比例过低 → 视为无效
            long delim = countOccurrences(text, delimiter);
            if (!any || delim > bestDelim || (delim == bestDelim && ratio > bestRatio)) {
                any = true;
                bestDelim = delim;
                bestRatio = ratio;
                bestEnc = enc;
            }
        }
        if (!any) {
            return tryDecodeStrict(sample, "utf-8") != null ? "utf-8" : "latin-1";
        }
        return bestEnc;
    }

    public static String detectFile(Path path, String delimiter) throws IOException {
        byte[] sample;
        try (InputStream in = Files.newInputStream(path)) {
            sample = in.readNBytes(SAMPLE_BYTES);
        }
        return detect(sample, delimiter);
    }

    /** 将 'auto'/'' 解析为具体编码名；其余原样返回。 */
    public static String resolveEncoding(Path path, String encoding, String delimiter) throws IOException {
        if (encoding == null || encoding.isBlank() || encoding.equals("auto")) {
            return detectFile(path, delimiter);
        }
        return encoding;
    }

    /**
     * 按行流式解码：通用换行（\r\n / \r / \n 统一切行）、去行尾换行、解码错误用替换字符。
     * 返回的 Stream 持有底层 Reader，<b>必须 try-with-resources 关闭</b>。
     */
    public static Stream<String> iterLines(Path path, String encoding, String delimiter) throws IOException {
        String resolved = resolveEncoding(path, encoding, delimiter);
        InputStream in = Files.newInputStream(path);
        try {
            Charset cs;
            if (resolved.equalsIgnoreCase("utf-8-sig")) {
                in = stripUtf8Bom(in);
                cs = StandardCharsets.UTF_8;
            } else {
                cs = toCharset(resolved);          // UTF-16 由 charset 自身消费 BOM
            }
            CharsetDecoder dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, dec));
            final InputStream toClose = in;
            return br.lines().onClose(() -> {
                try { br.close(); } catch (IOException ignored) {}
                try { toClose.close(); } catch (IOException ignored) {}
            });
        } catch (RuntimeException | IOException e) {
            in.close();
            throw e;
        }
    }

    // ---- 内部 ----

    private static InputStream stripUtf8Bom(InputStream in) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(in, 3);
        byte[] b = new byte[3];
        int n = pin.read(b, 0, 3);
        boolean isBom = n == 3 && b[0] == BOM_UTF8[0] && b[1] == BOM_UTF8[1] && b[2] == BOM_UTF8[2];
        if (!isBom && n > 0) pin.unread(b, 0, n);
        return pin;
    }

    /** token → Java Charset。 */
    static Charset toCharset(String enc) {
        String e = enc.toLowerCase();
        return switch (e) {
            case "utf-8", "utf8" -> StandardCharsets.UTF_8;
            case "utf-8-sig" -> StandardCharsets.UTF_8;
            case "utf-16" -> StandardCharsets.UTF_16;
            case "utf-16le" -> StandardCharsets.UTF_16LE;
            case "utf-16be" -> StandardCharsets.UTF_16BE;
            case "latin-1", "latin1", "iso-8859-1" -> StandardCharsets.ISO_8859_1;
            case "ascii", "us-ascii" -> StandardCharsets.US_ASCII;
            default -> Charset.forName(enc);   // gbk / gb18030 / cp037 / cp500 / cp1047 ... 大小写不敏感
        };
    }

    private static String tryDecodeStrict(byte[] sample, String enc) {
        try {
            CharsetDecoder dec = toCharset(enc).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(sample)).toString();
        } catch (CharacterCodingException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double printableRatio(String text) {
        if (text.isEmpty()) return 0.0;
        int ok = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\t' || isPrintable(ch)) ok++;
        }
        return (double) ok / text.length();
    }

    private static boolean isPrintable(char ch) {
        if (Character.isISOControl(ch)) return false;
        int type = Character.getType(ch);
        return type != Character.UNASSIGNED && type != Character.CONTROL && type != Character.SURROGATE;
    }

    private static long countOccurrences(String text, String sub) {
        if (sub.isEmpty()) return 0;
        long c = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            c++;
            idx += sub.length();
        }
        return c;
    }

    private static boolean startsWith(byte[] arr, byte[] prefix) {
        if (arr.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (arr[i] != prefix[i]) return false;
        return true;
    }
}
