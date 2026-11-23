# Iris 2.2 · B2 数据分析工作台 · 字段映射表

> 日期：2026-11-22
> 依据：本分支 B1 实际代码（`AdminAnalyticsVO` 等 13 个 VO、`AdminAnalyticsServiceImpl`、
> `AdminAnalyticsMapper`），不是交付报告。
> 用途：B2 页面上每个数字/文案 → 后端字段的**唯一对照表**。没有对应字段的展示项一律不做。

## 1. 响应根

| 页面元素 | 字段 | 类型（序列化后） | 说明 |
|---|---|---|---|
| 统计窗口 | `days` | number | 已收敛 [7,365] |
| 范围起止 | `rangeStart` / `rangeEnd` | string | `yyyy-MM-dd`，右开区间含当天 |
| 复核领域 | `review` | object | 恒 `HISTORICAL_ONLY` |
| 拒识领域 | `rejection` | object | LIVE |
| 模型与质量 | `model` | object | LIVE |
| 数据质量 | `dataQuality` | object | LIVE |

`Long` / `long` 由全局 `JsonConfig` 序列化成 String；`int` / `Integer` / `double` / `Double`
仍是 JSON number。前端转换层必须：计数 → number；可空数值（率、时延、均值）**保持 null**。

## 2. 判读与复核

| 页面元素 | 字段 | 类型 | 边界 |
|---|---|---|---|
| 数据状态 | `review.dataStatus` | string | 恒 `HISTORICAL_ONLY`；UI 不得改写为「正常」 |
| 待复核积压（全站） | `review.awaitingTotal` | number | 快照，不限窗口 |
| 窗口内入队 | `review.queuedInWindow` | number | 按当前状态，A-07 |
| 窗口内完成 | `review.completedInWindow` | number | A-08 分子 |
| 完成率 | `review.completionRate` | number \| null | 分母 0 → null |
| 已复核（按复核时间） | `review.reviewedInWindow` | number | A-09 分母 |
| 已改写 | `review.overriddenInWindow` | number | 子集，不与上一项相加 |
| 改写率 | `review.overrideRate` | number \| null | 分母 0 → null |
| 有效时延样本 | `review.latencySamples` | number | `humanReviewedAt` 非空 |
| 时延均值 / p50 / p90 / max | `review.avgMinutes` / `p50Minutes` / `p90Minutes` / `maxMinutes` | number \| null | 无样本 → null |
| 复核人工作量 | `review.reviewers[]` | array | 最多 50；`reviewerId` 是 String |
| 复核人字段 | `reviewerId` / `reviewerName` / `reviewed` / `overridden` / `avgMinutes` | string\|null / string\|null / number / number / number\|null | 名称缺失 → 「复核人 #id（未记录名称）」 |
| 触发复核原因 | `review.queueReasons[]` | `{reason,count,share}` | 分母 = `queuedInWindow`；`share` 可空 |
| 未归类原因数 | `review.unclassifiedReasonCount` | number | 契约外原因 |
| 未记录原因数 | `review.unknownReasonCount` | number | 空原因 |

## 3. 未出结论（拒识）

| 页面元素 | 字段 | 类型 | 边界 |
|---|---|---|---|
| 判读总量 | `rejection.total` | number | 窗口内全部记录 |
| 拒识数 | `rejection.rejected` | number | 只认 `aiRejected=1` |
| 拒识率 | `rejection.rate` | number \| null | 分母 0 → null |
| 未出结论原因 | `rejection.reasons[]` | `{reason,count,share}` | 分母 = `rejected` |
| 未归类 / 未记录 | `rejection.unclassifiedReasonCount` / `unknownReasonCount` | number | 不丢弃、独立计数 |

> 与「触发复核原因」是两套集合：`LOW_CONFIDENCE` 只出现在后者。

## 4. 模型与图像质量

| 页面元素 | 字段 | 类型 | 边界 |
|---|---|---|---|
| 模型版本表 | `model.versions[]` | array | 空 → 空态 |
| 版本行 | `modelVersion` / `count` / `rejected` / `rejectionRate` / `avgConfidence` / `confidenceSamples` / `ambiguousConfidence` | string\|null / number / number / number\|null / number\|null / number / number | 版本空 → `UNRECORDED` |
| 置信度有效样本 | `model.confidence.samples` | number | 仅 `1 < c <= 100` |
| 置信度均值 | `model.confidence.avg` | number \| null | 0–100 量纲；不是准确率 |
| 量纲歧义 | `model.confidence.ambiguous` | number | `(0,1]`，不乘 100 |
| 越界 / 缺失 | `model.confidence.outOfRange` / `missing` | number | ≤0 或 >100 / null |
| 置信度直方图 | `model.confidenceHistogram[]` | `{bucket,count}` | 固定 10 桶，空桶补 0 |
| 质量五档 | `model.qualityScores[]` | `{score,count}` | 固定 `100/75/50/25/0`，展示顺序低→高 |
| 质量越界 | `model.qualityOutOfContract` | number | 非空且不在契约值 |
| 质量分缺失 | `model.qualityUnknown` | number | null |
| 质量原因 | `model.qualityReasons[]` | `{reason,count,share=null}` | 多标签，合计可超过记录数；不展示占比 |
| 质量原因未归类 | `model.unclassifiedQualityReasonCount` | number | — |
| OOD 状态 | `model.oodStatuses[]` | `{status,count}` | 当前恒 `NOT_EVALUATED`；只做能力说明 |
| 校准状态 | `model.calibrationStatuses[]` | `{status,count}` | 原始分布 |

