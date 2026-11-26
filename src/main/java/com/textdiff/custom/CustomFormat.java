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
 *  - keyPattern 主键提取（取捕获组 1，如 {@code :20:(\S+)}）；
 *  - sceneSort 段内场次块排序配置（可空 = 不排序），语法见 {@link SceneSorter}：
 *    行区间 {@code 行号,起~止}（如 {@code 1,21~36}，起止分隔符 ~ 或 - 均可）
 *    或正则 {@code /正则/}，多组条件用 {@code ;} 分隔。
 * 配置以 JSON 存于作业 configLine，跨重启可解析。
 */
public record CustomFormat(String startPattern, String endPattern, String keyPattern, String sceneSort) {

    /** 兼容构造：无场次排序配置（= 段内不重排）。 */
    public CustomFormat(String startPattern, String endPattern, String keyPattern) {
        this(startPattern, endPattern, keyPattern, "");
    }

    public CustomFormat {
        if (startPattern == null || startPattern.isBlank()) {
            throw new IllegalArgumentException("段起始匹配式不能为空");
        }
        sceneSort = sceneSort == null ? "" : sceneSort.strip();
    }

    public static CustomFormat of(String start, String end, String key) {
        return of(start, end, key, null);
    }

    /** 带场次排序配置的构造（sceneSort 空白 = 不排序）；各模式即时校验，非法抛 IAE。 */
    public static CustomFormat of(String start, String end, String key, String sceneSort) {
        CustomFormat f = new CustomFormat(start == null ? "" : start.strip(),
                end == null || end.isBlank() ? "" : end.strip(),
                key == null || key.isBlank() ? "" : key.strip(),
                sceneSort == null || sceneSort.isBlank() ? "" : sceneSort.strip());
        f.compiledStart();
        f.compiledEnd();
        f.compiledKey();
        f.compiledSceneSort();
        return f;
    }

    /** configLine 编解码（JSON，键 start/end/key/scene_sort；fromConfigLine 忽略 srca/srcb 等附加键）。 */
    public String toConfigLine() {
        return toConfigLine(null, null);
    }

    /** 带 A/B 来源命名的 configLine：值非空且非默认侧名才写入（供批次总结/监测表头沿用启动时命名）。 */
    public String toConfigLine(String srca, String srcb) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("start", startPattern);
        m.put("end", endPattern == null ? "" : endPattern);
        m.put("key", keyPattern == null ? "" : keyPattern);
        if (sceneSort != null && !sceneSort.isBlank()) m.put("scene_sort", sceneSort);
        if (srca != null && !srca.isBlank() && !srca.strip().equals("A")) m.put("srca", srca.strip());
        if (srcb != null && !srcb.isBlank() && !srcb.strip().equals("B")) m.put("srcb", srcb.strip());
        return Json.write(m);
    }

    public static CustomFormat fromConfigLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = Json.read(line, Map.class);
            return new CustomFormat(str(m, "start"), str(m, "end"), str(m, "key"), str(m, "scene_sort"));
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

    /** 场次排序配置可空（空 = 段内不重排）；非空时编译校验，非法抛 IAE。 */
    public SceneSorter.Spec compiledSceneSort() {
        return sceneSort == null || sceneSort.isBlank() ? null : SceneSorter.compile(sceneSort);
    }

    private static Pattern compileOrThrow(String expr, String what) {
        try {
            return Pattern.compile(expr);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(what + "正则无效：" + e.getDescription());
        }
    }
}
