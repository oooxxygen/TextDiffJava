# TextDiffJava 使用说明

大规模（千万级行）新旧文本行级比对系统。本文面向使用者，覆盖安装、配置、界面功能、列名映射、AI 归纳分析、任务管理与导出。

> 里程碑与技术演进见 [MILESTONES.md](MILESTONES.md)；REST 契约摘要见本文第九节（响应统一 snake_case）。

## 一、快速开始

环境要求：JDK 21（便携包除外）。

```bash
# 方式一：源码运行
./gradlew bootRun

# 方式二：构建 jar 运行（推荐）
./gradlew bootJar
java -jar build/libs/TextDiffJava-0.1.0.jar

# 方式三：Docker
docker build -t textdiffjava .
docker run -p 8080:8080 -v $PWD/data:/data textdiffjava
```

启动后打开 http://localhost:8080 。

**首次启动**会在运行目录自动生成 `config.ini`（含全部默认值与中文注释），按需修改后重启生效；已有文件不会被覆盖。运行目录还承载 `results/`（结果）、`store/`（任务/映射等记录）、`configs/`（对比配置）。

## 二、config.ini 配置参考

优先级：环境变量 > config.ini > 内置默认。可用环境变量：`TEXTDIFF_HOST / TEXTDIFF_PORT / TEXTDIFF_MAX_THREADS / TEXTDIFF_MAX_IN_MEMORY_BYTES / TEXTDIFF_STORE_ENABLED / TEXTDIFF_AI_ENABLED / TEXTDIFF_AI_BASE_URL / TEXTDIFF_AI_API_KEY / TEXTDIFF_AI_MODEL / TEXTDIFF_AI_TIMEOUT / TEXTDIFF_AI_MAX_PROMPT_CHARS / TEXTDIFF_AI_RETRIES / TEXTDIFF_AI_RETRY_BACKOFF_MS`。

| 段 | 键 | 默认 | 说明 |
|---|---|---|---|
| [server] | host / port | 0.0.0.0 / 8080 | 监听地址与端口 |
| [engine] | max-threads | max(4, CPU核数) | 后台对比线程池上限 |
| [engine] | in-memory-bytes | 1073741824 | 单作业内存态最大字节数，超出自动落盘（堆外） |
| [store] | enabled | true | H2 双写镜像开关（false = 纯文件模式，功能不受影响） |
| [ai] | enabled | false | AI 归纳分析开关 |
| [ai] | base_url | 空 | OpenAI 兼容地址，如 `https://open.bigmodel.cn/api/paas/v4` |
| [ai] | api_key | 空 | 密钥（保存在服务端，界面不回显） |
| [ai] | model | 空 | 模型名 |
| [ai] | timeout | 60 | 单次请求超时秒数；生成完整报告建议 ≥300 |
| [ai] | max-prompt-chars | 120000 | 提示词字符预算，超限自动压缩重渲（适配 ≤256K 小上下文窗口） |
| [ai] | retries | 3 | 弱网容错：瞬时错误重试次数 |
| [ai] | retry-backoff-ms | 2000 | 重试退避基数（指数退避） |

## 三、新建对比

1. **单文件作业**：填写 A/B 文件路径（A 必填、B 可选）、昵称、主键列（KEYSEQ）、跳过列（OMITSEQ）、分隔符（默认 `" | "`）、编码（auto 自动检测）。
2. **目录批次**：填 A/B 目录 + 配置文件（configs/ 下的 `.conf/.txt/.ini`，可多选或 use_all），系统按文件通配名自动配对生成多个作业。
3. **内联规则**：直接按 `NICK:GLOB:KEYSEQ=...:OMITSEQ=...` 语法提交单条规则。
4. **上传模式**：直接上传文件对进行对比。

配置行语法（`COLS=` 令牌可显式携带列名，`;` 分隔）：

