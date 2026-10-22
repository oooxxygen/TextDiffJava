package com.textdiff.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.textdiff.store.DualJobStore;
import com.textdiff.task.JobManager;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/** 应用装配：配置加载、双写存储、任务管理器、snake_case REST 契约。 */
@Configuration
public class WebBeansConfig {

    @Bean
    public AppPaths appPaths() {
        return AppPaths.detect();
    }

    @Bean
    public AppConfig appConfig(AppPaths paths) {
        Function<String, String> env = System::getenv;
        return AppConfig.load(paths.userConfigIni(), env);
    }

    @Bean(destroyMethod = "close")
    public DualJobStore jobStore(AppPaths paths, AppConfig cfg) {
        return new DualJobStore(paths.baseDir().resolve("store"), cfg.store().enabled());
    }

    /** 字段名映射（源系统字段配置）：JSONL 事实来源 + H2 镜像。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.store.FieldMapStore fieldMapStore(AppPaths paths, AppConfig cfg) {
        return new com.textdiff.store.FieldMapStore(paths.baseDir().resolve("store"), cfg.store().enabled());
    }

    /** 特殊编码字段映射（混合编码栏位转码）：JSONL 事实来源 + H2 镜像。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.store.CharsetMapStore charsetMapStore(AppPaths paths, AppConfig cfg) {
        return new com.textdiff.store.CharsetMapStore(paths.baseDir().resolve("store"), cfg.store().enabled());
    }

    /** CTIS 动态栏位配置（'~~' 子栏位展开规则）：JSONL 事实来源 + H2 镜像。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.store.CtisDynFieldStore ctisDynFieldStore(AppPaths paths, AppConfig cfg) {
        return new com.textdiff.store.CtisDynFieldStore(paths.baseDir().resolve("store"), cfg.store().enabled());
    }

    /** 关注字段监测配置（DISCARD）：JSONL 事实来源 + H2 镜像。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.store.DiscardFieldStore discardFieldStore(AppPaths paths, AppConfig cfg) {
        return new com.textdiff.store.DiscardFieldStore(paths.baseDir().resolve("store"), cfg.store().enabled());
    }

    @Bean(destroyMethod = "close")
    public JobManager jobManager(DualJobStore store, AppPaths paths, AppConfig cfg,
                                 com.textdiff.store.FieldMapStore fieldMaps,
                                 com.textdiff.store.CharsetMapStore charsetMaps,
                                 com.textdiff.store.CtisDynFieldStore ctisDyn) {
        return new JobManager(store, paths.resultsDir(), cfg.engine(), fieldMaps, charsetMaps, ctisDyn);
    }

    /** 压缩文件预解压服务：*.gz 逻辑名匹配 → 临时目录解压 → 预解压池动态补位调度。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.task.ExtractService extractService(DualJobStore store, JobManager jobManager,
                                                           AppPaths paths, AppConfig cfg) {
        com.textdiff.task.ExtractService svc =
                new com.textdiff.task.ExtractService(store, jobManager, paths, cfg);
        jobManager.extractService = svc;
        return svc;
    }

    /**
     * 启动收尾（全部 bean 装配完成后）：清空上次进程残留的解压产物，修复存量批次时间，再恢复中断作业。
     * 恢复必须在 extractService/reportRunner 挂载之后——此前在 JobManager 构造器内恢复，
     * 压缩作业会在解压服务就绪前被直接当作文本对比 .gz 原文件。
     */
    @Bean
    org.springframework.boot.ApplicationRunner startupRecovery(
            JobManager jobManager, com.textdiff.task.ExtractService extractService,
            DualJobStore store) {
        return args -> {
            extractService.purgeExtractRoot();
            repairBatchCreatedAt(store);
            jobManager.start();
        };
    }

