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
 * CTIS 动态栏位配置存储：JSONL 追加为事实来源 + H2 双表镜像（禁用/失败自动降级）。
 *
 * H2 schema：
 *   ctis_dyn_field(text_name + col_seq PK, dyn_name, source_file, imported_at)
 *   ctis_dyn_item(dyn_name + item_code PK, item_name, primary_key, type_len, pad_align,
 *                 description, remark, source_file, imported_at)
 *
 * 查询主链路：对比文件名 →（FieldMapStore）report_id 且 parm_report_type=CTS → 适用清单命中 →
 * 引擎按 '~~' 编号展开虚拟子列（编号元数据供展示命名与悬停）。
 */
public final class CtisDynFieldStore implements AutoCloseable {
    private final Path dir;
    private final List<CtisDynFields.Apply> applies = new ArrayList<>();
    private final Map<String, Map<String, CtisDynFields.Item>> items = new LinkedHashMap<>();
    private Connection h2; // null = 纯文件模式

    public CtisDynFieldStore(Path storeDir, boolean h2Enabled) {
        this.dir = storeDir;
        try {
            Files.createDirectories(dir);
            load("ctis_dyn_applies.jsonl", CtisDynFields.Apply.class, applies);
            loadItems("ctis_dyn_items.jsonl");
        } catch (IOException e) {
            throw new UncheckedIOException("CtisDynFieldStore 初始化失败: " + dir, e);
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
                        CREATE TABLE IF NOT EXISTS ctis_dyn_field (
                          text_name VARCHAR(64) NOT NULL,
                          col_seq VARCHAR(32) NOT NULL,
                          dyn_name VARCHAR(128) NOT NULL,
                          source_file VARCHAR(256),
                          imported_at BIGINT,
                          PRIMARY KEY (text_name, col_seq))
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS ctis_dyn_item (
                          dyn_name VARCHAR(128) NOT NULL,
                          item_code VARCHAR(8) NOT NULL,
                          item_name VARCHAR(256),
                          primary_key VARCHAR(8),
                          type_len VARCHAR(64),
                          pad_align VARCHAR(32),
                          description VARCHAR(1024),
                          remark VARCHAR(256),
                          source_file VARCHAR(256),
                          imported_at BIGINT,
                          PRIMARY KEY (dyn_name, item_code))
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
            System.err.println("[store] CTIS 动态栏位 H2 镜像不可用，降级纯文件模式: " + e.getMessage());
            this.h2 = null;
        }
    }

    /** 导入（全量语义）：整体替换（与 H2 DELETE-all 镜像一致），原子重写失败不动旧数据。 */
    public synchronized CtisDynFields.Snapshot importAll(List<CtisDynFields.Apply> newApplies,
                                                         List<CtisDynFields.Item> newItems,
                                                         String sourceFile) {
        long now = System.currentTimeMillis() / 1000;
        applies.clear();
        items.clear();
        for (CtisDynFields.Apply r : newApplies) {
            applies.add(withSource(r, sourceFile, now));
        }
        for (CtisDynFields.Item r : newItems) {
            CtisDynFields.Item rec = withSource(r, sourceFile, now);
            items.computeIfAbsent(rec.dynName(), k -> new LinkedHashMap<>())
                    .put(rec.itemCode(), rec);
        }
        rewrite("ctis_dyn_applies.jsonl", applies);
        List<CtisDynFields.Item> allItems = flatItems();
        rewrite("ctis_dyn_items.jsonl", allItems);
        mirrorReset();
        return new CtisDynFields.Snapshot(new ArrayList<>(applies), allItems, List.of());
    }

    /** 适用文本（report_id）精确匹配（忽略大小写）的动态栏位清单。 */
    public synchronized List<CtisDynFields.Apply> appliesFor(String textName) {
        List<CtisDynFields.Apply> out = new ArrayList<>();
        if (textName == null) return out;
        for (CtisDynFields.Apply a : applies) {
            if (textName.equalsIgnoreCase(a.textName())) out.add(a);
        }
        return out;
    }

    /** 动态栏位的子栏位元数据：编号 → Item。 */
    public synchronized Map<String, CtisDynFields.Item> itemsFor(String dynName) {
        Map<String, CtisDynFields.Item> m = dynName == null ? null : items.get(dynName);
        return m == null ? Map.of() : m;
    }

