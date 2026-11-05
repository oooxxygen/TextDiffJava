package com.textdiff.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;

/** 拆分作业记录存储：{@code store/split_jobs.jsonl} 事实来源，列表按创建时间倒序。 */
public final class SplitJobStore implements AutoCloseable {
    private final Path file;
    private final LinkedHashMap<String, SplitJob> jobs = new LinkedHashMap<>();

    public SplitJobStore(Path storeDir) {
        this.file = storeDir.resolve("split_jobs.jsonl");
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try (var br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    SplitJob j = Json.read(line, SplitJob.class);
                    if (j.jobId() != null && !j.jobId().isEmpty()) jobs.put(j.jobId(), j);
                } catch (RuntimeException e) {
                    System.err.println("[split-job] 坏行跳过: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[split-job] 读取失败: " + e);
        }
    }

    /** 全部作业，创建时间倒序（首页拆分作业卡片顺序）。 */
    public synchronized ArrayList<SplitJob> list() {
        ArrayList<SplitJob> out = new ArrayList<>(jobs.values());
        out.sort(Comparator.comparingLong(SplitJob::createdAt).reversed());
        return out;
    }

    public synchronized SplitJob get(String jobId) {
        return jobId == null ? null : jobs.get(jobId);
    }

    public synchronized void save(SplitJob job) {
        jobs.put(job.jobId(), job);
        persist();
    }

    public synchronized boolean delete(String jobId) {
        boolean removed = jobs.remove(jobId) != null;
        if (removed) persist();
        return removed;
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (SplitJob j : jobs.values()) {
                    bw.write(Json.write(j));
                    bw.write("\n");
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("拆分作业持久化失败: " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        // JSONL 即时落盘，无后台资源
    }
}
