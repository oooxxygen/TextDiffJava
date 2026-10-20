package com.textdiff.store;

import java.util.List;

/**
 * CTIS 动态栏位配置记录（源系统 BXXF_BUFFER 区域说明 Excel 导入）。
 *
 * sheet1（适用清单，一行 = 一个文本的一个动态栏位）：
 *   适用文本名（= bat_report_type_parm.report_id，如 BPAF）、关联行序号（文本内栏位序号，如 107）、
 *   关联行名称（动态栏位逻辑名，如 DYNAMIC_MESSAGE_01）。
 * sheet2（子栏位明细，一行 = 动态栏位内一个编号段）：
 *   数据项名称、主键、数据类型/长度、字段编号（'~~' 后的 2 位编号，如 1B）、
 *   对齐补位、数据字段描述、备注以及相关说明。
 */
public final class CtisDynFields {
    private CtisDynFields() {}

    /** sheet1 一行：textName 适用文本名，colSeq 关联行序号（栏位定位用，数字或栏位名），dynName 关联行名称。 */
    public record Apply(String textName, String colSeq, String dynName,
                        String sourceFile, long importedAt) {}

    /** sheet2 一行：itemCode 字段编号（2 位字母数字），其余为元数据（悬停/命名用）。 */
    public record Item(String dynName, String itemCode, String itemName, String primaryKey,
                       String typeLen, String padAlign, String description, String remark,
                       String sourceFile, long importedAt) {}

    /** 一次解析/导入的结果：记录 + 告警（不影响落库的可读性告警）。 */
    public record Snapshot(List<Apply> applies, List<Item> items, List<String> warnings) {}
}
