package com.textdiff.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段名映射存储（源系统字段配置）：JSONL 追加为事实来源 + H2 双表镜像（禁用/失败自动降级）。
 *
 * H2 schema：
 *   report_type_parm(report_id PK, report_file_name, parm_report_type, ownership_group, source_file, imported_at)
 *   report_conf_field(report_id + col_index PK, field_name, field_format, field_length)
 *
 * 查询主链路：对比文件名 → report_id → 0-based 有序字段名列表（填充 CompareConfig.columnNames）；
 * 归属组/文件名映射供 AI 分析产物命名（[归属组]文件昵称_实际文件名.md）；
 * 字段名/类型(field_format)/长度(field_length) 供提示词「栏位属性」表辅助 AI 归纳分析。
 */
public final class FieldMapStore implements AutoCloseable {
    private final Path dir;
    private final Map<String, FieldMaps.ReportType> types = new LinkedHashMap<>();
    private final Map<String, Map<Integer, FieldMaps.ReportField>> fields = new LinkedHashMap<>();
    private Connection h2; // null = 纯文件模式

    public FieldMapStore(Path storeDir, boolean h2Enabled) {
        this.dir = storeDir;
        try {
            Files.createDirectories(dir);
            load("field_types.jsonl", FieldMaps.ReportType.class, r -> r.reportId(), types);
            load("field_fields.jsonl", FieldMaps.ReportField.class, FieldMapStore::groupKey, null);
        } catch (IOException e) {
            throw new UncheckedIOException("FieldMapStore 初始化失败: " + dir, e);
        }
        if (h2Enabled) enableH2(storeDir.resolve("h2"));
    }

