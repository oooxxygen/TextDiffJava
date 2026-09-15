# TextDiffJava

大规模（千万级行）新旧文本行级比对系统——Python 版 [textdiff](https://github.com/OopusYo/textdiff) 的 Java 完整移植与增强。Spring Boot 3.3.4 + JDK 21（虚拟线程），依赖仅 `spring-boot-starter-web` + `h2`。

## 功能

- **比对引擎**（O(nA+nB)，千万级行堆外落盘）
  - 编码自动检测（BOM 优先 + EBCDIC cp037/cp500/cp1047/GB18030 打分），支持 `ENCA`/`ENCB` 指定
  - `" | "` 分隔（`DELIM=` 可配）+ `|||||` 文件尾部（trailer）逐字段对比
  - 规则格式：`NICK:GLOB:KEYSEQ=3/4/5:OMITSEQ=1/2/10...`（主键列、整列跳过、忽略列、REPLACE 预变换）
  - KeyIndex SPI：内存 → 堆外（mmap 行存储 + 开放寻址哈希表）透明落盘
- **主键唯一性检测**：A/B 侧重复键自动统计，"完成"状态旁醒目提示 ⚠ 主键重复·配置需重检
- **任务管理**：批次（目录对 + 多行配置自动配对）/作业状态机 pending→running→done/failed/stopped；`[engine] max-threads` 可配线程池；终止/重试/重启自动重入队；作业完成自动登记默认任务（差异CSV导出 / AI分析），【任务管理】页三级层级跟踪（状态/重新生成/删除）
- **列名映射导入**：源系统字段配置 CSV（bat_report_type_parm* / bat_report_conf_field*）→ 昵称↔文件名（支持通配）/归属组、昵称↔字段清单（名称/类型/长度）落库，对比时按文件名自动注入真实列名
- **前端**（Vue3 无构建）：类 Git 三分区（一致=蓝/绿默认折叠懒加载；差异=红；未匹配=灰）、按列响应式双栏、字符级 LCS 高亮、评议 note、全局搜索
- **持久化**：JSONL 文件为事实来源 + H2 尽力镜像（`[store] enabled=false` 可禁用）；结果流式落盘 `results/{jobId}/result.jsonl`，重启可重载
- **CSV 导出**：全量（所有记录所有字段）/ 差异（一条字段差异一行）；作业/批次端点 + 任务完成自动导出到 `results/{batchId}/export/`
- **AI 归纳分析**：每个对比完成后自动产出
  - `prompt.md`：固定框架提示词模板（v1，`configs/analysis-template.md` 可覆盖）填充差异特征与栏位属性（字段名/类型/长度）落盘——可追踪、可移植到任何环境/模型手工复跑
  - `ai_analysis.md`：阅读友好 Markdown——开头 AI 生成提示，一级标题 `[归属组]文件昵称_实际文件名`，附栏位属性表；任务管理导出副本与差异 CSV 同目录
  - OpenAI 兼容调用（`[ai] enabled/base_url/api_key/model/timeout`）+ 健壮性：SSE 流式（空闲/总时限看门狗）、弱网指数退避重试、上下文预算超限自动压缩重渲（适配 ≤256K 小窗口）
- **REST API**：契约摘要见 [docs/USER_GUIDE.md](docs/USER_GUIDE.md) 第九节（响应统一 snake_case）

## 快速开始

```bash
# 本地运行（JDK 21）
./gradlew bootRun
# 或
./gradlew bootJar && java -jar build/libs/TextDiffJava-0.1.0.jar
```

打开 http://localhost:8080 ，提交批次（目录对 + 配置文件/内联规则）或上传文件对。首次启动会自动生成带注释的 `config.ini` 默认配置，修改后重启生效。

> 📖 功能全览与操作指引：[docs/USER_GUIDE.md](docs/USER_GUIDE.md) · 里程碑演进：[docs/MILESTONES.md](docs/MILESTONES.md)

### 配置（config.ini，env 变量优先）

```ini
[server]
host = 0.0.0.0
port = 8080

[engine]
max-threads = 8            ; 对比线程池
in-memory-bytes = 1073741824

[store]
enabled = true             ; H2 镜像开关（JSONL 文件层始终启用）

[ai]
enabled = false
base_url = https://open.bigmodel.cn/api/paas/v4
api_key =
model = glm-4-flash
timeout = 60
```

env 覆盖：`TEXTDIFF_HOST / TEXTDIFF_PORT / TEXTDIFF_MAX_THREADS / TEXTDIFF_MAX_IN_MEMORY_BYTES / TEXTDIFF_STORE_ENABLED / TEXTDIFF_AI_ENABLED / TEXTDIFF_AI_BASE_URL / TEXTDIFF_AI_API_KEY / TEXTDIFF_AI_MODEL / TEXTDIFF_AI_TIMEOUT`

### Docker

```bash
docker build -t textdiffjava .
docker run -p 8080:8080 -v $PWD/data:/data textdiffjava
```

### 便携包

`./gradlew runtimeZip` 产出 jlink/jpackage 免安装镜像（含 EBCDIC 字符集模块 `jdk.charsets`）。

## 开发

```bash
./gradlew test          # 全量测试（v01 真实数据端到端在无数据环境自动跳过）
```

分支模型：`main` 主分支（tag `v*` 触发 Release：jpackage 便携包 + GHCR 镜像），`feature/**` 开发。

## 已知边界

- Excel 导入导出与拆分（split）端点为 Python 版遗留能力，本移植未实现（返回 501）
- `POST /api/settings/runtime`、`POST /api/settings/ai` 为只读提示，请改 `config.ini` 后重启
