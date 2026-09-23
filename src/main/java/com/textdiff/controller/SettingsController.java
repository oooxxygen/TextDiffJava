package com.textdiff.controller;

import com.textdiff.config.AppConfig;
import com.textdiff.task.JobManager;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 设置端点：AI 接入 / 对比并发度——界面保存即时生效（改内存 + 写回 config.ini），无需重启。 */
@RestController
@RequestMapping("/api")
public class SettingsController {
    private final AppConfig cfg;
    private final JobManager jobs;
    private final com.textdiff.task.TaskManager taskManager;
    private final com.textdiff.report.ReportCompareService reportService;
    private final com.textdiff.custom.CustomCompareService customService;

    public SettingsController(AppConfig cfg, JobManager jobs,
                              com.textdiff.task.TaskManager taskManager,
                              com.textdiff.report.ReportCompareService reportService,
                              com.textdiff.custom.CustomCompareService customService) {
        this.cfg = cfg;
        this.jobs = jobs;
        this.taskManager = taskManager;
        this.reportService = reportService;
        this.customService = customService;
    }

    @GetMapping("/ai/status")
    public Map<String, Object> aiStatus() {
        return Map.of("enabled", cfg.ai().usable(), "model", cfg.ai().model());
    }

    @GetMapping("/settings/ai")
    public Map<String, Object> aiSettings() {
        return Map.of("protocol", nvl(cfg.ai().protocol()),
                "base_url", nvl(cfg.ai().baseUrl()),
                "model", nvl(cfg.ai().model()),
                "timeout", cfg.ai().timeoutSeconds(),
                "max_concurrency", cfg.ai().maxConcurrency(),
                "enabled", cfg.ai().enabled(),
                "api_key_set", cfg.ai().apiKey() != null && !cfg.ai().apiKey().isBlank());
    }

    /** AI 接入保存：立即生效（此后所有 AI 调用按新配置执行），同时写回 config.ini [ai] 段。 */
    @PostMapping("/settings/ai")
    public Map<String, Object> saveAiSettings(@RequestBody Map<String, Object> body) {
        AppConfig.AiConfig cur = cfg.ai();
        boolean enabled = body.get("enabled") != null
                ? Boolean.parseBoolean(String.valueOf(body.get("enabled"))) : cur.enabled();
        String protocol = strOr(body.get("protocol"), cur.protocol());
        String baseUrl = strOr(body.get("base_url"), cur.baseUrl());
        String model = strOr(body.get("model"), cur.model());
        // api_key 留空 = 保留现有密钥
        String apiKey = body.get("api_key") != null && !String.valueOf(body.get("api_key")).isBlank()
                ? String.valueOf(body.get("api_key")).strip() : cur.apiKey();
        int timeout = intOr(body.get("timeout"), cur.timeoutSeconds());
        int maxConcurrency = intOr(body.get("max_concurrency"), cur.maxConcurrency());

        if (enabled && (baseUrl.isBlank() || model.isBlank())) {
            throw new IllegalArgumentException("启用 AI 需要填写 Base URL 与模型名");
        }
        if (!"openai".equalsIgnoreCase(protocol) && !"anthropic".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("协议仅支持 openai / anthropic");
        }
        AppConfig.AiConfig next = new AppConfig.AiConfig(enabled, protocol.toLowerCase(), baseUrl, apiKey, model,
                Math.max(5, timeout), cur.maxPromptChars(), cur.retries(), cur.retryBackoffMs(),
                Math.max(1, maxConcurrency));
        cfg.updateAi(next);
        // TaskManager 的 AI 并发信号量按新并发度重调
        taskManager.resizeAiConcurrency(Math.max(1, maxConcurrency));
        return Map.of("ok", true, "enabled", enabled, "api_key_set", apiKey != null && !apiKey.isBlank(),
                "model", nvl(model), "base_url", nvl(baseUrl));
    }

    @PostMapping("/settings/ai/test")
    public Map<String, Object> testAi() {
        if (!cfg.ai().usable()) {
            return Map.of("ok", false, "model", nvl(cfg.ai().model()),
                    "error", "AI 未启用：请填写 Base URL 与模型名后保存");
        }
        try {
            new com.textdiff.ai.AiClient(cfg.ai()).complete("请回复 OK 两个字母。");
            return Map.of("ok", true, "model", nvl(cfg.ai().model()), "error", "");
        } catch (RuntimeException e) {
            return Map.of("ok", false, "model", nvl(cfg.ai().model()), "error",
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @GetMapping("/settings/runtime")
    public Map<String, Object> runtime() {
        return Map.of("max_workers", cfg.engine().maxThreads());
    }

    /** 对比并发度保存：三个对比线程池立即 resize，同时写回 config.ini [engine] 段。 */
    @PostMapping("/settings/runtime")
    public Map<String, Object> saveRuntime(@RequestBody Map<String, Object> body) {
        int maxWorkers = intOr(body.get("max_workers"), cfg.engine().maxThreads());
        if (maxWorkers < 1 || maxWorkers > 64) {
            throw new IllegalArgumentException("并发度需在 1–64 之间");
        }
        cfg.updateEngineMaxThreads(maxWorkers);
        jobs.resizeEnginePool(maxWorkers);
        reportService.resizePool(maxWorkers);
        customService.resizePool(maxWorkers);
        return Map.of("ok", true, "max_workers", maxWorkers);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String strOr(Object o, String def) {
        String s = o == null ? null : String.valueOf(o);
        return s == null || s.isBlank() ? (def == null ? "" : def) : s.strip();
    }

    private static int intOr(Object o, int def) {
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
