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

    @Bean(destroyMethod = "close")
    public JobManager jobManager(DualJobStore store, AppPaths paths, AppConfig cfg) {
        return new JobManager(store, paths.resultsDir(), cfg.engine());
    }

    /** REST 响应统一 snake_case，对齐前端契约。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