    /**
     * 存量数据自愈：正常批次先建档后建作业，createdAt ≤ 任一作业的 createdAt；
     * 晚于最早作业即曾被 BatchRecord.copy 漏拷漂移（存成保存时刻），按最早作业创建时间回填。
     */
    static void repairBatchCreatedAt(DualJobStore store) {
        for (com.textdiff.store.BatchRecord b : store.listBatches()) {
            long minChild = Long.MAX_VALUE;
            for (com.textdiff.store.JobRecord j : store.listJobs(b.id)) {
                if (j.createdAt > 0 && j.createdAt < minChild) minChild = j.createdAt;
            }
            if (minChild != Long.MAX_VALUE && b.createdAt > minChild) {
                b.createdAt = minChild;
                store.saveBatch(b);
            }
        }
    }

    /** 任务管理：登记/执行作业完成后的默认生成任务（差异 CSV 导出 + AI 分析）。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.store.TaskStore taskStore(AppPaths paths, AppConfig cfg) {
        return new com.textdiff.store.TaskStore(paths.baseDir().resolve("store"), cfg.store().enabled());
    }

    /** AI 分析器（自动触发改由 TaskManager 编排；归属组命名来自字段映射）。 */
    @Bean(destroyMethod = "shutdown")
    public com.textdiff.ai.AiAnalyzer aiAnalyzer(DualJobStore store, AppConfig cfg, AppPaths paths,
                                                 com.textdiff.store.FieldMapStore fieldMaps) {
        return new com.textdiff.ai.AiAnalyzer(store, cfg, paths, null, fieldMaps);
    }

    @Bean(destroyMethod = "close")
    public com.textdiff.task.TaskManager taskManager(DualJobStore store,
                                                     com.textdiff.store.TaskStore taskStore,
                                                     com.textdiff.ai.AiAnalyzer aiAnalyzer,
                                                     com.textdiff.store.FieldMapStore fieldMaps,
                                                     AppPaths paths, AppConfig cfg,
                                                     JobManager jobManager) {
        com.textdiff.task.TaskManager tm = new com.textdiff.task.TaskManager(
                store, taskStore, aiAnalyzer, fieldMaps, paths, paths.resultsDir(),
                Math.max(1, cfg.ai().maxConcurrency()));
        jobManager.doneHook = tm::registerDefaults; // 作业 done → 登记差异CSV导出 + AI分析两条任务
        return tm;
    }

    /** 报表对比服务（【报表对比】：header 模板核对批次）；作业经 JobManager.submit 路由执行。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.report.ReportCompareService reportCompareService(
            DualJobStore store, AppPaths paths, AppConfig cfg,
            com.textdiff.store.FieldMapStore fieldMaps, JobManager jobManager) {
        com.textdiff.report.ReportCompareService svc = new com.textdiff.report.ReportCompareService(
                store, paths.resultsDir(), paths.configsDir(), fieldMaps, Math.max(1, cfg.engine().maxThreads()));
        jobManager.reportRunner = svc::run;
        return svc;
    }

    /** 自定义格式对比服务（【自定义格式对比】：段起止/主键正则的报文类文本对比）；作业经 JobManager.submit 路由执行。 */
    @Bean(destroyMethod = "close")
    public com.textdiff.custom.CustomCompareService customCompareService(
            DualJobStore store, AppPaths paths, AppConfig cfg, JobManager jobManager) {
        com.textdiff.custom.CustomCompareService svc = new com.textdiff.custom.CustomCompareService(
                store, paths.resultsDir(), Math.max(1, cfg.engine().maxThreads()));
        jobManager.customRunner = svc::run;
        return svc;
    }

    /** 关注字段监测采集（DISCARD_COLS 懒生成 + 缓存）：依赖报表服务复用其解析分支。 */
    @Bean
    public com.textdiff.export.DiscardColsService discardColsService(
            DualJobStore store, com.textdiff.store.DiscardFieldStore discardFields,
            com.textdiff.store.FieldMapStore fieldMaps,
            com.textdiff.report.ReportCompareService reportService, AppPaths paths) {
        return new com.textdiff.export.DiscardColsService(
                store, discardFields, fieldMaps, reportService, paths.resultsDir());
    }

    /** REST 响应统一 snake_case，对齐前端契约。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
