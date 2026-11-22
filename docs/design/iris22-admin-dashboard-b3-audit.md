# B3-0 · 运营总览数据源与交互审计

> 状态：四项决策已确认并实施（2026-11-22）。落地记录见
> [`iris22-admin-dashboard-b3-implementation.md`](iris22-admin-dashboard-b3-implementation.md)。
>
> 审计对象：`web/src/pages/admin/AdminDashboardPage.tsx`（2026-11-22 工作树版本）
> 目的：在改版动手前，逐张卡片核对「数据从哪来、点了会发生什么、是否与数据分析重复、
> 是否依赖已关闭能力」。本文只记录事实与判断，不含改版视觉方案。
> 结论先行：**积压卡与复核状态分布这两处必须改，否则 B3 会把一个已经不存在的流程
> 画成当前运营任务。**

## 1. 审计范围与方法

| 层 | 核对对象 |
| --- | --- |
| 页面 | `AdminDashboardPage.tsx` 全部卡片、CTA、脚注、空态 |
| 接口 | `GET /api/stats/admin/overview`（`AdminStatsController` → `AdminStatsServiceImpl` → `AdminStatsMapper`） |
| 列表 | `GET /api/diagnosis/list`（最近 5 条） |
| 下钻 | `AdminDiagnosisPage` 的 `?review=` 入口、复核写入入口 |
| 门禁 | `ClinicalReviewQualification.mayWriteFormalClinicalReview()` |
| 能力面 | 仓库内是否存在健康检查 / OOD 评估接口 |

## 2. 逐卡审计

### 2.1 读数轨（KPI）

| 卡片 | 数据来源（字段 → SQL） | 真实可操作性 | 与数据分析的关系 | 判定 |
| --- | --- | --- | --- | --- |
| 窗口内判读 | `diagnosisInRange`、`previousDiagnosisInRange` → `COUNT(*) … createTime >= from` | 无动作；只报量 | Analytics 不重复展示规模读数 | 保留 |
| AI 拒识 | `diagnosisAiRejected` → `COUNT(*) … aiRejected = 1`；占比分母是 `diagnosisInRange` | 无动作（运营待办区另有「去查看」） | Analytics 讲「为什么拒识」，Dashboard 只报「拒了多少」 | 保留，需与 Analytics 交叉链接 |
| 新增用户 | `userNew`、`previousUserNew` → `t_user` 窗口计数 | 无动作 | Analytics 不含用户维度 | 保留 |
| 待审核资讯 | `newsByStatus` 中 `name = 3` | `→ /admin/news?status=3`，真实可操作 | Analytics 不含资讯 | 保留 |

KPI 本身没有重复 Analytics 的分析内容，问题在别处：**同一页的积压卡把「历史状态」
写成了当前任务**（见 2.3）。

### 2.2 判读趋势

- 数据：`trendDays` + `diagnosisTrend` + `previousDiagnosisTrend`，后端按天补零后返回
  （`AdminStatsServiceImpl#fillDays`）。
- 交互：仅时间窗切换（7 / 30 / 90 天），无导出、无下钻。
- 判定：真实、可解释，属于 B3「02 趋势」的现成底座；补「新增用户趋势」即可，
  不复制 Analytics 的模型/质量图表。

### 2.3 待人工复核积压卡（P0）

代码：`AdminDashboardPage.tsx:212-253`；数据：`diagnosisAwaitingReviewTotal`
（`AdminStatsMapper.java:58-61`，全站 `humanReviewStatus = 'AWAITING_REVIEW'` 计数）。

事实链：

1. 卡片文案写「积压需要人工复核才能进入正式记录」，CTA 是「去复核」。
2. CTA 落到 `/admin/diagnosis?review=requiresReview`，筛选本身有效
   （`AdminDiagnosisPage.tsx:149-164` 白名单解析）。
3. 但该页的「人工复核」行内动作被禁用：
   `AdminDiagnosisPage.tsx:719-728` 的菜单项 `disabled: true`，提示取自
   `clinicalReviewSafeOff`（`:489-497`）。
4. 后端写入同样恒拒：`ClinicalReviewQualification.java:48-52` 直接 `return false`。
5. 因此「积压 → 去复核 → 清掉」这条运营闭环**在系统里不存在**。列表能看，
   但不能处理；这些 `AWAITING_REVIEW` 行只会永久留在计数里。

判断：这不是文案小问题，而是**把已关闭能力包装成当前待办**。B3 若不改，
运营指挥台的核心卡会持续制造一个无法完成的动作。

处置方向（三选一，B3 讨论后定）：

- **A（推荐）**：把「积压」改成「历史复核状态 · 只读」读数，说明写入已关闭，
  不再给「去复核」CTA；当前任务区只留真正能处理的事项。
- **B**：卡片整块移出 B3，只在数据分析的复核分区保留历史统计。
- **C**：保留入口但改为「查看历史复核记录」，去掉「积压/清空」语义。

### 2.4 复核状态分布（P0 同源问题）

代码：`AdminDashboardPage.tsx:110-119`、`:263-269`；数据：
`reviewDistribution`（`AdminStatsMapper.java:77-80`，窗口内按 `humanReviewStatus` 分组，
含 `NULL` 分组）。

事实：

- 图表未标注数据可信状态，且给 `AWAITING_REVIEW` 加强调色
  （`accentNames={[AWAITING_REVIEW_LABEL]}`），视觉上等于「当前有活要干」。
- 数据分析页对同一批复核数据返回并展示 `HISTORICAL_ONLY`
  （`ReviewAnalyticsVO.java:12-21` 固定值；页面 `AdminDataAnalysisPage.tsx` 的历史提示条）。
