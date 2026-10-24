package com.textdiff.controller;

import com.textdiff.store.CompareGroup;
import com.textdiff.store.CompareGroupExcel;
import com.textdiff.store.CompareGroupStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对比配置分组端点（配置管理页 + 目录对比分组选择）：
 * GET    /api/compare-groups（全部分组）
 * POST   /api/compare-groups（新增/更新一个分组：{name, members[]}）
 * DELETE /api/compare-groups?name=（删除分组）
 * POST   /api/compare-groups/import（multipart .xlsx：分组名称 + 文本昵称 两列，合并导入）
 */
@RestController
@RequestMapping("/api/compare-groups")
public class CompareGroupController {
    private final CompareGroupStore store;

    public CompareGroupController(CompareGroupStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompareGroup g : store.all()) out.add(groupMap(g));
        return Map.of("groups", out, "db_available", store.dbAvailable());
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("缺少分组名称 name");
        List<String> members = new ArrayList<>();
        Object raw = body.get("members");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !o.toString().isBlank()) members.add(o.toString());
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            members.addAll(List.of(s.split("[、,，;；\\s|/]+")));
        }
        if (members.isEmpty()) throw new IllegalArgumentException("分组「" + name + "」成员为空");
        CompareGroup g = store.upsert(name, members, "手工维护");
        return groupMap(g);
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam String name) {
        if (!store.delete(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分组不存在: " + name);
        }
        return Map.of("deleted", true, "name", name);
    }

    /** Excel 导入（合并语义：同名分组替换，其余保留）。 */
    @PostMapping("/import")
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("未选择 Excel 文件（.xlsx）");
        String name = file.getOriginalFilename() == null ? "groups.xlsx" : file.getOriginalFilename();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
        CompareGroupExcel.Parsed parsed = CompareGroupExcel.parse(content, name);
        if (parsed.groups().isEmpty()) {
            throw new IllegalArgumentException("未解析到分组数据（需 分组名称 + 文本昵称 两列）"
                    + (parsed.warnings().isEmpty() ? "" : "；告警: " + parsed.warnings()));
        }
        List<String> changed = store.importAll(parsed.groups(),
                Path.of(name).getFileName().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", changed.size());
        out.put("groups", changed);
        out.put("total", store.all().size());
        out.put("source_file", name);
        out.put("db_available", store.dbAvailable());
        if (!parsed.warnings().isEmpty()) out.put("warnings", parsed.warnings());
        return out;
    }

    private static Map<String, Object> groupMap(CompareGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.name());
        m.put("members", g.members());
        m.put("count", g.members().size());
        m.put("source_file", g.sourceFile());
        m.put("imported_at", g.importedAt());
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
