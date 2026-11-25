package com.textdiff.store;

/**
 * 普通文本比对命名配置（配置自定义名称保存、载入复用）。
 *
 * groupName 配置组（可空）：同组配置圈定一组命名规则，提交时可按组对比；
 * fileRegex 文件名筛选（可空）：支持正则或 * ? 通配；目录模式下「逻辑名或原始文件名」
 *           命中该式的文本纳入核对（双解释双名，任一命中即可，如 01A*0D.sx1.gz.*）。
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
     * 文件名筛选校验：先按正则解释；正则非法但含 <code>* ?</code> 时按通配符兜底编译
     * （两种解释均无效仍拒绝）。空式 = 不过滤（返回 null）。
     * 实际匹配用 {@link #matches(String, String)}（正则∨通配双解释、逻辑名∨原始名双名）。
     */
    public static java.util.regex.Pattern compileFileFilter(String fileRegex) {
        if (fileRegex == null || fileRegex.isBlank()) return null;
        try {
            return java.util.regex.Pattern.compile(fileRegex.strip());
        } catch (java.util.regex.PatternSyntaxException e) {
            java.util.regex.Pattern glob = compileGlob(fileRegex.strip());
            if (glob != null) return glob;
            throw new IllegalArgumentException("文件名正则无效：" + e.getDescription()
                    + "（支持正则；或仅用 * ? 通配，如 01A*0D.sx1.gz.*）");
        }
    }

    /** 通配符解释（{@code *}→{@code .*}、{@code ?}→{@code .}，其余字符按字面转义）；无 * ? 时返回 null。 */
    private static java.util.regex.Pattern compileGlob(String p) {
        if (p.indexOf('*') < 0 && p.indexOf('?') < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : p.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else {
                if ("\\[](){}+.^$|-".indexOf(c) >= 0) sb.append('\\');
                sb.append(c);
            }
        }
        return java.util.regex.Pattern.compile(sb.toString());
    }

    /** 双解释正则列表：正则式（可编译时）+ 通配式（含 * ? 时）；空式 = 空列表（全部命中）。 */
    private java.util.List<java.util.regex.Pattern> matchPatterns() {
        java.util.List<java.util.regex.Pattern> out = new java.util.ArrayList<>();
        if (fileRegex == null || fileRegex.isBlank()) return out;
        String p = fileRegex.strip();
        try {
            out.add(java.util.regex.Pattern.compile(p));
        } catch (java.util.regex.PatternSyntaxException ignored) {
            // 正则解释无效时仅靠通配解释
        }
        java.util.regex.Pattern glob = compileGlob(p);
        if (glob != null) out.add(glob);
        return out;
    }

    /** 文件名筛选是否命中（空式 = 全部命中）。 */
    public boolean matches(String logicalName) {
        return matches(logicalName, logicalName);
    }

    /**
     * 文件名筛选是否命中：正则或通配任一解释命中「逻辑名或原始文件名」任一即可。
     * 例：{@code 01A*0D.sx1.gz.*} 按正则解释 {@code A*} 为量词命中不了逻辑名
     * {@code 01A1020D.sx1.gz}（剥日期后缀），按通配解释命中原始名
     * {@code 01A1020D.sx1.gz.202609271}。
     */
    public boolean matches(String logicalName, String rawName) {
        java.util.List<java.util.regex.Pattern> ps = matchPatterns();
        if (ps.isEmpty()) return true;
        for (java.util.regex.Pattern p : ps) {
            if (logicalName != null && p.matcher(logicalName).matches()) return true;
            if (rawName != null && !rawName.equals(logicalName) && p.matcher(rawName).matches()) return true;
        }
        return false;
    }
}
