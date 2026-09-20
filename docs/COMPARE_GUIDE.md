# TextDiff 对比原理与实例新手指南

> 面向第一次接触本系统的用户：讲清**普通文本、报表、非结构化（自定义格式）**三类对比模块的原理与核心实现，并用真实数据逐一演示**完全匹配（含/不含跳过配置）、部分匹配、未匹配**各种情形下的数据展示、导出与对比结论。
>
> 文中所有实例均可复现：示例数据位于 `acceptance-run/guide/`，通过服务 REST 接口提交即可得到与本文一致的输出。

---

## 1. 系统概览

TextDiff 是一个本地部署的**文件对比与核对平台**（Spring Boot 3 + JDK 21 后端，Vue 3 免构建前端），围绕一个核心问题展开：**A、B 两侧下传/生成的数据文件，内容到底有没有差异、差异在哪里、严重程度如何。**

| 模块 | 适用文件 | 配对身份 | 代表场景 |
| --- | --- | --- | --- |
| 普通文本对比 | 分隔符文本（`\|`、逗号、制表符…） | 配置的主键列（KEYSEQ） | 下传数据文件逐记录核对 |
| 报表对比 | 固定版式报表（含模板、表尾合计、折行、分页） | 整行 / 配置主键 / 跳过列 | 核对日报表、账页类报表 |
| 自定义格式对比 | 非结构化文本（SWIFT MT950 等） | 正则提取的段主键 | 报文、日志类分段文件 |

配套能力：字段名映射（Excel 导入后自动给差异列命名）、特殊字符集字段转码（EBCDIC 内嵌 UTF-16）、差异 CSV / 全量 CSV / 批次明细 Excel 导出、AI 分析（Markdown + 自包含 HTML 报告）。

---

## 2. 所有模块共用的概念

**A 侧 / B 侧**。每次对比都围绕一对文件（或两对目录）进行：A 侧通常是"源系统下传"的基准，B 侧是"目标系统落地"的待核对方。接口参数为 `file_a / file_b` 或 `dir_a / dir_b`。

**记录与主键**。文本模块把一行按分隔符切成若干"栏位"（列）。主键（配置格式 `KEYSEQ=1/2`，1-based）负责回答"哪一行和哪一行是一回事"。主键在系统内部用不可见字符 `0x1F` 拼接：

```java
/** 组合主键内部连接符（unit separator 0x1F，数据中几乎不可能出现）。 */
public static final String KEY_SEP = String.valueOf((char) 0x1F);

/** 组合主键（用原值拼接）；列不足以取键时返回 null（malformed）。 */
public String keyOf(String[] cols) {
    if (keyColumns.isEmpty()) return null;
    StringBuilder sb = new StringBuilder();
    for (int j = 0; j < keyColumns.size(); j++) {
        int i = keyColumns.get(j);
        if (i < 0 || i >= cols.length) return null;
        if (j > 0) sb.append(KEY_SEP);
        sb.append(cols[i]);
    }
    return sb.toString();
}
```
（`src/main/java/com/textdiff/engine/Rules.java`）

界面与导出文件中 `0x1F` 一律显示为 `:`，例如主键 `1+2 列` 显示为 `A0001:DEPOSIT`。

**跳过栏位**。`OMITSEQ=4` 表示第 4 列"不参与差异判定、也不参与身份定位"——A、B 两侧第 4 列不同不算差异；报表/文本模式下未配置主键时，跳过列还会被排除出"整行键"。

**四种对比结论**。每条记录的对比结果必然是四者之一：

| 状态 | 含义 | 界面颜色 |
| --- | --- | --- |
| `equal` | A、B 两侧该记录完全一致 | 绿 |
| `diff`（partial） | 记录配对成功，但存在不同栏位 | 红 |
| `unmatched_a`（仅 A 有） | B 侧找不到这条记录 | 橙 |
| `unmatched_b`（仅 B 有） | A 侧找不到这条记录 | 橙 |

**分区**。文本模块区分"数据区"与"表尾区（TRAILER）"；报表模块进一步区分"表头区 / 业务区 / 表尾区"；自定义格式模块区分"文件头 / 段 / 文件尾"。展示与导出都会标注 `section`，让表头表尾的"相同"不与业务数据的"相同"混在一起。

**数量与总体评判**。批次维度会统计两侧总记录数，并按偏离率给出等级（见 §6.5）：偏离 ≥10% 保持关注、≥20% 差异明显、≥50% 严重问题。

