plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.beryx.runtime") version "1.13.1"
}

group = "com.textdiff"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

application {
    mainClass = "com.textdiff.Application"
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.h2database:h2:2.3.232")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 测试运行目录隔离：避免污染仓库根目录（store/results/configs 等）
    systemProperty("textdiff.base.dir",
            layout.buildDirectory.dir("test-run").get().asFile.absolutePath)
    doFirst { delete(layout.buildDirectory.dir("test-run")) }
}

// 便携运行时（jlink）模块集 —— EBCDIC/UTF-16 依赖 jdk.charsets
runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    modules.set(listOf(
        "java.base", "java.logging", "java.sql", "java.naming",
        "java.management", "java.desktop", "jdk.charsets", "jdk.crypto.ec",
        "java.instrument", "jdk.unsupported"
    ))
    jpackage {
        imageName = "TextDiff"
        imageOptions = listOf("--app-version", project.version.toString())
    }
}
