package com.textdiff.controller;

import com.textdiff.store.CharsetMapStore;
import com.textdiff.store.CharsetMaps;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特殊编码字段映射端点（源系统下传数据表结构 Excel 导入）：
 * POST /api/charset-map/import（multipart .xlsx，列名定位：数据表英文名/表内字段序号/BOCS字段内部存储字符集）
 * GET /api/charset-map/status。
 * 仅保留非 E 码（如 UTF-16）的特殊字符集字段；对比引擎据此对混合编码栏位按值转码 UTF-8。
 */
@RestController
@RequestMapping("/api/charset-map")
public class CharsetMapController {
    /** Excel 表头列名 → 记录字段。 */
    private static final String COL_TABLE = "数据表英文名";
    private static final String COL_ORDINAL = "表内字段序号";
    private static final String COL_CHARSET = "bocs字段内部存储字符集";
    private static final String COL_FIELD = "bocs字段内部存储英文名";
    private static final String COL_LENGTH = "数据最大长度";

    private final CharsetMapStore store;

    public CharsetMapController(CharsetMapStore store) {
        this.store = store;
    }

    @PostMapping("/import")
    public Map<String, Object> importXlsx(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请上传数据表结构 Excel（.xlsx）");
        List<CharsetMaps.FieldCharset> records;
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            records = parse(wb, original);
        }
        if (records.isEmpty()) {
            throw new IllegalArgumentException("未在 Excel 中识别到特殊字符集字段"
                    + "（需含表头：数据表英文名 / 表内字段序号 / BOCS字段内部存储字符集）");
        }
        List<CharsetMaps.FieldCharset> all = store.importAll(records);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", store.tableCount());
        out.put("fields", all.size());
        out.put("source_file", original);
        out.put("db_available", store.dbAvailable());
        return out;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", store.tableCount());
        out.put("fields", store.fieldCount());
        out.put("source_file", store.lastSourceFile());
        out.put("db_available", store.dbAvailable());
        return out;
    }

    /** 解析工作簿全部 sheet：按表头列名定位（容忍列序变化），仅保留非 E 码字段。 */
    public static List<CharsetMaps.FieldCharset> parse(Workbook wb, String sourceFile) {
        DataFormatter fmt = new DataFormatter();
        org.apache.poi.ss.usermodel.FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
        List<CharsetMaps.FieldCharset> out = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        for (Sheet sheet : wb) {
            int[] idx = findHeader(sheet, fmt, eval);
            if (idx == null) continue; // 封面/目录/修改历史等无数据表结构表头的 sheet
            for (int r = idx[5] + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String table = text(row, idx[0], fmt, eval);
                String charset = text(row, idx[2], fmt, eval);
                String ordinal = text(row, idx[1], fmt, eval);
                if (table.isBlank() || charset.isBlank() || ordinal.isBlank()) continue;
                if ("E".equalsIgnoreCase(charset)) continue; // E 码为主字符集，无需转码
                int col;
                try {
                    col = Integer.parseInt(ordinal.strip()) - 1; // 表内序号 1-based → 0-based 列号
                } catch (NumberFormatException e) {
                    continue;
                }
                if (col < 0) continue;
                out.add(new CharsetMaps.FieldCharset(table.strip(), col, charset.strip(),
                        text(row, idx[3], fmt, eval), text(row, idx[4], fmt, eval), sourceFile, now));
            }
        }
        return out;
    }

    /** 表头行定位：返回 [表名, 序号, 字符集, 字段名, 长度, 行号]，找不到返回 null。 */
    private static int[] findHeader(Sheet sheet, DataFormatter fmt, org.apache.poi.ss.usermodel.FormulaEvaluator eval) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int table = -1, ordinal = -1, charset = -1, field = -1, length = -1;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String v = text(row, c, fmt, eval).replace("\n", "").strip().toLowerCase();
                switch (v) {
                    case COL_TABLE -> table = c;
                    case COL_ORDINAL -> ordinal = c;
                    case COL_CHARSET -> charset = c;
                    case COL_FIELD -> field = c;
                    case COL_LENGTH -> length = c;
                    default -> { }
                }
            }
            if (table >= 0 && ordinal >= 0 && charset >= 0) {
                return new int[]{table, ordinal, charset, field, length, r};
            }
        }
        return null;
    }

    /** 单元格文本；公式单元格经 evaluator 取计算结果（序号列常为 =C42+1 类公式）。 */
    private static String text(Row row, int col, DataFormatter fmt, org.apache.poi.ss.usermodel.FormulaEvaluator eval) {
        if (row == null || col < 0) return "";
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return fmt.formatCellValue(cell, eval).strip();
            } catch (RuntimeException e) {
                return "";
            }
        }
        return fmt.formatCellValue(cell).strip();
    }
}
