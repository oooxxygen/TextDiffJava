package com.textdiff.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.textdiff.config.AppConfig;
import com.textdiff.engine.Summary;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.ResultFiles;
import com.textdiff.task.JobManager;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 归纳分析协调器：作业 done 后自动触发（aiHook）。
 * ① 渲染 prompt.md（无论 AI 是否启用，均落盘供审计/跨环境移植）；
 * ② AI 启用时调用模型并解析固定 JSON 结构 → ai_analysis.json。
 * 失败不阻塞任务，aiStatus=failed 可通过 /reanalyze 重试。
 */
public final class AiAnalyzer {
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

    private final JobStore store;
    private final AppConfig cfg;
    private final Path configsDir;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-analyzer");
        t.setDaemon(true);
        return t;
    });

    public AiAnalyzer(JobStore store, AppConfig cfg, com.textdiff.config.AppPaths paths, JobManager manager) {
        this.store = store;
        this.cfg = cfg;
        this.configsDir = paths.configsDir();
        if (manager != null) manager.doneHook = this::enqueue;
    }

    /** 作业完成后由 JobManager 回调。 */
    public void enqueue(JobRecord job) {
        pool.submit(() -> analyze(job.id));
    }

    /** /reanalyze 入口：failed/disabled/done 的作业重新执行。 */
    public void reanalyze(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null) return;
        job.aiStatus = "pending";
        store.saveJob(job);
        pool.submit(() -> analyze(jobId));
    }

    public void analyze(String jobId) {
        JobRecord job = store.getJob(jobId);
        if (job == null || !JobRecord.DONE.equals(job.status)) return;
        Path dir = Path.of(job.resultDir);
        job.aiStatus = "running";
        store.saveJob(job);
        try {
            JobMeta meta = ResultFiles.readMeta(dir);
            Summary summary = ResultFiles.readSummary(dir);
            PromptRenderer.render(dir, job, meta, summary, configsDir); // prompt.md 恒产出

            if (!cfg.ai().usable()) {
                job.aiStatus = "disabled";
                store.saveJob(job);
                return;
            }
            String prompt = java.nio.file.Files.readString(dir.resolve("prompt.md"),
                    java.nio.charset.StandardCharsets.UTF_8);
            String content = new AiClient(cfg.ai()).complete(prompt);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("content", content);
            out.put("model", cfg.ai().model());
            out.put("generated_at", System.currentTimeMillis() / 1000);
            Matcher m = JSON_BLOCK.matcher(content);
            if (m.find()) {
                try {
                    out.put("structured", com.textdiff.store.Json.MAPPER.readTree(m.group(1)));
                } catch (RuntimeException ignored) {
                    out.put("structured_error", "JSON 块解析失败，请查看 content");
                }
            }
            java.nio.file.Files.write(dir.resolve("ai_analysis.json"),
                    com.textdiff.store.Json.write(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            job.aiStatus = "done";
        } catch (Exception e) {
            job.aiStatus = "failed";
            StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            job.error = "AI 分析失败: " + e.getMessage() + " @ " + top;
        }
        store.saveJob(job);
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    public boolean enabled() {
        return cfg.ai().usable();
    }

    public String model() {
        return cfg.ai().model();
    }

    public static JsonNode readStructured(Path resultDir) {
        Path f = resultDir.resolve("ai_analysis.json");
        if (!java.nio.file.Files.isRegularFile(f)) return null;
        try {
            JsonNode root = com.textdiff.store.Json.MAPPER.readTree(f.toFile());
            return root.path("structured");
        } catch (Exception e) {
            return null;
        }
    }
}
