package com.textdiff.store;

/**
 * 普通文本比对命名配置（配置自定义名称保存、载入复用）。
 *
 * groupName 配置组（可空）：同组配置圈定一组命名规则，提交时可按组对比；
 * fileRegex 文件名正则（可空）：目录模式下仅逻辑名命中该式的文本纳入核对。
 */
public record TextLineSavedConfig(String nickname, String groupName, String fileRegex,
                                  String keyPositions, String encodingA, String encodingB,
                                  String sourceA, String sourceB, String label, long createdAt) {

    public TextLineSavedConfig {
        nickname = nickname == null ? "" : nickname.strip();
        groupName = groupName == null ? "" : groupName.strip();
        fileRegex = fileRegex == null ? "" : fileRegex.strip();
        keyPositions = keyPositions == null ? "" : keyPositions.strip();
        encodingA = encodingA == null || encodingA.isBlank() ? "auto" : encodingA.strip();
        encodingB = encodingB == null || encodingB.isBlank() ? "auto" : encodingB.strip();
        sourceA = sourceA == null ? "" : sourceA.strip();
        sourceB = sourceB == null ? "" : sourceB.strip();
        label = label == null ? "" : label.strip();
    }

    /**
     * 文件名筛选编译：优先按正则解释；正则非法且仅含普通字符与 <code>* ?</code> 通配时
     * 按通配符兜底（{@code *}→{@code .*}、{@code ?}→{@code .}，如 {@code *ATMP*}），
     * 其余非法式仍拒绝。空式 = 不过滤（返回 null）。
     */
    public static java.util.regex.Pattern compileFileFilter(String fileRegex) {
        if (fileRegex == null || fileRegex.isBlank()) return null;
        String p = fileRegex.strip();
        try {
            return java.util.regex.Pattern.compile(p);
        } catch (java.util.regex.PatternSyntaxException e) {
            if (p.matches("[^\\[\\]{}()+.^$|\\\\]*")) { // 仅普通字符 + * ?：按通配符兜底
                StringBuilder sb = new StringBuilder();
                for (char c : p.toCharArray()) {
                    if (c == '*') sb.append(".*");
                    else if (c == '?') sb.append('.');
                    else sb.append(c);
                }
                return java.util.regex.Pattern.compile(sb.toString());
            }
            throw new IllegalArgumentException("文件名正则无效：" + e.getDescription()
                    + "（支持正则；或仅用 * ? 通配，如 *ATMP*）");
        }
    }

    /** 文件名筛选是否命中逻辑名（空式 = 全部命中）。 */
    public boolean matches(String logicalName) {
        java.util.regex.Pattern p = compileFileFilter(fileRegex);
        return p == null || (logicalName != null && p.matcher(logicalName).matches());
    }
}