- 两页对同一数据给出不同可信级别，是 B3 必须消除的口径冲突。

处置：B3 若保留分布，必须带「历史数据 · 写入已关闭」标记；更稳妥的做法是
把复核维度整体交给 Analytics，Dashboard 只保留「判读 / 拒识 / 用户 / 资讯」四条运营线。

### 2.5 运营待办

代码：`AdminDashboardPage.tsx:275-313`。

| 待办 | 数据 | 目标 | 真实性 |
| --- | --- | --- | --- |
| AI 拒识记录 | `diagnosisAiRejected` | `/admin/diagnosis?review=aiRejected` | 可查看，不能「处理」；应叫「去查看」 |
| 知识库处理失败 | `knowledgeFailed`（全站 `status = 2`） | `/admin/knowledge?status=2` | 可处理后端真实支持 |
| 资讯待审核 | `newsByStatus[name=3]` | `/admin/news?status=3` | 可审核，真实闭环 |

判断：这是 B3「03 真实业务待办」应保留的骨架，但需要区分「可处理」与「可查看」，
并把已关闭的复核队列排除在外。

### 2.6 最近判读记录

- 数据：`listDiagnoses({ current: 1, pageSize: 5 })`，独立查询，失败有 `FaultNote`。
- 交互：只读，入口「打开诊断管理」。
- 判定：真实、可操作，属 B3「04 最近活动」的底座。保留。

### 2.7 脚注与读数兜底（P2）

代码：`AdminDashboardPage.tsx:132-134`、`:505-512`。

- `read()` 在 `stats` 已返回但字段为 `undefined` 时显示 `0`。当前 VO 都是原始类型，
  暂时不会触发；但这是「缺字段 = 零」的隐患，与 B2 的「null 保持 null」纪律不一致。
- 平均置信度走旧口径 `confidencePercent(stats.avgConfidence)`，会把 `(0,1)` 的样本
  当概率换算；Analytics 对同一批数据区分「有效样本 / 量纲歧义 / 越界 / 缺失」，
  两页会给出不同均值。B3 应改为直接引用 Analytics 的置信度口径，或明确标注差异。

## 3. 能力面核对（决定 B3 能画什么）

| 想画的内容 | 系统是否具备真实数据源 | 结论 |
| --- | --- | --- |
| 数据库 / Redis / Chroma / gRPC 健康灯 | **没有**。仓库无 Actuator、无 `/health` 控制器（`pom.xml` 无 actuator 依赖） | 不能画「系统正常」；要么新增最小健康接口，要么不画 |
| OOD 防护状态 | 有状态值，但当前恒为 `NOT_EVALUATED`（检测器未启用） | 只能显示「未评估」，不能显示绿色健康 |
| 待人工复核队列 | 有历史数据，**无写入能力** | 只能作为历史读数，不能作为当前待办 |
| 协作运行状态 | 有接口，但默认关闭、未部署 | B3 不展示 |
| 模型版本运行分布 | Analytics 已提供（窗口内） | Dashboard 不重复，交给 Analytics |

## 4. B3 改版边界建议

| B3 区块 | 可以放 | 不可以放 |
| --- | --- | --- |
| 01 核心运营概览 | 判读量、拒识量/率、新增用户、资讯待审（各带窗口与环比） | 复核「积压」、系统健康灯 |
| 02 趋势 | 判读趋势（含上一窗口）、新增用户趋势 | OOD 健康趋势、伪造的置信度趋势 |
| 03 真实业务待办 | 知识库失败、资讯待审核；AI 拒识标为「查看分析」 | 需要资格核验的复核写入 |
| 04 最近活动 + 状态 | 最近 5 条判读；**仅当新增真实健康接口后**才放系统状态 | 用记录数/请求成功率冒充健康检查 |

## 5. 必须先做的决定（进入 B3 设计前）

1. 复核积压卡按 2.3 的 A/B/C 哪个方向处理（推荐 A）。
2. 复核状态分布是否整体移交给数据分析页（推荐移交）。
3. 系统状态区块是否立项一个最小真实接口；若不立项，B3 不设计该区块。
4. 平均置信度是否统一到 Analytics 口径（推荐统一）。

## 6. 证据索引

| 事实 | 位置 |
| --- | --- |
| 积压卡与 CTA | `web/src/pages/admin/AdminDashboardPage.tsx:212-253` |
| 复核分布与强调色 | `web/src/pages/admin/AdminDashboardPage.tsx:110-119`、`:263-269` |
| `read()` 缺字段补零 | `web/src/pages/admin/AdminDashboardPage.tsx:132-134` |
| 置信度脚注 | `web/src/pages/admin/AdminDashboardPage.tsx:505-512` |
| 积压计数 SQL | `src/main/java/com/csust/eyediagnosis/mapper/AdminStatsMapper.java:58-61` |
| 复核分布 SQL | `src/main/java/com/csust/eyediagnosis/mapper/AdminStatsMapper.java:77-80` |
| 复核写入禁用 | `web/src/pages/admin/AdminDiagnosisPage.tsx:489-497`、`:719-728` |
| 资格门禁恒 false | `src/main/java/com/csust/eyediagnosis/service/ClinicalReviewQualification.java:48-52` |
| 无 Actuator 依赖 | `pom.xml` 中无 `spring-boot-starter-actuator`；控制器目录无 health 映射 |
| OOD 恒未评估 | `src/main/java/com/csust/eyediagnosis/service/impl/DiagnosisServiceImpl.java:568` |
