# WP-08B.2 · 运行时闭环（N1 入口 · 工作台接线 · PostgreSQL 隔离验收）

状态：2026-11-28。**真实供应商请求 1 次**（所有者单次授权，隔离环境，记录见
`wp-08b2-paid-integration-plan.md` §10），`eye-diagnosis.news-curation.enabled` 仍默认 `false`。
本文记录「入口已就绪、隔离验收已通过、单份资料真实跑通一次」的证据；扩量与账单对账仍未执行。

## 1. 结论

| 问题 | 结论 |
| --- | --- |
| 管理员能否从产品界面实际触发一次生产精选？ | 能：`POST /api/admin/news-workbench/materials/{id}/curate`，工作台「AI 精选」页签 |
| 模型由谁选择？ | 服务端配置（`run-profile-key` 或唯一 profile），客户端不能指定模型 |
| 结果会不会自动发布？ | 不会：先落回执，成功回执需管理员显式转成 `CURATED_NEEDS_REVIEW` 候选 |
| 预算/回执/熔断/幂等是否复用旧路径？ | 完全复用 `runModel` 的预扣、四道闸门、回执与重放语义 |
| 是否新增迁移？ | 否；PG 线仍到 V7 |
| 真实模型调用 | 已执行 1 次（2026-11-28 单次授权，隔离环境）：`SUCCEEDED` / HTTP 200 / 1694 token / ¥0.031512 标准费率成本 |

## 2. 生产入口与调用链

| 方法 | 路径 | 权限 | 作用 |
| --- | --- | --- | --- |
| GET | `/api/admin/news-workbench/materials?source=&current=&pageSize=` | admin | 当前可见资料修订的精选就绪列表（证据路由、门禁、活跃候选、最近回执）；`Cache-Control: no-store` |
| POST | `/api/admin/news-workbench/materials/{materialRevisionId}/curate` | admin | 触发一次生产 P05；返回回执快照，不建稿、不发布 |
| GET | `/api/admin/news-workbench/materials/{materialRevisionId}/receipts` | admin | 该修订在生产策略下的回执历史（最早在前）；`no-store` |
| GET/POST | `/api/admin/news-workbench/materials/{id}/evidence-verifications` | admin | 证据核验账本读取 / 登记（沿用 WP-08B.2） |
| POST | `/api/admin/news-workbench/evidence-verifications/{id}/revoke` | admin | 撤回核验（保留历史） |
| POST | `/api/admin/news-workbench/receipts/{callId}/materialize` | admin | 冻结回执转待审候选（幂等，可显式替代旧候选） |

调用链：

```
POST /materials/{id}/curate
  → NewsWorkbenchService.runCuration
      → NewsCurationService.describeRevision(id)      # 只读：证据路由 / 门禁 / 候选 / 回执
      → NewsCurationService.runAdminCuration(id, actor)
          → requireInvocationBoundary()               # enabled + 无嵌套事务
          → requireVisibleRevision + buildSnapshot
          → NewsEditorialEvidence.route(...)          # SKIP 在预扣前直接拒绝
          → resolveRunProfileKey()                    # 服务端裁决模型 profile
          → runModel(id, policy, null, Selection("production", P05, profileKey, null))
              → reserveAttempt → dispatch → client.invoke → settleOutcome
```

成功的 P05 回执**不会**自动生成候选（`prepared != null` 的结算路径只写回执与冻结输出），
必须再调用 `materialize`。这与 WP-08B.2 的「冻结回执转待审候选」设计一致。

## 3. 安全边界

- **服务端裁决模型**：`NewsCurationProperties.runProfileKey`；留空时仅当恰好配置 1 个 profile 才继续，
  否则 `MODEL_PROFILE_NOT_CONFIGURED`。请求体里没有任何模型字段。
- **证据门禁前置**：`INTEGRITY_RETRACTED` / `INTEGRITY_CONCERN` / `TRIAL_NO_CHANGE` / `EVIDENCE_SKIP`
  在 `reserveAttempt` 之前拒绝，不产生回执、不占用预算、不发请求。
