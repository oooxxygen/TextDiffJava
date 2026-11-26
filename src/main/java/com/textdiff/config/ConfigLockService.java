package com.textdiff.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * 配置锁闭（🔒 锁闭配置页面）：锁闭期间所有会写本地配置的接口被 {@link ConfigWriteGuard}
 * 拒绝（423），前端仅驻留本次页面修改；解除/开启锁闭需输入管理密钥，与
 * {@code configs/.admin_key}（base64 编码存储）解码后的字面量一致才允许变更。
 * 锁闭状态持久化在 {@code configs/.lock_state.json}（重启保持）。
 * 密钥文件缺失时按出厂默认密钥生成；存在则以文件内容为准（可手工更换）。
 */
public class ConfigLockService {

    /** 出厂默认管理密钥（仅用于首次生成密钥文件；之后以文件为准）。 */
    static final String DEFAULT_ADMIN_KEY = "330e2dcc7cce4a3da57d7295fb189daf";

    private final Path keyFile;
    private final Path stateFile;
    private volatile boolean locked;

    public ConfigLockService(AppPaths paths) {
        this(paths.configsDir().resolve(".admin_key"), paths.configsDir().resolve(".lock_state.json"));
    }

    ConfigLockService(Path keyFile, Path stateFile) {
        this.keyFile = keyFile;
        this.stateFile = stateFile;
    }

    /** 启动初始化：确保密钥文件存在并加载锁闭状态（不阻断启动）。 */
    public void init() {
        try {
            if (!Files.isRegularFile(keyFile)) {
                writeAtomically(keyFile, Base64.getEncoder()
                        .encodeToString(DEFAULT_ADMIN_KEY.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            System.err.println("[config-lock] 密钥文件初始化失败（不阻断启动）: " + e.getMessage());
        }
        try {
            if (Files.isRegularFile(stateFile)) {
                String json = Files.readString(stateFile, StandardCharsets.UTF_8).strip();
                locked = json.contains("\"locked\" : true") || json.contains("\"locked\":true");
            }
        } catch (IOException e) {
            System.err.println("[config-lock] 锁闭状态读取失败（按未锁闭处理）: " + e.getMessage());
        }
    }

    public boolean isLocked() {
        return locked;
    }

    /** 管理密钥校验：base64 解码后与输入字面量一致。 */
    public boolean verifyKey(String input) {
        if (input == null || input.isBlank()) return false;
        try {
            String stored = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
            String decoded = new String(Base64.getDecoder().decode(stored), StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(
                    decoded.getBytes(StandardCharsets.UTF_8),
                    input.strip().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 开启/解除锁闭（调用前须先 {@link #verifyKey} 校验通过），状态原子落盘。 */
    public void setLocked(boolean value) {
        locked = value;
        try {
            writeAtomically(stateFile, "{\"locked\" : " + value + ", \"updated_at\" : "
                    + System.currentTimeMillis() / 1000 + "}");
        } catch (IOException e) {
            throw new UncheckedIOException("锁闭状态写入失败", e);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
