package com.textdiff.store;

import java.util.List;

/** 任务元数据/评议持久化 SPI。实现需线程安全。 */
public interface JobStore extends AutoCloseable {

    void saveBatch(BatchRecord batch);

    BatchRecord getBatch(String id);

    List<BatchRecord> listBatches();

    void saveJob(JobRecord job);

    JobRecord getJob(String id);

    List<JobRecord> listJobs(String batchId);

    void putNote(NoteRecord note);

    List<NoteRecord> getNotes(String jobId);

    /** H2 镜像是否处于可用状态（仅状态展示用；文件层始终是事实来源）。 */
    boolean dbAvailable();

    @Override
    void close();
}
