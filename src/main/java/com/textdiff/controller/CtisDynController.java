package com.textdiff.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CTIS 动态栏位配置端点：
 * POST /api/ctis-dyn/import（multipart .xlsx：sheet1 适用清单 + sheet2 子栏位明细，全量原子替换）
 * GET  /api/ctis-dyn/status（当前配置概览）
 * GET  /api/ctis-dyn/get?dyn=（指定关联行名称的子栏位明细）
 */
@RestController
@RequestMapping("/api/ctis-dyn")
public class CtisDynController {
    private final com.textdiff.store.CtisDynFieldStore store;

    public CtisDynController(com.textdiff.store.CtisDynFieldStore store) {
        this.store = store;
    }

    /** 导入（全量替换）：解析失败抛 400（旧数据不受影响），告警随摘要返回不阻断。 */
    @PostMapping("/import")
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("未选择 Excel 文件（.xlsx）");
        String name = file.getOriginalFilename() == null ? "ctis.xlsx" : file.getOriginalFilename();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
        com.textdiff.store.CtisDynFields.Snapshot parsed = com.textdiff.store.CtisDynExcel.parse(content, name);
        com.textdiff.store.CtisDynFields.Snapshot saved = store.importAll(parsed.applies(), parsed.items(),
                Path.of(name).getFileName().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applies", saved.applies().size());
        out.put("items", saved.items().size());
        out.put("dyn_names", store.dynNames());
        out.put("texts", saved.applies().stream().map(com.textdiff.store.CtisDynFields.Apply::textName).distinct().toList());
        out.put("source_file", name);
        out.put("db_available", store.dbAvailable());
        if (!parsed.warnings().isEmpty()) out.put("warnings", parsed.warnings());
        return out;
    }

    /** 当前配置概览（设置页状态行）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applies", store.allApplies().size());
        out.put("items", store.itemCount());
        out.put("dyn_names", store.dynNames());
        out.put("texts", store.allApplies().stream()
                .map(com.textdiff.store.CtisDynFields.Apply::textName).distinct().toList());
        out.put("source_file", store.lastSourceFile());
        out.put("imported_at", store.lastImportedAt());
        out.put("db_available", store.dbAvailable());
        return out;
    }

    /** 指定关联行名称的适用清单 + 子栏位明细（配置浏览）。 */
    @GetMapping("/get")
    public Map<String, Object> get(@RequestParam String dyn) {
        List<com.textdiff.store.CtisDynFields.Apply> applies = new ArrayList<>();
        for (var a : store.allApplies()) {
            if (a.dynName() != null && a.dynName().equalsIgnoreCase(dyn)) applies.add(a);
        }
        if (applies.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无此关联行名称: " + dyn);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        store.itemsFor(applies.get(0).dynName()).values().forEach(it -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_code", it.itemCode());
            m.put("item_name", it.itemName());
            m.put("primary_key", it.primaryKey());
            m.put("type_len", it.typeLen());
            m.put("pad_align", it.padAlign());
            m.put("description", it.description());
            m.put("remark", it.remark());
            items.add(m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dyn_name", applies.get(0).dynName());
        out.put("applies", applies.stream().map(a -> Map.of(
                "text_name", a.textName(), "col_seq", a.colSeq())).toList());
        out.put("items", items);
        return out;
    }
}
