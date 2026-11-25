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
 * 普通文本比对命名配置存储：{@code store/textline_configs.jsonl} 事实来源。
 * 配置量小（手工维护），纯 JSONL 每次全量原子重写，无 H2 镜像（与 SplitConfigStore 同构）。
 */
public final class TextLineConfigStore implements AutoCloseable {
    private final Path file;
    private final LinkedHashMap<String, TextLineSavedConfig> byNick = new LinkedHashMap<>();

    public TextLineConfigStore(Path storeDir) {
        this.file = storeDir.resolve("textline_configs.jsonl");
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try (var br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    TextLineSavedConfig c = Json.read(line, TextLineSavedConfig.class);
                    if (!c.nickname().isEmpty()) byNick.put(c.nickname(), c);
                } catch (RuntimeException e) {
                    System.err.println("[textline-config] 坏行跳过: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[textline-config] 读取失败: " + e);
        }
    }

    /** 全部配置（落库顺序）。 */
    public synchronized ArrayList<TextLineSavedConfig> all() {
        return new ArrayList<>(byNick.values());
    }

    public synchronized TextLineSavedConfig get(String nickname) {
        return nickname == null ? null : byNick.get(nickname.strip());
    }

    public synchronized TextLineSavedConfig save(TextLineSavedConfig c) {
        TextLineSavedConfig fixed = new TextLineSavedConfig(c.nickname(), c.groupName(), c.fileRegex(),
                c.keyPositions(), c.encodingA(), c.encodingB(), c.sourceA(), c.sourceB(),
                c.label(), System.currentTimeMillis() / 1000);
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
                for (TextLineSavedConfig c : byNick.values()) {
                    bw.write(Json.write(c));
                    bw.write("\n");
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("普通文本比对配置持久化失败: " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        // JSONL 即时落盘，无后台资源
    }
}