---

## 3. 模块一：普通文本对比

### 3.1 原理

```
读取文件 → 自动检测编码 → 逐行按分隔符切列（表尾区单独收集）
        → 按主键列生成主键
        → A 侧全部记录建内存/磁盘索引（同时检测重复键）
        → B 侧逐条流式比对：命中键 → 逐列比较；未命中 → 仅 B 有
        → A 侧未被命中过的键 → 仅 A 有
        → 表尾（TRAILER）键值对单独比对（含 RecNum 行数核对）
```

特点：**流式 + 外排**，几百万行也不会撑爆内存（索引超限自动落盘）；文本模块**没有"部分匹配"**——主键对不上就是"仅 A/仅 B"，不会猜测两条残缺记录是同一条。

### 3.2 核心代码

**① 编码自动检测**（决定文件按什么编码读）。按候选编码逐一"严格解码 + 打分"，分隔符出现次数多、可打印比例高者胜。关键防错点：样本里出现 `0x20` 字节就排除 EBCDIC 家族——因为 EBCDIC 的空格是 `0x40`，`0x20` 是控制符，纯 ASCII 文本若被误判成 EBCDIC 会整个变成乱码：

```java
for (String enc : CANDIDATES) {
    if (hasAsciiSpace && EBCDIC_CANDIDATES.contains(enc)) continue;
    String text = tryDecodeStrict(sample, enc);
    if (text == null) continue;            // 解码失败
    double ratio = printableRatio(text);
    if (ratio < 0.5) continue;             // 可打印比例过低 → 视为无效
    long delim = countOccurrences(text, delimiter);
    if (!any || delim > bestDelim || (delim == bestDelim && ratio > bestRatio)) { ... }
}
```
（`src/main/java/com/textdiff/engine/Encoding.java`）

**② 行 → 记录切分**（`Parser.parseData`）。遇表尾前缀（默认 `|||||`）即进入表尾收集；空行计入表尾空行；普通行按分隔符切列：

```java
if (!inTrailer[0] && line.startsWith(trailerPrefix)) inTrailer[0] = true;
if (inTrailer[0]) { consumeTrailerLine(line, trailerPrefix, trailer); return; }
if (line.isEmpty()) { trailer.emptyCount++; return; }
String[] cols = splitColumns(line, delimiter);
trailer.dataCount++;
data.add(cols);
```
（`src/main/java/com/textdiff/engine/Parser.java`）

**③ 无主键时的整行回退**。未配置 KEYSEQ 时，"整行即身份"——任意一列变化都会被视为"删一行 + 加一行"，而不是报错或静默丢数据：

```java
/**
 * 取记录主键：配置了 KEYSEQ 用主键列；未配置任何主键时整行作键（等价「整行对比」模式）；
 * 仅当主键列越界（列不足/格式异常）返回 null 计入 malformed。
 */
private static String keyOf(Rules.RuleEngine engine, String[] cols) {
    if (engine.keyColumns.isEmpty()) return String.join(Rules.KEY_SEP, cols);
    return engine.keyOf(cols);
}
```
（`src/main/java/com/textdiff/engine/Comparator.java`）

**④ 主索引 + 流式比对**（`Comparator.compareFiles` 两个阶段）：

```java
// 阶段 1：建 A 索引；put 前探查以捕获重复键
for (String[] cols : Parser.parseData(la, delim, tp, trailerA)) {
    String key = keyOf(engine, cols);
    if (key == null) { summary.malformedA++; continue; }
    if (index.get(key) != null) { summary.keyDupA++; addDupSample(summary, key); }
    index.put(key, cols);
}
// 阶段 2：流式比对 B
for (String[] cols : Parser.parseData(lb, delim, tp, trailerB)) {
    String key = keyOf(engine, cols);
    ...
    String[] aCols = index.get(key);
    if (aCols == null) { sink.addRow(RowDiff.onlyB(...)); summary.onlyB++; }
    else {
        index.markSeen(key);
        int[] diffCols = compareCols(aCols, cols, engine);
        if (diffCols.length > 0) { sink.addRow(RowDiff.diff(...)); summary.diff++; }
        else { sink.addRow(RowDiff.equal(...)); summary.equal++; }
    }
}
// 阶段 3：A 中未被 markSeen 的键 → 仅 A 有；阶段 4：表尾比对
```
（节选，`src/main/java/com/textdiff/engine/Comparator.java`）

