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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特殊编码字段映射存储（源系统下传数据表结构）：JSONL 追加为事实来源 + H2 镜像（禁用/失败自动降级）。
 *
 * H2 schema：
 *   field_charset(table_name + col_index PK, charset, field_name, field_length, source_file, imported_at)
 *
 * 查询主链路：对比文件名 → 数据表英文名 →（0-based 列号 → 特殊字符集），
 * 供对比引擎对混合编码字段（如 EBCDIC 文件内嵌 UTF-16 栏位）按值探测转码 UTF-8。
 */
public final class CharsetMapStore implements AutoCloseable {
    private final Path dir;
    private final Map<String, Map<Integer, CharsetMaps.FieldCharset>> tables = new LinkedHashMap<>();
    private Connection h2; // null = 纯文件模式

    public CharsetMapStore(Path storeDir, boolean h2Enabled) {
        this.dir = storeDir;
        try {
            Files.createDirectories(dir);
            load("charset_fields.jsonl");
        } catch (IOException e) {
            throw new UncheckedIOException("CharsetMapStore 初始化失败: " + dir, e);
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
                        CREATE TABLE IF NOT EXISTS field_charset (
                          table_name VARCHAR(64) NOT NULL,
                          col_index INT NOT NULL,
                          charset VARCHAR(32) NOT NULL,
                          field_name VARCHAR(256),
                          field_length VARCHAR(32),
                          source_file VARCHAR(256),
                          imported_at BIGINT,
                          PRIMARY KEY (table_name, col_index))
                        """);
            }
            this.h2 = c;
            mirrorReset();
        } catch (Exception e) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                }
            }
            System.err.println("[store] 编码映射 H2 镜像不可用，降级纯文件模式: " + e.getMessage());
            this.h2 = null;
        }
    }

    /** 导入（全量语义）：整体替换（与 H2 DELETE-all 镜像一致）。 */
    public synchronized List<CharsetMaps.FieldCharset> importAll(List<CharsetMaps.FieldCharset> list) {
        tables.clear();
        for (CharsetMaps.FieldCharset r : list) {
            tables.computeIfAbsent(r.tableName(), k -> new LinkedHashMap<>()).put(r.colIndex(), r);
        }
        rewrite("charset_fields.jsonl", all());
        mirrorReset();
        return all();
    }

    private List<CharsetMaps.FieldCharset> all() {
        List<CharsetMaps.FieldCharset> out = new ArrayList<>();
        for (Map<Integer, CharsetMaps.FieldCharset> m : tables.values()) out.addAll(m.values());
        return out;
    }

    /** 0-based 列号 → 特殊字符集（该表无特殊编码字段时返回空表）。 */
    public synchronized Map<Integer, String> colCharsetsForTable(String tableName) {
        Map<Integer, CharsetMaps.FieldCharset> m = tableName == null ? null : tables.get(tableName);
        if (m == null || m.isEmpty()) return Map.of();
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, CharsetMaps.FieldCharset> e : m.entrySet()) {
            out.put(e.getKey(), e.getValue().charset());
        }
        return out;
    }

    public synchronized boolean hasTable(String tableName) {
        return tableName != null && tables.containsKey(tableName);
    }

    /** 导入的表数量（状态展示）。 */
    public synchronized int tableCount() {
        return tables.size();
    }

    /**
     * 对比文件名 → 数据表英文名。匹配优先级：表名精确/茎/通配 →
     * 词元包含（文件名茎按非字母数字切词后含表名，如 {@code 01.VSNA.102}、{@code VSNA_20260801}）→
     * 字段名映射 report_id 兜底（report_id 与表英文名同名的场景）。
     */
    public synchronized String tableForFile(String fileName, FieldMapStore fieldMaps) {
        String name = Path.of(fileName).getFileName().toString();
        String stem = stripExt(name);
        for (String t : tables.keySet()) {
            if (t.equalsIgnoreCase(name)) return t;
        }
        for (String t : tables.keySet()) {
            if (stripExt(t).equalsIgnoreCase(stem)) return t;
        }
        for (String t : tables.keySet()) {
            if (FieldMapStore.globMatches(t, name) || FieldMapStore.globMatches(t, stem)) return t;
        }
        String[] tokens = stem.split("[^A-Za-z0-9]+");
        for (String t : tables.keySet()) {
            for (String tok : tokens) {
                if (!tok.isEmpty() && tok.equalsIgnoreCase(t)) return t;
            }
        }
        if (fieldMaps != null) {
            String reportId = fieldMaps.reportIdForFile(fileName);
            if (reportId != null && tables.containsKey(reportId)) return reportId;
        }
        return null;
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot <= 0 ? n : n.substring(0, dot);
    }

    /** 字段总数（状态展示）。 */
    public synchronized int fieldCount() {
        return all().size();
    }

    public boolean dbAvailable() {
        return h2 != null;
    }

    /** 最近一次导入来源文件名（状态展示）。 */
    public synchronized String lastSourceFile() {
        String last = "";
        for (CharsetMaps.FieldCharset r : all()) {
            if (r.sourceFile() != null && !r.sourceFile().isBlank()) last = r.sourceFile();
        }
        return last;
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

    private void load(String fileName) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    CharsetMaps.FieldCharset r = Json.read(line, CharsetMaps.FieldCharset.class);
                    tables.computeIfAbsent(r.tableName(), k -> new LinkedHashMap<>()).put(r.colIndex(), r);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void rewrite(String fileName, List<CharsetMaps.FieldCharset> records) {
        Path target = dir.resolve(fileName);
        Path tmp = dir.resolve(fileName + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (CharsetMaps.FieldCharset r : records) {
                w.write(Json.write(r));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CharsetMapStore 重写失败: " + fileName, e);
        }
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new UncheckedIOException("CharsetMapStore 替换失败: " + fileName, ex);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CharsetMapStore 替换失败: " + fileName, e);
        }
    }

    /** 镜像自愈：按文件层全量重建。 */
    private void mirrorReset() {
        if (h2 == null) return;
        try (Statement st = h2.createStatement()) {
            st.execute("DELETE FROM field_charset");
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO field_charset(table_name, col_index, charset, field_name, field_length, "
                        + "source_file, imported_at) KEY(table_name, col_index) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (CharsetMaps.FieldCharset r : all()) {
                ps.setString(1, r.tableName());
                ps.setInt(2, r.colIndex());
                ps.setString(3, r.charset());
                ps.setString(4, r.fieldName());
                ps.setString(5, r.fieldLength());
                ps.setString(6, r.sourceFile());
                ps.setLong(7, r.importedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    private synchronized void degrade(SQLException e) {
        System.err.println("[store] 编码映射 H2 镜像写入失败，降级纯文件模式: " + e.getMessage());
        try {
            if (h2 != null) h2.close();
        } catch (SQLException ignored) {
        }
        h2 = null;
    }
}
