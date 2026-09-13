package com.textdiff.config;

/** 对比执行引擎配置。maxThreads 控制后台对比线程池上限（需求 4.1）。 */
public record EngineConfig(int maxThreads, long maxInMemoryBytes) {

    public static final long DEFAULT_MAX_IN_MEMORY_BYTES = 1024L * 1024 * 1024;

    public static EngineConfig defaults() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new EngineConfig(Math.max(4, cores), DEFAULT_MAX_IN_MEMORY_BYTES);
    }
}
