package com.textdiff.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 拆分规则配置存储：{@code store/split_configs.jsonl} 事实来源。
 * 配置量小（手工维护的规则集），纯 JSONL 每次全量原子重写，无 H2 镜像。
 */
public final class SplitConfigStore implements AutoCloseable {
    private final Path file;
    private final LinkedHashMap<String, SplitConfig> byNick = new LinkedHashMap<>();

    public SplitConfigStore(Path storeDir) {
        this.file = storeDir.resolve("split_configs.jsonl");
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try (var br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    SplitConfig c = Json.read(line, SplitConfig.class);
                    if (!c.nickname().isEmpty()) byNick.put(c.nickname(), c);
                } catch (RuntimeException e) {
                    System.err.println("[split-config] 坏行跳过: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[split-config] 读取失败: " + e);
        }
    }

    /** 全部配置（落库顺序）。 */
    public synchronized ArrayList<SplitConfig> all() {
        return new ArrayList<>(byNick.values());
    }

    public synchronized SplitConfig get(String nickname) {
        return nickname == null ? null : byNick.get(nickname.strip());
    }

    public synchronized SplitConfig save(SplitConfig c) {
        SplitConfig fixed = new SplitConfig(c.nickname(), c.delimiter(), c.encoding(), c.trailerPrefix(),
                c.rules(), c.sourceFile() == null ? "" : c.sourceFile(),
                System.currentTimeMillis() / 1000);
        byNick.put(fixed.nickname(), fixed);
        persist();
        return fixed;
    }

    public synchronized boolean delete(String nickname) {
        boolean removed = byNick.remove(nickname == null ? "" : nickname.strip()) != null;
        if (removed) persist();
        return removed;
    }

    /** 原子重写：临时文件 + ATOMIC_MOVE（对等其他 store 的全量重写语义）。 */
    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (SplitConfig c : byNick.values()) {
                    bw.write(Json.write(c));
                    bw.write("\n");
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("拆分规则配置持久化失败: " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        // JSONL 即时落盘，无后台资源
    }
}
