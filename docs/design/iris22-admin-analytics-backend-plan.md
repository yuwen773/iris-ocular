# Iris 2.2 管理端分析体系 · B1 后端实施方案

> 配套契约：`docs/design/iris22-admin-metric-registry.md`（口径以它为准）。
> 状态：**B1 后端已实施（2026-11-22）**：`GET /api/stats/admin/analytics`、Service、Mapper、VO、
> 单元/集成测试与 L2 验证均已落地；未新增索引、未建 V32、未改 Overview 行为。
> 目标：先交付可信、可扩展的统计能力；前端页面在 B2/B3 再做。

---

## 1. 范围

本轮只做三个领域：

| 领域 | 指标 ID | 数据状态 |
|---|---|---|
| 判读与复核 | A-01 … A-12 | 判读 LIVE；复核 HISTORICAL_ONLY |
| AI 模型与质量 | B-01 … B-08 | LIVE（B-07 只有单一值） |
| 数据质量自检 | §5 五条 | LIVE |

C（多 Agent）、D（RAG）、E（平台）只保留契约条目，不写代码。

---

## 2. 接口

### 2.1 端点

```
GET /api/stats/admin/analytics?days=30
```

- `@AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)`
- `days` 收敛 [7, 365]，默认 30（与 `overview` 一致）
- 保留 `GET /api/stats/admin/overview` **完全不变**（向后兼容）

### 2.2 响应形状

```jsonc
{
  "days": 30, "rangeStart": "2026-10-24", "rangeEnd": "2026-11-22",
  "review": {
    "dataStatus": "HISTORICAL_ONLY",
    "awaitingTotal": 0,                 // 全站快照（A-06），不限时间窗
    "queuedInWindow": 0,                // 窗口内创建、当前在队列（A-07）
    "completedInWindow": 0,             // A-08 分子
    "completionRate": null,             // 分母 0 → null
    "reviewedInWindow": 0,              // 按 humanReviewedAt（A-09 分母）
    "overriddenInWindow": 0,
    "overrideRate": null,
    "latencySamples": 0,                // 有效时延样本数
    "avgMinutes": null, "p50Minutes": null, "p90Minutes": null, "maxMinutes": null,
    "reviewers": [
      { "reviewerId": "12", "reviewerName": "张三", "reviewed": "40", "overridden": "3", "avgMinutes": 620.0 }
    ],
    "queueReasons": [ { "reason": "LOW_CONFIDENCE", "count": "30", "share": 0.5 } ],
    "unclassifiedReasonCount": 0,       // 契约外原因
    "unknownReasonCount": 0             // 原因为空的入队记录
  },
  "rejection": {
    "total": 0, "rejected": 0, "rate": null,
    "reasons": [ { "reason": "QUALITY_FAIL", "count": "0", "share": null } ],
    "unclassifiedReasonCount": 0,
    "unknownReasonCount": 0
  },
  "model": {
    "versions": [ { "modelVersion": "efficientnet-b4-fundus-v1", "count": "0", "rejected": "0",
                    "rejectionRate": null, "avgConfidence": null,
                    "confidenceSamples": "0", "ambiguousConfidence": "0" } ],
    "confidence": { "samples": "0", "avg": null, "ambiguous": "0", "outOfRange": "0", "missing": "0" },
    "confidenceHistogram": [ { "bucket": "0–10", "count": "0" } ],   // 固定 10 桶
    "qualityScores": [ { "score": 100, "count": "0" } ],             // 固定 5 档
    "qualityOutOfContract": 0,
    "qualityUnknown": 0,
    "qualityReasons": [ { "reason": "blur", "count": "0", "share": null } ],
    "unclassifiedQualityReasonCount": 0,
    "oodStatuses": [ { "status": "NOT_EVALUATED", "count": "0" } ],
    "calibrationStatuses": [ { "status": "CALIBRATED", "count": "0" } ]
  },
  "dataQuality": {
    "unknownReviewStatuses": [ { "status": "APPROVED", "count": "3" } ],
    "unknownOodStatuses": [],
    "unknownCalibrationStatuses": [],
    "qualityOutOfContract": 0,
    "ambiguousConfidence": 0,
    "confidenceOutOfRange": 0,
    "confidenceMissing": 0,
    "reviewedWithoutTime": 0
  }
}
```

