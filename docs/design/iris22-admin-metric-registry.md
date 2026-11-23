# Iris 2.2 管理端分析体系 · 指标契约（Metric Registry）

> 状态：B0 草案（2026-11-22），待评审后冻结为 v1。
> 适用范围：`/admin/dashboard` 与 `/admin/data-analysis` 两个页面，以及
> `GET /api/stats/admin/*` 的所有读数。
> 规则正文以本文件为准；新增指标必须先在这里登记，再写 SQL 与页面。

---

## 0. 三条不可让渡的原则

1. **一个指标只有一个定义。** 页面可以换图标、换图表、换排序，不能换口径。
   任何页面自算的比率都不算数，必须回到本表。
2. **时间字段必须显式。** 同一张表里「判读发生」和「复核发生」是两条时间轴，
   任何指标都要写明用哪一条，禁止混用。
3. **没有数据源就不做图。** 宁可显示「尚未接入 / 未记录 / 写入已关闭」，
   不允许把缺失值补成 0，不允许用自评指标冒充准确率。

---

## 1. 数据源事实核对（2026-11-22）

### 1.1 判读证据链（LIVE）

写入链路：Python gRPC `DiagnosisResponse` → `DiagnosisEvidence` → `t_diagnosis`。
依据：`algorithm/grpc_server.py`、`src/main/java/com/csust/eyediagnosis/service/DiagnosisEvidence.java`。

| 字段 | 真实取值契约 | 备注 |
|---|---|---|
| `confidence` | **0–100**（`top1 * 100`） | 历史/演示数据里存在 0–1 值。既有页面用 `normalizeConfidence` 做**单条展示**兼容；**新 Analytics 接口不套这个启发式**——量纲无法确认的样本只计数、不参与聚合 |
| `qualityScore` | **{100, 75, 50, 25, 0}**（`100 - 25 × 原因数`） | 演示库里出现过 1/74/92/96，属越界数据 |
| `qualityReasons` | 逗号分隔的子集：`blur` / `brightness` / `contrast` / `fundus_coverage` | 多标签；`VARCHAR(512)` |
| `abstainReason` | 枚举：`QUALITY_FAIL` / `OOD` / `LOW_CONFIDENCE` / `NOT_ASSESSED` / `MODEL_NOT_LOADED` | `NOT_ASSESSED`、`MODEL_NOT_LOADED` 当前代码路径未产生；演示库有中文自由文本 |
| `aiRejected` | `rejected`：**只有 QUALITY_FAIL / OOD 为 true** | `LOW_CONFIDENCE` 时 `rejected=false`、`requires_review=true` |
| `oodStatus` | 枚举：`NOT_EVALUATED` / `IN_DISTRIBUTION` / `OUT_OF_DISTRIBUTION` / `EVALUATION_FAILED` | **当前恒为 `NOT_EVALUATED`**：OOD 检测器被有意停用 |
| `calibrationStatus` | `CALIBRATED` / `NOT_CALIBRATED` | — |
| `humanReviewStatus` | `AI_ONLY` / `AWAITING_REVIEW` / `REVIEWED` / `OVERRIDDEN` | 历史库另有 `PENDING_AI`、`APPROVED`，不在契约内 |

### 1.2 复核写入已安全关闭（关键约束）

`ClinicalReviewQualification.mayWriteFormalClinicalReview()` **恒返回 false**
（D-01b，2026-11-18 产品已裁定）。因此：

- 新产生的 `REVIEWED` / `OVERRIDDEN` / `humanReviewedAt` / `humanReviewerId` **没有增量**；
- 现有复核数据只有历史记录与演示数据；
- **复核效率相关指标的数据状态一律是 `HISTORICAL_ONLY`**，页面必须写明，
  不得作为「当前运营读数」展示。

依据：`src/main/java/com/csust/eyediagnosis/service/ClinicalReviewQualification.java`。

### 1.3 其它领域的数据可用性

