package com.textdiff.controller;

import com.textdiff.config.AppConfig;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 设置端点：AI 状态（M5 启用真实调用）、运行时线程数（只读展示 + 重启生效）。 */
@RestController
@RequestMapping("/api")
public class SettingsController {
    private final AppConfig cfg;

    public SettingsController(AppConfig cfg) {
        this.cfg = cfg;
    }

    @GetMapping("/ai/status")
    public Map<String, Object> aiStatus() {
        return Map.of("enabled", cfg.ai().usable(), "model", cfg.ai().model());
    }

    @GetMapping("/settings/ai")
    public Map<String, Object> aiSettings() {
        return Map.of("protocol", "openai",
                "base_url", nvl(cfg.ai().baseUrl()),
                "model", nvl(cfg.ai().model()),
                "timeout", cfg.ai().timeoutSeconds(),
                "enabled", cfg.ai().enabled(),
                "api_key_set", cfg.ai().apiKey() != null && !cfg.ai().apiKey().isBlank());
    }

    @PostMapping("/settings/ai")
    public Map<String, Object> saveAiSettings(@RequestBody Map<String, Object> body) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "AI 设置请修改 config.ini [ai] 段（enabled/base_url/api_key/model/timeout）后重启");
    }

    @PostMapping("/settings/ai/test")
    public Map<String, Object> testAi() {
        if (!cfg.ai().usable()) {
            return Map.of("ok", false, "model", nvl(cfg.ai().model()),
                    "error", "AI 未启用：请配置 config.ini [ai] enabled/base_url/model");
        }
        try {
            String reply = new com.textdiff.ai.AiClient(cfg.ai()).complete("请回复 OK 两个字母。");
            return Map.of("ok", true, "model", cfg.ai().model(), "error", "");
        } catch (RuntimeException e) {
            return Map.of("ok", false, "model", cfg.ai().model(), "error",
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @GetMapping("/settings/runtime")
    public Map<String, Object> runtime() {
        return Map.of("max_workers", cfg.engine().maxThreads());
    }

    @PostMapping("/settings/runtime")
    public Map<String, Object> saveRuntime(@RequestBody Map<String, Object> body) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "线程数请修改 config.ini [engine] max-threads 后重启（当前 " + cfg.engine().maxThreads() + "）");
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
