# TextDiffJava 实施计划（最终版 2026-09-13：M0-M7 全部完成，v0.1.0 已打标）

## 交付状态

全部里程碑完成，全量测试绿（24 个测试类）。git：`main` 主分支 + `master` 开发分支 + tag `v0.1.0`（推送远端后触发 Release 工作流）。

### 提交清单（本阶段 12 个）
| 里程碑 | 提交 | 内容 |
|---|---|---|
| M2 | cf3468a | H2 依赖 + [engine]/[store] 配置节（env 覆盖） |
| M2 | 9cd94d1 | B 侧重复键检测 + Summary.keyDup*/dupKeySamples |
| M2 | 4693fc9 | JobStore SPI + FileJobStore（JSONL/last-wins/抗半行） |
| M2 | f284cc9 | H2JobStore + DualJobStore 双写门面（文件为事实来源/降级/自愈重建） |
| M2 | c392cb1 | ResultFiles（result.jsonl 流式写/zone 分页/summary/meta） |
| M2 | 2b0dad6 | JobManager（状态机/线程池/cancel/retry/重启重入队） |
| M3 | 5f0c0xx 一批 | store 升级（删除/notedKeys/zone 计数/snake_case） |
| M3 | (同上) | REST 全端点（契约见 docs/api-contract.md，47 端点中核心全实现，split/Excel 返回 501） |
| M3 | b575c58 | 前端 keyWarning 醒目告警（3 处状态旁） |
| M4 | 771bebb | CSV 导出（全量/差异一行一字段，RFC4180+BOM）+ 自动导出 |
| M5 | f78fb3b | DiffFeatureExtractor + 模板 v1 + PromptRenderer(prompt.md) + OpenAI 兼容调用 + ai_analysis.json |
| M7 | 6b5e612 | v01 真实数据端到端（25286/25265 行，diff 25191，编码 utf-8，prompt.md/导出产出）+ AI 修复 |
| M6 | 930bb4d | CI 矩阵 + Release 工作流 + Dockerfile + README + v0.1.0 |

### 端到端验证结论（v01：bocso vs bocsoxc，配置.txt 原文）
- 25286/25265 行，trailer 14+14，equal 2 / diff 25191 / onlyA 79 / onlyB 58，无重复键
- 差异集中列：29/63/64（64 列 23251 行）、46/47（1905）等 —— 与"新旧文本某列规律性差异"场景吻合，AI 归纳管线可采样分析
- prompt.md 恒产出（AI 禁用时 aiStatus=disabled），自动导出 CSV 产出
- 期间修复两处真实缺陷：finishNumeric 未 trim、aiHook 在状态落盘前触发

## 剩余可选项（未纳入本次范围）
- 远端仓库推送（无 remote，需用户提供 GitHub 仓库后 `git push origin main master v0.1.0`）
- Excel 导入导出、split 拆分功能（Python 版遗留，需求未要求）
- POST /api/settings/runtime|ai 运行时热更新（当前提示改 config.ini 重启）
