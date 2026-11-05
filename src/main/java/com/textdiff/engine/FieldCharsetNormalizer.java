package com.textdiff.engine;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 非 EBCDIC 文件的内嵌 UTF-16 栏位预转码（需求：对比文件中有字段配置为 UTF16 格式时，
 * 逐行按<b>二进制</b>读取，UTF-16 栏位转为可读文本，其余栏位按文件编码正常解码，再参与对比）。
 *
 * 触发（JobManager.runJob）：字符集映射按文件名命中该表且含 UTF-16 族栏位，且文件编码<b>非</b>主机
 * 单字节字符集——cp037/cp500/cp1047 仍走 {@link FieldTranscoder} 逐值转码（既有已验收路径，保持不变）。
 *
 * 三条路径：
 * <ol>
 *   <li>文件编码本身是 UTF-16 族（BOM 检测命中 / 显式配置）→ 普通行读取已正确解码，不处理；</li>
 *   <li>无 BOM 但字节样本呈整文件 UTF-16 形态（NUL 交叠密度高 + UTF-8 严格解码失败 +
 *       某一字节序严格解码成功）→ 按该字节序整行解码重写；</li>
 *   <li>常规文件 → 按分隔符<b>字节</b>切列：UTF-16 栏位按字节区间逐值探测解码（BE/LE 择优，
 *       劣于按文件编码解码时<b>原样保留</b>——对侧正常文本天然不受影响），其余栏位按文件编码解码。</li>
 * </ol>
 * 输出行以<b>原文件编码</b>重写临时文件（对比引擎按原编码配置读取，结果展示/导出即 UTF-8）；
 * 逐值探测语义与 {@link FieldTranscoder} 一致：解出 NUL/替换符/控制符视为非目标编码，原样保留。
 */
public final class FieldCharsetNormalizer {
    private static final int SNIFF_BYTES = 64 * 1024;

    private FieldCharsetNormalizer() { }

    /** 映射中是否含 UTF-16 族栏位（UTF16/UTF-16/UTF-16BE/LE/UCS-2 等）。 */
    public static boolean hasUtf16Field(Map<Integer, String> colCharsets) {
        if (colCharsets == null) return false;
        for (String cs : colCharsets.values()) {
            if (isUtf16Name(cs)) return true;
        }
        return false;
    }

    /** 字符集名是否 UTF-16 族（容忍 UTF16 / UTF-16 / ucs2 等写法）。 */
    public static boolean isUtf16Name(String cs) {
        if (cs == null) return false;
        String n = cs.toLowerCase().replace("-", "").replace("_", "").replace(" ", "");
        return n.equals("utf16") || n.startsWith("utf16") || n.equals("ucs2") || n.equals("ucs-2");
    }

    /** 该编码名是否主机单字节字符集（EBCDIC，由 FieldTranscoder 处理）。 */
    public static boolean isHostEncodingName(String encoding) {
        if (encoding == null) return false;
        String e = encoding.toLowerCase();
        return e.equals("cp037") || e.equals("cp500") || e.equals("cp1047");
    }

    /**
     * 预转码入口：不需要处理时原样返回 input；需要时写 {@code tmpDir/normalized_{tag}.txt} 并返回其路径。
     * colLengths 为映射声明的「数据最大长度」（定宽信号）；任何解析失败由调用方兜底（返回原文件，不阻断对比）。
     */
    public static Path normalize(Path input, String resolvedEncoding, String delimiter,
                                 Map<Integer, String> colCharsets, Map<Integer, Integer> colLengths,
                                 Path tmpDir, String tag) throws IOException {
        if (input == null || colCharsets == null || colCharsets.isEmpty() || delimiter == null
                || delimiter.isEmpty()) {
            return input;
        }
        Map<Integer, Integer> lengths = colLengths == null ? Map.of() : colLengths;
        Charset cs = Encoding.toCharset(resolvedEncoding);
        // 路径①：编码本身 UTF-16 族 → 行读取已正确解码（BOM 由 charset 消费）
        if (isUtf16Name(resolvedEncoding)) return input;

        byte[] sample = sample(input);
        // 路径②：无 BOM 整文件 UTF-16（仅当 UTF-8 严格解码失败时才认定，避免误伤正常文本；
        // 且解码结果必须含分隔符——「主体编码 + 定宽 UTF-16 栏位」的小文件 NUL 密度也可能虚高）
        Charset whole = sniffWholeUtf16(sample, delimiter);
        Path out = tmpDir.resolve("normalized_" + tag + ".txt");
        Files.createDirectories(tmpDir);
        if (whole != null) {
            rewriteWholeLines(input, out, whole, cs);
            return out;
        }
        // 路径③：按分隔符字节切列 + 逐值探测
        byte[] delim = delimiter.getBytes(cs);
        if (delim.length == 0) return input;
        rewriteByColumns(input, out, cs, delim, colCharsets, lengths);
        return out;
    }