- **只跑当前修订**：列表只取 `t_news_material.latestRevisionId` 指向的修订；写路径同样校验
  （`MATERIAL_REVISION_SUPERSEDED`），旧修订的回执也不能再转候选，避免对过期版本花钱或建稿。
- **人工审核不可绕过**：模型输出只能落为 `CURATED_NEEDS_REVIEW`（或证据违规的
  `REJECTED_EVIDENCE_VIOLATION`），发布仍走 WP-04 服务层门禁。
- **重复触发**：顺序重复触发同一 logical 调用时，`reserveAttempt` 返回同一 `callId`
  （`replayed = true`），不产生第二次供应商请求；**并发**的第二次在第一条仍为 RESERVED/DISPATCHED 时
  被拒绝为 `CURATION_AWAITING_RECONCILIATION`，拿不到同一 `callId`（重放只发生在已结算的回执上）。

## 4. 错误语义与回执状态

| 情形 | 服务端结果 | 界面提示 |
| --- | --- | --- |
| 证据路由 SKIP | 异常 `EVIDENCE_GATE_SKIP:<reason>`，无回执 | 「证据准入未通过，不发起付费调用」+ 门禁原因 |
| 免费额度用尽（用完即停） | 回执 `FAILED_DEFINITIVE` / `MODEL_FREE_QUOTA_EXHAUSTED`，预留退回，不熔断 | 「免费额度已耗尽（用完即停），本次未产生费用」 |
| 欠费 | `FAILED_DEFINITIVE` / `MODEL_BALANCE_INSUFFICIENT`，不熔断 | 「模型账户欠费」 |
| 鉴权失败 | `FAILED_DEFINITIVE` / `MODEL_AUTH_REJECTED`，熔断该 scope | 「已熔断，请核查密钥后重置」 |
| 429 / 超时 / 传输异常 | `OUTCOME_UNKNOWN`，预留继续占用 | 「结果未知，需人工对账，系统不会自动重发」 |
| 输出格式错误 | `FAILED_CHARGED` / `INVALID_STRUCTURED_OUTPUT`，无候选 | 「已扣费但输出未通过契约」 |
| 应用预算耗尽 | 异常 `BUDGET_EXHAUSTED`，无新回执 | 「今日精选预算已耗尽，未发出请求」 |
| 整次实验上限 | 异常 `LIFETIME_BUDGET_EXHAUSTED` | 「整次实验总预算已耗尽」 |
| 存在待对账/在途回执 | 异常 `CURATION_AWAITING_RECONCILIATION` | 「请先在回执台账完成对账」 |
| 熔断中 | 异常 `CURATION_CIRCUIT_BROKEN:<reason>` | 「请先在预算页重置」 |
| 该修订已有确定性失败回执 | 列表标 `curatable=false`，按钮禁用 | 「上次为确定性失败；本入口只重放既有回执」 |
| 该修订存在在途/未知回执 | 列表标 `curatable=false`（`AWAITING_RECONCILIATION`） | 「请先在回执台账完成对账」 |
| 该修订已被同资料的更新版本取代 | 异常 `MATERIAL_REVISION_SUPERSEDED`，不发请求 | 「该修订已被更新版本取代，请刷新后对当前修订运行」 |
| 旧修订的成功回执 | 转候选被拒（`MATERIAL_REVISION_SUPERSEDED`） | 「该修订已被更新版本取代，不能再生成候选」 |

确定失败（额度/欠费/鉴权/格式）都已落回执；同一修订再次触发只会重放既有回执，不会再次计费。
「确定性失败」与「可运行」都按**当前 logical 调用**判定：identity 由服务端 profile、prompt 版本与证据
overlay 组成，换了其中任何一项就是一个新的 logical 调用——旧回执只是历史，按钮随之恢复可用，
服务端也确实会发起新调用（唯一的确定性失败恢复路径，见 §9）。

## 5. 工作台变化（`web/`）

