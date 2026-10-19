package com.textdiff.controller;

import com.textdiff.config.AppConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    private final AppConfig config;

    public HealthController(AppConfig config) {
        this.config = config;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        String v = HealthController.class.getPackage() == null
                ? null : HealthController.class.getPackage().getImplementationVersion();
        if (v != null) m.put("version", v);
        // 实际最大堆：验证 config.ini [jvm] jvm_opts 是否被启动脚本注入生效
        m.put("max_memory_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        m.put("jvm_opts_ini", config.jvmOpts() == null ? "" : config.jvmOpts());
        return m;
    }
}
