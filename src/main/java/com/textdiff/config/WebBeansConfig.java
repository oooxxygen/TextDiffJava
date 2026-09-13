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

    /** AI 分析器（构造即挂载 aiHook，作业 done 后自动触发 prompt.md/ai_analysis.json 产出）。 */
    @Bean(destroyMethod = "shutdown")
    public com.textdiff.ai.AiAnalyzer aiAnalyzer(DualJobStore store, AppConfig cfg,
                                                 AppPaths paths, JobManager jobManager) {
        return new com.textdiff.ai.AiAnalyzer(store, cfg, paths, jobManager);
    }

    /** REST 响应统一 snake_case，对齐前端契约。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
