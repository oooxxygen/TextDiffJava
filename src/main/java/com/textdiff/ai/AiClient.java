package com.textdiff.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.textdiff.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 /chat/completions 调用（java.net.http，零新依赖）。
 *
 * 健壮性（弱网/小上下文窗口兼容）：
 * - SSE 流式接收（stream=true）：生成报告常需数分钟，流式下链路活性由「块间空闲超时 + 总时限」
 *   双看门狗判定，避免单条阻塞长读被代理掐线后干等到超时；服务端不支持流式时自动回退非流式。
 * - 瞬时错误（超时/连接失败/HTTP 429/5xx）按指数退避重试（retries 次）。
 * - 上下文超限（HTTP 400 且报文含 context/length 特征词）抛 {@link ContextTooLongException}，
 *   由上层压缩提示词后重试，不做无意义原样重发。
 */
public final class AiClient {
    /** 模型上下文窗口装不下 prompt（或其派生特征词），上层应压缩后重试。 */
    public static final class ContextTooLongException extends IllegalStateException {
        public ContextTooLongException(String message) {
            super(message);
        }
    }

    /** SSE 行队列哨兵：正常结束 / 泵线程异常。 */
    private static final String SSE_DONE = "\u0000done";
    private static final String SSE_ERROR = "\u0000err:";