    // ---- 路径②：整文件 UTF-16 无 BOM ----

    /**
     * 样本 NUL 交叠密度高 + UTF-8 严格解码失败 + 某字节序严格成功 + <b>解码结果含分隔符</b> → 该字节序；
     * 否则 null（含分隔符校验：主体编码文件混入定宽 UTF-16 栏位时 NUL 密度也可能虚高，按 UTF-16 整行
     * 解出的文本不会包含「 | 」这类字面分隔符）。
     */
    static Charset sniffWholeUtf16(byte[] sample, String delimiter) {
        if (sample.length < 8) return null;
        long zeros = 0;
        for (byte b : sample) {
            if (b == 0) zeros++;
        }
        double ratio = (double) zeros / sample.length;
        if (ratio < 0.15) return null;
        if (tryDecodeStrict(sample, StandardCharsets.UTF_8) != null) return null; // 本就是正常 UTF-8 文本
        boolean be = tryDecodeStrict(sample, StandardCharsets.UTF_16BE) != null;
        boolean le = tryDecodeStrict(sample, StandardCharsets.UTF_16LE) != null;
        Charset cs = be && !le ? StandardCharsets.UTF_16BE
                : le && !be ? StandardCharsets.UTF_16LE
                : be ? StandardCharsets.UTF_16BE : null;
        if (cs == null) return null;
        String decoded = tryDecodeStrict(sample, cs);
        if (decoded == null || delimiter == null || !decoded.contains(delimiter)) return null;
        return cs;
    }

    private static String tryDecodeStrict(byte[] bytes, Charset cs) {
        try {
            return cs.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException | RuntimeException e) {
            return null;
        }
    }

