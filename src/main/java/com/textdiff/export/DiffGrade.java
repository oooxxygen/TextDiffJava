package com.textdiff.export;

/**
 * 数量偏离总体评判（对比文件记录数实际数量偏离）：
 * 偏离率 = 数量差 / (两侧记录数总和 / 2)。
 * ≥50% 严重问题；≥20% 差异明显；≥10% 保持关注；其余正常。
 */
public final class DiffGrade {
    private DiffGrade() {}

    public record Grade(String label, String desc) {}

    /** 评判：label = 等级（正常/保持关注/差异明显/严重问题），desc = 状态情况描述。 */
    public static Grade of(long totalA, long totalB) {
        long diff = Math.abs(totalA - totalB);
        long sum = totalA + totalB;
        if (sum == 0) return new Grade("正常", "两侧无记录");
        // 偏离率相对两侧总和的一半：diff / (sum/2) = 2*diff/sum（放大 1000 倍取整避免浮点）
        long perMille = diff * 2000 / sum;
        String desc = "数量差 " + diff + "（偏离 " + perMille / 10 + "." + perMille % 10 + "%）";
        if (perMille >= 500) return new Grade("严重问题", desc);
        if (perMille >= 200) return new Grade("差异明显", desc);
        if (perMille >= 100) return new Grade("保持关注", desc);
        return new Grade("正常", desc);
    }
}