    private static final java.util.regex.Pattern CONTEXT_HINTS = java.util.regex.Pattern.compile(
            "context|maximum.{0,20}(length|token)|too long|max_tokens|上下文|超长|长度",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final AppConfig.AiConfig cfg;
    private final HttpClient client;
    private final ExecutorService pump = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-sse-pump");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean streamSupported = true; // 服务端拒绝 stream 参数时置 false

    public AiClient(AppConfig.AiConfig cfg) {
        this.cfg = cfg;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(10, cfg.timeoutSeconds())))
                .build();
    }

    public boolean usable() {
        return cfg.usable();
    }

    public String model() {
        return cfg.model();
    }

    /** 发送 prompt，返回模型文本响应。瞬时失败重试；上下文超限抛 ContextTooLongException。 */
    public String complete(String prompt) {
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "temperature", 0.2,
                "messages", List.of(Map.of("role", "user", "content", prompt)));
        int attempts = Math.max(0, cfg.retries()) + 1;
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (streamSupported) {
                    try {
                        return streamComplete(body);
                    } catch (StreamUnsupportedException e) {
                        streamSupported = false; // 本次尝试内立即回退非流式
                    }
                }
                return blockingComplete(body);
            } catch (ContextTooLongException | NonRetryableException e) {
                throw e; // 压缩提示词重试 / 直接失败，均不做原样重发
            } catch (IllegalStateException e) {
                last = e; // 响应结构/接口错误：可重试（服务端偶发空响应）
            } catch (IOException e) {
                last = new IllegalStateException("AI 调用失败: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI 调用被中断", e);
            }
            if (attempt < attempts) sleep(attempt);
        }
        throw last != null ? last : new IllegalStateException("AI 调用失败：未知错误");
    }

    /** 非流式：一次性读完整响应。 */
    private String blockingComplete(Map<String, Object> body) throws IOException, InterruptedException {
        HttpResponse<String> resp = send(body, Map.of(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw httpError(resp.statusCode(), resp.body());
        return parseCompletion(resp.body());
    }

    /**
     * SSE 流式：逐行读 data 增量并累积。空闲看门狗 = max(30s, timeout/5)，
     * 总时限 = timeout（避免单块长读挂死；被掐线时尽早判定转重试）。
     */
    private String streamComplete(Map<String, Object> body) throws IOException, InterruptedException {
        HttpResponse<java.util.stream.Stream<String>> resp =
                send(body, Map.of("stream", true), HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() / 100 != 2) {
            String errBody = String.join("\n", resp.body().toList());
            throw httpError(resp.statusCode(), errBody);
        }
        String contentType = resp.headers().firstValue("content-type").orElse("");
        if (!contentType.contains("text/event-stream")) {
            // 网关/代理可能吞掉 SSE：按普通 JSON 响应解析
            return parseCompletion(String.join("\n", resp.body().toList()));
        }

        long timeoutMs = cfg.timeoutSeconds() * 1000L;
        long idleMs = Math.max(30_000, timeoutMs / 5);
        long deadline = System.currentTimeMillis() + timeoutMs;
        BlockingQueue<String> lines = new LinkedBlockingQueue<>(4096);
        pump.submit(() -> {
            try (java.util.stream.Stream<String> s = resp.body()) {
                s.forEach(l -> {
                    try {
                        lines.put(l);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
                lines.put(SSE_DONE);
            } catch (Exception e) {
                lines.offer(SSE_ERROR + e.getMessage());
            }
        });

        StringBuilder content = new StringBuilder();
        while (true) {
            String line = lines.poll(idleMs, TimeUnit.MILLISECONDS);
            if (line == null) {
                throw new IllegalStateException("AI 流式响应空闲超时（" + idleMs / 1000 + "s 无增量），疑似链路中断");
            }
            if (SSE_DONE.equals(line)) break;
            if (line.startsWith(SSE_ERROR)) {
                throw new IOException("AI 流式接收中断: " + line.substring(SSE_ERROR.length()));
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("AI 流式响应总时限超时（" + cfg.timeoutSeconds() + "s）");
            }
            String payload = line.startsWith("data:") ? line.substring(5).trim() : "";
            if (payload.isEmpty()) continue;
            if ("[DONE]".equals(payload)) break;
            JsonNode chunk = com.textdiff.store.Json.MAPPER.readTree(payload);
            JsonNode err = chunk.path("error");
            if (!err.isMissingNode()) {
                String msg = err.path("message").asText("AI 流式返回 error 块");
                throw new IllegalStateException("AI 接口错误: " + msg);
            }
            JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
            if (delta.isMissingNode()) delta = chunk.path("choices").path(0).path("text");
            if (!delta.isMissingNode()) content.append(delta.asText());
        }
        if (content.toString().isBlank()) throw new IllegalStateException("AI 流式响应内容为空");
        return content.toString();
    }

    private <T> HttpResponse<T> send(Map<String, Object> body, Map<String, Object> extra,
                                     HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        Map<String, Object> full = new java.util.LinkedHashMap<>(body);
        full.putAll(extra);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(com.textdiff.store.Json.write(full)))
                .build();
        return client.send(request, handler);
    }

    private IllegalStateException httpError(int status, String body) {
        String msg = "AI 接口 HTTP " + status + ": " + truncate(body);
        if (status == 400 && CONTEXT_HINTS.matcher(body).find()) {
            return new ContextTooLongException(msg); // 上下文装不下：压缩提示词才有意义
        }
        if (status == 400 && body.toLowerCase().contains("stream")) {
            return new StreamUnsupportedException(msg);
        }
        if (status == 429 || status / 100 == 5) {
            return new IllegalStateException(msg); // 瞬时：可重试
        }
        return new NonRetryableException(msg); // 参数/鉴权错误重试无意义
    }

    private String parseCompletion(String body) throws IOException {
        JsonNode root = com.textdiff.store.Json.MAPPER.readTree(body);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("AI 响应缺少 choices[0].message.content");
        }
        return content.asText();
    }

    private void sleep(int attempt) {
        try {
            long backoff = Math.max(100, cfg.retryBackoffMs()) * (1L << Math.min(attempt - 1, 5));
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 300));
    }

    /** 服务端不支持 stream 参数：回退非流式。 */
    private static final class StreamUnsupportedException extends IllegalStateException {
        StreamUnsupportedException(String message) {
            super(message);
        }
    }

    /** 参数/鉴权类错误：重试无意义，直接失败。 */
    private static final class NonRetryableException extends IllegalStateException {
        NonRetryableException(String message) {
            super(message);
        }
    }
}