    public synchronized List<CtisDynFields.Apply> allApplies() {
        return new ArrayList<>(applies);
    }

    public synchronized int itemCount() {
        return flatItems().size();
    }

    public synchronized List<String> dynNames() {
        return new ArrayList<>(items.keySet());
    }

    /** 最近一次导入来源文件名（状态展示）。 */
    public synchronized String lastSourceFile() {
        String last = "";
        for (CtisDynFields.Apply a : applies) {
            if (a.sourceFile() != null && !a.sourceFile().isBlank()) last = a.sourceFile();
        }
        return last;
    }

    /** 最近一次导入时间（秒；无记录返回 0）。 */
    public synchronized long lastImportedAt() {
        long last = 0;
        for (CtisDynFields.Apply a : applies) last = Math.max(last, a.importedAt());
        for (CtisDynFields.Item i : flatItems()) last = Math.max(last, i.importedAt());
        return last;
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

    private static CtisDynFields.Apply withSource(CtisDynFields.Apply r, String sourceFile, long at) {
        return new CtisDynFields.Apply(r.textName(), r.colSeq(), r.dynName(), sourceFile, at);
    }

    private static CtisDynFields.Item withSource(CtisDynFields.Item r, String sourceFile, long at) {
        return new CtisDynFields.Item(r.dynName(), r.itemCode(), r.itemName(), r.primaryKey(),
                r.typeLen(), r.padAlign(), r.description(), r.remark(), sourceFile, at);
    }

    private List<CtisDynFields.Item> flatItems() {
        List<CtisDynFields.Item> out = new ArrayList<>();
        for (Map<String, CtisDynFields.Item> m : items.values()) out.addAll(m.values());
        return out;
    }

    private <T> void load(String fileName, Class<T> type, List<T> into) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    into.add(Json.read(line, type));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void loadItems(String fileName) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    CtisDynFields.Item r = Json.read(line, CtisDynFields.Item.class);
                    items.computeIfAbsent(r.dynName(), k -> new LinkedHashMap<>()).put(r.itemCode(), r);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** 全量重写 JSONL（临时文件 + 原子替换），保证落盘内容与内存层一致。 */
    private void rewrite(String fileName, List<?> records) {
        Path target = dir.resolve(fileName);
        Path tmp = dir.resolve(fileName + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Object r : records) {
                w.write(Json.write(r));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CtisDynFieldStore 重写失败: " + fileName, e);
        }
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new UncheckedIOException("CtisDynFieldStore 替换失败: " + fileName, ex);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CtisDynFieldStore 替换失败: " + fileName, e);
        }
    }

    /** 镜像自愈：按文件层全量重建。 */
    private void mirrorReset() {
        if (h2 == null) return;
        try (Statement st = h2.createStatement()) {
            st.execute("DELETE FROM ctis_dyn_field");
            st.execute("DELETE FROM ctis_dyn_item");
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO ctis_dyn_field(text_name, col_seq, dyn_name, source_file, imported_at) "
                        + "KEY(text_name, col_seq) VALUES (?, ?, ?, ?, ?)")) {
            for (CtisDynFields.Apply r : applies) {
                ps.setString(1, r.textName());
                ps.setString(2, r.colSeq());
                ps.setString(3, r.dynName());
                ps.setString(4, r.sourceFile());
                ps.setLong(5, r.importedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO ctis_dyn_item(dyn_name, item_code, item_name, primary_key, type_len, pad_align, "
                        + "description, remark, source_file, imported_at) KEY(dyn_name, item_code) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (CtisDynFields.Item r : flatItems()) {
                ps.setString(1, r.dynName());
                ps.setString(2, r.itemCode());
                ps.setString(3, r.itemName());
                ps.setString(4, r.primaryKey());
                ps.setString(5, r.typeLen());
                ps.setString(6, r.padAlign());
                ps.setString(7, r.description());
                ps.setString(8, r.remark());
                ps.setString(9, r.sourceFile());
                ps.setLong(10, r.importedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    private synchronized void degrade(SQLException e) {
        System.err.println("[store] CTIS 动态栏位 H2 镜像写入失败，降级纯文件模式: " + e.getMessage());
        try {
            if (h2 != null) h2.close();
        } catch (SQLException ignored) {
        }
        h2 = null;
    }
}