### 3.3 实例数据与运行结果

示例数据（`acceptance-run/guide/text/`，分隔符为 ` | `），字段名 `REC_ID/NAME/AMT/STATUS/CITY` 由配置 `COLS=` 提供：

```text
A/完全匹配.txt            B/完全匹配.txt
K0001 | 张三 | 100.00 | A | 上海
K0002 | 李四 | 200.00 | B | 北京      ← 五行与 A 侧逐字节一致
K0003 | 王五 | 150.50 | A | 广州
K0004 | 赵六 | 300.00 | C | 深圳
K0005 | 钱七 | 80.25 | B | 杭州
```

配置行（`acceptance-run/configs/guide.conf`）：

```text
全等场景:完全匹配.txt:KEYSEQ=1:DELIM= | :COLS=<base64(字段名)>:ENCA=utf-8:ENCB=utf-8
```

#### 场景 1：完全匹配（含主键、不含跳过配置）

提交 `POST /api/batch-compare`（dir_a=text/A, dir_b=text/B, config_file=guide.conf）后，作业 **全等场景 · 完全匹配.txt** 的汇总：

```json
{ "equal": 5, "diff": 0, "only_a": 0, "only_b": 0, "total_a": 5, "total_b": 5,
  "encoding_a": "utf-8", "encoding_b": "utf-8", "key_warning": false }
```

**结论解读**：5 条记录全部 `equal`，主键无重复警告，两侧编码都自动识别为 UTF-8。批次页显示绿色"全等"，总体评判为"正常"。

#### 场景 2：部分匹配与未匹配

`部分与未匹配.txt`：A 侧 `K0002` 金额 100（B 侧 200），A 侧多一条 `K0005`，B 侧多一条 `K0006`：

```text
A/部分与未匹配.txt          B/部分与未匹配.txt
K0001 | 张三 | 100.00 | A | 上海        K0001 | 张三 | 100.00 | A | 上海
K0002 | 李四 | 100.00 | B | 北京        K0002 | 李四 | 200.00 | B | 北京
K0003 | 王五 | 150.50 | A | 广州        K0003 | 王五 | 150.50 | A | 广州
K0004 | 赵六 | 300.00 | C | 深圳        K0004 | 赵六 | 300.00 | C | 深圳
K0005 | 钱七 | 80.25 | B | 杭州         K0006 | 孙八 | 66.00 | A | 成都
```

汇总：`equal=3, diff=1, only_a=1, only_b=1`。结果接口（`GET /api/jobs/{id}/result`）：

```json
{"key": "K0001", "status": "equal",       "section": "data",
 "a_cols": ["K0001","张三","100.00","A","上海"], "b_cols": ["K0001","张三","100.00","A","上海"], "diff_cols": []}
{"key": "K0002", "status": "diff",        "section": "data",
 "a_cols": ["K0002","李四","100.00","B","北京"], "b_cols": ["K0002","李四","200.00","B","北京"], "diff_cols": [2]}
{"key": "K0006", "status": "unmatched_b", "section": "data",
 "a_cols": null, "b_cols": ["K0006","孙八","66.00","A","成都"], "diff_cols": []}
{"key": "K0005", "status": "unmatched_a", "section": "data",
 "a_cols": ["K0005","钱七","80.25","B","杭州"], "b_cols": null, "diff_cols": []}
```

**结论解读**：`K0002` 主键相同、第 3 列（`diff_cols=[2]`，界面显示为"栏位 3 / AMT"）不同 → `diff`；`K0005` 只在 A、`K0006` 只在 B。文本模块**不会**把"李四 100"和"李四 200"以外的残缺记录做相似度撮合——这就是文本与报表模块在"部分匹配"上的本质区别（见 §4.3 场景 2）。

#### 场景 3：整行无主键模式

同一对"完全匹配"文件，配置行**去掉 KEYSEQ**：

```text
整行无主键:完全匹配.txt:DELIM= | :COLS=...:ENCA=utf-8:ENCB=utf-8
```

汇总：`equal=5, diff=0, only_a=0, only_b=0`。此时每条记录的"键"就是整行（内部 `0x1F` 连接、展示为 `:`）：

```json
{"key": "K0001:张三:100.00:A:上海", "status": "equal", ...}
```

