import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.beryx.runtime") version "1.13.1"
}

group = "com.textdiff"
version = "0.9.18"

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
            lines[idx] = lines[idx].replace("\$DEFAULT_JVM_OPTS",
                    "\$DEFAULT_JVM_OPTS \$BASE_DIR_PROP \$CONFIG_JVM_OPTS")
            val block = """
                |CONFIG_JVM_OPTS=""
                |BASE_DIR_PROP=""
                |if [ -d "${'$'}APP_HOME/data" ]; then BASE_DIR_PROP="-Dtextdiff.base.dir=${'$'}APP_HOME/data"; fi
                |for INI_FILE in "${'$'}PWD/config.ini" "${'$'}APP_HOME/data/config.ini" "${'$'}APP_HOME/config.ini"; do
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
            lines[idx] = lines[idx].replace("%DEFAULT_JVM_OPTS%",
                    "%DEFAULT_JVM_OPTS% %BASE_DIR_PROP% %CONFIG_JVM_OPTS%")
            val block = """
                |@rem JVM 参数注入：读取 config.ini [jvm] jvm_opts（CWD 优先，其次 APP_HOME\data、APP_HOME；等号两侧可有空格）
                |@rem 便携布局（APP_HOME\data 存在）时显式指定数据根——Windows 启动器工作目录不定，双保险
                |set "CONFIG_JVM_OPTS="
                |set "BASE_DIR_PROP="
                |if exist "%APP_HOME%\data\" set "BASE_DIR_PROP=-Dtextdiff.base.dir=%APP_HOME%\data"
                |if exist "config.ini" for /f "usebackq eol=# tokens=1,* delims== " %%a in (`findstr /i /b "jvm_opts" "config.ini"`) do set "CONFIG_JVM_OPTS=%%b"
                |if not defined CONFIG_JVM_OPTS if exist "%APP_HOME%\data\config.ini" for /f "usebackq eol=# tokens=1,* delims== " %%a in (`findstr /i /b "jvm_opts" "%APP_HOME%\data\config.ini"`) do set "CONFIG_JVM_OPTS=%%b"
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

// 便携包装配（v0.9.16+ 布局）——以 beryx runtimeZip 产物为基础后处理重打包：
//   剥掉其 zip 根目录（image/），把程序件（bin/conf/legal/lib/release）收进顶层文件夹 v$version（=版本 tag 号），
//   补入 upgrade/（平移升级脚本，见 scripts/upgrade/）与 data/（用户数据根，首次启动由 AppPaths 自动
//   识别收口；包内预置 README 说明各目录用处）与顶层 README.txt。
// 产物名保持 build/image.zip——CI 的改名上传流程不变。
tasks.register("portableZip") {
    dependsOn("runtimeZip")
    doLast {
        val beryxZip = layout.buildDirectory.file("image.zip").get().asFile
        val outZip = layout.buildDirectory.file("image.portable.zip").get().asFile
        val newTop = "v${project.version}"
        val src = ZipFile(beryxZip)
        val entries = src.entries().asSequence().toList()
        val roots = entries.map { it.name.split("/").first() }.toSet()
        if (roots.size != 1) throw GradleException("runtimeZip 产物顶层异常（$roots），无法装配便携包")
        val oldRoot = roots.first()
        ZipOutputStream(outZip.outputStream()).use { zos ->
            fun put(name: String, content: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
            for (e in entries) {
                if (e.isDirectory) continue
                val rel = e.name.removePrefix("$oldRoot/")
                if (rel.isBlank()) continue
                put("$newTop/$rel", src.getInputStream(e).readBytes())
            }
            fileTree("scripts/upgrade").forEach { f ->
                put("$newTop/upgrade/${f.name}", f.readBytes())
            }
            put("$newTop/README.txt", file("scripts/portable/README.txt").readBytes())
            put("$newTop/data/README.txt", file("scripts/portable/data/README.txt").readBytes())
        }
        src.close()
        outZip.copyTo(beryxZip, overwrite = true)
        outZip.delete()
        println("portableZip: 顶层 $newTop/（程序件 + upgrade/ + data/），产物 ${beryxZip.name}")
    }
}
