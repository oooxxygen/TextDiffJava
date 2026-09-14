# TextDiff 差异归纳分析提示词模板 v1

> 本文件为机器填充的提示词：由 TextDiff 在对比完成后按本模板生成 `prompt.md`。
> 可直接复制到任何大模型（OpenAI 兼容接口、智谱、OpenRouter 等）执行分析，保证跨环境可移植、可追踪。
> 填充时间：{{generatedAt}} · 模板版本：{{templateVersion}}

## 角色与任务

你是资深数据核对专家。请基于下方"对比固定信息"与"差异特征"，对本次新旧文本比对结果做归纳分析，输出**严格固定结构**（见文末"输出格式要求"）。

## 一、对比固定信息

- 作业昵称：{{nickname}}
- 文件 A（旧）：{{fileA}}
- 文件 B（新）：{{fileB}}
- A 侧编码：{{encodingA}} · B 侧编码：{{encodingB}}
- 主键配置：KEYSEQ={{keySeq}}（0-based 列：{{keyColumns}}）
- 跳过列：OMITSEQ={{omitSeq}}（0-based 列：{{omitColumns}}）
- 分隔符：{{delimiter}}

### 栏位属性（源系统字段配置）

分析时请结合各栏位的类型与长度判断差异成因（如补位、截断、格式化、精度重算等）。

{{fieldAttributes}}

### 文件尾部（trailer）对比

{{trailerTable}}

### 主键唯一性检测

{{keyUniqueness}}

## 二、差异概况

{{diffColOverview}}

## 三、逐差异列特征（采样）

{{columnFeatures}}

{{dynamicSection}}

## 输出格式要求（必须严格遵守）

请输出以下固定结构，先 Markdown 小节、再一个机器可解析的 ```json 代码块：

### 固定小节
1. `## 整体结论`：3-5 句话总结本次对比（总量、差异规模、集中列）。
2. `## 差异列规律`：逐差异列分析数值分布与模式（递增/位移/格式变化/空值/日期推进等）。
3. `## 可能成因`：对最显著的 2-3 个规律给出业务侧可能原因假设。
4. `## 改进意见`：针对比对配置（KEYSEQ/OMITSEQ/REPLACE）与数据质量给出可执行建议。

### JSON 块
```json
{
  "overall": "一句话整体结论",
  "columns": [
    {"col": 0, "name": "列名", "pattern": "规律描述", "cause": "可能成因", "confidence": "high|medium|low"}
  ],
  "causes": ["成因1", "成因2"],
  "suggestions": ["建议1", "建议2"],
  "candidate_replace": [
    {"col": 0, "pattern": "正则或字面量", "repl": "替换为", "reason": "建议理由"}
  ]
}
```
其中 `candidate_replace` 用于将规律固化为 TextDiff 的 REPLACE 预处理规则（0-based 列号），无建议时给空数组。

{{aiHints}}