约定：

- 所有比率在**后端**算；前端只格式化。比率为 `null` 表示「不适用」，不是 0。
- 复核领域固定带 `dataStatus = HISTORICAL_ONLY`（D-01b 关闭正式复核写入），
  前端不得当当前运营读数展示。
- `Long` 仍会被全局序列化成 String（上面 JSON 注释里的数字是序列化前的类型），
  前端沿用 `num()` 归一。
- 未知枚举不丢弃：契约外合并为 `UNCLASSIFIED`，空值合并为 `UNRECORDED`，
  计数同时进入 `dataQuality.*`。
- 置信度只聚合 `1 < confidence <= 100`；`(0,1]` 计 `ambiguousConfidence`，
  不自动 ×100。

### 2.3 类与职责

```
controller/AdminStatsController          + analytics(days)
service/AdminAnalyticsService            接口：analytics(int days)
service/impl/AdminAnalyticsServiceImpl   派生比率、分档、分位数组装（唯一计算点）
mapper/AdminAnalyticsMapper              @Select 聚合查询（按领域分组写方法）
model/vo/stats/AdminAnalyticsVO          顶层
model/vo/stats/ReviewAnalyticsVO         复核领域
model/vo/stats/RejectionAnalyticsVO      未出结论领域
model/vo/stats/ModelAnalyticsVO          模型与质量领域
model/vo/stats/DataQualityVO             自检
model/vo/stats/*StatVO                   单行明细（复核人 / 版本 / 原因 / 状态 / 分档 / 直方图 / 时延 / 置信度）
```

**同步修改**：`AdminStatsController` 类注释「两个页面共用这一个入口」改为
「overview 供仪表盘；analytics 供数据分析」，避免下一个人被误导。

---

## 3. SQL 设计

### 3.1 复核工作量（A-09 / A-11）

```sql
SELECT d.humanReviewerId AS reviewerId,
       u.userName       AS reviewerName,
       COUNT(*)         AS reviewed,
       SUM(d.humanReviewStatus = 'OVERRIDDEN') AS overridden,
       AVG(TIMESTAMPDIFF(MINUTE, d.createTime, d.humanReviewedAt)) AS avgMinutes
FROM t_diagnosis d
LEFT JOIN t_user u ON u.id = d.humanReviewerId AND u.isDelete = 0
WHERE d.isDelete = 0
  AND d.humanReviewStatus IN ('REVIEWED', 'OVERRIDDEN')
  AND d.humanReviewedAt >= #{from} AND d.humanReviewedAt < #{to}
GROUP BY d.humanReviewerId, u.userName
ORDER BY reviewed DESC
LIMIT 50;
```

- `REVIEWED` 与 `OVERRIDDEN` 是互斥状态，`reviewed` 含两者，
  「改写率」用 `overridden / reviewed`；**不要**把两列相加。
- 名称缺失时后端返回 `reviewerName = null`，前端显示「复核人 #id（未记录名称）」。

### 3.2 复核时延分位（A-10）

MySQL 8 **没有** `PERCENTILE_CONT()`。用窗口函数取最近秩：

```sql
WITH latency AS (
  SELECT TIMESTAMPDIFF(MINUTE, createTime, humanReviewedAt) AS m,
         ROW_NUMBER() OVER (ORDER BY TIMESTAMPDIFF(MINUTE, createTime, humanReviewedAt)) AS rn,
         COUNT(*)    OVER () AS total
  FROM t_diagnosis
  WHERE isDelete = 0
    AND humanReviewStatus IN ('REVIEWED','OVERRIDDEN')
    AND humanReviewedAt IS NOT NULL
    AND humanReviewedAt >= #{from} AND humanReviewedAt < #{to}
)
SELECT total,
       AVG(m) AS avgMinutes,
       MAX(m) AS maxMinutes,
       MAX(CASE WHEN rn = CEIL(total * 0.5) THEN m END) AS p50Minutes,
       MAX(CASE WHEN rn = CEIL(total * 0.9) THEN m END) AS p90Minutes
FROM latency;
```

`total = 0` 时所有值为 NULL → 前端「暂无数据」。

