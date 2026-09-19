package com.textdiff.store;

/** 特殊编码字段映射（源系统下传数据表结构）记录。 */
public final class CharsetMaps {
    private CharsetMaps() {}

    /**
     * 数据表结构 Excel 一行（仅保留非 E 码的特殊字符集字段）：
     * 表英文名 + 表内字段序号（1-based）→ 字符集（如 UTF-16）与字段名/长度（展示辅助）。
     */
    public record FieldCharset(String tableName, int colIndex, String charset,
                               String fieldName, String fieldLength,
                               String sourceFile, long importedAt) {}
}
