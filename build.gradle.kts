plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.beryx.runtime") version "1.13.1"
}

group = "com.textdiff"
version = "0.9.5"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

application {
    mainClass = "com.textdiff.Application"
    // OOM 防护缺省（TEXT_DIFF_JAVA_OPTS/JAVA_OPTS 可覆盖）：堆上限按物理内存 50%（物理机/容器一致），
    // 堆溢出自动转储到工作目录，便于事后分析；配合作业级 OOM 捕获，服务不再因单个大作业崩溃
    // MaxDirectMemorySize：堆外哈希表（大键量对比的索引表）默认上限随堆比例过小，显式 2g 兜底
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=50.0", "-XX:MaxDirectMemorySize=2g",
            "-XX:+HeapDumpOnOutOfMemoryError")
}

// 启动脚本注入：进程启动时读取 config.ini [jvm] jvm_opts 注入 JVM 参数（CWD 优先，其次 APP_HOME；
// 均无 = 仅内置缺省；env TEXT_DIFF_JAVA_OPTS/JAVA_OPTS 仍可覆盖）。设置页保存后重启服务生效。
// 采用行定位而非整段锚点：裸 startScripts（bootJar）与 runtimeZip（beryx 插件自备模板）两种
// 模板的 exec/eval 行形态不同，逐行定位 DEFAULT_JVM_OPTS 引用点对两者都成立。
tasks.startScripts {
    doLast {
        fun fail(kind: String, text: String) {
            println("==== startScripts $kind 模板全文（注入定位失败诊断） ====")
            println(text)
            println("==== 模板全文结束 ====")
            throw GradleException("startScripts 注入定位失败（$kind 模板已变化）")
        }

        // ---- unix ----
        run {
            val text = unixScript.readText()
            if (text.contains("CONFIG_JVM_OPTS=")) return@run // 已注入（幂等）
            // 两种模板统一行定位：Gradle 默认模板 = printf '%s\n' "$DEFAULT_JVM_OPTS ...（eval 多行块内）；
            // beryx runtimeZip 模板 = eval set -- $DEFAULT_JVM_OPTS $CDS_JVM_OPTS ...
            val lines = text.lines().toMutableList()
            val idx = lines.indexOfFirst {
                it.contains("\$DEFAULT_JVM_OPTS") && !it.contains("DEFAULT_JVM_OPTS=")
                        && (it.contains("printf") || it.contains("eval set --"))
            }
            if (idx < 0) fail("unix", text)
            lines[idx] = lines[idx].replace("\$DEFAULT_JVM_OPTS", "\$DEFAULT_JVM_OPTS \$CONFIG_JVM_OPTS")
            val block = """
                |CONFIG_JVM_OPTS=""
                |for INI_FILE in "${'$'}PWD/config.ini "${'$'}APP_HOME/config.ini"; do
                |  if [ -f "${'$'}INI_FILE" ]; then
                |    CONFIG_JVM_OPTS=${'$'}(sed -n 's/^[[:space:]]*jvm_opts[[:space:]]*=[[:space:]]*//p' "${'$'}INI_FILE" | tail -n 1 | tr -d '\r')
                |    [ -n "${'$'}CONFIG_JVM_OPTS" ] && break
                |  fi
                |done
            """.trimMargin()
            lines.addAll(idx, block.lines().filter { it.isNotEmpty() })
            unixScript.writeText(lines.joinToString("\n") + (if (text.endsWith("\n")) "\n" else ""))
        }

        // ---- windows bat ----
        run {
            val text = windowsScript.readText()
            if (text.contains("CONFIG_JVM_OPTS=")) return@run // 已注入（幂等）
            val lines = text.lines().toMutableList()
            val idx = lines.indexOfFirst {
                it.contains("%JAVA_EXE%") && it.contains("%DEFAULT_JVM_OPTS%")
            }
            if (idx < 0) fail("windows", text)
            lines[idx] = lines[idx].replace("%DEFAULT_JVM_OPTS%", "%DEFAULT_JVM_OPTS% %CONFIG_JVM_OPTS%")
            val block = """
                |@rem JVM 参数注入：读取 config.ini [jvm] jvm_opts（CWD 优先，其次 APP_HOME；等号两侧可有空格）
                |set "CONFIG_JVM_OPTS="
                |if exist "config.ini" for /f "usebackq eol=# tokens=1,* delims== " %%a in (`findstr /i /b "jvm_opts" "config.ini"`) do set "CONFIG_JVM_OPTS=%%b"
                |if not defined CONFIG_JVM_OPTS if exist "%APP_HOME%\config.ini" for /f "usebackq eol=# tokens=1,* delims== " %%a in (`findstr /i /b "jvm_opts" "%APP_HOME%\config.ini"`) do set "CONFIG_JVM_OPTS=%%b"
            """.trimMargin()
            lines.addAll(idx, block.lines().filter { it.isNotEmpty() })
            windowsScript.writeText(lines.joinToString("\r\n") + (if (text.endsWith("\r\n")) "\r\n" else ""))
        }
    }
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
