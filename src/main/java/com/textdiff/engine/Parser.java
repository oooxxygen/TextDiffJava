package com.textdiff.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 栏位切分与 trailer 分离。对等 Python parser.py。
 *
 * 数据行按分隔符切列；文件尾部以 trailerPrefix（默认 "|||||"）开头的行进入 trailer 区，
 * 逐行解析为 key=value。trailer 行不按分隔符切分，避免 Sep=" | " 之类值被误切。
 */
public final class Parser {
    private Parser() {}

    /** trailer 一条记录（key, 原始 value）。 */
    public record Rec(String key, String value) {}

    /** trailer 区解析结果（迭代数据行完毕后填充）。 */
    public static final class TrailerData {
        public final List<Rec> records = new ArrayList<>();      // (key, raw_value)
        public final Map<String, String> meta = new LinkedHashMap<>();  // 去引号的结构化字段
        public final Map<Integer, Long> colCountDist = new LinkedHashMap<>();
        public long dataCount = 0;
        public long emptyCount = 0;
    }

    /** ``line.split(delimiter)`` 等价：保留尾部空串（limit=-1）。 */
    public static String[] splitColumns(String line, String delimiter) {
        return line.split(Pattern.quote(delimiter), -1);
    }

    private static String unquote(String s) {
        s = s.strip();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static void consumeTrailerLine(String line, String trailerPrefix, TrailerData t) {
        String body = line.startsWith(trailerPrefix) ? line.substring(trailerPrefix.length()) : line;
        if (body.isEmpty()) return;
        int eq = body.indexOf('=');
        if (eq >= 0) {
            String key = body.substring(0, eq).strip();
            String value = body.substring(eq + 1);
            t.records.add(new Rec(key, value));
            t.meta.put(key, unquote(value));
        } else {
            String key = body.strip();
            t.records.add(new Rec(key, ""));
            t.meta.putIfAbsent(key, "");
        }
    }

    /**
     * 消费行流，产出数据行列表；trailer 行写入 {@code trailer}，空行跳过并计数。
     * 一旦进入 trailer 区，其后所有行均按 trailer 处理（对等 parser.iter_data）。
     */
    public static List<String[]> parseData(Stream<String> lines, String delimiter,
                                           String trailerPrefix, TrailerData trailer) {
        List<String[]> data = new ArrayList<>();
        boolean[] inTrailer = {false};
        lines.forEach(line -> {
            if (!inTrailer[0] && trailerPrefix != null && !trailerPrefix.isEmpty()
                    && line.startsWith(trailerPrefix)) {
                inTrailer[0] = true;
            }
            if (inTrailer[0]) {
                consumeTrailerLine(line, trailerPrefix, trailer);
                return;
            }
            if (line.isEmpty()) {
                trailer.emptyCount++;
                return;
            }
            String[] cols = splitColumns(line, delimiter);
            trailer.colCountDist.merge(cols.length, 1L, Long::sum);
            trailer.dataCount++;
            data.add(cols);
        });
        return data;
    }

    /** trailer.RecNum 是否与解析出的数据行数一致；无 RecNum 返回 null。 */
    public static Boolean recnumCheck(TrailerData trailer) {
        String raw = trailer.meta.get("RecNum");
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.strip()) == trailer.dataCount;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ParsedFile(List<String[]> data, TrailerData trailer) {
        public int totalLines() {
            return data.size() + trailer.records.size();
        }
    }

    /** 一次性解析整个文件（小文件/测试用；大文件走流式）。 */
    public static ParsedFile parseFile(Path path, String encoding, String delimiter,
                                       String trailerPrefix) throws IOException {
        TrailerData trailer = new TrailerData();
        List<String[]> data;
        try (Stream<String> lines = Encoding.iterLines(path, encoding, delimiter)) {
            data = parseData(lines, delimiter, trailerPrefix, trailer);
        }
        return new ParsedFile(data, trailer);
    }
}
