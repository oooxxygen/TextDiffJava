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
 * 关注字段监测配置存储：JSONL 事实来源 + H2 镜像（禁用/失败自动降级），与 CtisDynFieldStore 同构。
 *
 * H2 schema：
 *   discard_field(nickname + col_seq PK, field_name, field_meaning, source_file, imported_at)
 *
 * 查询主链路：对比作业昵称精确匹配（忽略大小写）→ 监测栏位清单 → 批次/单文本采集 A/B 去重取值。
 */
public final class DiscardFieldStore implements AutoCloseable {
    private final Path dir;
    private final List<DiscardField> fields = new ArrayList<>();
    private Connection h2; // null = 纯文件模式

    public DiscardFieldStore(Path storeDir, boolean h2Enabled) {
        this.dir = storeDir;
        try {
            Files.createDirectories(dir);
            load("discard_fields.jsonl");
        } catch (IOException e) {
            throw new UncheckedIOException("DiscardFieldStore 初始化失败: " + dir, e);
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
                        CREATE TABLE IF NOT EXISTS discard_field (
                          nickname VARCHAR(64) NOT NULL,
                          col_seq INT NOT NULL,
                          field_name VARCHAR(256),
                          field_meaning VARCHAR(512),
                          source_file VARCHAR(256),
                          imported_at BIGINT,
                          PRIMARY KEY (nickname, col_seq))
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
            System.err.println("[store] 关注字段监测 H2 镜像不可用，降级纯文件模式: " + e.getMessage());
            this.h2 = null;
        }
    }

    /** 导入（全量替换）：Excel 为唯一真相，整体覆盖（与 H2 DELETE-all 镜像一致）。 */
    public synchronized List<DiscardField> importAll(List<DiscardField> list, String sourceFile) {
        long now = System.currentTimeMillis() / 1000;
        fields.clear();
        for (DiscardField r : list) {
            fields.add(new DiscardField(r.nickname(), r.colSeq(), r.fieldName(), r.fieldMeaning(),
                    sourceFile, now));
        }
        rewrite("discard_fields.jsonl", fields);
        mirrorReset();
        return new ArrayList<>(fields);
    }

    /** 清空监测配置。 */
    public synchronized void clear() {
        fields.clear();
        rewrite("discard_fields.jsonl", fields);
        mirrorReset();
    }

    /** 昵称精确匹配（忽略大小写）的监测栏位清单（按栏位序号升序）。 */
    public synchronized List<DiscardField> forNickname(String nickname) {
        List<DiscardField> out = new ArrayList<>();
        if (nickname == null) return out;
        for (DiscardField f : fields) {
            if (nickname.equalsIgnoreCase(f.nickname())) out.add(f);
        }
        out.sort(java.util.Comparator.comparingInt(DiscardField::colSeq));
        return out;
    }

    /** 是否存在任一监测配置（快速短路：无配置时采集链路零开销）。 */
    public synchronized boolean isEmpty() {
        return fields.isEmpty();
    }

    public synchronized List<DiscardField> all() {
        return new ArrayList<>(fields);
    }

    /** 昵称去重清单（状态展示）。 */
    public synchronized List<String> nicknames() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (DiscardField f : fields) seen.putIfAbsent(f.nickname(), Boolean.TRUE);
        return new ArrayList<>(seen.keySet());
    }

    /** 最近一次导入来源文件名（状态展示）。 */
    public synchronized String lastSourceFile() {
        String last = "";
        for (DiscardField f : fields) {
            if (f.sourceFile() != null && !f.sourceFile().isBlank()) last = f.sourceFile();
        }
        return last;
    }

    /** 最近一次导入时间（秒；无记录返回 0）。 */
    public synchronized long lastImportedAt() {
        long last = 0;
        for (DiscardField f : fields) last = Math.max(last, f.importedAt());
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

    private void load(String fileName) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    fields.add(Json.read(line, DiscardField.class));
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
            throw new UncheckedIOException("DiscardFieldStore 重写失败: " + fileName, e);
        }
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new UncheckedIOException("DiscardFieldStore 替换失败: " + fileName, ex);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("DiscardFieldStore 替换失败: " + fileName, e);
        }
    }

    /** 镜像自愈：按文件层全量重建。 */
    private void mirrorReset() {
        if (h2 == null) return;
        try (Statement st = h2.createStatement()) {
            st.execute("DELETE FROM discard_field");
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO discard_field(nickname, col_seq, field_name, field_meaning, source_file, imported_at) "
                        + "KEY(nickname, col_seq) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (DiscardField r : fields) {
                ps.setString(1, r.nickname());
                ps.setInt(2, r.colSeq());
                ps.setString(3, r.fieldName());
                ps.setString(4, r.fieldMeaning());
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
        System.err.println("[store] 关注字段监测 H2 镜像写入失败，降级纯文件模式: " + e.getMessage());
        try {
            if (h2 != null) h2.close();
        } catch (SQLException ignored) {
        }
        h2 = null;
    }
}
