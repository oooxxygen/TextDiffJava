package com.textdiff.config;

import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** config.ini 加载 + 环境变量覆盖；优先级 env > ini > default。对等 Python config.py。
 *  首次启动（ini 不存在）自动生成带注释的默认配置文件。
 *  可热更新：界面保存（AI 接入 / 并发度）直接改内存值并写回 ini（保留注释），无需重启。 */
public final class AppConfig {
    private volatile ServerConfig server;
    private volatile EngineConfig engine;
    private volatile StoreConfig store;
    private volatile AiConfig ai;
    private final Path iniPath;

    public AppConfig(ServerConfig server, EngineConfig engine, StoreConfig store, AiConfig ai) {
        this(server, engine, store, ai, null);
    }

    public AppConfig(ServerConfig server, EngineConfig engine, StoreConfig store, AiConfig ai, Path iniPath) {
        this.server = server;
        this.engine = engine;
        this.store = store;
        this.ai = ai;
        this.iniPath = iniPath;
    }

    public ServerConfig server() { return server; }
    public EngineConfig engine() { return engine; }
    public StoreConfig store()   { return store; }
    public AiConfig ai()         { return ai; }

    /** AI 配置热更新：改内存值（此后 AiClient/AiAnalyzer 立即按新值调用）并写回 ini [ai] 段。 */
    public void updateAi(AiConfig next) {
        this.ai = next;
        if (iniPath != null) {
            setIniValue(iniPath, "ai", "enabled", String.valueOf(next.enabled()));
            setIniValue(iniPath, "ai", "base_url", next.baseUrl() == null ? "" : next.baseUrl());
            setIniValue(iniPath, "ai", "api_key", next.apiKey() == null ? "" : next.apiKey());
            setIniValue(iniPath, "ai", "model", next.model() == null ? "" : next.model());
            setIniValue(iniPath, "ai", "protocol", next.protocol() == null ? "openai" : next.protocol());
            setIniValue(iniPath, "ai", "timeout", String.valueOf(next.timeoutSeconds()));
            setIniValue(iniPath, "ai", "max-concurrency", String.valueOf(next.maxConcurrency()));
        }
    }

    /** 对比线程池上限热更新：改内存值并写回 ini [engine] 段（各线程池由调用方同步 resize）。 */
    public void updateEngineMaxThreads(int maxThreads) {
        this.engine = new EngineConfig(maxThreads, engine.maxInMemoryBytes());
        if (iniPath != null) {
            setIniValue(iniPath, "engine", "max-threads", String.valueOf(maxThreads));
        }
    }

    public static AppConfig load(Path iniPath, Function<String, String> env) {
        ensureDefaultIni(iniPath);
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
                pick(env.apply("TEXTDIFF_AI_PROTOCOL"), aiSec.get("protocol"), "openai"),
                pick(env.apply("TEXTDIFF_AI_BASE_URL"), aiSec.get("base_url"), ""),
                pick(env.apply("TEXTDIFF_AI_API_KEY"), aiSec.get("api_key"), ""),
                pick(env.apply("TEXTDIFF_AI_MODEL"), aiSec.get("model"), ""),
                Integer.parseInt(pick(env.apply("TEXTDIFF_AI_TIMEOUT"), aiSec.get("timeout"), "60")),
                Long.parseLong(pick(env.apply("TEXTDIFF_AI_MAX_PROMPT_CHARS"), aiSec.get("max-prompt-chars"),
                        "120000")),
                Integer.parseInt(pick(env.apply("TEXTDIFF_AI_RETRIES"), aiSec.get("retries"), "3")),
                Long.parseLong(pick(env.apply("TEXTDIFF_AI_RETRY_BACKOFF_MS"), aiSec.get("retry-backoff-ms"),
                        "2000")),
                Integer.parseInt(pick(env.apply("TEXTDIFF_AI_MAX_CONCURRENCY"), aiSec.get("max-concurrency"),
                        "2")));

