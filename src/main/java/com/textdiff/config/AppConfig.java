package com.textdiff.config;

import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** config.ini 加载 + 环境变量覆盖；优先级 env > ini > default。对等 Python config.py。 */
public record AppConfig(ServerConfig server, EngineConfig engine, StoreConfig store, AiConfig ai) {

    public static AppConfig load(Path iniPath, Function<String, String> env) {
        Map<String, Map<String, String>> ini = parseIni(iniPath);
        Map<String, String> srv = ini.getOrDefault("server", Map.of());
        Map<String, String> eng = ini.getOrDefault("engine", Map.of());
        Map<String, String> sto = ini.getOrDefault("store", Map.of());

        String host = pick(env.apply("TEXTDIFF_HOST"), srv.get("host"), "0.0.0.0");
        int port = Integer.parseInt(pick(env.apply("TEXTDIFF_PORT"), srv.get("port"), "8080"));

        EngineConfig defEng = EngineConfig.defaults();
        int maxThreads = Integer.parseInt(
                pick(env.apply("TEXTDIFF_MAX_THREADS"), eng.get("max-threads"),
                        String.valueOf(defEng.maxThreads())));
        long maxInMemoryBytes = Long.parseLong(
                pick(env.apply("TEXTDIFF_MAX_IN_MEMORY_BYTES"), eng.get("in-memory-bytes"),
                        String.valueOf(defEng.maxInMemoryBytes())));

        boolean storeEnabled = Boolean.parseBoolean(
                pick(env.apply("TEXTDIFF_STORE_ENABLED"), sto.get("enabled"), "true"));

        Map<String, String> aiSec = ini.getOrDefault("ai", Map.of());
        AiConfig ai = new AiConfig(
                Boolean.parseBoolean(pick(env.apply("TEXTDIFF_AI_ENABLED"), aiSec.get("enabled"), "false")),
                pick(env.apply("TEXTDIFF_AI_BASE_URL"), aiSec.get("base_url"), ""),
                pick(env.apply("TEXTDIFF_AI_API_KEY"), aiSec.get("api_key"), ""),
                pick(env.apply("TEXTDIFF_AI_MODEL"), aiSec.get("model"), ""),
                Integer.parseInt(pick(env.apply("TEXTDIFF_AI_TIMEOUT"), aiSec.get("timeout"), "60")));

        return new AppConfig(new ServerConfig(host, port),
                new EngineConfig(maxThreads, maxInMemoryBytes),
                new StoreConfig(storeEnabled), ai);
    }

    /** AI 归纳分析（OpenAI 兼容 /chat/completions）。 */
    public record AiConfig(boolean enabled, String baseUrl, String apiKey, String model, int timeoutSeconds) {
        public boolean usable() {
            return enabled && baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
        }
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
