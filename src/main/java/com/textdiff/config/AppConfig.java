package com.textdiff.config;

import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** config.ini 加载 + 环境变量覆盖；优先级 env > ini > default。对等 Python config.py。 */
public record AppConfig(ServerConfig server) {

    public static AppConfig load(Path iniPath, Function<String, String> env) {
        Map<String, Map<String, String>> ini = parseIni(iniPath);
        Map<String, String> srv = ini.getOrDefault("server", Map.of());

        String host = pick(env.apply("TEXTDIFF_HOST"), srv.get("host"), "0.0.0.0");
        int port = Integer.parseInt(pick(env.apply("TEXTDIFF_PORT"), srv.get("port"), "8080"));
        return new AppConfig(new ServerConfig(host, port));
    }

    private static String pick(String env, String ini, String def) {
        if (env != null && !env.isBlank()) return env.trim();
        if (ini != null && !ini.isBlank()) return ini.trim();
        return def;
    }

    private static Map<String, Map<String, String>> parseIni(Path path) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (path == null || !Files.isRegularFile(path)) return out;
        String section = "";
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    out.computeIfAbsent(section, k -> new HashMap<>());
                } else {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        out.computeIfAbsent(section, k -> new HashMap<>())
                           .put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("读取配置失败: " + path, e);
        }
        return out;
    }
}
