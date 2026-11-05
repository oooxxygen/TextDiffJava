package com.textdiff.store;

import java.util.List;

/**
 * 文本拆分规则配置（配置管理界面维护、JSONL 持久化）：昵称唯一，供拆分作业载入。
 *
 * 规则模型：多条规则 = 分组，按序求值、<b>首条命中即归属</b>，全部未命中归「其他」组；
 * 规则内多条件支持 且（logic=all，默认）/ 或（logic=any）组合。
 * 条件类型 op：eq 定值 / contains 包含 / prefix 前缀 / suffix 后缀 / regex 栏位正则 / range 范围（起~止）。
 */
public record SplitConfig(String nickname, String delimiter, String encoding, String trailerPrefix,
                          List<Rule> rules, String sourceFile, long updatedAt) {

    /** 单条规则（一个分组）：name=组名；logic=all 且 / any 或。 */
    public record Rule(String name, String logic, List<Cond> conditions) {
        public boolean any() {
            return "any".equalsIgnoreCase(logic);
        }
    }

    /** 单条件：col 为 1-based 栏位序号，op/value 语义见类注释。 */
    public record Cond(int col, String op, String value) { }

    public SplitConfig {
        nickname = nickname == null ? "" : nickname.strip();
        delimiter = delimiter == null || delimiter.isBlank() ? " | " : delimiter;
        encoding = encoding == null || encoding.isBlank() ? "auto" : encoding.strip();
        trailerPrefix = trailerPrefix == null ? "|||||" : trailerPrefix;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
