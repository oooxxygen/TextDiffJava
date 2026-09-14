package com.textdiff.store;

import java.util.List;

/** 字段名映射（源系统字段配置）记录。 */
public final class FieldMaps {
    private FieldMaps() {}

    /** bat_report_type_parm*.csv 一行（按 report_id 去重）：文件昵称 ↔ 文件名 ↔ 文件类型/归属组。 */
    public record ReportType(String reportId, String reportFileName, String parmReportType,
                             String ownershipGroup, String sourceFile, long importedAt) {}

    /** bat_report_conf_field*.csv 一行：昵称 + 0-based 列号 → 字段名/字段类型/字段长度。 */
    public record ReportField(String reportId, int colIndex, String fieldName, String fieldFormat,
                              String fieldLength) {}

    /** 一次导入的汇总。 */
    public record ImportSummary(int types, int fields, List<String> files, List<String> nicknames) {}
}