```ini
INCT0101:01A***0*.v01:KEYSEQ=3/4/5:OMITSEQ=1/2/10:DELIM= | :ENCA=auto:SRCA=A:TRAILER=|||||
```

## 四、界面功能（Vue3，无构建）

- **作业列表**：批次/单文件作业状态徽章（pending/running/done/failed/stopped），主键重复时醒目 ⚠ 提示「配置需重检」；支持改名/星标/锁定/删除/重跑/终止/重试，全文搜索。
- **结果页**（类 Git 三分区）：
  - 一致区（蓝/绿，默认折叠懒加载）、差异区（红）、未匹配区（灰）；
  - 差异记录按列双栏对照 + **字符级 LCS 高亮**；每列标题显示真实字段名（来自列名映射）；
  - 差异列频次表（定位集中差异）；trailer（文件尾）逐字段对比与 RecNum 校验；
  - 评议 note（对主键标注说明）、全局搜索、锁定、标签、重跑；
  - **AI 分析面板**：查看/重新生成分析、下载 prompt.md、导出；
  - 导出按钮（全量 CSV / 差异 CSV）。
- **文本拆分**：Python 版遗留能力，当前未实现（端点 501）。

## 五、列名映射导入（源系统字段配置）

设置页【列名映射导入】上传两个 CSV（可多选），**纯映射落库，不生成 .conf**：

| CSV（文件名前缀） | 建立映射 | 关键列 |
|---|---|---|
| `bat_report_type_parm*` | 文件昵称 ↔ 文件名 ↔ 文件类型/归属组 | report_id、report_file_name、parm_report_type、ownership_group |
| `bat_report_conf_field*` | 文件昵称 ↔ 字段清单及属性 | report_id、field_index（1-based 列序）、field_name、field_format（类型）、field_length（长度） |

- 落库 H2 表 `report_type_parm` / `report_conf_field`（JSONL 文件为事实来源，H2 镜像可降级/自愈）。
- `report_file_name` 支持通配模式（`01A***0*.v01`，`*`/`***` 任意串、`?` 单字符，大小写不敏感），对比作业运行时按文件名匹配注入列名。
- 导入大文件（数十 MB）无压力（上传上限 100MB）；CSV 引号内换行、GBK/UTF-8 编码自动适配。
- 匹配到的归属组用于 AI 分析导出命名（见第六节）。

## 六、AI 归纳分析

每个对比作业完成（done）后自动触发（也可在结果页手动触发/重新生成）：

1. **prompt.md**：按模板（内置 `prompts/analysis-template.md`，可放 `configs/analysis-template.md` 覆盖）填充差异特征与**栏位属性表**（字段名/类型/长度）后落盘——可审计、可移植到任何环境手工复跑。
2. **ai_analysis.md**：模型产出的阅读友好 Markdown——
   - 开头为 AI 生成提示（模型名 + 生成时间）；
   - 一级标题 `[归属组]文件昵称_实际文件名`；
   - 正文：整体结论 / 差异列规律 / 可能成因 / 改进意见；
   - 文末附栏位属性表。
3. **导出**：任务管理生成的 AI 分析副本与差异 CSV **同目录**，文件名 `[归属组]文件昵称_实际文件名.md`（Windows 非法字符自动替换；归属组为空时省略方括号段）。归属组取列名映射的 `ownership_group`，无映射时退回对比配置组名。

**健壮性**（弱网 / 小上下文窗口）：

- SSE 流式接收：块间空闲看门狗 + 总时限，链路中断尽早判定转重试；服务端不支持流式自动回退；
- 瞬时错误（超时/连接失败/429/5xx）指数退避重试；参数/鉴权错误快速失败不空转；
- 提示词超 `max-prompt-chars` 预算或模型报上下文超限 → 自动紧凑压缩重渲（top 20 差异列、每列 3 组采样、属性表聚焦差异列）后重试。

## 七、任务管理

每次对比完成自动登记两条**默认生成任务**（层级：批次 → 作业 → 任务，与对比结果页一致）：

