package com.textdiff.config;

/** 持久化配置：JSONL 文件始终写入（事实来源）；enabled=false 时 H2 镜像关闭，纯文件运行。 */
public record StoreConfig(boolean enabled) {

    public static StoreConfig defaults() {
        return new StoreConfig(true);
    }
}
