package com.textdiff.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本段解析：按用户给定的起始/结束匹配式把整份文本切成一段段（如 MT950 报文
 * {@code {1:..}{4:
 * :20:..
 * -}}）。
 *
 * 段 = 起始行（含）~ 结束行（含）；无结束式时到下一段起始行之前 / 文件尾。
 * 首段之前的行进 head、末段之后的残行进 tail（供对照而非丢弃）。
 * 主键 = 主键式在段内首个命中行的捕获组 1（无组取整体命中；未命中 = null，
 * 无主键段走段全文精确匹配）。
 */
public final class SegmentParser {
    private SegmentParser() {}

    /** key 可空（主键未命中）；lines 为段原始行；startLineNo 为段首行（1-based）。 */
    public record Segment(String key, List<String> lines, int startLineNo) {
        public String text() {
            return String.join("\n", lines);
        }
    }

    public record Parsed(List<Segment> segments, List<String> head, List<String> tail,
                         long keyMiss, List<String> warnings) {}

    public static Parsed parse(List<String> lines, CustomFormat cfg) {
        List<String> warnings = new ArrayList<>();
        Pattern start = cfg.compiledStart();
        Pattern end = cfg.compiledEnd();
        Pattern key = cfg.compiledKey();

        List<Segment> out = new ArrayList<>();
        List<String> head = new ArrayList<>();
        int n = lines.size();
        int firstStart = -1;
        int i = 0;
        while (i < n) {
            if (!start.matcher(lines.get(i)).find()) {
                if (firstStart < 0) head.add(lines.get(i));
                i++;
                continue;
            }
            if (firstStart < 0) firstStart = i;
            int s = i;
            int e;
            if (end != null) {
                e = -1;
                if (end.matcher(lines.get(s)).find()) e = s;
                for (int j = s + 1; j < n && e < 0; j++) {
                    if (end.matcher(lines.get(j)).find()) {
                        e = j;
                    } else if (start.matcher(lines.get(j)).find()) {
                        break; // 段未正常结束即遇新段：就地截断
                    }
                }
                if (e < 0) {
                    e = n - 1;
                    warnings.add("第 " + (s + 1) + " 行起的段未匹配到结束式，按文件尾截断");
                }
            } else {
                e = s;
                for (int j = s + 1; j < n; j++) {
                    if (start.matcher(lines.get(j)).find()) break;
                    e = j;
                }
            }
            List<String> segLines = new ArrayList<>(lines.subList(s, e + 1));
            out.add(new Segment(extractKey(segLines, key), segLines, s + 1));
            i = e + 1;
        }
        List<String> tail = new ArrayList<>();
        if (firstStart >= 0 && !out.isEmpty()) {
            int lastEnd = out.get(out.size() - 1).startLineNo() - 1 + out.get(out.size() - 1).lines().size();
            for (int k = lastEnd; k < n; k++) tail.add(lines.get(k));
        }
        long keyMiss = out.stream().filter(g -> g.key() == null).count();
        return new Parsed(out, head, tail, keyMiss, warnings);
    }

    /** 主键：主键式在段内首个命中行的捕获组 1（无捕获组取整体命中）。 */
    static String extractKey(List<String> segLines, Pattern key) {
        if (key == null) return null;
        for (String line : segLines) {
            Matcher m = key.matcher(line);
            if (m.find()) return m.groupCount() >= 1 ? m.group(1) : m.group();
        }
        return null;
    }
}
