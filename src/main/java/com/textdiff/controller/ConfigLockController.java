package com.textdiff.controller;

import com.textdiff.config.ConfigLockService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 配置锁闭开关（🔒）：查询锁闭状态；开启/解除锁闭需管理密钥
 * （与 configs/.admin_key 解码后的字面量一致，见 ConfigLockService）。
 */
@RestController
@RequestMapping("/api")
public class ConfigLockController {

    private final ConfigLockService lock;

    public ConfigLockController(ConfigLockService lock) {
        this.lock = lock;
    }

    /** 当前锁闭状态（不含任何密钥信息）。 */
    @GetMapping("/config-lock")
    public Map<String, Object> status() {
        return Map.of("locked", lock.isLocked());
    }

    /**
     * 开启/解除锁闭：body {action: "lock"|"unlock", key: 管理密钥}。
     * 密钥与本地存储字面量不一致 → 400。
     */
    @PostMapping("/config-lock")
    public Map<String, Object> change(@RequestBody Map<String, Object> body) {
        String action = body.get("action") == null ? "" : body.get("action").toString().strip();
        String key = body.get("key") == null ? "" : body.get("key").toString();
        if (!"lock".equals(action) && !"unlock".equals(action)) {
            throw new IllegalArgumentException("action 仅支持 lock / unlock");
        }
        if (!lock.verifyKey(key)) {
            throw new IllegalArgumentException("管理密钥不正确");
        }
        lock.setLocked("lock".equals(action));
        return Map.of("ok", true, "locked", lock.isLocked());
    }
}