**结论解读**：若 B 侧任何一列被改动，该行键随之改变，将呈现为"仅 A 有一行 + 仅 B 有一行"。整行模式适合"行本身即身份"的文件（如流水日志），核对"逐字节一致"。

#### 场景 4：完全匹配 + 跳过配置

`跳过演示.txt` 里 STATUS 列（第 4 列）与 AMT 列（第 3 列）都被改动：

```text
A/跳过演示.txt              B/跳过演示.txt
K0001 | 张三 | 100.00 | A | 上海        K0001 | 张三 | 100.00 | X | 上海
K0002 | 李四 | 100.00 | B | 北京        K0002 | 李四 | 200.00 | B | 北京
K0003 | 王五 | 150.50 | A | 广州        K0003 | 王五 | 150.50 | Z | 广州
```

| 作业 | 配置 | equal | diff | 结论 |
| --- | --- | --- | --- | --- |
| 跳过前对比 | `KEYSEQ=1` | 0 | 3 | 三条全部有差异（K0001/K0003 状态列、K0002 金额列） |
| 跳过后对比 | `KEYSEQ=1:OMITSEQ=4` | 2 | 1 | 状态列被"忽略"：K0001、K0003 变为全等，只剩 K0002 金额差异 |

跳过后的差异 CSV（`GET /api/jobs/{id}/export?type=diff`）：

```csv
键值,状态,分区,REC_ID(A),REC_ID(B),NAME(A),NAME(B),AMT(A),AMT(B),STATUS(A),STATUS(B),CITY(A),CITY(B)
K0001,equal,data,K0001,,张三,,100.00,,A,,上海,
K0002,diff,data,K0002,K0002,李四,李四,100.00,200.00,B,B,北京,北京
K0003,equal,data,K0003,,王五,,150.50,,A,,广州,
```

**结论解读**：OMITSEQ 的作用是"明知该列两侧天然不同（如流水号、时间戳、机构号），把它排除出核对范围"。对比"跳过前/跳过后"两张汇总即可看出配置的效果。

---

## 4. 模块二：报表对比

### 4.1 原理

报表与普通文本最大的不同：**报表自带版式**——表头标题、栏位行、页与页之间重复的表头、末尾的合计（`TOTAL-COUNT`/`TOTAL-AMT`），甚至一行记录折成多行（续行）。报表对比的思路：

```
模板（<报表名>.header 文件）与报表 A/B 逐行对照 → 定位表头长度
→ 在报表里找"表头锚点行"确定每页/每段的边界
→ 每段剥掉表头块；段尾按模板骨架剥掉表尾块（值填充形态匹配）
→ 中间剩下的就是业务行
→ 业务行按"整行键 / 配置主键 / 去除跳过列后的键"分组配对
→ 配不上的残余记录做一轮"相似度撮合"（部分匹配）
→ 同时对表尾的 COUNT/AMT 声明值与实际统计做数量核对
```

### 4.2 核心代码

**① 表头定位**（`detectHeaderLen`）：模板第 i 行与报表第 i 行完全一致、非空白，且模板下一行是空白（表头与表尾骨架的分隔），则表头长度 = i+1：

```java
private static int detectHeaderLen(List<String> template, List<String> report) {
    int m = template.size(), n = report.size();
    int h = 0;
    for (int i = 0; i < Math.min(m, n); i++) {
        String t = template.get(i);
        if (t.equals(report.get(i)) && !t.isBlank()
                && (i + 1 >= m || template.get(i + 1).isBlank())) {
            h = i + 1;
        }
    }
    return h;
}
```
（`src/main/java/com/textdiff/report/ReportParser.java`）

**② 分区与表尾剥离**。定位后模板剩余部分是"表尾骨架"；对每段内容区自尾向上逐行与骨架对齐，允许"值填充形态"（模板写 `END`，报表写 `END|3|`）：

```java
String anchor = template.get(headerLen - 1);   // 表头最后一行是锚点
for (int i = headerLen - 1; i < n; i++)
    if (report.get(i).equals(anchor)) anchors.add(i);   // 每个锚点 = 一页/一段表头
...
// 表尾块匹配：骨架自内容区尾部向上逐行对齐
if (g.equals(f) || (g.isBlank() && f.isBlank()) || filledLike(g, f)) ... 
```
（节选，`ReportParser.parse`）

