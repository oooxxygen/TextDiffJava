package com.textdiff.store;

/**
 * 关注字段监测配置条目（DISCARD.xlsx 导入）：昵称 + 1-based 栏位序号 + 栏位名 + 含义 + 字段类型。
 * 对比作业按昵称精确命中后，采集该栏位在 A/B 两侧数据行的去重取值。
 *
 * 字段类型（fieldType）四分类：1 废弃字段 / 2 功能未实施 / 3 无法对比 / 4 数据治理；
 * 0 = 未指定（旧格式导入无该列，或类型值非法）。
 * 监测语义：1/4 要求 B 侧（新线）取值 ⊆ A 侧（旧线）取值；2 要求新旧线完全一致；3 仅报表展示不监控。
 */
public record DiscardField(String nickname, int colSeq, String fieldName, String fieldMeaning,
                           int fieldType, String sourceFile, long importedAt) {

    /** 类型文案：1 废弃字段 / 2 功能未实施 / 3 无法对比 / 4 数据治理 / 其他 未指定。 */
    public static String label(int type) {
        return switch (type) {
            case 1 -> "废弃字段";
            case 2 -> "功能未实施";
            case 3 -> "无法对比";
            case 4 -> "数据治理";
            default -> "未指定";
        };
    }

    /** 是否参与监测（类型 3 无法对比仅报表展示，不监控、不列具体值）。 */
    public static boolean monitored(int type) {
        return type != 3;
    }

    /**
     * 差异栏位前缀（[X-YYYY]）：X=类型序号、YYYY=类型名，如 [1-废弃字段]；
     * 未指定类型返回空串（无前缀）。
     */
    public static String prefix(int type) {
        return switch (type) {
            case 1 -> "[1-废弃字段]";
            case 2 -> "[2-功能未实施]";
            case 3 -> "[3-无法对比]";
            case 4 -> "[4-数据治理]";
            default -> "";
        };
    }

    /**
     * 类型值解析（导入容错）：1~4 数字直接接受；含关键字的文案映射
     * （废弃/未实施/无法对比/无法比对/治理）；其余返回 0（未指定）。
     */
    public static int parseType(String v) {
        if (v == null || v.isBlank()) return 0;
        String s = v.strip();
        if (s.length() == 1 && s.charAt(0) >= '1' && s.charAt(0) <= '4') return s.charAt(0) - '0';
        if (s.contains("废弃")) return 1;
        if (s.contains("未实施")) return 2;
        if (s.contains("无法")) return 3;
        if (s.contains("治理")) return 4;
        return 0;
    }
}