| 任务 | 产物 | 默认路径 |
|---|---|---|
| 差异CSV导出 | `*_全量.csv`（所有记录所有字段）+ `*_差异.csv`（一条字段差异一行） | `results/{批次ID}/export/`（设置页可改导出目录） |
| AI分析（文本模板） | `[归属组]文件昵称_实际文件名.md` | 与差异 CSV 同目录（结果目录另存原件 ai_analysis.md） |

- 每条任务展示：状态（待开始/进行中/已完成/失败）、产物路径、触发方式（自动/手动）、重新生成、删除。
- 重新生成 = 手动重跑（进行中不可重复触发）；删除仅移除跟踪记录，不动产物文件；删除作业/批次会级联清理任务记录。
- 服务重启自动恢复：中断残留任务重新入队，缺失任务补登记。

## 八、数据与存储布局

```
运行目录/
├── config.ini            # 配置（首次启动自动生成）
├── configs/              # 对比配置（{nickname}.conf）+ analysis-template.md 覆盖
├── results/
│   ├── {jobId}/          # 作业结果：result.jsonl / summary.json / meta.json / prompt.md / ai_analysis.md
│   └── {batchId}/export/ # 自动导出：全量/差异 CSV + AI 分析 Markdown
└── store/                # jobs/tasks.jsonl、H2 镜像（textdiff.mv.db）、字段映射、任务路径设置
```

JSONL 文件层始终是事实来源；H2 仅尽力镜像，损坏自动重建、禁用不影响功能。

## 九、REST API 摘要

| 方法 + 路径 | 说明 |
|---|---|
| POST `/api/compare` `/api/batch-compare` `/api/upload` | 单文件 / 目录批次 / 上传对比 |
| GET `/api/joblist` `/api/batches/{id}` | 作业列表 / 批次子作业 |
| GET `/api/jobs/{id}/meta` `/api/jobs/{id}/result` | 结果元信息 / 分区分页读取（q + note 过滤） |
| POST `/api/jobs/{id}/rerun` `/cancel` `/retry` `/label` `/lock` `/star` `/notes` | 作业操作 |
| DELETE `/api/jobs` `/api/batches` | 删除（锁定保护，级联清理任务） |
| GET/POST `/api/configs` `/api/configs/raw` `/api/parse-config` | 对比配置 CRUD / 解析预览 |
| POST `/api/configs/import-structure` `/api/configs/import-baseline` | 列名映射导入 / 基线导入 |
| GET `/api/fieldmaps` `/api/fieldmaps/get` | 映射浏览 / 指定报表字段清单（含类型/长度） |
| POST `/api/jobs/{id}/analyze` `/reanalyze` GET `/api/jobs/{id}/prompt` | AI 分析 / 重新分析 / 提示词下载 |
| GET `/api/jobs/{id}/export?zone=` | CSV 导出（全量/差异） |
| GET `/api/tasks` POST `/api/tasks/{id}/regenerate` DELETE `/api/tasks/{id}` | 任务跟踪 |
| GET/POST `/api/settings/tasks`，GET `/api/settings/ai|runtime`，POST `/api/settings/ai/test`，GET `/api/ai/status` | 设置与 AI 状态 |

## 十、常见问题

- **导入 CSV 报 Maximum upload size exceeded**：旧版本限制，现上限 100MB；如仍遇到请升级。
- **AI 任务失败：request timed out**：生成完整报告耗时较长，建议 `timeout = 300` 以上；已内置流式与重试。
- **模型报上下文超限**：调小 `max-prompt-chars`（如 48000），系统会自动压缩提示词重试。
- **对比结果显示「第几列」而非字段名**：请先在设置页完成列名映射导入，映射按文件名自动匹配。
- **状态旁 ⚠ 主键重复**：KEYSEQ 配置与数据不符，请重检配置（结果仍可查看）。
- **改了 config.ini 不生效**：配置重启生效（界面提示 501 的设置项均属此类）。
