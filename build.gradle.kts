plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.beryx.runtime") version "1.13.1"
}

group = "com.textdiff"
version = "0.9.1"

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
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    // AI 分析报告 Markdown → HTML（GFM 表格扩展：报告尾部事实表）
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 测试运行目录隔离：避免污染仓库根目录（store/results/configs 等）
    systemProperty("textdiff.base.dir",
            layout.buildDirectory.dir("test-run").get().asFile.absolutePath)
    doFirst { delete(layout.buildDirectory.dir("test-run")) }
    // 本机 C 盘紧张时，用 TD_TMP 环境变量把测试临时目录（JUnit @TempDir）重定向到大容量盘；CI 不受影响
    System.getenv("TD_TMP")?.let { jvmArgs("-Djava.io.tmpdir=$it") }
}

// 便携运行时（jlink）模块集
// jdk.charsets：EBCDIC/UTF-16；java.security.jgss：Tomcat 启动 createDefaultRealm 即加载（缺则 web 容器起不来）；
// java.net.http：AiClient 的 HttpClient；java.security.sasl：java.naming 的 SASL 认证
runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    modules.set(listOf(
        "java.base", "java.logging", "java.sql", "java.naming",
        "java.management", "java.desktop", "jdk.charsets", "jdk.crypto.ec",
        "java.instrument", "jdk.unsupported",
        "java.security.jgss", "java.security.sasl", "java.net.http"
    ))
    jpackage {
        imageName = "TextDiff"
        imageOptions = listOf("--app-version", project.version.toString())
    }
}
