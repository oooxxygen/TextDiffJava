# TextDiffJava 实施计划（最新版 2026-09-13：M0-M2 完成，进行到 M3）

## 背景与现状

**已完成（M0/M1）：** 引擎层对等 Python 版——编码自动检测（BOM + EBCDIC cp037/500/1047/GB18030 打分，默认 auto）、`" | "` 切分 + `|||||` trailer 解析、规则配置解析（KEYSEQ/OMITSEQ/IGNORESEQ/DELIM/ENCA/ENCB/SRCA/SRCB/TRAILER/REPLACE）、按列正则预变换、O(nA+nB) 哈希对比器、KeyIndex SPI（内存/堆外 mmap/自动落盘）。Vue3 无构建前端（1963 行）已移植，API 契约 `/api/jobs/{id}/meta|result|notes|export`、`/api/batches/{id}/overview|export-all`。

**已完成（M2，2026-09-13，6 个提交，全量测试绿）：**
- cf3468a feat(config): H2 依赖 + [engine]/[store] 配置节（maxThreads/maxInMemoryBytes/storeEnabled，env 覆盖）
- 9cd94d1 feat(engine): B 侧重复键检测 + Summary.keyDupA/keyDupB/dupKeySamples（cap 20）；修复 Rules 中 Comparator 同名遮蔽
- 4693fc9 feat(store): JobStore SPI + FileJobStore（JSONL 追加/last-wins/抗半行损坏/防御性拷贝）
- f284cc9 feat(store): H2JobStore（CLOB JSON 列）+ DualJobStore 双写门面（文件为事实来源/失败降级/启动自愈重建镜像）
- c392cb1 feat(store): ResultFiles——result.jsonl 流式写（JsonlSink 实现 ResultSink）/zone 分页读（unmatched=a+b）/summary.json/meta.json（JobMeta）
- 2b0dad6 feat(task): JobManager——批次（目录对+多行配置→pairFiles 配对多作业）/状态机 pending→running→done/failed/stopped/可配线程池/cancel（尽力中断）/retry/重启重入队/keyWarning 自动置位/aiStatus=pending 交接

## 剩余里程碑

### M3 REST API（对齐前端契约）——当前进行
- `POST /api/compare/submit`：目录模式（dirA/dirB + 配置行/文件，多行→多作业）+ 上传模式（multipart）；`Rules.pairFiles` 配对。
- `GET /api/jobs`、`/api/jobs/{id}/meta（含 keyWarning、aiStatus）|result(分页 zone/offset/limit)|notes|export|prompt（M5 后）|csv（M4 后）`、`POST /api/jobs/{id}/cancel|retry|reanalyze(M5 后)`、notes 读写。
- `GET /api/batches/{id}/overview`（Summary + 配置快照 + AI 分析位）、`/api/batches/{id}/export-all`（M4 后）。
- `/api/configs` CRUD、`POST /api/parse-config` 预览、编码枚举接口（含 auto 默认）。
- Spring 装配：AppConfig → DualJobStore + JobManager beans（`@Configuration`）。
- 前端小改："完成"状态旁渲染 keyWarning 醒目告警（"该对比配置需要重检"，点击查看重复键样例）。

### M4 CSV 导出
- 全量导出：所有记录全部字段；差异导出：仅差异记录，一条字段差异一行（键值、栏位号、栏位名、A 值、B 值）。
- 任务完成后自动导出到 `results/{batchId}/export/*.csv`；RFC 4180 转义自实现，零依赖。

### M5 AI 归纳分析 + 提示词模板
- 内置模板 `resources/prompts/analysis-template.md`（版本号标注），`configs/` 可覆盖；占位符：{{nickname}}/{{fileA}}/{{fileB}}/{{encodingA}}/{{encodingB}}/{{trailerTable}}/{{keyUniqueness}}/{{diffColOverview}}/{{columnFeatures}}/{{dynamicSection}}；要求 AI 返回固定结构（markdown 固定小节 + 机器可解析 JSON 块：规律/成因假设/改进意见/候选 REPLACE 规则）。
- PromptRenderer + DiffFeatureExtractor（流式扫 result.jsonl 仅 diff 行，每列采样 20~50）→ `results/{jobId}/prompt.md` 落盘（审计+移植载体）。
- AI 调用：OpenAI 兼容 `/chat/completions`，`[ai] enabled/base_url/api_key/model/timeout`，java.net.http；差异列多时按列分批多次请求。
- 产出 `ai_analysis.json`（meta/overview 返回）；候选 REPLACE 支持"应用为替换规则"重跑；`/reanalyze` 重试；aiStatus 状态机 pending/running/done/failed。

### M7 端到端验证
- `O:\CodeRepos\v01` 全流程（含 prompt.md 人工检查与移植复跑、主键告警展示、重启加载）。
- 集成测试：MockMvc 全端点、导出断言、H2 禁用回退、结果重载、DiffFeatureExtractor、模板渲染快照、AI 响应解析（mock）。

### M6 CI/CD + 交付
- GitHub Actions：ci.yml（ubuntu+windows 矩阵、JDK 21）、release.yml（tag v* → jpackage 便携包 win/linux + Docker 镜像推 GHCR + Release）。
- Dockerfile、.dockerignore、README；main 主分支 tag/release，feature 分支开发，首个 release v0.1.0。

## 执行顺序
~~M2~~ ✅ → M3 → M4 → M5 → M7 → M6，每里程碑 2-5 个语义化提交。
