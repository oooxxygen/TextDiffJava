package com.textdiff.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 段内「场次块」重排：同一批次 A/B 两侧段内多个场次（如 MT950 的 {@code :61:} 块）
 * 先后顺序可能不同，先按排序键重排再对比，使等值场次可匹配。
 *
 * <p>场次块 = {@code ^:\d{2,}:} 开头的行（场次行，标签含冒号如 {@code :61:}）+
 * 其后续非场次行，直到下一场次行 / 段尾。段首未进入任何块前的散行（如 MT950 的
 * {@code {4:}、:20: 头行）与匹配段结束式的尾行（如 {@code -}}）固定在原端部不参与重排；
 * 若调用方未提供段结束式（两参 {@link #reorder(List, String)}），尾部散行随最后一块。
 * 排序为稳定排序：比较器仅当两块标签相同且两块都取到键时按键比较，其余情况视为相等，
 * 保持原相对顺序。
 *
 * <p>排序配置（sceneSort）语法，多组条件用 {@code ;} 分隔，各组键依次拼接比较：
 * <ul>
 *   <li>行区间：{@code 行号,起~止}（如 {@code 1,21~36}）——取块内相对行（1-based）
 *       的第 起~止 字符（1-based 闭区间，越界截到行尾，整行越界 = 无键）；
 *       起止分隔符 {@code ~} 与 {@code -} 均可；行号、起、止均 ≥1 且 起≤止。</li>
 *   <li>正则：{@code /正则/}（整串以 {@code /} 包裹，如 {@code /[0-9]{9}/}）——在块内各行
 *       顺序查找，首个命中取捕获组 1（无捕获组取整体命中）；未命中 = 无键。</li>
 * </ul>
 * 任一组条件取不到值 → 该块无键（不参与按键重排，保持原位）。
 */
public final class SceneSorter {
    private SceneSorter() {}

    /** 场次行：{@code :NN:} 开头（NN 至少两位数字）。 */
    private static final Pattern SCENE_LINE = Pattern.compile("^:(\\d{2,}):");
    /** 行区间条件：{@code 行号,起~止}（起止分隔符 ~ 或 -）。 */
    private static final Pattern RANGE = Pattern.compile("^(\\d+),(\\d+)[~-](\\d+)$");

    /** 场次块：标签（如 {@code :61:}，含冒号）+ 块内原始行 + 排序键（null = 无键）。 */
    private record Block(String tag, List<String> lines, String key) {}

    /** 编译后的排序配置：一组键提取规则，依次取值拼接为排序键。 */
    public record Spec(List<Rule> rules) {}

    /** 单条键提取规则。 */
    public interface Rule {}

    /** 模式①：块内相对行 lineNo（1-based）的 [from, to] 字符（1-based 闭区间）。 */
    public record RangeRule(int lineNo, int from, int to) implements Rule {}

    /** 模式②：块内顺序查找首个命中，取捕获组 1（无捕获组取整体命中）。 */
    public record RegexRule(Pattern pattern) implements Rule {}

    /** 解析并校验排序配置；非法（含空白）抛 IllegalArgumentException。调用方以空白 = 不排序先行短路。 */
    public static Spec compile(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("场次排序配置不能为空");
        }
        List<Rule> rules = new ArrayList<>();
        for (String raw : spec.split(";", -1)) { // limit -1：保留尾随空条件，如 "1,7~14;" 判非法
            String g = raw.strip();
            if (g.isEmpty()) {
                throw new IllegalArgumentException("场次排序配置含空条件：" + spec);
            }
            if (g.startsWith("/")) {
                if (g.length() < 2 || !g.endsWith("/")) {
                    throw new IllegalArgumentException("场次排序正则条件须以 / 包裹：" + g);
                }
                try {
                    rules.add(new RegexRule(Pattern.compile(g.substring(1, g.length() - 1))));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("场次排序正则无效：" + e.getDescription());
                }
            } else {
                Matcher m = RANGE.matcher(g);
                if (!m.matches()) {
                    throw new IllegalArgumentException(
                            "场次排序行区间条件格式应为 行号,起~止（如 1,21~36；起止分隔亦可用 -）：" + g);
                }
                int lineNo = Integer.parseInt(m.group(1));
                int from = Integer.parseInt(m.group(2));
                int to = Integer.parseInt(m.group(3));
                if (lineNo < 1 || from < 1) {
                    throw new IllegalArgumentException("场次排序行号与字符位均须 ≥1：" + g);
                }
                if (from > to) {
                    throw new IllegalArgumentException("场次排序字符区间起须 ≤ 止：" + g);
                }
                rules.add(new RangeRule(lineNo, from, to));
            }
        }
        return new Spec(List.copyOf(rules));
    }

    /** 段内场次块重排；spec 空白（= 不排序）原样返回。无段结束式信息，尾部散行随最后一块。 */
    public static List<String> reorder(List<String> segLines, String spec) {
        if (spec == null || spec.isBlank()) return segLines;
        return reorder(segLines, compile(spec), null);
    }

    /**
     * 段内场次块重排（已编译配置）；无场次块原样返回。
     * endTrailer 非空时：首个匹配段结束式的行及其后的行固定在段尾，不归入场次块。
     */
    static List<String> reorder(List<String> segLines, Spec spec, Pattern endTrailer) {
        if (spec == null) return segLines;
        int n = segLines.size();
        List<String> prefix = new ArrayList<>();
        int i = 0;
        while (i < n && !SCENE_LINE.matcher(segLines.get(i)).find()) {
            prefix.add(segLines.get(i));
            i++;
        }
        if (i == n) return segLines; // 段内无场次块，原样

        List<Block> blocks = new ArrayList<>();
        List<String> suffix = new ArrayList<>();
        String tag = null;
        List<String> cur = null;
        while (i < n) {
            String line = segLines.get(i);
            Matcher m = SCENE_LINE.matcher(line);
            if (m.find()) {
                if (cur != null) blocks.add(new Block(tag, cur, blockKey(cur, spec)));
                tag = line.substring(0, m.end()); // 含冒号，如 ":61:"
                cur = new ArrayList<>();
                cur.add(line); // 场次行与其后续非场次行（直到下一场次行/尾行）同块
            } else if (endTrailer != null && endTrailer.matcher(line).find()) {
                if (cur != null) blocks.add(new Block(tag, cur, blockKey(cur, spec)));
                cur = null;
                for (int k = i; k < n; k++) suffix.add(segLines.get(k)); // 尾行固定在段尾
                break;
            } else if (cur != null) {
                cur.add(line);
            }
            i++;
        }
        if (cur != null) blocks.add(new Block(tag, cur, blockKey(cur, spec)));

        blocks.sort((a, b) -> {
            if (!a.tag().equals(b.tag())) return 0; // 标签不同的块不互相重排
            if (a.key() == null || b.key() == null) return 0; // 无键块保持原位（稳定排序）
            return a.key().compareTo(b.key());
        });

        List<String> out = new ArrayList<>(prefix);
        for (Block b : blocks) out.addAll(b.lines());
        out.addAll(suffix);
        return out;
    }

    /** 块排序键：各规则依次取值拼接；任一规则取不到 → null（无键）。 */
    static String blockKey(List<String> blockLines, Spec spec) {
        StringBuilder sb = new StringBuilder();
        for (Rule r : spec.rules()) {
            String part;
            if (r instanceof RangeRule) {
                RangeRule rr = (RangeRule) r;
                if (rr.lineNo() > blockLines.size()) return null;
                String line = blockLines.get(rr.lineNo() - 1);
                if (rr.from() > line.length()) return null; // 整行越界 = 无键
                part = line.substring(rr.from() - 1, Math.min(rr.to(), line.length()));
            } else if (r instanceof RegexRule) {
                Pattern p = ((RegexRule) r).pattern();
                part = null;
                for (String line : blockLines) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        part = m.groupCount() >= 1 ? m.group(1) : m.group();
                        break;
                    }
                }
                if (part == null) return null;
            } else {
                return null;
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
