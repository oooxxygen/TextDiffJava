package com.textdiff.engine;

import java.util.*;

/**
 * 一次对比所用的全部规则配置。列索引统一为 0-based。对等 Python models.CompareConfig。
 * 可变类：规则解析（Rules.parseLegacy）需逐字段填充。
 */
public final class CompareConfig {
    public String nickname = "";
    public String fileGlob = "*";
    public String delimiter = " | ";
    public String encodingA = "auto";
    public String encodingB = "auto";
    public List<Integer> keyColumns = new ArrayList<>();      // 主键列（0-based）
    public List<Integer> omitColumns = new ArrayList<>();     // 整列跳过：不比对
    public List<Integer> ignoreColumns = new ArrayList<>();   // 不作主键也不标差异
    /** 按列正则替换：{col -> [ {pattern, repl}, ... ]}，文件级预变换时应用。 */
    public Map<Integer, List<String[]>> replaceRules = new LinkedHashMap<>();
    public String trailerPrefix = "|||||";
    public List<String> columnNames = new ArrayList<>();      // 0-based 列 -> 列名
    public String group = "";
    public String sourceA = "A";
    public String sourceB = "B";

    /** 比对时需要跳过的列集合（omit ∪ ignore）。 */
    public Set<Integer> skipSet() {
        Set<Integer> s = new HashSet<>(omitColumns);
        s.addAll(ignoreColumns);
        return s;
    }
}