**③ 相似度撮合（部分匹配）**。主键配不上的残余 A/B 记录，用"值倒排索引 + 逐列相似度"找最佳配对；相似度 = 各列（相等 1 分 / 一空一非 0 分 / 编辑距离得分）的平均值，阈值 0.6：

```java
for (Row x : leftA) {
    String[] pfx = projected(x.fields(), skip);
    int best = -1; double bestScore = 0;
    for (String v : pfx) {                       // 用 A 记录的每个值查 B 的倒排索引
        for (int bj : candidates) {
            double sc = similarity(pfx, projB.get(bj));
            if (sc >= PARTIAL_THRESHOLD && sc > bestScore) { bestScore = sc; best = bj; }
        }
    }
    if (best >= 0) { out.add(RowDiff.diff(...)); partial++; }  // 撮合成功 → diff
    else unmatchedA.add(x);                                     // 找不到 → 仅 A 有
}
```
（节选，`src/main/java/com/textdiff/report/ReportComparator.java`）

**④ 数量核对**：表尾声明的 `TOTAL-COUNT`/合计数量与实际统计行数比对，结果写进摘要 `count_checks`。

### 4.3 实例数据与运行结果

模板 `R901.header`（表头 3 行 + 空行 + 表尾骨架 2 行）：

```text
BANK-DAILY-LIST
        LIST
  ACCT  NAME  AMT

  TOTAL-COUNT   TOTAL-AMT
  END
```

报表 `A/R901.102`（B 侧有两处差异：A0002 金额 250→260；A0002 那行之外，A 的 A0003 在 B 变成 A0009）：

```text
A/R901.102                          B/R901.102
BANK-DAILY-LIST                     BANK-DAILY-LIST
        LIST                                LIST
  ACCT  NAME  AMT                     ACCT  NAME  AMT
  A0001  DEPOSIT  100.00              A0001  DEPOSIT  100.00
  A0002  LOAN  250.00                 A0002  LOAN  260.00
  A0003  FEE  12.50                   A0009  FEE  7.00
                                      （A 侧此处为 A0003，B 侧换成 A0009）
  TOTAL-COUNT   TOTAL-AMT             TOTAL-COUNT   TOTAL-AMT
  END|        3|                      END|        3|
```
（两侧相同：表头 3 行、空行、表尾 2 行；业务行第 2、3 行有差异）

#### 场景 1：完全匹配（R900）

`rpt/A` 与 `rpt/B` 中内容一致的 `R900.101`（A0001~A0003，无跳过/主键配置，整行对比）。汇总：

```json
{ "section_count_a": 1, "row_count_a": 3, "equal": 3, "partial": 0,
  "header_block_diff": 0, "footer_block_diff": 0,
  "count_checks": [
    {"side": "A", "declared": "3", "counted": 3, "match": true},
    {"side": "B", "declared": "3", "counted": 3, "match": true}],
  "warnings": [] }
```

结果流分三个分区（`section` 字段）：

```json
{"key":"1#1","status":"equal","section":"header","a_cols":["BANK-DAILY-LIST"], ...}
{"key":"1#1","status":"equal","section":"footer","a_cols":["  TOTAL-COUNT   TOTAL-AMT"], ...}
{"key":"A0001  DEPOSIT  100.00","status":"equal","section":"data",
 "a_cols":["A0001","DEPOSIT","100.00"], "b_cols":["A0001","DEPOSIT","100.00"]}
```

**结论解读**：表头 3 行、表尾 2 行原样比对；业务 3 行全等；表尾声明的记录数 3 与实际统计 3 一致（`count_checks.match=true`）。这类"账实相符"是报表核对最关心的结论之一。

#### 场景 2：部分匹配与未匹配（整行模式，R901）

R901 的汇总：`equal=1, partial=2, only_a=0, only_b=0`，count_checks 同样全部匹配。两条 diff：

```json
{"key":"A0002  LOAN  250.00","status":"diff","section":"data",
 "a_cols":["A0002","LOAN","250.00"], "b_cols":["A0002","LOAN","260.00"], "diff_cols":[2]}
{"key":"A0003  FEE  12.50","status":"diff","section":"data",
 "a_cols":["A0003","FEE","12.50"], "b_cols":["A0009","FEE","7.00"], "diff_cols":[0,2]}
```