### 3.3 拒识原因（A-05）与触发复核原因（A-12）

```sql
-- A-05：未出结论
SELECT abstainReason AS reason, COUNT(*) AS count
FROM t_diagnosis
WHERE isDelete = 0 AND aiRejected = 1
  AND createTime >= #{from} AND createTime < #{to}
GROUP BY abstainReason;
```

```sql
-- A-12：进入复核队列（含 LOW_CONFIDENCE）
-- 没有状态历史表，用当前状态兜底；不加 IS NOT NULL，空原因要作为「未记录」暴露出来
SELECT abstainReason AS reason, COUNT(*) AS count
FROM t_diagnosis
WHERE isDelete = 0
  AND humanReviewStatus IN ('AWAITING_REVIEW', 'REVIEWED', 'OVERRIDDEN')
  AND createTime >= #{from} AND createTime < #{to}
GROUP BY abstainReason;
```

后端把已知枚举归组；契约外取值合并为 `UNCLASSIFIED`、空值合并为 `UNRECORDED`，
两者分别计数（`unclassifiedReasonCount` / `unknownReasonCount`），不丢弃。

### 3.4 模型版本（B-01 / B-02）

```sql
SELECT modelVersion, COUNT(*) AS count,
       SUM(aiRejected = 1) AS rejected,
       AVG(CASE WHEN confidence > 1 AND confidence <= 100 THEN confidence END) AS avgConfidence,
       SUM(CASE WHEN confidence > 1 AND confidence <= 100 THEN 1 ELSE 0 END) AS confidenceSamples,
       SUM(CASE WHEN confidence > 0 AND confidence <= 1 THEN 1 ELSE 0 END) AS ambiguousConfidence
FROM t_diagnosis
WHERE isDelete = 0 AND createTime >= #{from} AND createTime < #{to}
GROUP BY modelVersion;
```

**置信度量纲按 0–100 使用，不做猜测式归一**（决策 2）：只有
`1 < confidence <= 100` 的样本参与均值与直方图；`(0,1]` 计入
`ambiguousConfidence`，`<= 0` 或 `> 100` 计入 `outOfRange`，NULL 计入 `missing`。
三者都不进入任何图表，只在数据质量区显示。**不得仅凭数值 ≤1 就乘 100**：
0.74 既可能是 74%，也可能是 0.74%。

### 3.5 质量分档（B-05）与质量原因（B-06）

```sql
SELECT qualityScore, COUNT(*) AS count
FROM t_diagnosis
WHERE isDelete = 0 AND createTime >= #{from} AND createTime < #{to}
GROUP BY qualityScore;
```

Service 层按 `{100,75,50,25,0}` 匹配；其余非空值 → `outOfContract`；NULL → `unknown`。

```sql
SELECT qualityReasons
FROM t_diagnosis
WHERE isDelete = 0 AND qualityReasons IS NOT NULL AND qualityReasons <> ''
  AND createTime >= #{from} AND createTime < #{to};
```

多标签在 Java 里拆分计数（逗号分隔，去空白，只认已知四个标签）。

---

## 4. 索引：基准实测结论

### 4.1 方法

一次性库 `diag_analytics_bench`，10 万行 MD5 去相关造数
（18% 拒识、30% 已复核、5 个复核人、3 个模型版本、5 档质量分、200 天时间跨度），
`ANALYZE TABLE` 后跑 `EXPLAIN ANALYZE`，窗口 90 天。**基准库已删除，不留在本机。**

### 4.2 实测（100k 行）

| 查询 | 建索引前 | 建索引后 | 说明 |
|---|---|---|---|
| A 复核人工作量 | 54 ms | **18 ms** | 覆盖索引，无回表 |
| B 复核时延 | 43 ms | **14 ms** | 同上 |
| C 拒识原因 | 36 ms | **14 ms** | 覆盖索引 |
| D 模型版本 | 66 ms | **32 ms** | 覆盖索引；`confidence` 是 DOUBLE，索引变宽 |
| E 质量分档 | 51 ms | **10 ms** | 覆盖索引 |
| OOD 分布 | 47 ms | **11 ms** | 同一覆盖索引 |

反例：只加 `idx_createTime (createTime)` 后，D/E 反而变慢（116 / 71 ms）——
38k 次随机回表比全表顺序扫更贵。**只建单列时间索引是错误的。**

