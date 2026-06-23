package com.textdiff;

import com.textdiff.config.CharsetSmokeCheck;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /** 启动期校验关键字符集（EBCDIC/UTF-16）存在，裁剪运行时漏 jdk.charsets 时快速失败。 */
    @Bean
    ApplicationRunner charsetCheck() {
        return args -> CharsetSmokeCheck.verify();
    }
}