- 新增「AI 精选」页签（`AdminNewsWorkbenchPage`），复用既有 `DataTable` / `Drawer` / `RiskTag` / `admin-btn`：
  - 资料列表：标题/修订、证据路由与门禁原因、许可与候选状态、最近回执；
  - 「运行 AI 精选」受 `overview.curationEnabled` 与 `curatable` 双重约束；
  - 运行结果面板：回执状态、路由、模型/Provider、token、标准费率费用、错误码、
    该修订的回执历史；`OUTCOME_UNKNOWN` / `FAILED_CHARGED` / 额度耗尽各有说明文案；
  - 「转为待审候选」调用 `materialize`，成功后提示候选编号并引导到「精选候选」队列。
- 「证据核验」抽屉：读取活跃/已撤回核验，登记同行评审/完整性/研究设计/授权范围/许可，支持撤回。
- 页脚边界声明更新为：仅在「AI 精选」页签开放管理员触发、受预算与熔断约束、不自动建稿或发布。
- 在线精选关闭时其他页签（采集、信源、预算、评测、刊物、候选）行为不变。

## 6. 隔离验收

### 6.1 后端（真实 PostgreSQL 18 + pgvector）

`NewsCurationRuntimePostgresTest`（`BUSINESS_TEST_URL`，`scripts/verify-pg.sh --full`）13 例：

1. profile 只由服务端配置裁决（缺省/歧义/不存在都拒绝）；
2. 已撤稿资料在预扣与模型调用之前被拒绝；
3. 采集 → 证据核验 → 管理员触发 → `SUCCEEDED` → 回执 → 转候选 → `CURATED_NEEDS_REVIEW` 草稿
   → 人工审核发布 → 公开列表口径（`isDelete=0` 且 `PUBLISHED`）可读到该行且留有发布修订；
4. 未审核草稿直接发布被发布门禁拒绝（「待复核候选必须经待审核流转…」，状态保持 `DRAFT`）；
5. 重复触发重放同一回执，只有 1 次模型调用；
6. 免费额度耗尽 → `FAILED_DEFINITIVE`，不熔断，预算退回；
7. 鉴权失败 → 熔断；同修订重放、其他修订被拒；
8. 429 → `OUTCOME_UNKNOWN`，再次触发要求先对账；
9. 日预算耗尽 → 第二次在校验阶段拒绝，不发请求；
10. 结构化输出非法 → `FAILED_CHARGED`，不产生候选；
11. 运行后撤回证据 → 转候选被拒（`EVIDENCE_VERIFICATION_STALE`）；
12. 同资料重新采集出新修订后：旧修订触发精选被拒（不发请求、不加回执）、旧回执转候选被拒、列表只列新修订；
13. 确定性失败后切换到另一个已配置 profile：新的 logical 调用成功，列表恢复「可运行」，旧失败回执保留为历史。

「用户端展示」这一步在本套件里验到公开列表口径的数据行与发布修订；HTTP 层页面渲染由
`web/` 全量用例与既有资讯接口的 Postgres 套件覆盖，未在真实浏览器里重放本文这一次精选。

另有 `NewsOpenAiCompatibleModelClientTest` 8 例（免费额度/欠费/鉴权分类）与
`NewsWorkbenchControllerSecurityTest` 8 例（新端点仅管理员、读取 `no-store`、错误文案）。

`scripts/verify-pg.sh --full` 实测（2026-11-28，含新增的 `NewsCurationPropertiesBindingTest`）：
`Tests run: 1434, Failures: 0, Errors: 0, Skipped: 290`
（290 条均为未注入对应环境的其它 opt-in 套件），`check_surefire_skips.py ... business` 通过——
12 个 business/coordination 套件全部 0 skipped，其中本套件 13/13。

### 6.2 前端

`web/`：`npm run typecheck`、`npm run lint`、`npm run build` 通过；全量 `vitest run` 120 文件 / 1267 用例通过。
新增：`AdminNewsWorkbenchPage.test.tsx`（触发运行→转候选→登记证据；关闭态按钮禁用）、
`newsWorkbenchApi.test.ts`（Long 字符串 id、计数归一化）。

