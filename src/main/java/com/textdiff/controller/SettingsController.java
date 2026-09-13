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
        // M5 接入 [ai] 配置节后返回真实 enabled/model
        return Map.of("enabled", false, "model", "");
    }

    @GetMapping("/settings/ai")
    public Map<String, Object> aiSettings() {
        return Map.of("protocol", "openai", "base_url", "", "model", "",
                "timeout", 60, "enabled", false, "api_key_set", false);
    }

    @PostMapping("/settings/ai")
    public Map<String, Object> saveAiSettings(@RequestBody Map<String, Object> body) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "AI 设置将在 M5 里程碑启用");
    }

    @PostMapping("/settings/ai/test")
    public Map<String, Object> testAi() {
        return Map.of("ok", false, "model", "", "error", "AI 未启用（M5 里程碑启用）");
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
}
