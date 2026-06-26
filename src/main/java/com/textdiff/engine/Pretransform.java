package com.textdiff.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * 源文件按列正则替换 → UTF-8 文本「M」文件（供有替换规则的对比作业预处理）。对等 Python pretransform.py。
 * 逐行流式：trailer 行原样透传；数据行按分隔符切列，对配置列做替换后重组。
 */
public final class Pretransform {
    private Pretransform() {}

    /**
     * 01A3020D.v01 -> 01A3020M.v01。取首个 '.' 前的 stem：
     * 末位为 'D' 则改 'M'，否则 stem 末尾追加 '_M'，再接回原扩展名。
     */
    public static String transformedName(String basename) {
        String stem, ext;
        int dot = basename.indexOf('.');
        if (dot >= 0) {
            stem = basename.substring(0, dot);
            ext = basename.substring(dot);  // 含 '.'
        } else {
            stem = basename;
            ext = "";
        }
        if (stem.endsWith("D")) {
            stem = stem.substring(0, stem.length() - 1) + "M";
        } else {
            stem = stem + "_M";
        }
        return stem + ext;
    }

    /** 按 config.replaceRules 对 src 逐行做按列替换，写出 UTF-8 文本 dst。 */
    public static void transformFile(Path srcPath, String srcEncoding, Path dstPath, CompareConfig config)
            throws IOException {
        Rules.RuleEngine engine = new Rules.RuleEngine(config);
        String delim = config.delimiter;
        String tp = config.trailerPrefix;
        Path parent = dstPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (BufferedWriter out = Files.newBufferedWriter(dstPath, StandardCharsets.UTF_8);
             Stream<String> lines = Encoding.iterLines(srcPath, srcEncoding, delim)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (tp != null && !tp.isEmpty() && line.startsWith(tp)) {
                    out.write(line);
                    out.write('\n');               // trailer 原样
                    continue;
                }
                String[] cols = Parser.splitColumns(line, delim);
                for (int i = 0; i < cols.length; i++) {
                    cols[i] = engine.normalize(i, cols[i]);
                }
                out.write(String.join(delim, cols));
                out.write('\n');
            }
        }
    }
}
