# TextDiffJava 里程碑文档

大规模（千万级行）新旧文本行级比对系统——Python 版 textdiff 的 Java 完整移植与增强。
技术基座：Spring Boot 3.3.4 + JDK 21（虚拟线程），运行依赖仅 `spring-boot-starter-web` + `h2`。

> 时间线按 git 提交记录整理；每行末尾为关键提交哈希，可在仓库内检索详情。

## 一、里程碑总览

| 里程碑 | 完成日期 | 主题 | 关键提交 |
|---|---|---|---|
| M0 工程骨架 | 2026-06-23 | Spring Boot 骨架、配置/路径/静态资源、字符集启动校验 | 5ab561c |
| M1 引擎核心 | 2026-06-26 | 数据模型、编码检测（EBCDIC/UTF-16/GB18030）、栏位切分与 trailer 解析 | f4cec05 → eddba50 |
| M2 规则与对比器 | 2026-06-26 | 规则解析引擎、按列正则预变换（REPLACE）、内存索引对比器 + trailer 比对 | 04f1572 → 5abf660 |
| M3 堆外引擎 | 2026-06-29 | KeyIndex SPI、mmap 行存储、堆外开放寻址哈希表、自动落盘（千万级行） | 4808247 → 1a99625 |
| M4 持久化层 | 2026-09-13 | JobStore SPI、JSONL 文件事实来源 + H2 双写镜像（降级/自愈）、结果流式落盘与分区分页 | cf3468a → c392cb1 |
| M5 任务与 REST | 2026-09-13 | 批次/作业状态机、可配线程池、cancel/retry/重启重入队、全量 REST 端点、主键唯一性告警 | 2b0dad6 → b575c58 |
| M6 导出与 AI | 2026-09-14 | CSV 全量/差异导出 + 任务完成自动导出；AI 归纳分析（提示词模板/调用/自动触发） | 771bebb → 6b5e612 |
| M7 DevOps | 2026-09-14 | GitHub Actions CI（ubuntu/windows 矩阵）、Release（jpackage 便携包 + GHCR 镜像）、Dockerfile、v0.1.0 | 930bb4d → 2e02cd0 |

## 二、v0.1.0 发布（2026-09-14）

- tag `v0.1.0` → `2e02cd0`，GitHub Release：便携包 + jar + GHCR 镜像（`ghcr.io/oooxxygen/textdiffjava`）。
- 修复 Release 工作流 GHCR 未登录问题（docker/login-action@v3）。
- 全量测试绿；CI（ubuntu/windows 矩阵）双绿；v01 真实数据端到端验收通过（差异 25191 行与历史基线一致）。

## 三、v0.1.0 后特性冲刺

### 1. 列名映射导入 v1（2026-09-14，4a44bc1）

- 结构 CSV（`bat_report_type_parm*` / `bat_report_conf_field*`）解析入库；
- FieldMapStore：JSONL 事实来源 + H2 双表镜像（禁用/失败自动降级，启动自愈重建）；
- 对比作业运行时按文件名自动注入列名（COLS），对比界面与导出显示真实字段名。

### 2. 任务管理（2026-09-15，bfad874 / 7142b3b / 150bfe1 / c05f5ff）

- 作业完成自动登记两条默认生成任务：**差异 CSV 导出**、**AI 分析（文本模板）**，状态机 pending→running→done/failed，trigger auto/manual；
- 【任务管理】导航页：批次 → 作业 → 双任务行三级层级（与对比结果一致），支持重新生成 / 删除；
- 生成路径可在设置页配置；作业/批次删除级联清理任务记录；作业已删除的任务明确置 failed（不永久卡「待开始」）；
- 任务管理 → 结果/批次支持原路返回；搜索框样式统一。

### 3. 列名映射导入重构 + AI 分析 Markdown 化（2026-09-15，1968463）

**导入重构（纯映射落库，不再生成 .conf）：**