    /** 路径②重写：整行按 lineCs 解码 → 以文件原编码 outCs 重写。 */
    private static void rewriteWholeLines(Path input, Path out, Charset lineCs, Charset outCs)
            throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(input), 1 << 16);
             var os = Files.newOutputStream(out)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(8192);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    writeDecoded(os, line.toByteArray(), lineCs, outCs);
                    line.reset();
                } else {
                    line.write(b);
                }
            }
            if (line.size() > 0) writeDecoded(os, line.toByteArray(), lineCs, outCs);
        }
    }

    // ---- 路径③：分隔符字节切列 + 逐值探测 ----

    private static void rewriteByColumns(Path input, Path out, Charset cs, byte[] delim,
                                         Map<Integer, String> colCharsets,
                                         Map<Integer, Integer> colLengths) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(input), 1 << 16);
             var os = Files.newOutputStream(out)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(8192);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    byte[] raw = stripTrailingCr(line.toByteArray());
                    os.write(translateLine(raw, cs, delim, colCharsets, colLengths));
                    os.write('\n');
                    line.reset();
                } else {
                    line.write(b);
                }
            }
            if (line.size() > 0) {
                os.write(translateLine(stripTrailingCr(line.toByteArray()), cs, delim, colCharsets, colLengths));
            }
        }
    }

    /** 单行转换：UTF-16 栏位逐值探测解码，其余列按文件编码解码，重新拼接并以文件原编码编码回字节。 */
    static byte[] translateLine(byte[] raw, Charset cs, byte[] delim, Map<Integer, String> colCharsets,
                                Map<Integer, Integer> colLengths) {
        java.util.List<byte[]> parts = splitBytes(raw, delim);
        StringBuilder sb = new StringBuilder(parts.size() * 8 + 8);
        for (int i = 0; i < parts.size(); i++) {
            String charset = colCharsets.get(i);
            if (charset != null && isUtf16Name(charset)) {
                Integer len = colLengths.get(i);
                sb.append(probeUtf16(parts.get(i), cs, len == null ? 0 : len));
            } else {
                sb.append(new String(parts.get(i), cs));
            }
            if (i < parts.size() - 1) sb.append(new String(delim, cs));
        }
        return sb.toString().getBytes(cs);
    }

    private static byte[] stripTrailingCr(byte[] raw) {
        if (raw.length > 0 && raw[raw.length - 1] == '\r') {
            return java.util.Arrays.copyOfRange(raw, 0, raw.length - 1);
        }
        return raw;
    }

    /**
     * 逐值探测：
     * <ul>
     *   <li>定宽命中（声明长度 L &gt; 0 且栏位字节宽 = 2L，真实下传 UTF-16 栏位按 2×L 定宽填充）→
     *       UTF-16（BE/LE 择优）解码并剥离填充，解不出可读文本则原样；</li>
     *   <li>非定宽（未声明长度或宽度不符）→ <b>保守</b>：仅当按文件编码解码出现 NUL/替换符/控制符
     *       （脏解码）且 UTF-16 解码洁净度更高时才采纳——对侧正常文本与主编码可读值不受影响。</li>
     * </ul>
     */
    static String probeUtf16(byte[] raw, Charset fileCs, int declaredLen) {
        if (raw.length == 0) return "";
        int off = 0;
        if (raw.length >= 2 && ((raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF
                || (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE)) {
            off = 2; // 字段级 BOM
        }
        byte[] body = off > 0 ? java.util.Arrays.copyOfRange(raw, off, raw.length) : raw;
        String main = new String(body, fileCs);
        String be = tryDecodeStrict(body, StandardCharsets.UTF_16BE);
        String le = tryDecodeStrict(body, StandardCharsets.UTF_16LE);
        String best = pick(be, le);
        if (best == null) return main;                       // 无严格可行解 → 原样
        boolean fixedWidth = declaredLen > 0 && raw.length == 2 * declaredLen;
        if (!fixedWidth) {
            // 保守路径：主编码解码洁净（无 NUL/替换符/控制符）→ 原样
            if (!containsSpecial(main) || score(best) <= score(main)) return main;
            return stripPadding(best);
        }
        String stripped = stripPadding(best);
        if (containsSpecial(stripped)) return main;          // 定宽但解不出可读文本 → 原样
        return stripped;
    }

    /** 是否含 NUL/替换符/控制符（含 TAB）——定长数据栏位中的这些字符即「解码不可信」信号。 */
    private static boolean containsSpecial(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\uFFFD' || ch == 0 || ch < 0x20 || ch == 0x7F) return true;
        }
        return false;
    }

    /** BE/LE 择优：仅有严格可行解者；两可时取洁净度分高者，同分取 BE（主机序）。 */
    private static String pick(String be, String le) {
        if (be == null) return le;
        if (le == null) return be;
        return score(le) > score(be) ? le : be;
    }

    /**
     * 洁净度打分：常见可读区（ASCII/CJK/假名/全角/拉丁扩展）+1，替换符与 NUL −4，
     * 其余控制符（含 TAB）−2。谚文等冷僻区间不计正分——GBK 汉字按 UTF-16 误解码常落在谚文区，
     * 计正分会把对侧正常文本误判成「更优的 UTF-16 解码」。
     */
    private static int score(String s) {
        if (s == null) return Integer.MIN_VALUE / 2;
        int sc = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\uFFFD' || ch == 0) sc -= 4;
            else if (ch < 0x20 || ch == 0x7F) sc -= 2;
            else if ((ch >= 0x20 && ch <= 0x7E) || (ch >= 0x4E00 && ch <= 0x9FFF)
                    || (ch >= 0x3000 && ch <= 0x303F) || (ch >= 0xFF00 && ch <= 0xFFEF)
                    || (ch >= 0x3040 && ch <= 0x30FF) || (ch >= 0x0100 && ch <= 0x024F)) {
                sc += 1;
            }
        }
        return sc;
    }

    /** 去首尾填充（NUL/半角/全角空格），对齐 FieldTranscoder.stripPadding。 */
    private static String stripPadding(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isPad(s.charAt(start))) start++;
        while (end > start && isPad(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isPad(char ch) {
        return ch == '\u0000' || ch == ' ' || ch == '\t' || ch == '\u3000';
    }

    private static void writeDecoded(java.io.OutputStream os, byte[] raw, Charset lineCs, Charset outCs)
            throws IOException {
        String text = new String(raw, lineCs);
        os.write(text.getBytes(outCs));
        os.write('\n');
    }

    /** 字面量字节切列（保留尾部空段，对齐 Parser.splitColumns(-1) 语义）。 */
    static java.util.List<byte[]> splitBytes(byte[] raw, byte[] delim) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i + delim.length <= raw.length; i++) {
            boolean hit = true;
            for (int j = 0; j < delim.length; j++) {
                if (raw[i + j] != delim[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                out.add(java.util.Arrays.copyOfRange(raw, start, i));
                i += delim.length - 1;
                start = i + 1;
            }
        }
        out.add(java.util.Arrays.copyOfRange(raw, start, raw.length));
        return out;
    }

    private static byte[] sample(Path input) throws IOException {
        try (InputStream in = Files.newInputStream(input)) {
            return in.readNBytes(SNIFF_BYTES);
        }
    }
}