| 领域 | 表 / 字段 | 状态 |
|---|---|---|
| 多 Agent 工具调用 | `t_agent_tool_audit`（toolName / toolStatus / errorCategory / startedAt / finishedAt） | 可用，但仅普通聊天；协作默认关闭 |
| 多 Agent 协作 | `t_agent_collab_run` / `t_agent_collab_event` | `NOT_ENABLED`（默认全 false） |
| RAG 检索 | **无检索追踪表** | `INSTRUMENTATION_REQUIRED`：先设计埋点，不做图表 |
| 知识库 | `t_knowledge_document`（状态 / 分块数） | 可用（仅存量，无检索质量） |
| 服务可用率 | **无真实监控数据** | 不展示；不得用请求数推算 |

### 1.4 本地库不是契约

本机 `diagnosis` 库（5 条存活记录）里同时存在 `humanReviewStatus=APPROVED`、
`oodStatus=ok/reject`、`qualityScore=1/74/92/96`、中文 `abstainReason`。
这些**都不在契约内**，属于演示/历史数据。契约与页面必须以本文件的枚举为准，
并把契约外取值归入「未归类」，不能静默丢弃、不能并入正常档。

---

## 2. 时间语义

| 时间字段 | 含义 | 用于 |
|---|---|---|
| `createTime` | 判读记录创建（判读发生） | 判读量、病种分布、拒识率、模型/质量分布 |
| `humanReviewedAt` | 人工复核完成 | 复核量、复核人工作量、复核时延 |

**禁止**：用 `createTime` 筛「窗口内完成的复核」，或用 `humanReviewedAt` 筛「窗口内发生的判读」。
现有 `overview` 的 `diagnosisReviewed` 是「窗口内创建且当前已复核」，
**不是**「窗口内完成的复核」；新分析接口不得复用它，页面若沿用必须改文案。

另一个硬约束：**没有状态变更历史表**。所有「进入队列 / 完成复核」都是
**当前状态快照**，无法还原某条记录在过去某天的状态。相关指标一律标注
「按当前状态统计」。

---

## 3. 指标定义表

数据状态取值：`LIVE` / `HISTORICAL_ONLY` / `INSTRUMENTATION_REQUIRED` / `NOT_ENABLED`。
`isDelete = 0` 是所有查询的默认条件，下表不再重复。

### A. 判读与复核

| ID | 指标 | 回答的问题 | 公式 | 时间字段 | 缺失/边界规则 | 状态 |
|---|---|---|---|---|---|---|
| A-01 | 判读量 | 窗口内做了多少判读？ | `COUNT(*)` | `createTime` | — | LIVE |
| A-02 | 判读环比 | 比上一个等长窗口涨跌多少？ | `(cur - prev) / prev` | `createTime` | `prev = 0` → **不适用**（显示「—」），不是 0% | LIVE |
| A-03 | 病种构成 | 各病种占比？ | `GROUP BY result` | `createTime` | `result` 为空 → 「未出结论」单独一档 | LIVE |
| A-04 | AI 拒识率 | 多少判读没有给出结论？ | `SUM(aiRejected=1) / COUNT(*)` | `createTime` | 分母含全部记录；分子只认 `aiRejected` | LIVE |
| A-05 | 未出结论原因构成 | 为什么没出结论？ | `GROUP BY abstainReason WHERE aiRejected=1` | `createTime` | 契约外取值 → 「未归类原因」；NULL → 「未记录原因」 | LIVE |
| A-06 | 待复核积压 | 现在有多少等待复核？ | `COUNT(*) WHERE humanReviewStatus='AWAITING_REVIEW'` | 快照（无窗口） | 与 A-07 不同，不要合并 | LIVE |
| A-07 | 窗口内入队量 | 窗口内创建、当前在复核队列的？ | `COUNT(*) WHERE humanReviewStatus IN ('AWAITING_REVIEW','REVIEWED','OVERRIDDEN')` | `createTime` | **按当前状态**；无法还原历史状态 | LIVE |
| A-08 | 复核完成率 | 入队后完成了多少？ | `(REVIEWED+OVERRIDDEN) / 入队集合` | `createTime` | 分母 = A-07；`APPROVED`/`PENDING_AI` 归「未归类状态」，不计入分子分母 | HISTORICAL_ONLY |
| A-09 | 复核改写率 | 复核推翻 AI 的比例？ | `OVERRIDDEN / (REVIEWED+OVERRIDDEN)` | `humanReviewedAt` | `REVIEWED` 与 `OVERRIDDEN` 互斥，**不得相加当总数**；无数据 → 不适用 | HISTORICAL_ONLY |
| A-10 | 复核时延 | 从判读到复核完成多久？ | `humanReviewedAt - createTime`；avg / p50 / p90 / max | `humanReviewedAt` | 只算 `humanReviewedAt` 非空；**是端到端时延，不是处理时长**（无入队时间） | HISTORICAL_ONLY |
| A-11 | 复核人工作量 | 谁复核了多少？ | `GROUP BY humanReviewerId` | `humanReviewedAt` | 名称优先 `t_user.userName`，缺失 → 「复核人 #id（未记录名称）」；**不写「医生」** | HISTORICAL_ONLY |
| A-12 | 触发复核原因构成 | 哪些原因把记录推进队列？ | `GROUP BY abstainReason WHERE humanReviewStatus IN ('AWAITING_REVIEW','REVIEWED','OVERRIDDEN')` | `createTime` | **包含 `LOW_CONFIDENCE`**（它 `rejected=false`，不在 A-05 里）；原因为空 → 「未记录原因」，契约外 → 「未归类原因」，两者都单独计数、不丢弃 | LIVE |