| CSV | 建立映射 | 关键列 |
|---|---|---|
| `bat_report_type_parm*` | 昵称 ↔ 文件名 ↔ 文件类型/归属组 | report_id、report_file_name（支持 `01A***0*.v01` 通配匹配）、parm_report_type、**ownership_group（归属组）** |
| `bat_report_conf_field*` | 昵称 ↔ 字段清单及属性 | report_id、field_index（1-based 定序）、field_name、field_format（类型）、**field_length（长度）** |

- 落库 H2 表 `report_type_parm` / `report_conf_field`（旧库自动补列迁移）；multipart 上限放开至 100MB（此前 6.2MB 字段表被默认 1MB 拒绝）；CSV 引号内换行记录级解析。

**AI 分析 Markdown 产物：**

- 产物 `ai_analysis.md`（替代 ai_analysis.json）：**开头为 AI 生成提示**（模型名 + 生成时间），**一级标题 `[归属组]文件昵称_实际文件名`**（昵称已含文件名时不重复拼接），文末附**栏位属性表**（字段名/类型/长度）；
- 栏位属性（field_format + field_length）同时注入提示词，辅助模型判断补位/截断/精度类差异；
- 任务管理导出的 AI 分析副本与差异 CSV **同目录**，命名 `[归属组]文件昵称_实际文件名.md`；`ai_dir` 独立目录设置废弃。

**AI 健壮性（弱网 / 小上下文窗口 ≤256K 兼容）：**

- **SSE 流式接收**：块间空闲超时（max(30s, 超时/5)）+ 总时限双看门狗，链路中断尽早判定转重试；服务端不支持 stream 自动回退非流式；
- **指数退避重试**：超时/连接失败/HTTP 429/5xx 重试 `retries` 次（基数 `retry-backoff-ms`）；参数/鉴权类错误快速失败；
- **上下文预算压缩**：提示词超 `max-prompt-chars`（默认 120000 字符）或模型报上下文超限时，自动切换紧凑预算重渲（top 20 差异列、每列 3 组采样、属性表裁剪至差异列），兜底截断送达。

### 4. 默认配置生成（2026-09-15，6564125）

- 首次启动（config.ini 不存在）自动生成带注释的默认配置文件（[server]/[engine]/[store]/[ai] 全键位），用户修改后重启生效；已有文件绝不覆盖；生成失败按内置默认值继续运行。

### 5. 分析模板 v2 与任务编排增强（2026-09-16，fa76589 + 本批）

- **AI 分析模板 v2**：输出固定四节——结论（分点事实清单：文本/对照规则列数+字段名/条数偏离值/差异栏位占比/尾部条数核对）、主键唯一定位检测（重复键红色 🔴 突出）、差异栏位分析（特征 + **TOP20 差异明细表**（主键+A/B 值）+ 克制观察）、文件尾部纯表格对比；移除 JSON 块与发散成因要求；提示词采样含记录主键（TOP20，紧凑预算仍 3）。
- **AI 并发控制**：`[ai] max-concurrency`（默认 2，env `TEXTDIFF_AI_MAX_CONCURRENCY`），批量任务经信号量限制同时调用 AI 的并发数。
- **批量重新生成**：任务管理页勾选任务批量重跑（差异 CSV / AI 分析），可选未来执行时间（datetime-local）；`POST /api/tasks/batch-regenerate`（task_ids / job_ids + run_at）；`scheduled_at` 持久化（JSONL + H2 `task_record` 补列），重启后由调度器到点恢复执行。

## 四、当前状态

- 分支模型：`main` 主线（tag `v*` 触发 Release），CI 双平台矩阵常绿。
- 测试：143 例全绿（引擎/存储/AI/任务/端点/真实数据端到端，无样例数据环境自动跳过）。
- 已知边界（Python 版遗留未移植）：Excel 配置导出、split 文本拆分端点返回 501；`POST /api/settings/runtime|ai` 为只读提示（改 config.ini 重启）。