### 4.3 建议索引（待真实数据量复核）

```sql
-- V34+（V31 已被 PR #142 占用；V32/V33 已被 P1 整改 PR 占用；实施时重新核对迁移矩阵）
ALTER TABLE t_diagnosis
  ADD INDEX idx_review_cover
    (humanReviewStatus, humanReviewedAt, humanReviewerId, createTime, isDelete),
  ADD INDEX idx_abstain
    (aiRejected, createTime, abstainReason, isDelete),
  ADD INDEX idx_create_cover
    (createTime, isDelete, modelVersion, confidence, aiRejected, qualityScore, oodStatus),
  ALGORITHM=INPLACE, LOCK=NONE;
```

门槛（按用户要求，全部满足才加）：

1. 在**真实数据量级**的表上重跑同一组 `EXPLAIN ANALYZE`；
2. 对比建索引前后的写放大与磁盘占用；
3. `LOCK=NONE` 仍可能等元数据锁，安排在低峰执行，执行前 `SHOW PROCESSLIST`；
4. 迁移必须可回滚（对应 `DROP INDEX` 写在发布说明里）；
5. 如果真实查询模式与基准不同，以 `EXPLAIN` 为准调整索引，不照搬本文。

---

## 5. 测试计划与实际落点

| 层 | 用例（已实施） |
|---|---|
| Service 单测 `AdminAnalyticsServiceTest`（16） | 时间窗收敛、分母为 0 → null、两套原因过滤、契约外/空原因分组、置信度三类异常不计入、直方图 10 桶补齐、质量分 5 档 + 越界/空、质量原因多标签、状态 NULL → UNRECORDED、复核人缺名、时延空样本、版本缺失 |
| Controller `AdminStatsControllerTest`（4） | 两条路由各自委托、默认 30 天、两个方法都带 `@AuthCheck(admin)`；实际 403 由 `AuthInterceptor` 统一负责（既有机制，不在本测试重复） |
| Mapper / SQL `AdminAnalyticsMySqlIntegrationTest`（1，需 `FLYWAY_TEST_URL`） | 真 MySQL：窗口函数分位、`AVG(CASE WHEN)`、NULL 分组、越界质量分、两套原因口径、`reviewedWithoutTime`；已接入 `flyway-mysql` CI job |
| 数据质量 | §5 五条自检对应 `dataQuality.*` 字段；测试断言各自能返回非零 |
| 前端（B2） | 六种数据状态文案、双主题、窄屏 |

验证命令：`scripts/verify-fast.sh --java 'AdminAnalyticsServiceTest,AdminStatsControllerTest,AdminAnalyticsMySqlIntegrationTest,AdminStatsServiceTest' --db`
（本次实跑：4 类 29 用例 PASS）；准备合并时 `scripts/verify.sh`。新增的集成类已登记进
`scripts/check_surefire_skips.py` 的 opt-in 契约，避免整类被静默跳过仍算绿。

---

## 6. 实施顺序

1. ✅ 契约评审通过（本文件 + metric registry）
2. ✅ VO / Mapper / Service / Controller + 单测（不含索引）
3. ⏸ 在真实数据量上跑 `EXPLAIN ANALYZE` → 决定是否加索引（本轮按约束不建索引；V32/V33 已被其他 PR 占用）
4. ✅ 数据质量自检接进接口（`dataQuality`）
5. ⏸ 前端 B2/B3

## 7. 与六项验收标准的对应

| 验收标准 | 本方案落点 |
|---|---|
| 两页职责清晰、不复制 KPI | overview 不动；analytics 只出增量维度；页面 KPI 在 B2/B3 重定义 |
| 指标有可审计定义与样本口径 | metric registry + 响应带 `samples` / `dataQuality` |
| 支持维度对比、筛选、明细 | B1 提供分组与分位；明细分页在 B2 |
| Dashboard 卡片可直达处理页 | B3（本方案不含） |
| 缺失/历史/异常值可信展示 | `dataStatus` + 未归类/越界/未记录字段 |
| 双主题、权限、窄屏、迁移测试 | 权限与迁移测试在 B1；主题与窄屏在 B2 |