> A-05 与 A-12 是两个不同的问题：前者是「没出结论」，后者是「需要人看一眼」。
> 图表标题必须分别写「未出结论原因」与「触发复核原因」，不能都叫「拒识原因」。
> A-12 的分母是「进入过复核队列」的记录，因没有状态历史表，只能用当前状态
> （`AWAITING_REVIEW / REVIEWED / OVERRIDDEN`）兜底；原因为空的入队记录必须作为
> 「未记录原因」出现——它是数据质量信号，不能靠 `IS NOT NULL` 静默过滤掉。

### B. AI 模型与质量

| ID | 指标 | 回答的问题 | 公式 | 时间字段 | 缺失/边界规则 | 状态 |
|---|---|---|---|---|---|---|
| B-01 | 模型版本运行量 | 各模型版本跑了多少？ | `GROUP BY modelVersion` | `createTime` | NULL → 「未记录版本」 | LIVE |
| B-02 | 模型版本拒识率 | 哪个版本更容易不出结论？ | `AVG(aiRejected)` per version | `createTime` | 同 A-04 | LIVE |
| B-03 | 模型自评置信度均值 | 模型平均有多自信？ | `AVG(confidence) WHERE 1 < confidence <= 100` | `createTime` | **不是准确率**；量纲以 0–100 为准，`(0,1]` 视为量纲未知、单独计数、**不得自动 ×100**；无样本 → 「—」 | LIVE |
| B-04 | 置信度分布 | 自信度分布形状？ | 0–100 十档直方图（`0–10` … `90–100`） | `createTime` | 只含 `1 < confidence <= 100`；NULL 与量纲未知各自单列，不画进任何桶 | LIVE |
| B-05 | 图像质量分档 | 质量分落在哪一档？ | 按 {100,75,50,25,0} 精确分档 | `createTime` | **按契约值分档，不按百分位**；契约外值 → 「越界值」；NULL → 「未记录」 | LIVE |
| B-06 | 质量原因构成 | 图像为什么不合格？ | 拆分 `qualityReasons` 按标签计数（多标签） | `createTime` | 一行可计多个原因，合计可超过记录数；空串 → 「未记录原因」 | LIVE |
| B-07 | OOD 状态构成 | 有多少影像在分布外？ | `GROUP BY oodStatus` | `createTime` | 当前只有 `NOT_EVALUATED`；显示「尚未评估」而不是画一条 100% 的假分布 | LIVE（单一值） |
| B-08 | 校准状态构成 | 有多少结论经过校准？ | `GROUP BY calibrationStatus` | `createTime` | 契约外/NULL → 「未记录」 | LIVE |

### C. 多 Agent 协作（P1，暂不实现）

| ID | 指标 | 数据前提 | 状态 |
|---|---|---|---|
| C-01 | 工具调用量与成功率 | `t_agent_tool_audit` | 可用（普通聊天） |
| C-02 | 工具错误类别构成 | `errorCategory` | 可用 |
| C-03 | 工具耗时分位 | `startedAt` / `finishedAt` | 可用 |
| C-04 | 协作运行成功率 / 失败阶段 | `t_agent_collab_run` / `t_agent_collab_event` | `NOT_ENABLED`（默认关闭） |

