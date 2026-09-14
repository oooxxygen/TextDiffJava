package com.textdiff.ai;

import com.sun.net.httpserver.HttpServer;
import com.textdiff.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** AiClient 健壮性：SSE 流式解析、弱网重试、上下文超限识别、不可重试错误。 */
class AiClientTest {

    private static AppConfig.AiConfig ai(String baseUrl, int timeoutSeconds, int retries, long backoffMs) {
        return new AppConfig.AiConfig(true, baseUrl, "sk-test", "test-model",
                timeoutSeconds, 120000, retries, backoffMs);
    }

    @Test
    void streamingSseAccumulatesDeltas() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                String[] deltas = {"## 整体", "结论", "：金额 +1"};
                for (String d : deltas) {
                    String chunk = "{\"choices\":[{\"delta\":{\"content\":"
                            + com.textdiff.store.Json.MAPPER.writeValueAsString(d) + "}}]}";
                    os.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        });
        server.start();
        try {
            AiClient client = new AiClient(ai("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 5, 0, 10));
            assertEquals("## 整体结论：金额 +1", client.complete("prompt"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transientServerErrorsRetriedUntilSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            if (calls.get() <= 2) { // 前两次 500：指数退避后重试
                ex.sendResponseHeaders(500, -1);
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                String chunk = "{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}";
                os.write(("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            AiClient client = new AiClient(ai("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    5, 3, 10));
            assertEquals("OK", client.complete("prompt"));
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void contextTooLongThrowsSpecificExceptionWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            byte[] bytes = "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(400, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            AiClient client = new AiClient(ai("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    5, 3, 10));
            assertThrows(AiClient.ContextTooLongException.class, () -> client.complete("prompt"));
            assertEquals(1, calls.get()); // 上下文超限不原样重发
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authErrorFailsFastWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            ex.sendResponseHeaders(401, -1);
        });
        server.start();
        try {
            AiClient client = new AiClient(ai("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    5, 3, 10));
            assertThrows(IllegalStateException.class, () -> client.complete("prompt"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamUnsupportedFallsBackToBlocking() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = calls.incrementAndGet();
            if (n == 1) { // stream 请求被拒
                byte[] bytes = "{\"error\":{\"message\":\"stream is not supported\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(400, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write("{\"choices\":[{\"message\":{\"content\":\"fallback OK\"}}]}"
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            AiClient client = new AiClient(ai("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    5, 2, 10));
            assertEquals("fallback OK", client.complete("prompt"));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