    private synchronized void enableH2(Path h2Dir) {
        Connection c = null;
        try {
            Files.createDirectories(h2Dir);
            c = DriverManager.getConnection(
                    "jdbc:h2:file:" + h2Dir.resolve("textdiff").toAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS report_type_parm (
                          report_id VARCHAR(64) PRIMARY KEY,
                          report_file_name VARCHAR(256) NOT NULL,
                          parm_report_type VARCHAR(128),
                          ownership_group VARCHAR(128),
                          source_file VARCHAR(256),
                          imported_at BIGINT)
                        """);
                // 旧库迁移：早于归属组字段建立的表补列（H2 支持 ADD COLUMN IF NOT EXISTS）
                st.execute("ALTER TABLE report_type_parm ADD COLUMN IF NOT EXISTS ownership_group VARCHAR(128)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS report_conf_field (
                          report_id VARCHAR(64) NOT NULL,
                          col_index INT NOT NULL,
                          field_name VARCHAR(256),
                          field_format VARCHAR(64),
                          field_length VARCHAR(32),
                          PRIMARY KEY (report_id, col_index))
                        """);
                // 旧库迁移：早于长度字段建立的表补列
                st.execute("ALTER TABLE report_conf_field ADD COLUMN IF NOT EXISTS field_length VARCHAR(32)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_rtp_file ON report_type_parm(report_file_name)");
            }
            this.h2 = c;
            resyncMirror();
        } catch (Exception e) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                }
            }
            System.err.println("[store] 字段映射 H2 镜像不可用，降级纯文件模式: " + e.getMessage());
            this.h2 = null;
        }
    }
    /** 导入（全量语义）：同 report_id 覆盖类型；字段列表整体替换（与 H2 DELETE-all 镜像一致）。 */
    public synchronized FieldMaps.ImportSummary importAll(List<FieldMaps.ReportType> t,
                                                          List<FieldMaps.ReportField> f,
                                                          List<String> files) {
        for (FieldMaps.ReportType r : t) {
            types.put(r.reportId(), r);
        }
        fields.clear();
        for (FieldMaps.ReportField r : f) {
            fields.computeIfAbsent(r.reportId(), k -> new LinkedHashMap<>()).put(r.colIndex(), r);
        }
        // 导入是批量操作：整文件重写（原子替换）而非追加，保证重载后与内存一致
        rewrite("field_types.jsonl", types.values());
        List<FieldMaps.ReportField> all = new ArrayList<>();
        for (Map<Integer, FieldMaps.ReportField> m : fields.values()) all.addAll(m.values());
        rewrite("field_fields.jsonl", all);
        mirrorType(new ArrayList<>(types.values()));
        mirrorFieldsReset(all);
        return new FieldMaps.ImportSummary(types.size(), fieldCount(), files,
                new ArrayList<>(t.stream().map(FieldMaps.ReportType::reportId).distinct().toList()));
    }

    public synchronized List<FieldMaps.ReportType> allTypes() {
        return new ArrayList<>(types.values());
    }

    public synchronized FieldMaps.ReportType typeByReport(String reportId) {
        return types.get(reportId);
    }

    /** 0-based 有序字段名（无映射返回空列表）。 */
    public synchronized List<String> fieldNamesFor(String reportId) {
        Map<Integer, FieldMaps.ReportField> m = fields.get(reportId);
        if (m == null || m.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Integer i : new java.util.TreeSet<>(m.keySet())) {
            String n = m.get(i).fieldName();
            out.add(n == null ? "" : n);
        }
        return out;
    }

    /** 0-based 有序字段记录（字段名/类型/长度，供栏位属性表）；无映射返回空列表。 */
    public synchronized List<FieldMaps.ReportField> fieldsFor(String reportId) {
        Map<Integer, FieldMaps.ReportField> m = fields.get(reportId);
        if (m == null || m.isEmpty()) return List.of();
        List<FieldMaps.ReportField> out = new ArrayList<>();
        for (Integer i : new java.util.TreeSet<>(m.keySet())) out.add(m.get(i));
        return out;
    }

    /**
     * 对比文件名 → report_id。匹配优先级：report_file_name 精确 → 去扩展名茎精确 →
     * 通配匹配（report_file_name 常为 01A**&#42;0&#42;.v01 这类模式，星号匹配任意串、问号单字符，大小写不敏感）。
     */
    public synchronized String reportIdForFile(String fileName) {
        String name = Path.of(fileName).getFileName().toString();
        String stem = stripExt(name);
        for (FieldMaps.ReportType t : types.values()) {
            if (t.reportFileName() != null && t.reportFileName().equalsIgnoreCase(name)) return t.reportId();
        }
        for (FieldMaps.ReportType t : types.values()) {
            if (t.reportFileName() != null && stripExt(t.reportFileName()).equalsIgnoreCase(stem)) return t.reportId();
        }
        for (FieldMaps.ReportType t : types.values()) {
            if (t.reportFileName() != null && globMatches(t.reportFileName(), name)) return t.reportId();
        }
        for (FieldMaps.ReportType t : types.values()) {
            if (t.reportFileName() != null && globMatches(stripExt(t.reportFileName()), stem)) return t.reportId();
        }
        return null;
    }

    /** 通配匹配：* / *** 任意串，? 单字符；其余字符按字面（大小写不敏感）。 */
    static boolean globMatches(String pattern, String name) {
        if (pattern == null || pattern.isBlank()) return false;
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toLowerCase().toCharArray()) {
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                case '.' -> re.append("\\.");
                case '\\' -> re.append("\\\\");
                default -> re.append(c);
            }
        }
        return name.toLowerCase().matches(re.toString());
    }

    /** 字段总数（状态展示）。 */
    public synchronized int fieldCount() {
        return fields.values().stream().mapToInt(Map::size).sum();
    }

    public boolean dbAvailable() {
        return h2 != null;
    }

    @Override
    public synchronized void close() {
        if (h2 != null) {
            try {
                h2.close();
            } catch (SQLException ignored) {
            }
        }
    }

    // ---- internals ----

    private static String groupKey(FieldMaps.ReportField r) {
        return r.reportId() + "#" + String.format("%04d", r.colIndex());
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot <= 0 ? n : n.substring(0, dot);
    }

    private <T> void load(String fileName, Class<T> type,
                          java.util.function.Function<T, String> keyOf,
                          Map<String, T> flat) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    T rec = Json.read(line, type);
                    if (flat != null) {
                        flat.put(keyOf.apply(rec), rec);
                    } else {
                        FieldMaps.ReportField r = (FieldMaps.ReportField) rec;
                        fields.computeIfAbsent(r.reportId(), k -> new LinkedHashMap<>()).put(r.colIndex(), r);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** 全量重写 JSONL（临时文件 + 原子替换），保证落盘内容与内存层一致。 */
    private void rewrite(String fileName, java.util.Collection<?> records) {
        Path target = dir.resolve(fileName);
        Path tmp = dir.resolve(fileName + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Object r : records) {
                w.write(Json.write(r));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("FieldMapStore 重写失败: " + fileName, e);
        }
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new UncheckedIOException("FieldMapStore 替换失败: " + fileName, ex);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("FieldMapStore 替换失败: " + fileName, e);
        }
    }

    private void mirrorType(FieldMaps.ReportType r) {
        if (h2 == null) return;
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO report_type_parm(report_id, report_file_name, parm_report_type, ownership_group, "
                        + "source_file, imported_at) KEY(report_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, r.reportId());
            ps.setString(2, r.reportFileName());
            ps.setString(3, r.parmReportType());
            ps.setString(4, r.ownershipGroup());
            ps.setString(5, r.sourceFile());
            ps.setLong(6, r.importedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    private void mirrorFieldsReset(List<FieldMaps.ReportField> all) {
        if (h2 == null) return;
        try (Statement st = h2.createStatement()) {
            st.execute("DELETE FROM report_conf_field");
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO report_conf_field(report_id, col_index, field_name, field_format, field_length) "
                        + "KEY(report_id, col_index) VALUES (?, ?, ?, ?, ?)")) {
            for (FieldMaps.ReportField r : all) {
                ps.setString(1, r.reportId());
                ps.setInt(2, r.colIndex());
                ps.setString(3, r.fieldName());
                ps.setString(4, r.fieldFormat());
                ps.setString(5, r.fieldLength());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    /** 镜像自愈：按文件层全量重建。 */
    private void resyncMirror() {
        try (Statement st = h2.createStatement()) {
            st.execute("DELETE FROM report_type_parm");
            st.execute("DELETE FROM report_conf_field");
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        mirrorType(new ArrayList<>(types.values()));
        List<FieldMaps.ReportField> all = new ArrayList<>();
        for (Map<Integer, FieldMaps.ReportField> m : fields.values()) all.addAll(m.values());
        mirrorFieldsReset(all);
    }

    private void mirrorType(List<FieldMaps.ReportType> list) {
        if (h2 == null) return;
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO report_type_parm(report_id, report_file_name, parm_report_type, ownership_group, "
                        + "source_file, imported_at) KEY(report_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (FieldMaps.ReportType r : list) {
                ps.setString(1, r.reportId());
                ps.setString(2, r.reportFileName());
                ps.setString(3, r.parmReportType());
                ps.setString(4, r.ownershipGroup());
                ps.setString(5, r.sourceFile());
                ps.setLong(6, r.importedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    private synchronized void degrade(SQLException e) {
        System.err.println("[store] 字段映射 H2 镜像写入失败，降级纯文件模式: " + e.getMessage());
        try {
            if (h2 != null) h2.close();
        } catch (SQLException ignored) {
        }
        h2 = null;
    }
}