### D. RAG 与知识库（P1，先埋点）

| ID | 指标 | 数据前提 | 状态 |
|---|---|---|---|
| D-01 | 检索命中率 | 需要检索追踪表（查询、命中、分数、引用） | `INSTRUMENTATION_REQUIRED` |
| D-02 | 引用证据充分率 | 需要引用的可核验快照 | `INSTRUMENTATION_REQUIRED` |
| D-03 | 低效查询清单 | 同 D-01 | `INSTRUMENTATION_REQUIRED` |

> 在埋点与数据契约落地前，**不得**用文档数、分块数冒充「检索质量」。

### E. 用户与平台运营（P2）

| ID | 指标 | 数据前提 | 状态 |
|---|---|---|---|
| E-01 | 活跃用户 | 需要登录/行为事件或可接受的会话口径 | `INSTRUMENTATION_REQUIRED` |
| E-02 | 功能使用分布 | 需要前端/后端使用事件 | `INSTRUMENTATION_REQUIRED` |
| E-03 | 服务可用率 | 需要真实监控（探针、SLO） | 不展示；禁止用请求数推算 |

---

## 4. 展示与措辞规则

| 情形 | 必须显示 | 禁止 |
|---|---|---|
| 窗口内没有记录 | 「暂无数据」+ 窗口说明 | 画一排 0 的 KPI、画贴地趋势线 |
| 字段存在但为空 | 「未记录」 | 当成 0 参与平均/占比 |
| 能力未接入 | 「尚未接入」+ 一句原因 | 画占位图、用「0」表示 |
| 写入已关闭 | 「历史数据 · 写入已安全关闭」 | 暗示仍有新增复核 |
| 有数据但样本过小 | 显示样本数 n | 用百分比制造确定性 |
| 模型自评 | 「模型自评置信度」 | 「准确率」「诊断正确率」 |

其它：复核人一律写「复核人」；严重度/风险永远带文字标签；颜色不作唯一信息载体。

---

## 5. 数据质量门禁（合并前必须跑）

1. **契约外枚举清单**：统计 `humanReviewStatus`、`abstainReason`、`oodStatus`、
   `calibrationStatus` 中不在本文件枚举内的取值数量；>0 时页面显示「未归类」档，
   并在发布说明中列出数量。
2. **质量分越界**：`qualityScore NOT IN (100,75,50,25,0)` 且非 NULL 的行数。
3. **置信度量纲**：`confidence > 0 AND confidence <= 1` 的行数（旧数据/演示数据）；
   确认它们被单独计入 `ambiguousConfidence`、<b>不乘 100、不进任何图表</b>。
4. **复核时间完整性**：`humanReviewStatus IN ('REVIEWED','OVERRIDDEN') AND humanReviewedAt IS NULL` 的行数；
   这些记录不进入 A-09 / A-10 / A-11 的分母，并单独计数。
5. **双页一致性**：同一指标在两页必须取自同一服务方法；发现两处各算一次即视为缺陷。

---

## 6. 变更流程

- 新增/修改指标 → 先改本文件（含公式、时间字段、缺失规则、状态）→ 再改代码 → 再改页面。
- 每次变更在文末追加一行变更记录（日期、指标 ID、原因、影响页面）。
- 本文件与 `DESIGN.md` 的数据真实性条冲突时，以本文件为准（更具体）。

## 7. 变更记录

| 日期 | 指标 | 变更 | 影响 |
|---|---|---|---|
| 2026-11-22 | 全部 | 建立 B0 契约草案 | 仪表盘 / 数据分析 |
| 2026-11-22 | B-03 / B-04 | 取消「≤1 自动 ×100」：只有 `1 < c <= 100` 参与聚合，`(0,1]` 计入量纲未知、单独计数 | 数据分析 |
| 2026-11-22 | A-12 | 分母改为「当前处于/曾进入复核队列」的记录；去掉 `abstainReason IS NOT NULL`，空原因作为「未记录」计数 | 数据分析 |
