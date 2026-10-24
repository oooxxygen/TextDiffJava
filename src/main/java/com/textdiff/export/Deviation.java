package com.textdiff.export;

/**
 * 文本偏离度双指标（批次总结）：
 *  内容偏离度 = 差异条数（有差异 + 仅A + 仅B）/ A 选区总条数；
 *  条数偏离度 = |B 选区总条数 − A 选区总条数| / A 选区总条数。
 *
 * 分级（两项指标同一阈值）：≥50% 严重问题（红）、≥20% 务必关注（黄）、≥10% 微量偏移（紫）、其余 正常。
 */
public final class Deviation {
    private Deviation() {}

    public record Metric(double ratio, String grade) {
        public String percent() {
            return String.format("%.1f", ratio * 100) + "%";
        }
    }

    /** 内容偏离度：差异条数 / A 总条数（A 为 0 时分母按 1 计，有差异即 100%+）。 */
    public static Metric content(long diff, long onlyA, long onlyB, long totalA) {
        double r = (diff + onlyA + onlyB) / (double) Math.max(1, totalA);
        return new Metric(r, grade(r));
    }

    /** 条数偏离度：|B − A| / A 总条数。 */
    public static Metric count(long totalA, long totalB) {
        double r = Math.abs(totalB - totalA) / (double) Math.max(1, totalA);
        return new Metric(r, grade(r));
    }

    public static String grade(double ratio) {
        if (ratio >= 0.5) return "严重问题";
        if (ratio >= 0.2) return "务必关注";
        if (ratio >= 0.1) return "微量偏移";
        return "正常";
    }

    /** 徽标样式类（紫/黄/红/绿），HTML 报告与前端在线页共用语义。 */
    public static String badgeClass(String grade) {
        return switch (grade) {
            case "严重问题" -> "b-red";
            case "务必关注" -> "b-yellow";
            case "微量偏移" -> "b-purple";
            default -> "b-green";
        };
    }
}
