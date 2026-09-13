package com.textdiff.ai;

import com.sun.net.httpserver.HttpServer;
import com.textdiff.engine.CompareConfig;
import com.textdiff.engine.RowDiff;
import com.textdiff.engine.Rules;
import com.textdiff.engine.Status;
import com.textdiff.engine.Summary;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.ResultFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiPipelineTest {

    private static void writeResult(Path dir) throws Exception {
        try (var sink = ResultFiles.JsonlSink.create(dir.resolve(ResultFiles.RESULT_JSONL))) {
            sink.addRow(RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "2024-01-01", "x"}));
            sink.addRow(RowDiff.diff("k2", Status.SECTION_DATA,
                    new String[]{"k2", "2024-01-01", "100"},
                    new String[]{"k2", "2024-02-01", "101"}, new int[]{1, 2}));
            sink.addRow(RowDiff.diff("k3", Status.SECTION_DATA,
                    new String[]{"k3", "2024-01-02", "100"},
                    new String[]{"k3", "2024-02-02", "101"}, new int[]{1, 2}));
        }
    }

    @Test
    void extractorFindsPatterns(@TempDir Path dir) throws Exception {
        writeResult(dir);
        var f = DiffFeatureExtractor.extract(dir.resolve(ResultFiles.RESULT_JSONL));
        assertEquals(2, f.columns.size());
        var c1 = f.columns.get(1); // 日期列
        assertEquals(2, c1.count);
        assertEquals(2, c1.dateShaped);
        assertTrue(c1.commonPrefix.startsWith("2024-0"));
        var c2 = f.columns.get(2); // 数值 +1
        assertEquals(2, c2.numericPairs);
        assertEquals("+1", c2.numericDeltaMode);
    }

    @Test
    void promptRenderingFillsAllPlaceholders(@TempDir Path dir) throws Exception {
        writeResult(dir);
        Summary s = new Summary();
        s.totalA = 3;
        s.diff = 2;
        s.diffColFreq.put(1, 2L);
        s.diffColFreq.put(2, 2L);
        JobRecord job = new JobRecord("j1", "b1", "样例", "NICK:*.txt:KEYSEQ=1", "a.txt", "b.txt",
                dir.toString());
        JobMeta meta = ResultFiles.buildMeta(job, "utf-8", "utf-8", s);
        ResultFiles.writeMeta(dir, meta);
        Path prompt = PromptRenderer.render(dir, job, meta, s, null);
        String text = Files.readString(prompt, StandardCharsets.UTF_8);
        assertFalse(text.contains("{{")); // 占位符全部填充
        assertTrue(text.contains("2024-01-01")); // 采样值对
        assertTrue(text.contains("+1")); // 数值差规律
        assertTrue(text.contains("KEYSEQ=1"));
        assertTrue(text.contains("主键均唯一"));
        assertTrue(text.contains("## 四、AI 动态分析要求")); // 有差异时动态段存在
    }

    @Test
    void noDiffOmitsDynamicSection(@TempDir Path dir) throws Exception {
        try (var sink = ResultFiles.JsonlSink.create(dir.resolve(ResultFiles.RESULT_JSONL))) {
            sink.addRow(RowDiff.equal("k1", Status.SECTION_DATA, new String[]{"k1", "1"}));
        }
        Summary s = new Summary();
        s.totalA = 1;
        s.equal = 1;
        JobRecord job = new JobRecord("j2", "b1", "n", "NICK:*.txt:KEYSEQ=1", "a", "b", dir.toString());
        Path prompt = PromptRenderer.render(dir, job, null, s, null);
        String text = Files.readString(prompt, StandardCharsets.UTF_8);
        assertTrue(text.contains("无差异列") || text.contains("动态分析部分省略"));
        assertFalse(text.contains("## 四、AI 动态分析要求"));
    }

    @Test
    void aiCallProducesAnalysisJson(@TempDir Path dir) throws Exception {
        writeResult(dir);
        Summary s = new Summary();
        s.diff = 2;
        s.diffColFreq.put(1, 2L);
        JobRecord job = new JobRecord("jAI", "b1", "n", "NICK:*.txt:KEYSEQ=1", "a", "b", dir.toString());
        job.status = JobRecord.DONE;
        ResultFiles.writeSummary(dir, s);

        String reply = """
                ## 整体结论
                日期列整体后移一个月，金额 +1。
                ```json
                {"overall":"日期位移+数值递增","columns":[{"col":1,"name":"日期","pattern":"整体 +1 月",
                "cause":"账期重算","confidence":"high"}],"causes":["账期切换"],
                "suggestions":["用 REPLACE 规范化日期"],"candidate_replace":[]}
                ```""";
        // 假 OpenAI 兼容服务
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String body = "{\"choices\":[{\"message\":{\"content\":"
                    + com.textdiff.store.Json.MAPPER.writeValueAsString(reply) + "}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        // 通过环境变量模拟 [ai] 配置
        var cfg = AppConfigFake.ai(true, "http://127.0.0.1:" + port + "/v1", "sk-test",
                "test-model", 5);

        var store = new com.textdiff.store.FileJobStore(dir.resolve("store"));
        store.saveJob(job);
        var analyzer = new AiAnalyzer(store, cfg, new com.textdiff.config.AppPaths(dir, dir), null);
        // JobManager 为 null 时 aiHook 挂载跳过（测试直接调 analyze）
        analyzer.analyze("jAI");

        assertEquals("done", store.getJob("jAI").aiStatus);
        Path analysis = dir.resolve("ai_analysis.json");
        assertTrue(Files.exists(analysis));
        var json = com.textdiff.store.Json.MAPPER.readTree(analysis.toFile());
        assertTrue(json.path("content").asText().contains("整体后移"));
        assertEquals("日期位移+数值递增", json.path("structured").path("overall").asText());
        assertTrue(json.path("structured").path("columns").get(0).path("confidence").asText().equals("high"));
        assertTrue(Files.exists(dir.resolve("prompt.md")));
        server.stop(0);
    }

    /** 测试辅助：手动构造 AppConfig（避免依赖 ini 文件）。 */
    static final class AppConfigFake {
        static com.textdiff.config.AppConfig ai(boolean enabled, String baseUrl, String key,
                                                String model, int timeout) {
            return new com.textdiff.config.AppConfig(
                    new com.textdiff.config.ServerConfig("127.0.0.1", 0),
                    com.textdiff.config.EngineConfig.defaults(),
                    new com.textdiff.config.StoreConfig(false),
                    new com.textdiff.config.AppConfig.AiConfig(enabled, baseUrl, key, model, timeout));
        }
    }
}
