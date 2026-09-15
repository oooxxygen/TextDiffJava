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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成任务存储（任务管理）：JSONL 追加为事实来源 + H2 task_record 镜像（禁用/失败自动降级）。
 *
 * H2 schema：
 *   task_record(task_id PK, batch_id, job_id, task_type, status, task_trigger,
 *               output_path, error, created_at, started_at, finished_at)
 *
 * 状态机：pending → running → done / failed（failed 可经重新生成回到 pending）。
 */
public final class TaskStore implements AutoCloseable {
    private final Path dir;
    private final Map<String, TaskRecord> tasks = new LinkedHashMap<>();
    private Connection h2; // null = 纯文件模式

    public TaskStore(Path storeDir, boolean h2Enabled) {
        this.dir = storeDir;
        try {
            Files.createDirectories(dir);
            load();
        } catch (IOException e) {
            throw new UncheckedIOException("TaskStore 初始化失败: " + dir, e);
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
                        CREATE TABLE IF NOT EXISTS task_record (
                          task_id VARCHAR(32) PRIMARY KEY,
                          batch_id VARCHAR(64),
                          job_id VARCHAR(64) NOT NULL,
                          task_type VARCHAR(32) NOT NULL,
                          status VARCHAR(16) NOT NULL,
                          task_trigger VARCHAR(16),
                          output_path VARCHAR(512),
                          error VARCHAR(1024),
                          created_at BIGINT,
                          started_at BIGINT,
                          finished_at BIGINT)
                        """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_job ON task_record(job_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_batch ON task_record(batch_id)");
                st.execute("ALTER TABLE task_record ADD COLUMN IF NOT EXISTS scheduled_at BIGINT");
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
            System.err.println("[store] 任务记录 H2 镜像不可用，降级纯文件模式: " + e.getMessage());
            this.h2 = null;
        }
    }

    /** 新增或状态更新（整条落盘，last-wins）。 */
    public synchronized void save(TaskRecord t) {
        tasks.put(t.taskId, copy(t));
        append(t);
        mirrorUpsert(t);
    }

    public synchronized TaskRecord get(String taskId) {
        TaskRecord t = tasks.get(taskId);
        return t == null ? null : copy(t);
    }

    public synchronized List<TaskRecord> listForJob(String jobId) {
        List<TaskRecord> out = new ArrayList<>();
        for (TaskRecord t : tasks.values()) {
            if (t.jobId.equals(jobId)) out.add(copy(t));
        }
        return out;
    }

    public synchronized List<TaskRecord> listForBatch(String batchId) {
        List<TaskRecord> out = new ArrayList<>();
        for (TaskRecord t : tasks.values()) {
            if (batchId == null ? t.batchId == null : batchId.equals(t.batchId)) out.add(copy(t));
        }
        return out;
    }

    public synchronized List<TaskRecord> listAll() {
        List<TaskRecord> out = new ArrayList<>();
        for (TaskRecord t : tasks.values()) out.add(copy(t));
        return out;
    }

    /** 删除单条（任务管理页删除按钮）。 */
    public synchronized void delete(String taskId) {
        if (tasks.remove(taskId) == null) return;
        rewrite(tasks.values());
        mirrorReset(tasks.values());
    }

    /** 级联删除：作业/批次删除时清理其全部任务记录。 */
    public synchronized void deleteForJobs(Collection<String> jobIds) {
        boolean changed = tasks.values().removeIf(t -> jobIds.contains(t.jobId));
        if (!changed) return;
        rewrite(tasks.values());
        mirrorReset(tasks.values());
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

    private static TaskRecord copy(TaskRecord t) {
        TaskRecord c = new TaskRecord(t.taskId, t.batchId, t.jobId, t.taskType);
        c.status = t.status;
        c.trigger = t.trigger;
        c.outputPath = t.outputPath;
        c.error = t.error;
        c.createdAt = t.createdAt;
        c.startedAt = t.startedAt;
        c.finishedAt = t.finishedAt;
        c.scheduledAt = t.scheduledAt;
        return c;
    }

    private void load() throws IOException {
        Path file = dir.resolve("tasks.jsonl");
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    TaskRecord t = Json.read(line, TaskRecord.class);
                    if (t.taskId != null) tasks.put(t.taskId, t); // last-wins
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void append(TaskRecord t) {
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("tasks.jsonl"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(Json.write(t));
            w.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("TaskStore 追加失败", e);
        }
    }

    /** 删除后整文件重写（原子替换），避免墓碑记录在重载时复活。 */
    private void rewrite(Collection<TaskRecord> records) {
        Path target = dir.resolve("tasks.jsonl");
        Path tmp = dir.resolve("tasks.jsonl.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (TaskRecord t : records) {
                w.write(Json.write(t));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("TaskStore 重写失败", e);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new UncheckedIOException("TaskStore 替换失败", ex);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("TaskStore 替换失败", e);
        }
    }

    private void mirrorUpsert(TaskRecord t) {
        if (h2 == null) return;
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO task_record(task_id, batch_id, job_id, task_type, status, task_trigger, "
                        + "output_path, error, created_at, started_at, finished_at, scheduled_at) "
                        + "KEY(task_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bind(ps, t);
            ps.executeUpdate();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    /** 镜像自愈：按文件层全量重建。 */
    private void resyncMirror() {
        mirrorReset(tasks.values());
    }

    private void mirrorReset(Collection<TaskRecord> records) {
        if (h2 == null) return;
        try (Statement st = h2.createStatement()) {
            st.execute("DELETE FROM task_record");
        } catch (SQLException e) {
            degrade(e);
            return;
        }
        try (PreparedStatement ps = h2.prepareStatement(
                "MERGE INTO task_record(task_id, batch_id, job_id, task_type, status, task_trigger, "
                        + "output_path, error, created_at, started_at, finished_at, scheduled_at) "
                        + "KEY(task_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (TaskRecord t : records) {
                bind(ps, t);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            degrade(e);
        }
    }

    private static void bind(PreparedStatement ps, TaskRecord t) throws SQLException {
        ps.setString(1, t.taskId);
        ps.setString(2, t.batchId);
        ps.setString(3, t.jobId);
        ps.setString(4, t.taskType);
        ps.setString(5, t.status);
        ps.setString(6, t.trigger);
        ps.setString(7, t.outputPath);
        ps.setString(8, t.error);
        ps.setLong(9, t.createdAt);
        ps.setLong(10, t.startedAt);
        ps.setLong(11, t.finishedAt);
        ps.setLong(12, t.scheduledAt);
    }

    private synchronized void degrade(SQLException e) {
        System.err.println("[store] 任务记录 H2 镜像写入失败，降级纯文件模式: " + e.getMessage());
        try {
            if (h2 != null) h2.close();
        } catch (SQLException ignored) {
        }
        h2 = null;
    }
}