## 5. 数据质量

| 页面元素 | 字段 | 类型 |
|---|---|---|
| 契约外复核状态 | `dataQuality.unknownReviewStatuses[]` | `{status,count}` |
| 契约外 OOD 状态 | `dataQuality.unknownOodStatuses[]` | `{status,count}` |
| 契约外校准状态 | `dataQuality.unknownCalibrationStatuses[]` | `{status,count}` |
| 质量分越界 | `dataQuality.qualityOutOfContract` | number |
| 置信度量纲歧义 | `dataQuality.ambiguousConfidence` | number |
| 置信度越界 / 缺失 | `dataQuality.confidenceOutOfRange` / `confidenceMissing` | number |
| 已复核缺时间 | `dataQuality.reviewedWithoutTime` | number |

## 6. 接口没有、页面因此不做的

| 想做的 | 缺什么 | 处理 |
|---|---|---|
| 复核时延直方图 | 无分桶字段 | 不做，只给 avg/p50/p90/max + 样本数 |
| 逐日趋势 / 环比 | analytics 接口无时间序列 | 不做；趋势留在仪表盘 |
| 病种构成 | analytics 接口无 `result` 分组 | 不做（仪表盘已有） |
| 下钻到原始记录 | 聚合接口不支持行级筛选 | 不做按钮 |
| 导出 / 实时刷新 / 自定义区间 | 无接口 | 不做 |
| 准确率 / 可用率 | 无标签、无监控数据 | 不做，只标「模型自评置信度」 |
| 参与率（复核人覆盖率） | 无分母定义 | 不做 |

## 7. 状态判定规则（前端只读后端，不自行推断）

- 复核：仅当 `review.dataStatus === "HISTORICAL_ONLY"` 显示历史条；值缺失或未识别 →
  显示「无法确认复核数据状态」，**不当作当前运营**。
- 其他领域后端未提供 `dataStatus`：页面只写「当前窗口统计」，不编造 `LIVE` 徽标。
- OOD：只看 `oodStatuses` 的原始值；只有 `NOT_EVALUATED` 时显示能力说明卡。
- 空窗口：四个领域（判读拒识、复核活动、模型版本、数据质量异常）全部为空 → 专属整页空态；
  只是 `rejection.total === 0` 时**不**判整页空——复核按复核完成时间统计，窗口内没有新判读
  仍可能有复核活动，此时复核分区照常显示，其余分区各自给分区空态。
  有记录但某指标样本为 0 → 说明「无符合口径的有效样本」。

## 8. 页面实现（B2，2026-11-22）

| 事项 | 落点 |
|---|---|
| 路由 | `/admin/data-analysis`（`App.tsx`），URL 参数 `?days=7|30|90|365&section=overview|review|model|quality` |
| 数据层 | `web/src/lib/adminApi.ts` → `getAdminAnalytics(days)`；转换层保留 null，缺失领域抛 `ApiError(-1, …)` |
| 查询键 | `queryKeys.admin.analytics(days)`；`gcTime: 0` + `staleTime: 0`，切窗立即失效 |
| 窗口守卫 | `data.days === days` 才渲染，后端回显错窗时当作未读到 |
| 展示映射 | `web/src/lib/analyticsDomain.ts`（格式化、枚举文案、分桶、口径元数据、可用性摘要） |
| 页面 | `web/src/pages/admin/AdminDataAnalysisPage.tsx`（四个分区 + 指标抽屉） |
| 组件 | `web/src/components/admin/analytics/`（Availability / StatusBadge / MetricDefinitionDrawer / Charts / Tables） |
| 样式 | `web/src/styles/admin-analytics.css`（零字面色；旧的 `admin-polish-analysis.css` 已删除） |
| 测试 | `AdminDataAnalysisPage.test.tsx`（15 例）、`analyticsDomain.test.ts`（18 例） |

页面不做的（与 §6 一致）：导出、下钻、实时刷新、自定义区间、准确率、时延直方图、趋势。

**数字显示约定**：计数走 `formatCount`（千分位）；比率走 `formatRate`（0–1 → 一位小数百分比，null → `—`）；
置信度走 `formatConfidence`（后端已是 0–100，只加 `%`，**不乘 100**）；时延走 `formatMinutes`（一位小数 + 分钟，null → `—`）。

