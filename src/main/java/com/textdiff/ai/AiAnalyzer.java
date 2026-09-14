package com.textdiff.ai;

import com.textdiff.config.AppConfig;
import com.textdiff.engine.Summary;
import com.textdiff.store.FieldMapStore;
import com.textdiff.store.JobMeta;
import com.textdiff.store.JobRecord;
import com.textdiff.store.JobStore;
import com.textdiff.store.ResultFiles;
import com.textdiff.task.JobManager;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 归纳分析协调器：作业 done 后由 TaskManager 编排触发（构造器不再直接挂载 doneHook）。
 * ① 渲染 prompt.md（无论 AI 是否启用，均落盘供审计/跨环境移植）；
 * ② AI 启用时调用模型，产出阅读友好的 Markdown → ai_analysis.md：
 *    开头为 AI 生成提示，一级标题为 [归属组]文件昵称_实际文件名，附栏位属性表。
 * 健壮性：弱网重试 + SSE 流式接收（idle 看门狗）；提示词超预算或模型报上下文超限 →
 * 紧凑预算压缩重渲再试，适配小上下文窗口（≤256K）。
 * 失败不阻塞任务，aiStatus=failed 可通过 /reanalyze 重试。
 */
public final class AiAnalyzer {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JobStore store;
    private final AppConfig cfg;
    private final Path configsDir;
    private final FieldMapStore fieldMaps; // 可空：归属组命名退回配置组名
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-analyzer");
        t.setDaemon(true);
        return t;
    });

    public AiAnalyzer(JobStore store, AppConfig cfg, com.textdiff.config.AppPaths paths,
                      JobManager manager, FieldMapStore fieldMaps) {
        this.store = store;
        this.cfg = cfg;
        this.configsDir = paths.configsDir();
        this.fieldMaps = fieldMaps;
    }

    /** 作业完成后由 TaskManager 回调。 */
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
            AiClient client = new AiClient(cfg.ai());

            // 全量预算渲染（prompt.md 恒产出，供审计/移植）
            PromptRenderer.render(dir, job, meta, summary, configsDir, fieldMaps, PromptRenderer.Budget.FULL);
            if (!cfg.ai().usable()) {
                job.aiStatus = "disabled";
                store.saveJob(job);
                return;
            }
            String prompt = java.nio.file.Files.readString(dir.resolve("prompt.md"),
                    java.nio.charset.StandardCharsets.UTF_8);

            String content;
            try {
                // 提示词超出预算：直接以紧凑预算重渲，适配小上下文窗口（≤256K）
                content = prompt.length() > cfg.ai().maxPromptChars()
                        ? completeCompact(client, dir, job, meta, summary)
                        : client.complete(prompt);
            } catch (AiClient.ContextTooLongException e) {
                // 模型侧仍报上下文超限：压缩提示词后重试（不做无意义原样重发）
                content = completeCompact(client, dir, job, meta, summary);
            }
            java.nio.file.Files.write(dir.resolve("ai_analysis.md"),
                    markdown(job, content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            job.aiStatus = "done";
        } catch (Exception e) {
            job.aiStatus = "failed";
            StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            job.error = "AI 分析失败: " + e.getMessage() + " @ " + top;
        }
        store.saveJob(job);
    }

    /** 紧凑预算重渲 prompt.md 后调用；仍超预算则截断正文（保证可送达）。 */
    private String completeCompact(AiClient client, Path dir, JobRecord job, JobMeta meta, Summary summary)
            throws Exception {
        PromptRenderer.render(dir, job, meta, summary, configsDir, fieldMaps, PromptRenderer.Budget.COMPACT);
        String prompt = java.nio.file.Files.readString(dir.resolve("prompt.md"),
                java.nio.charset.StandardCharsets.UTF_8);
        long budget = cfg.ai().maxPromptChars();
        if (prompt.length() > budget) {
            prompt = prompt.substring(0, (int) budget)
                    + "\n\n> ⚠ 提示词超出上下文预算，超出部分已截断；结论请基于已有信息给出。\n";
            java.nio.file.Files.writeString(dir.resolve("prompt.md"), prompt,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return client.complete(prompt);
    }

    /** 阅读友好 Markdown：开头 AI 生成提示 + 一级标题 + 正文 + 栏位属性附录。 */
    private String markdown(JobRecord job, String content) {
        String notice = "> 🤖 本文件由 AI 自动生成（模型：" + cfg.ai().model()
                + "；生成时间：" + LocalDateTime.now().format(TS)
                + "），内容仅供阅读参考，请以系统差异明细为准。";
        return notice + "\n\n# " + AiReportNamer.title(job, fieldMaps) + "\n\n"
                + content.strip() + "\n\n---\n\n## 附：栏位属性（源系统字段配置）\n\n"
                + PromptRenderer.fieldAttributeTable(job, fieldMaps) + "\n";
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
}