**结论解读**：第二条就是报表模块特有的**部分匹配**——`A0003 | FEE | 12.50` 与 `A0009 | FEE | 7.00` 主键对不上，但相似度撮合发现两行 3 列里"NAME=FEE"相同、其余两列部分相似（平均相似度 2/3 ≥ 阈值 0.6），于是撮合成一条 `diff`（差异栏位 1、3），而不是粗暴地报"仅 A 有 A0003 + 仅 B 有 A0009"。撮合失败的残余记录才会进入 `unmatched_a/b`（`only_a/only_b` 计数）。

#### 场景 3：主键模式（KEYSEQ=1）

同一对文件，提交时带 `key_seq=1`（以 ACCT 列为主键）。此时差异明细的"键值"变成主键列本身（`A0002`），撮合行为不变：

```json
{"key":"A0002","status":"diff", ..., "diff_cols":[2]}
{"key":"A0003  FEE  12.50","status":"diff", ..., "diff_cols":[0,2]}
```

**结论解读**：主键只影响"如何配对 + 键值展示"；金额列差异（栏位 3）与账号变更是两个独立结论。适合栏位错位但语义相同的报表。

#### 场景 4：跳过模式（OMITSEQ=3）

提交 `omit_seq=3`（金额列不参与）。汇总变为 `equal=2, partial=1`：K 侧 A0002 的 250→260 被跳过、A0001/A0002 全等；A0003/A0009 的撮合差异只剩账号列（`diff_cols=[0]`）。

```csv
状态,行标识,栏位号,栏位名,A值,B值
diff,A0003  FEE  12.50,1,栏位1,A0003,A0009
```

**结论解读**：跳过列在主键配对、差异判定、撮合相似度三个环节都被排除——比"眼睛 ignore"更彻底。

#### 场景 5：模板缺失（报错示范）

若把 `R901.102` 与 `R900.header` 混在一个目录提交，作业直接失败并给出可操作的错误：

```json
{ "status": "error", "error": "模板不存在：请在模板路径或数据目录下提供 R901.header" }
```

#### 场景 6：折行 / 控制行版式（X1000）

报表以控制行 `1@OD@|...` 开头时，系统**无需模板**自动分段、识别折行和分页。示例（`guide/text/rptfold/`）每条记录一行主行 + 一行续行（交易时间）：

```text
1@OD@|@T@|BANK-CODE:102|ORG-ID:51365|RPT-ID:X1000|DAT:2026/08/01|PRODUCT:50150206|
  A/C          CCY        AMT
  TX Time
  00100000001  USD   100.00
   00:06:04          ← 续行（折行）
  00100000002  JPY   200.00
   00:06:05

   CUR PG QTY:              2    ← 表尾声明数量
```

B 侧把第二条的金额 200→250、续行时间 00:06:05→00:07:12。汇总：

```json
{ "control_format": true, "fold_lines": 2, "row_count_a": 2, "equal": 1, "partial": 1,
  "count_checks": [ {"side":"A","declared":"2","counted":2,"match":true},
                    {"side":"B","declared":"2","counted":2,"match":true} ],
  "field_names": ["A/C","CCY","AMT","行2·TX Time"] }
```

折行记录的差异展示保留**两行物理行原文**：

```json
{"key":"00100000002  JPY   200.00","status":"diff","section":"data",
 "a_cols":["  00100000002  JPY   200.00","   00:06:05"],
 "b_cols":["  00100000002  JPY   250.00","   00:07:12"], "diff_cols":[0,1]}
```

**结论解读**：栏位名从列头自动提取（续行命名"行2·TX Time"）；`fold_lines=2` 表示识别出 2 条折行记录；数量核对把表尾 `CUR PG QTY: 2` 与实际 2 条业务行比对一致。差异 CSV 里折行记录的两个物理行会合并进一格（引号包裹、内含换行）。

---

## 5. 模块三：自定义格式（非结构化文本）

### 5.1 原理

没有模板、没有固定列的文件（SWIFT 报文、日志……）用**正则切段**：

```
段起始正则（如 ^\{1:）命中 → 开始一段
段结束正则（如 ^-}）命中 → 结束一段（缺省=下一个段起始行前）
段内主键正则（如 :20:(\S+)）第一个命中行的捕获组 1 → 段主键
主键相同的段两两配对 → 段全文一致 = equal；否则差异行号列表 = diff
配不上的段 → 仅 A 有 / 仅 B 有；首个段起始行之前的行 → 文件头，最后段之后 → 文件尾
```