        return new AppConfig(new ServerConfig(host, port),
                new EngineConfig(maxThreads, maxInMemoryBytes),
                new StoreConfig(storeEnabled), ai, iniPath);
    }

    /**
     * AI 归纳分析（OpenAI 兼容 /chat/completions 或 Anthropic 原生 /v1/messages）。
     * maxPromptChars：提示词字符预算（超限自动压缩重渲，适配 ≤256K 小上下文窗口）；
     * retries / retryBackoffMs：弱网容错重试次数与退避基数（指数退避）；
     * maxConcurrency：批量任务并行时同时调用 AI 的最大并发数（保护调用方服务）。
     */
    public record AiConfig(boolean enabled, String protocol, String baseUrl, String apiKey, String model,
                           int timeoutSeconds, long maxPromptChars, int retries, long retryBackoffMs,
                           int maxConcurrency) {
        /** 兼容旧 5 参构造（测试/外部调用）。 */
        public AiConfig(boolean enabled, String baseUrl, String apiKey, String model, int timeoutSeconds) {
            this(enabled, "openai", baseUrl, apiKey, model, timeoutSeconds, 120000, 3, 2000, 2);
        }

        /** 兼容旧 8 参构造（未引入并发度前的全参形式）。 */
        public AiConfig(boolean enabled, String baseUrl, String apiKey, String model, int timeoutSeconds,
                        long maxPromptChars, int retries, long retryBackoffMs) {
            this(enabled, "openai", baseUrl, apiKey, model, timeoutSeconds, maxPromptChars,
                    retries, retryBackoffMs, 2);
        }

        /** 兼容旧 9 参构造（未引入 protocol 前的全参形式）。 */
        public AiConfig(boolean enabled, String baseUrl, String apiKey, String model, int timeoutSeconds,
                        long maxPromptChars, int retries, long retryBackoffMs, int maxConcurrency) {
            this(enabled, "openai", baseUrl, apiKey, model, timeoutSeconds, maxPromptChars,
                    retries, retryBackoffMs, maxConcurrency);
        }

        public boolean usable() {
            return enabled && baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
        }
    }

    /** 就地更新 ini 键值（保留注释与键序；键不存在时插入到所在段末尾）。 */
    static void setIniValue(Path path, String section, String key, String value) {
        try {
            List<String> lines = Files.isRegularFile(path)
                    ? Files.readAllLines(path) : new ArrayList<>(List.of("[" + section + "]"));
            String cur = "";
            int secStart = -1, keyLine = -1, secEnd = lines.size();
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    if (cur.equals(section)) { secEnd = i; break; }
                    cur = t.substring(1, t.length() - 1).trim();
                    if (cur.equals(section)) { secStart = i; secEnd = lines.size(); }
                } else if (cur.equals(section) && secStart >= 0) {
                    int eq = t.indexOf('=');
                    if (eq > 0 && t.substring(0, eq).trim().equals(key)) { keyLine = i; break; }
                }
            }
            if (secStart < 0) { // 段不存在：追加到文件尾
                lines.add("");
                lines.add("[" + section + "]");
                lines.add(key + " = " + value);
            } else if (keyLine >= 0) {
                lines.set(keyLine, key + " = " + value);
            } else {
                lines.add(secEnd, key + " = " + value);
            }
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            Files.write(path, lines);
        } catch (Exception e) {
            System.err.println("[config] 写回配置失败（内存值已更新）: " + e.getMessage());
        }
    }

    private static String pick(String env, String ini, String def) {
        if (env != null && !env.isBlank()) return env.trim();
        if (ini != null && !ini.isBlank()) return ini.trim();
        return def;
    }

    /** 首次启动生成默认 ini（填充内置默认值与注释，供用户自行修改）；已有文件绝不覆盖。 */
    private static void ensureDefaultIni(Path path) {
        if (path == null || Files.isRegularFile(path)) return;
        EngineConfig defEng = EngineConfig.defaults();
        String tpl = """
                # TextDiff 配置文件（界面「设置」页保存即改这里并即时生效；也可直接编辑本文件，保存后重启生效）

                [server]
                # 监听地址与端口
                host = 0.0.0.0
                port = 8080

                [engine]
                # 后台对比线程池上限 / 单作业内存态最大字节数（超出自动落盘）
                max-threads = %d
                in-memory-bytes = %d

                [store]
                # H2 双写镜像开关（false = 纯文件模式，查询仍可用但无数据库表）
                enabled = true

                [ai]
                # AI 归纳分析（协议：openai = OpenAI 兼容 /chat/completions；anthropic = Anthropic 原生 /v1/messages）
                enabled = false
                protocol = openai
                base_url =
                api_key =
                model =
                # 单次请求超时（秒）；生成完整分析报告建议 ≥300
                timeout = 60
                # 提示词字符预算：超限自动压缩重渲，适配小上下文窗口（建议 ≤ 上下文窗口 token 数 × 2）
                max-prompt-chars = 120000
                # 弱网容错：瞬时错误（超时/5xx/429）重试次数与退避基数（指数退避）
                retries = 3
                retry-backoff-ms = 2000
                # AI 任务并发上限：批量作业并行完成时同时调用 AI 的最大并发数（防止冲击服务方）
                max-concurrency = 2
                """.formatted(defEng.maxThreads(), defEng.maxInMemoryBytes());
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, tpl);
            System.out.println("[config] 已生成默认配置文件: " + path.toAbsolutePath()
                    + "（请按需修改后重启生效）");
        } catch (Exception e) {
            System.err.println("[config] 默认配置文件生成失败（按内置默认值继续运行）: " + e.getMessage());
        }
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