**状态文案**：复核历史条始终带文字徽标；`dataStatus` 缺失时提示「无法确认这批复核数据的统计状态」，
不写成历史、也不写成当前。契约外枚举保留原值并进入数据质量清单，不做就近归类。

## 9. 验证记录（2026-11-22）

### 9.1 静态与单测

- `npm run typecheck`：通过。
- `npx vitest run src/lib/analyticsDomain.test.ts src/pages/admin/AdminDataAnalysisPage.test.tsx src/lib/adminApi.test.ts`：
  3 文件 / 35 例通过（窗口过期守卫、历史/未知状态、两套分母、null 破折号、
  OOD 能力卡、质量五档、口径抽屉、空窗口、故障重试、Long→String 与缺领域报错）。
- 邻接套件 `AdminDashboardPage.test.tsx`、`hardcoded-colors.test.ts` 一并跑：
  5 文件 / 60 例全部通过。
- `npm run build`：通过。

### 9.2 真实前后端联调

- 用最新 HEAD 启动后端（Flyway 到 V30，隔离 schema `diagnosis_b2probe`，MySQL 8.0.26），
  造 9 条覆盖边界的数据（拒识、低置信度入队、改写、待复核、歧义/越界/缺失置信度、
  契约外质量分与 OOD 状态、缺复核时间、窗口外积压）。
- 接口返回 `code=0`；计数确为 String、比率/时延 null 保持 null、直方图桶名是 U+2013 en dash。
- 页面四个分区均按真实响应渲染：8 条判读 / 拒识 2（25.0%）、复核入队 6 / 完成 4（66.7%）、
  P50 60 分钟、有效置信度 4 条均值 76.3%、质量五档与契约外计数、数据质量 10 类逐项计数。
- 口径抽屉读到本窗口实时计数（B-03：样本 4 / 均值 76.3% / 歧义 1 / 越界 1 / 缺失 2）。
- 双主题：`html.dark` 下 canvas `#12101c`、面板 `#1a1727`、当前标签浅紫，读数与徽标对比正常；
  浅色下 canvas `#f4f1fa`、面板纯白。
- 390×844：分区标签横向滚动且 44px 高、可用性卡单列、读数轨两列、表格容器横向滚动、
  页面无横向溢出。

### 9.3 浏览器视觉验收（Playwright，2026-11-22）

用本机 Chromium 1234 直接驱动 Vite 页面（不依赖 T3 预览窗口），登录探针管理员后截取
桌面 1440×900 与手机 390×844 × 浅色/深色 × 四个分区，另加口径抽屉、图表提示框、
图表↔读数表切换、手机端表格横向滚动，共 30 张。截图目录
`%TEMP%/iris-b2-shots`（临时产物，不入库）。四组 `pageerror` / console error 均为空。

验收结论：

- 浅色/深色两套令牌下正文、读数、徽标与图表刻度均可读；深色直方图与质量分档的柱体
  与底色有足够分离，没有出现「同色压同色」。
- 口径抽屉在桌面与手机上都覆盖在遮罩之上，背景被压暗、抽屉文字不与页面文字混层。
- 图表提示框可悬停命中，显示桶名与计数（如「50–60 · 记录数 1 条」），不是空框。
- 桌面端横向表格完整显示；手机端表格按最小列宽溢出、由容器横向滚动承接。
- 移动端发现并已修复两处可读性缺陷：
  1. 390px 下置信度十档的 X 轴标签互相压字 → 窄屏隔一个显示刻度（`interval=1`），
     读数表仍列全量；质量五档同样处理。
  2. 表格横向溢出时没有可见提示 → 窄屏显示「← 左右滑动查看全部列 →」，
     表格外壳加 `role="region"` / `tabIndex`，键盘也能滚动。

### 9.4 环境注意与启动门禁（不是 B1 缺陷）

本机 `application-dev.yml` 指向 **MySQL 3306（5.7）**。B1 的时延 SQL 使用 CTE + 窗口函数，
5.7 会直接返回 `50000 系统错误`。现在这条环境冲突由启动门禁兜住：

- 连 5.7 启动实测：应用启动到 `ApplicationRunner` 后失败退出（`exit code: 1`），
  日志给出「数据分析需要 MySQL 8.0+，当前连接的是 MySQL 5.7.19 … 本机通常为 3307 …
  `eye-diagnosis.database.require-mysql8=false`」，不会等到统计请求才报错。
- 连 8.0.26 启动实测：门禁记录「数据库版本 MySQL 8.0.26，满足数据分析最低要求 8.0」，
  应用正常监听；同一实例上 `/api/stats/admin/analytics?days=30` 返回 `code=0`。
- 不要在 5.7 上改写统计 SQL 来绕过；开发数据源必须指向 8.0 实例。

> 联调用的探针库、临时管理员、临时配置、截图脚本与测试服务均已删除/停止；
> 截图保留在临时目录供查看。
