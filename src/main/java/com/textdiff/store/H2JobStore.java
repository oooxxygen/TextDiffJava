package com.textdiff.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 镜像 JobStore：仅存任务元数据/评议（JSON 整体列），文本行永不入库。
 * 与 FileJobStore 表结构解耦——整条记录序列化为 JSON，schema 演进零迁移。
 */
public final class H2JobStore implements JobStore {
    private final Connection conn;

    public H2JobStore(Path dir) {
        Connection c = null;
        try {
            Class.forName("org.h2.Driver");
            Files.createDirectories(dir);
            // 多半单进程使用，无需 AUTO_SERVER；整条记录 JSON 化存 CLOB，schema 演进零迁移
            c = DriverManager.getConnection("jdbc:h2:file:" + dir.resolve("textdiff").toAbsolutePath());
            this.conn = c;
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS batches (id VARCHAR(64) PRIMARY KEY, data CLOB)");
                st.execute("CREATE TABLE IF NOT EXISTS jobs (id VARCHAR(64) PRIMARY KEY, batch_id VARCHAR(64), data CLOB)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS notes (
                          job_id VARCHAR(64), note_key VARCHAR(512), zone VARCHAR(32), data CLOB,
                          PRIMARY KEY (job_id, note_key, zone))
                        """);
            }
        } catch (Exception e) {
            if (c != null) {
                try {
                    c.close(); // 释放 .mv.db 文件锁，允许后续重试
                } catch (SQLException ignored) {
                }
            }
            throw new IllegalStateException("H2JobStore 初始化失败: " + dir, e);
        }
    }

    @Override
    public synchronized void saveBatch(BatchRecord batch) {
        exec("MERGE INTO batches(id, data) KEY(id) VALUES (?, ?)", batch.id, Json.write(batch));
    }

    @Override
    public synchronized BatchRecord getBatch(String id) {
        return queryOne("SELECT data FROM batches WHERE id = ?", id, BatchRecord.class);
    }

    @Override
    public synchronized List<BatchRecord> listBatches() {
        return queryList("SELECT data FROM batches ORDER BY id", BatchRecord.class, null);
    }

    @Override
    public synchronized void saveJob(JobRecord job) {
        exec("MERGE INTO jobs(id, batch_id, data) KEY(id) VALUES (?, ?, ?)",
                job.id, job.batchId, Json.write(job));
    }

    @Override
    public synchronized JobRecord getJob(String id) {
        return queryOne("SELECT data FROM jobs WHERE id = ?", id, JobRecord.class);
    }

    @Override
    public synchronized List<JobRecord> listJobs(String batchId) {
        if (batchId == null) {
            return queryList("SELECT data FROM jobs ORDER BY id", JobRecord.class, null);
        }
        return queryList("SELECT data FROM jobs WHERE batch_id = ? ORDER BY id", JobRecord.class, batchId);
    }

    @Override
    public synchronized void putNote(NoteRecord note) {
        if (note.note() == null || note.note().isEmpty()) {
            deleteNote(note.jobId(), note.key(), note.zone());
            return;
        }
        exec("MERGE INTO notes(job_id, note_key, zone, data) KEY(job_id, note_key, zone) VALUES (?, ?, ?, ?)",
                note.jobId(), note.key(), note.zone(), Json.write(note));
    }

    @Override
    public synchronized void deleteNote(String jobId, String key, String zone) {
        exec("DELETE FROM notes WHERE job_id = ? AND note_key = ? AND zone = ?", jobId, key, zone);
    }

    @Override
    public synchronized boolean deleteJob(String jobId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM jobs WHERE id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getLong(1) == 0) return false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        exec("DELETE FROM jobs WHERE id = ?", jobId);
        exec("DELETE FROM notes WHERE job_id = ?", jobId);
        return true;
    }

    @Override
    public synchronized boolean deleteBatch(String batchId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM batches WHERE id = ?")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getLong(1) == 0) return false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        exec("DELETE FROM jobs WHERE batch_id = ?", batchId);
        exec("DELETE FROM batches WHERE id = ?", batchId);
        return true;
    }

    @Override
    public synchronized java.util.Set<String> notedKeys(String jobId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT note_key, zone FROM notes WHERE job_id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.Set<String> out = new java.util.HashSet<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized List<NoteRecord> getNotes(String jobId) {
        return queryList("SELECT data FROM notes WHERE job_id = ? ORDER BY note_key, zone", NoteRecord.class, jobId);
    }

    @Override
    public boolean dbAvailable() {
        return true;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }

    /** 供 DualJobStore 重建镜像后清库（可选运维操作）。 */
    synchronized void clearAll() {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM batches");
            st.execute("DELETE FROM jobs");
            st.execute("DELETE FROM notes");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void exec(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("H2 写入失败: " + sql, e);
        }
    }

    private <T> T queryOne(String sql, String arg, Class<T> type) {
        List<T> all = queryList(sql, type, arg);
        return all.isEmpty() ? null : all.get(0);
    }

    private <T> List<T> queryList(String sql, Class<T> type, String arg) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (arg != null) ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(Json.read(rs.getString(1), type));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("H2 读取失败: " + sql, e);
        }
    }
}
