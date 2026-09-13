package com.textdiff.store;

import java.nio.file.Path;
import java.util.List;

/**
 * 双写门面：FileJobStore 始终为事实来源，H2JobStore 尽力镜像。
 * H2 被禁用（store.enabled=false）或首次写入/重建失败时自动降级纯文件模式；
 * 启动时以文件内容全量重建 H2 镜像（自愈：H2 落后/损坏无需人工干预）。
 */
public final class DualJobStore implements JobStore {
    private final FileJobStore file;
    private volatile H2JobStore h2; // null = 纯文件模式
    private final Path h2Dir;

    public DualJobStore(Path storeDir, boolean h2Enabled) {
        this.file = new FileJobStore(storeDir);
        this.h2Dir = storeDir.resolve("h2");
        if (h2Enabled) {
            enableH2();
        }
    }

    private synchronized void enableH2() {
        try {
            H2JobStore db = new H2JobStore(h2Dir);
            // 全量重建镜像：文件是事实来源
            db.clearAll();
            for (BatchRecord b : file.listBatches()) db.saveBatch(b);
            for (JobRecord j : file.listJobs(null)) db.saveJob(j);
            for (JobRecord j : file.listJobs(null)) {
                for (NoteRecord n : file.getNotes(j.id)) db.putNote(n);
            }
            this.h2 = db;
        } catch (RuntimeException e) {
            System.err.println("[store] H2 镜像不可用，降级纯文件模式: " + e.getMessage());
            this.h2 = null;
        }
    }

    private void mirror(Runnable h2Write) {
        H2JobStore db = h2;
        if (db == null) return;
        try {
            h2Write.run();
        } catch (RuntimeException e) {
            // 镜像写失败：关闭并降级，文件层不受影响
            System.err.println("[store] H2 镜像写入失败，降级纯文件模式: " + e.getMessage());
            try {
                db.close();
            } catch (RuntimeException ignored) {
            }
            h2 = null;
        }
    }

    @Override
    public void saveBatch(BatchRecord batch) {
        file.saveBatch(batch);
        mirror(() -> h2.saveBatch(batch));
    }

    @Override
    public BatchRecord getBatch(String id) {
        return file.getBatch(id);
    }

    @Override
    public List<BatchRecord> listBatches() {
        return file.listBatches();
    }

    @Override
    public void saveJob(JobRecord job) {
        file.saveJob(job);
        mirror(() -> h2.saveJob(job));
    }

    @Override
    public JobRecord getJob(String id) {
        return file.getJob(id);
    }

    @Override
    public List<JobRecord> listJobs(String batchId) {
        return file.listJobs(batchId);
    }

    @Override
    public void putNote(NoteRecord note) {
        file.putNote(note);
        mirror(() -> h2.putNote(note));
    }

    @Override
    public List<NoteRecord> getNotes(String jobId) {
        return file.getNotes(jobId);
    }

    @Override
    public void deleteNote(String jobId, String key, String zone) {
        file.deleteNote(jobId, key, zone);
        mirror(() -> h2.deleteNote(jobId, key, zone));
    }

    @Override
    public boolean deleteJob(String jobId) {
        boolean existed = file.deleteJob(jobId);
        if (existed) mirror(() -> h2.deleteJob(jobId));
        return existed;
    }

    @Override
    public boolean deleteBatch(String batchId) {
        boolean existed = file.deleteBatch(batchId);
        if (existed) mirror(() -> h2.deleteBatch(batchId));
        return existed;
    }

    @Override
    public java.util.Set<String> notedKeys(String jobId) {
        return file.notedKeys(jobId);
    }

    @Override
    public boolean dbAvailable() {
        return h2 != null;
    }

    @Override
    public void close() {
        H2JobStore db = h2;
        if (db != null) db.close();
        file.close();
    }
}
