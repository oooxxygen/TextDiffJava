package com.textdiff.custom;

import com.textdiff.store.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 自定义格式对比的用户配置：段的起止匹配式 + 主键提取式。
 *
 * 三个均为正则（对单行 find 匹配，可自行加 ^ 锚定行首）：
 *  - startPattern 段起始（如 MT950 报文 {@code ^\{1:}）；
 *  - endPattern 段结束（如 {@code ^-}}，含该行；留空 = 到下一段起始前）；
 *  - keyPattern 主键提取（取捕获组 1，如 {@code :20:(\S+)}）。
 * 配置以 JSON 存于作业 configLine，跨重启可解析。
 */
public record CustomFormat(String startPattern, String endPattern, String keyPattern) {

    public CustomFormat {
        if (startPattern == null || startPattern.isBlank()) {
            throw new IllegalArgumentException("段起始匹配式不能为空");
        }
    }

    public static CustomFormat of(String start, String end, String key) {
        CustomFormat f = new CustomFormat(start == null ? "" : start.strip(),
                end == null || end.isBlank() ? "" : end.strip(),
                key == null || key.isBlank() ? "" : key.strip());
        f.compiledStart();
        f.compiledEnd();
        f.compiledKey();
        return f;
    }

    /** configLine 编解码（JSON，键 start/end/key）。 */
    public String toConfigLine() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("start", startPattern);
        m.put("end", endPattern == null ? "" : endPattern);
        m.put("key", keyPattern == null ? "" : keyPattern);
        return Json.write(m);
    }

    public static CustomFormat fromConfigLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = Json.read(line, Map.class);
            return new CustomFormat(str(m, "start"), str(m, "end"), str(m, "key"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }

    public Pattern compiledStart() {
        return compileOrThrow(startPattern, "段起始匹配式");
    }

    /** 段结束式可空（空 = 段到下一段起始行之前）。 */
    public Pattern compiledEnd() {
        return endPattern == null || endPattern.isBlank() ? null : compileOrThrow(endPattern, "段结束匹配式");
    }

    /** 主键式可空（空 = 不提主键，按段全文精确多重集匹配）。 */
    public Pattern compiledKey() {
        return keyPattern == null || keyPattern.isBlank() ? null : compileOrThrow(keyPattern, "主键提取式");
    }

    private static Pattern compileOrThrow(String expr, String what) {
        try {
            return Pattern.compile(expr);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(what + "正则无效：" + e.getDescription());
        }
    }
}
