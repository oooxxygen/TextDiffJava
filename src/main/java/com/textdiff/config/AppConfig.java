package com.textdiff.config;

import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** config.ini 加载 + 环境变量覆盖；优先级 env > ini > default。对等 Python config.py。 */
public record AppConfig(ServerConfig server, EngineConfig engine, StoreConfig store) {

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

        return new AppConfig(new ServerConfig(host, port),
                new EngineConfig(maxThreads, maxInMemoryBytes),
                new StoreConfig(storeEnabled));
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
