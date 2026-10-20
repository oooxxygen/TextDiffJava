package com.textdiff.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CTIS 动态栏位「对比时虚拟子列展开」。
 *
 * 解析规则（与配置表约定一致）：
 *   动态栏位原值按 "~~" 切分，每段取段首 2 位字母/数字为编号、剩余为内容（如 ~~1B20260101 → 1B / 20260101）；
 *   段首 2 位不构成编号（静态前缀、空段等）按自然编号 1~n 展示；首尾定界产生的空段丢弃；
 *   不去前后空格（定长补位参与比对）；同编号重复出现按出现顺序配对（第 2 个起键为 编号*2）。
 *
 * 虚拟子列：每个（父栏位 × 子编号）注册为一个虚拟列，绝对索引 = {@link #VBASE} + 序号，
 * 参与 diff_cols / 差异列频次 / 导出列名（父标签-编号（数据项名称），如 107-1B（交易真实日期））；
 * 编号配对与出现顺序无关（仅顺序不同判等），单侧缺失的编号按空值明示差异。
 */
public final class DynExpand {
    /** 虚拟子列绝对索引基址：真实列号不可能达到（行长度上限内），保证 diff_cols 键不冲突。 */
    public static final int VBASE = 1_000_000;

    /** 一个动态栏位的展开规格（任务层解析配置后传入引擎）。 */
    public static final class Spec {
        public final int colIndex;            // 0-based 父栏位
        public final String parentLabel;      // 父标签（关联行序号原样，如 "107"），导出命名前缀
        public final String dynName;          // 关联行名称（悬停「字段名称」，如 DYNAMIC_MESSAGE_01）
        public final Map<String, Item> items; // 编号 → 子栏位元数据（悬停/命名）

        public Spec(int colIndex, String parentLabel, String dynName, Map<String, Item> items) {
            this.colIndex = colIndex;
            this.parentLabel = parentLabel;
            this.dynName = dynName;
            this.items = items == null ? Map.of() : items;
        }
    }

    /** 子栏位元数据（配置表 sheet2 行）。 */
    public record Item(String itemCode, String itemName, String primaryKey,
                       String typeLen, String padAlign, String description) {}

    /** 注册表项：一个虚拟子列（meta.dyn_cols 持久化，前端/导出按此渲染；record 供 summary.json 往返序列化）。 */
    public record Desc(int index, int parentCol, String parentLabel, String sub, String name,
                       String dynName, String itemName) {
        public Desc {
        }

        /** 短标签（差异列 chips / 导出栏位号）："107-1B"。 */
        public String label() {
            return parentLabel + "-" + sub;
        }
    }

    /** 行内一个子栏位的两侧取值（RowDiff.dyn 元素；单侧行另一侧为 null）。 */
    public static final class Val {
        public int idx;
        public int c;
        public String k;
        public String a;
        public String b;

        public Val() {}

        Val(int idx, int c, String k, String a, String b) {
            this.idx = idx;
            this.c = c;
            this.k = k;
            this.a = a;
            this.b = b;
        }
    }

    /** 整行比较结果（含虚拟子列差异与行内子值）。 */
    public record RowResult(int[] diffCols, List<Val> vals) {}

    private final List<Spec> specs;
    private final Map<Integer, Spec> byCol = new HashMap<>();
    private final LinkedHashMap<String, Desc> registry = new LinkedHashMap<>();
    private int seq = 0;

    private DynExpand(List<Spec> specs) {
        this.specs = specs;
        for (Spec s : specs) byCol.putIfAbsent(s.colIndex, s);
    }

    /** specs 空/ null 返回 null（走 legacy 逐列路径）。 */
    public static DynExpand of(List<Spec> specs) {
        if (specs == null) return null;
        List<Spec> effective = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Spec s : specs) {
            if (s == null || s.colIndex < 0 || seen.contains(s.colIndex)) continue;
            seen.add(s.colIndex);
            effective.add(s);
        }
        return effective.isEmpty() ? null : new DynExpand(effective);
    }

    public boolean isDyn(int colIndex) {
        return byCol.containsKey(colIndex);
    }

    /** 已注册虚拟子列（首见顺序；作业完成后写入 Summary/meta）。 */
    public List<Desc> descs() {
        return new ArrayList<>(registry.values());
    }

    /**
     * 整行比较：真实列逐列（跳过 omit/ignore 与动态父栏位），动态父栏位按子编号展开比对。
     * 差异索引 = 真实差异列 + 差异虚拟子列；vals 含全部子项（相等子项也供前端展示）。
     */
    public RowResult compareRow(String[] aCols, String[] bCols, Set<Integer> skip) {
        int la = aCols.length, lb = bCols.length;
        int n = Math.max(la, lb);
        List<Integer> diff = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (skip.contains(i) || byCol.containsKey(i)) continue;
            String a = i < la ? aCols[i] : "";
            String b = i < lb ? bCols[i] : "";
            if (!a.equals(b)) diff.add(i);
        }
        List<Val> vals = new ArrayList<>();
        for (Spec spec : specs) {
            int i = spec.colIndex;
            if (skip.contains(i)) continue;
            String a = i < la ? aCols[i] : "";
            String b = i < lb ? bCols[i] : "";
            LinkedHashMap<String, String> pa = parse(a);
            LinkedHashMap<String, String> pb = parse(b);
            LinkedHashSet<String> keys = new LinkedHashSet<>(pa.keySet());
            keys.addAll(pb.keySet());
            for (String k : keys) {
                String av = pa.get(k), bv = pb.get(k); // null = 该侧无此编号
                Desc d = register(spec, k);
                vals.add(new Val(d.index, i, k, av, bv));
                if (!Objects.equals(av, bv)) diff.add(d.index);
            }
        }
        int[] out = new int[diff.size()];
        for (int i = 0; i < out.length; i++) out[i] = diff.get(i);
        return new RowResult(out, vals);
    }

    /** 单侧行（only_a/only_b）子值展开：仅解析存在侧。 */
    public List<Val> expandSingle(String[] cols, Set<Integer> skip, boolean sideA) {
        List<Val> vals = new ArrayList<>();
        for (Spec spec : specs) {
            int i = spec.colIndex;
            if (skip.contains(i)) continue;
            String raw = i < cols.length ? cols[i] : "";
            for (Map.Entry<String, String> e : parse(raw).entrySet()) {
                Desc d = register(spec, e.getKey());
                vals.add(new Val(d.index, i, e.getKey(),
                        sideA ? e.getValue() : null, sideA ? null : e.getValue()));
            }
        }
        return vals;
    }

    /**
     * '~~' 分段解析：返回（键 → 内容），保持出现顺序。
     * 拼接组成为 '~~'+编号+内容——首个分段（任何 '~~' 之前）是静态前缀，不取编号；
     * 其后各段段首 2 位字母/数字为编号（同编号重复出现第 2 个起 键为 编号*2，按出现顺序配对）；
     * 无法解析编号的段按自然编号 1~n；首尾定界产生的空段丢弃。
     */
    static LinkedHashMap<String, String> parse(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split("~~", -1);
        int natural = 0;
        Map<String, Integer> codeSeen = new HashMap<>();
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (seg.isEmpty()) continue; // 首尾定界残留空段
            String code = i == 0 ? null : codeOf(seg); // 首段是 '~~' 之前的静态前缀
            String key;
            if (code == null) {
                key = "#" + (++natural);
                out.put(key, seg);
            } else {
                int nth = codeSeen.merge(code, 1, Integer::sum);
                key = nth == 1 ? code : code + "*" + nth;
                out.put(key, seg.substring(2));
            }
        }
        return out;
    }

    /** 段首 2 位均为字母/数字 → 编号（内容可为空）；否则 null（自然编号段）。 */
    static String codeOf(String seg) {
        if (seg.length() < 2) return null;
        char c1 = seg.charAt(0), c2 = seg.charAt(1);
        return (Character.isLetterOrDigit(c1) && Character.isLetterOrDigit(c2)) ? seg.substring(0, 2) : null;
    }

    private synchronized Desc register(Spec spec, String key) {
        String regKey = spec.colIndex + ":" + key;
        Desc d = registry.get(regKey);
        if (d != null) return d;
        String baseCode = key.startsWith("#") ? null : key.split("\\*", 2)[0];
        Item item = baseCode == null ? null : spec.items.get(baseCode);
        String sub = key.startsWith("#") ? key.substring(1) : key;
        String itemName = item == null ? null : item.itemName();
        String name = spec.parentLabel + "-" + sub
                + (itemName != null && !itemName.isBlank() ? "（" + itemName + "）" : "");
        d = new Desc(VBASE + (++seq), spec.colIndex, spec.parentLabel, sub, name,
                spec.dynName, itemName);
        registry.put(regKey, d);
        return d;
    }
}
