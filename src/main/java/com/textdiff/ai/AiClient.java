package com.textdiff.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.textdiff.config.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** OpenAI 兼容 /chat/completions 调用（java.net.http，零新依赖）。 */
public final class AiClient {
    private final AppConfig.AiConfig cfg;

    public AiClient(AppConfig.AiConfig cfg) {
        this.cfg = cfg;
    }

    public boolean usable() {
        return cfg.usable();
    }

    public String model() {
        return cfg.model();
    }

    /** 发送 prompt，返回模型文本响应。失败抛 IllegalStateException。 */
    public String complete(String prompt) {
        String url = cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "temperature", 0.2,
                "messages", List.of(Map.of("role", "user", "content", prompt)));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            com.textdiff.store.Json.write(body)))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("AI 接口 HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            JsonNode root = com.textdiff.store.Json.MAPPER.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IllegalStateException("AI 响应缺少 choices[0].message.content");
            }
            return content.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 300));
    }
}
