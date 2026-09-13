package com.textdiff.store;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONL 追加式 JobStore：任务元数据/评议的事实来源（H2 仅镜像）。
 * 每次保存整条记录追加一行，加载时后行覆盖前行（last-wins），天然保留操作轨迹。
 */
public final class FileJobStore implements JobStore {
    private final Path dir;
    private final Map<String, BatchRecord> batches = new LinkedHashMap<>();
    private final Map<String, JobRecord> jobs = new LinkedHashMap<>();
    private final Map<String, NoteRecord> notes = new LinkedHashMap<>(); // key = jobId|key|zone

    public FileJobStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
            load("batches.jsonl", BatchRecord.class, batches, r -> r.id);
            load("jobs.jsonl", JobRecord.class, jobs, r -> r.id);
            load("notes.jsonl", NoteRecord.class, notes, FileJobStore::noteKey);
        } catch (IOException e) {
            throw new UncheckedIOException("FileJobStore 初始化失败: " + dir, e);
        }
    }

    @Override
    public synchronized void saveBatch(BatchRecord batch) {
        batches.put(batch.id, batch.copy());
        append("batches.jsonl", batch);
    }

    @Override
    public synchronized BatchRecord getBatch(String id) {
        BatchRecord b = batches.get(id);
        return b == null ? null : b.copy();
    }

    @Override
    public synchronized List<BatchRecord> listBatches() {
        List<BatchRecord> out = new ArrayList<>();
        for (BatchRecord b : batches.values()) out.add(b.copy());
        return out;
    }

    @Override
    public synchronized void saveJob(JobRecord job) {
        jobs.put(job.id, job.copy());
        append("jobs.jsonl", job);
    }

    @Override
    public synchronized JobRecord getJob(String id) {
        JobRecord j = jobs.get(id);
        return j == null ? null : j.copy();
    }

    @Override
    public synchronized List<JobRecord> listJobs(String batchId) {
        List<JobRecord> out = new ArrayList<>();
        for (JobRecord j : jobs.values()) {
            if (batchId == null || batchId.equals(j.batchId)) out.add(j.copy());
        }
        return out;
    }

    @Override
    public synchronized void putNote(NoteRecord note) {
        notes.put(noteKey(note), note);
        append("notes.jsonl", note);
    }

    @Override
    public synchronized List<NoteRecord> getNotes(String jobId) {
        List<NoteRecord> out = new ArrayList<>();
        for (NoteRecord n : notes.values()) {
            if (jobId.equals(n.jobId())) out.add(n);
        }
        return out;
    }

    @Override
    public boolean dbAvailable() {
        return false; // 纯文件模式
    }

    @Override
    public synchronized void close() {}

    static String noteKey(NoteRecord n) {
        return n.jobId() + "|" + n.key() + "|" + n.zone();
    }

    private <T> void load(String fileName, Class<T> type, Map<String, T> target, java.util.function.Function<T, String> keyOf) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    T rec = Json.read(line, type);
                    target.put(keyOf.apply(rec), rec);
                } catch (RuntimeException e) {
                    // 单行损坏跳过：事实来源允许尾部半行（崩溃残留），不阻塞启动
                }
            }
        }
    }

    private void append(String fileName, Object record) {
        Path file = dir.resolve(fileName);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(Json.write(record));
            w.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("FileJobStore 追加失败: " + file, e);
        }
    }
}