### 5.2 核心代码

```java
while (i < n) {
    if (!start.matcher(lines.get(i)).find()) {          // 段起始之前的行 → 文件头
        if (firstStart < 0) head.add(lines.get(i));
        i++; continue;
    }
    int s = i, e;
    for (int j = s + 1; j < n && e < 0; j++) {
        if (end.matcher(lines.get(j)).find()) e = j;
        else if (start.matcher(lines.get(j)).find()) break;  // 段未正常结束即遇新段：就地截断
    }
    if (e < 0) { e = n - 1; warnings.add("...按文件尾截断"); }
    out.add(new Segment(extractKey(lines.subList(s, e + 1), key), lines.subList(s, e + 1), s + 1));
    i = e + 1;
}

/** 主键：主键式在段内首个命中行的捕获组 1（无捕获组取整体命中）。 */
static String extractKey(List<String> segLines, Pattern key) {
    for (String line : segLines) {
        Matcher m = key.matcher(line);
        if (m.find()) return m.groupCount() >= 1 ? m.group(1) : m.group();
    }
    return null;
}
```
（`src/main/java/com/textdiff/custom/SegmentParser.java`）

段的比对：主键相同 → 段全文相等即 `equal`，否则按**行数 + 逐行**定位差异（`diffCols` 记录差异行号）；主键配不上 → 单侧 unmatched。

### 5.3 实例：MT950 报文

原始样例（`O:/CodeRepos/NonStructuralData/MT950FILE.txt`）含 3 段 SWIFT MT950（段主键 `:20:SM26080100000001/2/3`）。构造 B 侧：**乱序**（003 放最前）、**改动**（002 段 `RD224`→`RD999`）、**伪造新增**（009 段），并删除 001 段：

```json
// 提交 POST /api/custom-compare
{ "start_pattern": "^\\{1:", "end_pattern": "^-}", "key_pattern": ":20:(\\S+)",
  "dir_a": ".../MT950/A/MT950FILE.txt", "dir_b": ".../MT950/B/MT950FILE.txt" }
```

汇总：`segments_a=3, segments_b=3, equal=1, diff=1, only_a=1, only_b=1`，四状态一屏俱全：

```text
unmatched_a  SM26080100000001   11 行（A 侧整段原文，B 侧 null）
diff         SM26080100000002   diff_cols=[7]（段内第 8 行 RD224→RD999）
equal        SM26080100000003    9 行（乱序不影响：段以主键配对，与出现顺序无关）
unmatched_b  SM26080100000099   11 行（伪造段被揪出）
```

导出 CSV（`GET /api/custom-jobs/{id}/export`）以"状态,主键,段内行号,A内容,B内容"组织（全等段不进入差异导出）：

```csv
状态,主键,段内行号,A内容,B内容
unmatched_a,SM26080100000001,,"{1:F01BKCHJPJTAXXX0001000001}{2:I950...}{4:
:20:SM26080100000001
:25:9513710019011025
..."
diff,SM26080100000002,8,":61:2608010801RD224,NMSCPP1005686353",":61:2608010801RD999,NMSCPP1005686353"
```

**结论解读**：非结构化模块的关键是三件正则配置。配好后系统能回答"多了哪段、少了哪段、哪段内部改了哪一行"，段内差异精确到行号。

---

## 6. 数据展示、导出与对比结论

### 6.1 界面展示

- **批次页**：批次卡显示总文件数/差异数、作业级进度与四状态汇总（相等/差异/仅A/仅B），作业行有彩色状态徽章。
- **结果页**：逐条记录的键值、状态、分区与逐列 A/B 值；差异栏位红色高亮；报表作业额外显示表头/表尾分区与数量核对；支持翻页读取（大文件不整页载入）。

### 6.2 差异 CSV / 全量 CSV

文本模块两个入口：

| 导出 | 内容 | 典型用途 |
| --- | --- | --- |
| 差异 CSV（`_差异.csv`） | 仅差异记录，一条字段差异一行（键值/栏位号/栏位名/A值/B值） | 给维护人员逐栏位核实 |
| 全量 CSV（`_全量.csv`） | 全部记录所有字段（键值/状态/分区/逐列 A、B 值） | 存档、Excel 透视 |

两个易用细节：