## 7. G-1…G-7 复核

| 编号 | 状态 | 说明 |
| --- | --- | --- |
| G-1 用完即停 | **已确认** | 所有者控制台截图 + 明确说明蓝色开关即「用完即停」 |
| G-2 免费额度余量 | **已确认（快照）** | 控制台截图：`qwen3.8-max-0902` 1M/1M，额度 2026-12-01 到期 |
| G-3 `json_object` / `enable_thinking` | **已确认** | 真实调用以 `JSON_OBJECT` + `disable-thinking: true` 返回 HTTP 200 且输出通过结构化校验 |
| G-4 计费状态 | **待对账** | 回执记标准费率 31512 micros；账单免费额度抵扣行与实付行尚未导出 |
| G-5 通用 API Key | **已确认** | 本机 `DASHSCOPE_API_KEY`（`secretRef` 取值，未入库、未打印） |
| G-6 隔离环境 | **已满足** | 一次性 PG18 + Redis 容器、无生产/患者数据、预算与回执表从零开始 |
| G-7 运行时调用入口 | **已解除** | 本轮 N1：3 个管理员端点 + 工作台页签 |

G-1…G-3、G-5…G-7 已具备证据；G-4（账单对账）仍需新的授权与账单页截图。
G-7 解除与单次授权是两件事：扩量仍需单独授权。

## 8. 首次真实调用 Runbook（2026-11-28 已按此执行一次）

0. 复核 G-1…G-5，全部通过并留截图；确认部署环境 `news-curation.enabled=false`。
1. 配置（不入库）：`run-profile-key: prod`；`model-profiles.prod` 按 `wp-08b2-paid-integration-plan.md` §3
   写入模型/端点/标准费率 `pricingVersion`；API Key 用环境变量或 `secretRef` 注入，禁止明文入库。
2. 在一次性 PostgreSQL 18 环境启动后端，确认预算表无历史行（`SELECT COUNT(*)`）。
3. 打开开关（仅该环境），管理员登录工作台 →「AI 精选」→ 选 1 份证据充分资料 →「证据核验」补齐。
4. 点「运行 AI 精选」，核对：
   - 返回回执 `SUCCEEDED`、`callId`、`logicalCallSha256`、token 与标准费率费用；
   - 供应商账单免费额度抵扣行与 token 一致；
   - 「转为待审候选」后候选为 `CURATED_NEEDS_REVIEW`，建稿停在 `PENDING_REVIEW`。
5. 任一异常（免费额度耗尽、欠费、429、非零应付）立即停止：不重试、不换模型、不扩量；
   `OUTCOME_UNKNOWN` 必须先在「预算与调用回执」对有账单记录再看。
6. 关闭开关，导出账单截图，按 `wp-08b2-paid-integration-plan.md` §10 模板写报告。

## 9. 未验证 / 未覆盖

- **确定性失败没有专用的「重试同一条回执」按钮**：`retryDefinitiveModel` 服务方法存在但没有 HTTP 入口。
  本轮不新增（不扩大架构）。恢复路径是让 logical 身份变化——换服务端 profile（`run-profile-key`）、
  换 prompt 版本或重新核验证据——此时会产生新的 logical 调用；同一 logical 仍然只重放、不重试。
- 真实供应商的**成功路径**（200 报文、token、延迟）已被 1 次调用覆盖；**失败路径**（429、免费额度耗尽的
  403、鉴权拒绝）仍只有脚本化夹具与官方文档，没有真实报文；
- 供应商账单与回执 token/费用的对账（未发生真实计费）；
- 多实例并发下的工作台行为（本期只依赖数据库层幂等，未做分布式调度）；
- `enable_thinking` / `response_format` 只验证了「请求被接受且输出可解析」；模型侧是否真的关闭思维链、
  是否真的按 JSON 约束生成，没有独立观测；
