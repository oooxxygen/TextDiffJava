package com.textdiff.config;

import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 配置锁闭写保护：锁闭期间拦截所有会写本地配置的接口（写方法 POST/PUT/DELETE/PATCH），
 * 直接回 423 {"detail": ...}（不入 Controller，保证任何路径都无法落盘）。
 * 查询类接口与锁闭开关本身（/api/config-lock）不受影响。
 */
public class ConfigWriteGuard implements HandlerInterceptor {

    private final com.textdiff.config.ConfigLockService lock;

    public ConfigWriteGuard(com.textdiff.config.ConfigLockService lock) {
        this.lock = lock;
    }

    /** 会修改持久化配置的路径前缀（设置 ini / 对比配置 conf / 各类配置存储 / 分组 / 拆分规则）。 */
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "/api/settings/ai",          // AI 设置（/api/settings/ai/test 下方单独豁免）
            "/api/settings/runtime",
            "/api/settings/jvm",
            "/api/settings/extract",
            "/api/settings/tasks",
            "/api/configs",              // 配置保存/删除/导入（含 /configs/import-*）
            "/api/config-files",         // 配置文件新建/重命名/删除
            "/api/charset-map/",
            "/api/ctis-dyn/import",
            "/api/discard-fields",
            "/api/compare-groups",
            "/api/split-configs",
            "/api/textline-configs"
    );

    /** 锁闭下仍允许的写请求（不落配置盘）。 */
    private static final List<String> EXEMPT = List.of(
            "/api/config-lock",          // 锁闭开关本身（否则无法解锁）
            "/api/settings/ai/test"      // 连接测试，不写盘
    );

    static boolean isBlocked(String method, String path) {
        if (path == null) return false;
        String m = method == null ? "" : method.toUpperCase();
        if (!m.equals("POST") && !m.equals("PUT") && !m.equals("DELETE") && !m.equals("PATCH")) return false;
        String p = path.split("\\?")[0];
        for (String ex : EXEMPT) {
            if (p.equals(ex) || p.startsWith(ex + "/")) return false;
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (p.equals(prefix) || p.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    @Override
    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                             jakarta.servlet.http.HttpServletResponse response, Object handler)
            throws Exception {
        if (!lock.isLocked()) return true;
        if (!isBlocked(request.getMethod(), request.getRequestURI())) return true;
        response.setStatus(423);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        try (PrintWriter w = response.getWriter()) {
            w.write("{\"detail\":\"配置已锁闭：本次修改仅驻留当前页面，不会写入任何配置文件；"
                    + "如需修改请先在设置页解除锁闭\"}");
        }
        return false;
    }
}
