package com.textdiff.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对比规则：解析既有 .txt 配置格式，并提供运行期规则引擎。对等 Python rules.py。
 *
 * 文本格式（以 ':' 分割，序列以 '/' 分割，1-based）：
 *   NICK:GLOB:KEYSEQ=3/4/5:OMITSEQ=1/2:IGNORESEQ=..:DELIM=..:ENCA=..:...:REPLACE=&lt;b64&gt;
 */
public final class Rules {
    private Rules() {}

    /** 组合主键内部连接符（unit separator 0x1F，数据中几乎不可能出现）。对等 Python rules.KEY_SEP。 */
    public static final String KEY_SEP = String.valueOf((char) 0x1F);

    private static final String[] EXT_KEYS = {
            "KEYSEQ", "OMITSEQ", "IGNORESEQ", "DELIM", "ENCA", "ENCB",
            "SRCA", "SRCB", "TRAILER", "REPLACE", "MTIME"
    };
    private static final Pattern TOKEN_SPLIT =
            Pattern.compile(":(?=(?:" + String.join("|", EXT_KEYS) + ")=)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** "3/4/5" -> [2,3,4]（1-based 转 0-based，去重排序）。 */
    public static List<Integer> parseSeq(String seq) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String tok : seq.split("/")) {
            tok = tok.strip();
            if (tok.isEmpty()) continue;
            int n = Integer.parseInt(tok);
            if (n < 1) throw new IllegalArgumentException("列序号必须 >= 1: " + tok);
            out.add(n - 1);
        }
        return new ArrayList<>(out);
    }

    /** 解析单行配置为 CompareConfig（兼容最小基线与扩展全字段两种文本）。 */
    public static CompareConfig parseLegacy(String text, String delimiter, String trailerPrefix) {
        text = text.strip();
        if (text.startsWith("=>")) text = text.substring(2).strip();

        String[] pieces = TOKEN_SPLIT.split(text, -1);
        String[] head = pieces[0].split(":", -1);
        if (head.length < 2) throw new IllegalArgumentException("配置至少需包含 昵称:通配名: " + text);

        CompareConfig cfg = new CompareConfig();
        cfg.delimiter = delimiter;
        cfg.trailerPrefix = trailerPrefix;
        cfg.nickname = head[0].strip();
        cfg.fileGlob = head[1].strip();

        for (int i = 1; i < pieces.length; i++) {
            String token = pieces[i];
            int eq = token.indexOf('=');
            if (eq < 0) continue;
            String key = token.substring(0, eq).strip().toUpperCase();
            String value = token.substring(eq + 1);
            switch (key) {
                case "KEYSEQ" -> cfg.keyColumns = parseSeq(value.strip());
                case "OMITSEQ" -> cfg.omitColumns = parseSeq(value.strip());
                case "IGNORESEQ" -> cfg.ignoreColumns = parseSeq(value.strip());
                case "DELIM" -> { if (!value.isEmpty()) cfg.delimiter = value; } // 保留内部空格，不 strip
                case "ENCA" -> cfg.encodingA = blankTo(value.strip(), "auto");
                case "ENCB" -> cfg.encodingB = blankTo(value.strip(), "auto");
                case "SRCA" -> cfg.sourceA = blankTo(value.strip(), "A");
                case "SRCB" -> cfg.sourceB = blankTo(value.strip(), "B");
                case "TRAILER" -> { if (!value.strip().isEmpty()) cfg.trailerPrefix = value.strip(); }
                case "REPLACE" -> cfg.replaceRules = decodeReplace(value.strip());
                default -> { /* MTIME 等忽略 */ }
            }
        }
        return cfg;
    }

    /** CompareConfig -> 文本行（1-based 序列）。full=true 追加扩展全字段。 */
    public static String toLegacyLine(CompareConfig cfg, boolean full) {
        List<String> parts = new ArrayList<>();
        parts.add(cfg.nickname == null ? "" : cfg.nickname);
        parts.add(cfg.fileGlob == null || cfg.fileGlob.isEmpty() ? "*" : cfg.fileGlob);
        if (!cfg.keyColumns.isEmpty()) parts.add("KEYSEQ=" + seq1based(cfg.keyColumns));
        if (!cfg.omitColumns.isEmpty()) parts.add("OMITSEQ=" + seq1based(cfg.omitColumns));
        if (!cfg.ignoreColumns.isEmpty()) parts.add("IGNORESEQ=" + seq1based(cfg.ignoreColumns));
        if (full) {
            parts.add("DELIM=" + (cfg.delimiter == null ? "" : cfg.delimiter));
            parts.add("ENCA=" + blankTo(cfg.encodingA, "auto"));
            parts.add("ENCB=" + blankTo(cfg.encodingB, "auto"));
            parts.add("SRCA=" + blankTo(cfg.sourceA, "A"));
            parts.add("SRCB=" + blankTo(cfg.sourceB, "B"));
            parts.add("TRAILER=" + (cfg.trailerPrefix == null ? "" : cfg.trailerPrefix));
            if (!cfg.replaceRules.isEmpty()) parts.add("REPLACE=" + encodeReplace(cfg.replaceRules));
        }
        return String.join(":", parts);
    }

    public static CompareConfig parseConfigFile(Path path, String delimiter, String trailerPrefix)
            throws java.io.IOException {
        for (String line : Files.readAllLines(path)) {
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return parseLegacy(line, delimiter, trailerPrefix);
            }
        }
        throw new IllegalArgumentException("配置文件为空: " + path);
    }

    // ---- REPLACE base64(JSON) 编解码 ----

    static String encodeReplace(Map<Integer, List<String[]>> rr) {
        ObjectNode obj = MAPPER.createObjectNode();
        for (Map.Entry<Integer, List<String[]>> e : rr.entrySet()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (String[] pair : e.getValue()) {
                ArrayNode p = MAPPER.createArrayNode();
                p.add(pair[0]);
                p.add(pair[1]);
                arr.add(p);
            }
            obj.set(String.valueOf(e.getKey()), arr);
        }
        try {
            byte[] raw = MAPPER.writeValueAsBytes(obj);
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            return "";
        }
    }

    static Map<Integer, List<String[]>> decodeReplace(String token) {
        Map<Integer, List<String[]>> out = new LinkedHashMap<>();
        if (token == null || token.isEmpty()) return out;
        try {
            byte[] raw = Base64.getDecoder().decode(token);
            JsonNode root = MAPPER.readTree(raw);
            root.fields().forEachRemaining(e -> {
                int col = Integer.parseInt(e.getKey());
                List<String[]> rules = new ArrayList<>();
                for (JsonNode pair : e.getValue()) {
                    rules.add(new String[]{pair.get(0).asText(), pair.get(1).asText()});
                }
                out.put(col, rules);
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    private static String seq1based(List<Integer> cols) {
        return cols.stream().map(c -> String.valueOf(c + 1)).collect(Collectors.joining("/"));
    }

    private static String blankTo(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }

    // ---- 运行期引擎 ----

    public record Compiled(Pattern pattern, String repl) {}

    /** 预编译规则，供 comparator 高频调用。 */
    public static final class RuleEngine {
        public final List<Integer> keyColumns;
        public final Set<Integer> skip;
        private final Map<Integer, List<Compiled>> compiled = new HashMap<>();

        public RuleEngine(CompareConfig config) {
            this.keyColumns = new ArrayList<>(config.keyColumns);
            this.skip = config.skipSet();
            for (Map.Entry<Integer, List<String[]>> e : config.replaceRules.entrySet()) {
                List<Compiled> list = new ArrayList<>();
                for (String[] pr : e.getValue()) {
                    list.add(new Compiled(Pattern.compile(pr[0]), pr[1]));
                }
                compiled.put(e.getKey(), list);
            }
        }

        /** 组合主键（用原值拼接）；列不足以取键时返回 null（malformed）。 */
        public String keyOf(String[] cols) {
            if (keyColumns.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < keyColumns.size(); j++) {
                int i = keyColumns.get(j);
                if (i < 0 || i >= cols.length) return null;
                if (j > 0) sb.append(KEY_SEP);
                sb.append(cols[i]);
            }
            return sb.toString();
        }

        public String normalize(int colIdx, String value) {
            List<Compiled> rules = compiled.get(colIdx);
            if (rules == null) return value;
            for (Compiled c : rules) {
                value = c.pattern().matcher(value).replaceAll(c.repl());
            }
            return value;
        }

        public boolean isSkipped(int colIdx) {
            return skip.contains(colIdx);
        }
    }

    // ---- 文件配对（目录批量对比用） ----

    public static boolean matchGlob(String filename, String glob) {
        String name = Path.of(filename).getFileName().toString();
        return FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(Path.of(name));
    }

    public static List<String> listMatching(Path directory, String glob) throws java.io.IOException {
        List<String> out = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(directory)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && matchGlob(p.getFileName().toString(), glob)) {
                    out.add(p.toAbsolutePath().toString());
                }
            }
        }
        out.sort(java.util.Comparator.comparing(s -> Path.of(s).getFileName().toString()));
        return out;
    }

    public static List<String[]> pairFiles(Path dirA, Path dirB, String glob) throws java.io.IOException {
        Map<String, String> a = new LinkedHashMap<>();
        for (String p : listMatching(dirA, glob)) a.put(Path.of(p).getFileName().toString(), p);
        Map<String, String> b = new LinkedHashMap<>();
        for (String p : listMatching(dirB, glob)) b.put(Path.of(p).getFileName().toString(), p);
        List<String> names = new ArrayList<>(a.keySet());
        names.retainAll(b.keySet());
        Collections.sort(names);
        List<String[]> pairs = new ArrayList<>();
        for (String n : names) pairs.add(new String[]{a.get(n), b.get(n)});
        return pairs;
    }
}
