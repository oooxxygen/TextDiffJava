package com.textdiff.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对比配置分组存储：JSONL 事实来源 + H2 镜像（禁用/失败自动降级），与 DiscardFieldStore 同构。
 *
 * H2 schema：
 *   compare_group(group_name PK, members(JSON 数组文本), source_file, imported_at)
 *
 * 用途：目录批次对比时选定分组，仅该分组成员（配置昵称清单）内的文本参与对比；
 * 未选分组则对比目录下全部文本（全部生效配置行）。
 */
public final class CompareGroupStore implements AutoCloseable {
    private final Path dir;
    private final Map<String, CompareGroup> groups = new LinkedHashMap<>();
    private Connection h2; // null = 纯文件模式

    public CompareGroupStore(Path storeDir, boolean h2Enabled) {
        this.dir = storeDir;
        try {
            Files.createDirectories(dir);
            load("compare_groups.jsonl");
        } catch (IOException e) {
            throw new UncheckedIOException("CompareGroupStore 初始化失败: " + dir, e);
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
                        CREATE TABLE IF NOT EXISTS compare_group (
                          group_name VARCHAR(128) NOT NULL PRIMARY KEY,
                          members VARCHAR(8192),
                          source_file VARCHAR(256),
                          imported_at BIGINT)
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
            System.err.println("[store] 对比配置分组 H2 镜像不可用，降级纯文件模式: " + e.getMessage());
            this.h2 = null;
        }
    }

    /** 新增/更新一个分组（同名整体替换）；成员规范化去重。 */
    public synchronized CompareGroup upsert(String name, List<String> members, String sourceFile) {
        String key = requireName(name);
        List<String> norm = CompareGroup.normalize(members);
        if (norm.isEmpty()) throw new IllegalArgumentException("分组「" + name + "」成员为空");
        CompareGroup g = new CompareGroup(key, norm, sourceFile, System.currentTimeMillis() / 1000);
        groups.put(key, g);
        persist();
        return g;
    }

    /** 批量导入（合并语义）：同名分组替换，其余保留。返回发生替换/新增的分组名。 */
    public synchronized List<String> importAll(List<CompareGroup> list, String sourceFile) {
        List<String> changed = new ArrayList<>();
        for (CompareGroup g : list) {
            String key = requireName(g.name());
            if (g.members() == null || g.members().isEmpty()) continue;
            groups.put(key, new CompareGroup(key, CompareGroup.normalize(g.members()),
                    sourceFile, System.currentTimeMillis() / 1000));
            changed.add(key);
        }
        if (!changed.isEmpty()) persist();
        return changed;
    }

    /** 删除分组；不存在返回 false。 */
    public synchronized boolean delete(String name) {
        boolean removed = groups.remove(requireName(name)) != null;
        if (removed) persist();
        return removed;
    }

    /** 全部分组（名称升序）。 */
    public synchronized List<CompareGroup> all() {
        List<CompareGroup> out = new ArrayList<>(groups.values());
        out.sort(java.util.Comparator.comparing(CompareGroup::name));
        return out;
    }

    /** 分组名清单（名称升序，前端下拉）。 */
    public synchronized List<String> names() {
        return all().stream().map(CompareGroup::name).toList();
    }

    /** 按名称查分组（忽略大小写精确）。 */
    public synchronized CompareGroup group(String name) {
        if (name == null || name.isBlank()) return null;
        for (CompareGroup g : groups.values()) {
            if (g.name().equalsIgnoreCase(name.strip())) return g;
        }
        return null;
    }

    /** 分组成员集合（忽略大小写匹配配置昵称用；无此分组返回空）。 */
    public synchronized Set<String> membersOf(String name) {
        CompareGroup g = group(name);
        return g == null ? Set.of() : new LinkedHashSet<>(g.members());
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

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("分组名称不能为空");
        return name.strip();
    }

    private void load(String fileName) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    CompareGroup g = Json.read(line, CompareGroup.class);
                    groups.put(g.name(), g);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** 全量重写 JSONL（临时文件 + 原子替换）。 */
    private void persist() {
        Path target = dir.resolve("compare_groups.jsonl");
        Path tmp = dir.resolve("compare_groups.jsonl.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (CompareGroup g : groups.values()) {
                w.write(Json.write(g));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CompareGroupStore 重写失败", e);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new UncheckedIOException("CompareGroupStore 替换失败", ex);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CompareGroupStore 替换失败", e);
        }
        mirrorReset();
    }

    /** 镜像自愈：按文件层全量重建。 */
    private void mirrorReset() {
        if (h2 == null) return;
        try (Statement st = h2.createStatement()) {
            st.execute("DELETE FROM compare_group");
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO compare_group(group_name, members, source_file, imported_at) "
                        + "KEY(group_name) VALUES (?, ?, ?, ?)")) {
            for (CompareGroup g : groups.values()) {
                ps.setString(1, g.name());
                ps.setString(2, Json.write(g.members()));
                ps.setString(3, g.sourceFile());
                ps.setLong(4, g.importedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    private synchronized void degrade(SQLException e) {
        System.err.println("[store] 对比配置分组 H2 镜像写入失败，降级纯文件模式: " + e.getMessage());
        try {
            if (h2 != null) h2.close();
        } catch (SQLException ignored) {
        }
        h2 = null;
    }
}
