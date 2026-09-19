package com.textdiff.engine;

import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Map;

/**
 * 混合编码字段转码器（按作业解析一次，逐行复用）。
 *
 * 场景：EBCDIC 主机下传文件中，个别栏位（映射表指定列）以 UTF-16 等特殊字符集内嵌于
 * 单字节字节流（E 码文件）。整文件按主机字符集解码后这些栏位是乱码，需取回原始字节
 * 再按目标字符集解码为可读文本（UTF-8 显示）。
 *
 * 设计约束（性能 + 单侧兼容）：
 *  - 仅主机单字节字符集（cp037/cp500/cp1047，字节↔字符 1:1 无损回转）启用；其余文件编码不转码；
 *  - 列→字符集映射在作业级解析一次，逐行只对配置列做「编码回转 + 目标解码」两个短串操作；
 *  - 逐值探测：回转解码失败 / 出现替换符或控制符 / 解出 NUL，视为该值并非特殊编码（如对侧
 *    已是正常文本、或该行该列为空填充），原样保留——天然兼容「只有一侧有问题」的场景。
 */
public final class FieldTranscoder {
    private static final String[] HOST_CHARSETS = {"cp037", "cp500", "cp1047"};

    private final Charset host;               // 文件（主机）字符集；null = 不启用
    private final Map<Integer, String> colCharsets; // 0-based 列号 → 特殊字符集（非空）

    public FieldTranscoder(String fileEncoding, Map<Integer, String> colCharsets) {
        if (colCharsets == null || colCharsets.isEmpty() || fileEncoding == null) {
            this.host = null;
            this.colCharsets = Map.of();
            return;
        }
        String e = fileEncoding.toLowerCase();
        boolean isHost = false;
        for (String h : HOST_CHARSETS) {
            if (e.equals(h)) { isHost = true; break; }
        }
        this.host = isHost ? Charset.forName(e) : null;
        this.colCharsets = isHost ? Map.copyOf(colCharsets) : Map.of();
    }

    public boolean enabled() {
        return host != null && !colCharsets.isEmpty();
    }

    /** 就地转码配置列；其余列与未命中映射的列原样返回（无映射时零开销路径）。 */
    public String[] apply(String[] cols) {
        if (!enabled() || cols == null) return cols;
        for (Map.Entry<Integer, String> e : colCharsets.entrySet()) {
            int i = e.getKey();
            if (i < 0 || i >= cols.length) continue;
            cols[i] = transcode(cols[i], e.getValue());
        }
        return cols;
    }

    /** 单值转码：主机编码回转字节 → 目标字符集解码；任何一步不洁净即原样返回（逐值探测）。 */
    private String transcode(String value, String target) {
        if (value == null || value.isEmpty()) return value;
        byte[] raw;
        try {
            java.nio.ByteBuffer buf = host.newEncoder()
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            raw = new byte[buf.remaining()];
            buf.get(raw);
        } catch (java.nio.charset.CharacterCodingException | RuntimeException ex) {
            return value; // 回转有损（非 1:1 字节）→ 非内嵌二进制场景
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw);
        String decoded;
        try {
            decoded = Encoding.toCharset(target).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buf).toString();
        } catch (java.nio.charset.CharacterCodingException | RuntimeException ex) {
            return value; // 解码失败 → 非目标编码
        }
        if (decoded.isEmpty()) return value;
        for (int i = 0; i < decoded.length(); i++) {
            char ch = decoded.charAt(i);
            if (ch == '\u0000' || ch == '\uFFFD' || (ch < 0x20 && ch != '\t')) {
                return value; // NUL/替换符/控制符 → 解出的不是可读文本，原样保留
            }
        }
        return stripPadding(decoded);
    }

    /** 去尾部填充：UTF-16 栏位定长补位（NUL/半角/全角空格）。 */
    private static String stripPadding(String s) {
        int end = s.length();
        while (end > 0) {
            char ch = s.charAt(end - 1);
            if (ch == '\u0000' || ch == ' ' || ch == '\t' || ch == '\u3000') end--;
            else break;
        }
        return end == s.length() ? s : s.substring(0, end);
    }
}
