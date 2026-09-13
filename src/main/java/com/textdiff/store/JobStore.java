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

    /** 删除评议（note 为空串的语义删除在控制器层转换为该方法）。 */
    void deleteNote(String jobId, String key, String zone);

    /** 删除单作业（元数据与结果目录由调用方处理）。返回是否存在。 */
    boolean deleteJob(String jobId);

    /** 级联删除批次及其全部作业。返回是否存在。 */
    boolean deleteBatch(String batchId);

    /** 指定作业拥有评议的键集合（结果分页 note 过滤用）。 */
    java.util.Set<String> notedKeys(String jobId);

    /** H2 镜像是否处于可用状态（仅状态展示用；文件层始终是事实来源）。 */
    boolean dbAvailable();

    @Override
    void close();
}
