package com.textdiff.store;

import java.util.List;

/** 对比配置分组成员（一行 = 一个分组）。 */
public record CompareGroup(String name, List<String> members,
                           String sourceFile, long importedAt) {

    /** 规范化成员（去空白、去重、保序）；空成员剔除。 */
    public static List<String> normalize(List<String> members) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (members != null) {
            for (String m : members) {
                if (m == null) continue;
                String t = m.strip();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return List.copyOf(out);
    }
}
