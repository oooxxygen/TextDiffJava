package com.textdiff.config;

/** 对比执行引擎配置。maxThreads 控制后台对比线程池上限（需求 4.1）；
 *  extractDir / preExtractCount 为压缩文件预解压的临时目录与预解压池上限（均可热更新）。 */
public record EngineConfig(int maxThreads, long maxInMemoryBytes,
                           String extractDir, int preExtractCount) {

    public static final long DEFAULT_MAX_IN_MEMORY_BYTES = 1024L * 1024 * 1024;
    public static final String DEFAULT_EXTRACT_DIR = "./temp";
    public static final int DEFAULT_PRE_EXTRACT_COUNT = 8;

    public EngineConfig {
        if (extractDir == null || extractDir.isBlank()) extractDir = DEFAULT_EXTRACT_DIR;
        if (preExtractCount < 1) preExtractCount = 1;
    }

    public static EngineConfig defaults() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new EngineConfig(Math.max(4, cores), DEFAULT_MAX_IN_MEMORY_BYTES,
                DEFAULT_EXTRACT_DIR, DEFAULT_PRE_EXTRACT_COUNT);
    }
}
