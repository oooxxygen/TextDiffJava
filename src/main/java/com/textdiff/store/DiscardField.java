package com.textdiff.store;

/**
 * 关注字段监测配置条目（DISCARD.xlsx 导入）：昵称 + 1-based 栏位序号 + 栏位名 + 含义。
 * 对比作业按昵称精确命中后，采集该栏位在 A/B 两侧数据行的去重取值。
 */
public record DiscardField(String nickname, int colSeq, String fieldName, String fieldMeaning,
                           String sourceFile, long importedAt) {
}