```java
/** 导出层键值展示：组合主键分隔符 0x1F → ':'。 */
public static String displayKey(String key) {
    return key == null ? "" : key.replace(Rules.KEY_SEP, ":");
}

/** ≥16 位的纯数字在 Excel 中超出 double 精度（15 位），按文本公式存储以保真展示。 */
static String excelSafe(String v) {
    if (v == null || v.isEmpty() || !LONG_DIGITS.matcher(v).matches()) return v;
    return "=\"" + v + "\"";
}
```
（`src/main/java/com/textdiff/export/CsvExporter.java`；CSV 为 UTF-8 带 BOM，Excel 直接打开中文不乱码，30 位长账号不会变科学计数法）

### 6.3 报表差异 CSV

报表作业导出列为「状态,行标识,栏位号,栏位名,A值,B值」，`栏位名` 在导入了字段名映射后自动带出业务名；折行报表 A值/B值 一格含多物理行。

### 6.4 批次详情 Excel

批次详情导出（`GET /api/report-batches/{id}/export-detail` 等）为双 Sheet：Sheet1 对比总览（数量 + 对比配置），Sheet2 差异栏位明细；文本/报表批次额外带「状态情况」「总体评判」两列。

### 6.5 总体评判（DiffGrade）

数量偏离率 = 数量差 ÷（两侧记录数总和 ÷ 2）：

```java
long perMille = diff * 2000 / sum;   // 偏离率放大 1000 倍取整避免浮点
if (perMille >= 500) return new Grade("严重问题", desc);   // ≥50%
if (perMille >= 200) return new Grade("差异明显", desc);   // ≥20%
if (perMille >= 100) return new Grade("保持关注", desc);   // ≥10%
return new Grade("正常", desc);
```
（`src/main/java/com/textdiff/export/DiffGrade.java`；desc 形如"数量差 15（偏离 13.9%）"）

示例：A=1000、B=1150 → 偏离 13.9% → **保持关注**（黄色徽章）；A=1000、B=1500 → 33.3% → **差异明显**；A=0、B=全部 → ≥50% → **严重问题**。前端批次页与结果记录按同一公式实时渲染徽章颜色。

### 6.6 AI 分析与 HTML 报告

文本作业完成后自动生成 AI 分析任务：`prompt.md`（按 V3 模板注入对比数据事实）→ `ai_analysis.md`（结论前置、差异栏位明细表）→ 同名自包含 `.html`（账页墨蓝渲染样式，可直接归档/发送，离线可打开）。

---

## 7. 三模块对照速查

| 维度 | 普通文本 | 报表 | 自定义格式 |
| --- | --- | --- | --- |
| 身份（主键） | KEYSEQ 列；缺省=整行 | 整行 / KEYSEQ / 去跳过列键 | 正则捕获的段主键 |
| 跳过栏位（OMITSEQ） | 排除比对 | 排除键+比对+撮合 | ——（以段为单位） |
| 部分匹配 | 无（键对不上即单侧 unmatched） | 相似度撮合（≥0.6 判 diff） | 无（段主键对不上即单侧） |
| 分区 | 数据 / TRAILER 表尾 | 表头块 / 业务区 / 表尾块（含数量核对） | 文件头 / 段 / 文件尾 |
| 版式适配 | —— | 模板 .header；控制行自分区；折行/分页 | 三段正则 |
| 典型结论 | 逐栏位差异+重复键警告+RecNum 核对 | 账实相符（count_checks）+撮合差异 | 段级增删改 |

## 8. 新手常见问题

1. **为什么报表整体被当成业务行、警告"未能从模板定位表头锚点"？** 模板与报表没有逐行一致的非空白表头（或模板选错文件）。先确认模板 `<报表名>.header` 与数据文件名对应。
2. **文本作业结果是空的？** 旧版本未配 KEYSEQ 时所有记录无法取键；现已支持整行作键。升级后重跑即可。
3. **ASCII 文件被识别成 EBCDIC 乱码？** 已修复（样本含 0x20 空格即排除 EBCDIC 家族）；特殊字符集字段（EBCDIC 内嵌 UTF-16）走"字符集映射导入"链路，不影响整体编码识别。
4. **只想核对金额列，别的天然不一致？** 用 OMITSEQ 把这些列排除；报表撮合也会随之更准。
5. **差异 CSV 在 Excel 里长账号变 1.23E+29？** 已通过 `="…"` 文本公式保真；若用其他工具打开，请按文本导入。
