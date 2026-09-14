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

    @Bean(destroyMethod = "close")
    public JobManager jobManager(DualJobStore store, AppPaths paths, AppConfig cfg,
                                 com.textdiff.store.FieldMapStore fieldMaps) {
        return new JobManager(store, paths.resultsDir(), cfg.engine(), fieldMaps);
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
                store, taskStore, aiAnalyzer, fieldMaps, paths, paths.resultsDir());
        jobManager.doneHook = tm::registerDefaults; // 作业 done → 登记差异CSV导出 + AI分析两条任务
        return tm;
    }

    /** REST 响应统一 snake_case，对齐前端契约。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
