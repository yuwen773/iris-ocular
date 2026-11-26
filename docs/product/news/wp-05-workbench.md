# WP-05 · Iris News Engine 管理员工作台与编辑台溯源接入

WP-05 在 WP-02（资料池与调度）、WP-03（精选与费用回执）及 WP-04（审核发布与修订历史）已落库数据之上，交付管理员资讯引擎工作台前后端闭环；本阶段**不新增 Flyway 迁移**（保持 `V42`），也不在界面开放实时付费 LLM 调用或外部联网抓取入口。

## 1. 后端工作台控制面（`newsworkbench`）

由 [NewsWorkbenchController](../../../src/main/java/com/csust/eyediagnosis/newsworkbench/NewsWorkbenchController.java) 与 [NewsWorkbenchService](../../../src/main/java/com/csust/eyediagnosis/newsworkbench/NewsWorkbenchService.java) 暴露 `/api/admin/news-workbench/*` 端点（全部标注 `@AuthCheck(mustRole = "admin")` 并受 `SameOriginFilter` 保护）：

- **总览与信源控制（`GET /overview`、`GET /sources`、`POST /sources/{source}/toggle`、`POST /sources/{source}/configs`）**：汇总实时 SQL 状态分布、在线精选开关 `curationEnabled`（关闭时界面诚实标示）、今日全局预算与最近一次 NewsBench 门禁结论；支持启停指定信源与登记新配置版本/游标。
- **采集窗口与租约审计（`GET /tasks`、`POST /tasks/enqueue`、`GET /tasks/{taskId}/leases`）**：分页筛选采集任务、手动入队新窗口并检测已过期租约；`LeaseRecordVO` 严格省略 `leaseTokenHash`，绝不向浏览器暴露租约凭证哈希。
- **候选队列与证据快照审查（`GET /candidates`、`GET /candidates/{candidateId}`）**：按候选状态、信源与证据等级筛选，返回关联 `t_news` 建稿 ID/状态、结构化声明（含逐字引文）、局限性、违规项、冻结证据快照 `evidenceSnapshotJson` 与模型调用回执详情。
- **预算、熔断恢复、超时回收与人工对账（`GET/POST /budgets`、`POST /budgets/reset-circuit`、`GET /receipts`、`POST /receipts/reclaim-stale`、`POST /receipts/{callId}/reconcile`）**：管理员可配置日额度、人工解除鉴权熔断、回收超时 `RESERVED`/`DISPATCHED` 回执，以及仅针对 `OUTCOME_UNKNOWN` 回执执行 `CONFIRMED_NOT_CHARGED` / `CONFIRMED_CHARGED_NO_OUTPUT` / `CONFIRMED_CHARGED_WITH_OUTPUT` 对账（写入 `admin:<userId>` 操作人标识）。
- **评测历史（`GET /bench-runs`）**：只读展示已落库的 NewsBench 运行统计与各万分比指标。

## 2. React 管理端工作台与编辑台集成（`web/`）

- **工作台页面（[AdminNewsWorkbenchPage](../../../web/src/pages/admin/AdminNewsWorkbenchPage.tsx)）**：纳入侧栏导航 `08 资讯引擎`（`/admin/news-workbench`）并修复 [AdminLayout](../../../web/src/components/admin/AdminLayout.tsx) 面包屑前缀匹配；提供 4 枚概览 KPI 卡与 URL 同步五分栏（精选候选 / 采集任务 / 信源与游标 / 预算与回执 / NewsBench），候选证据抽屉可直接调用 `POST /news/from-candidate/{candidateId}` 建稿并跳转编辑页。
- **新闻管理与编辑页衔接**：在 [AdminNewsPage](../../../web/src/pages/admin/AdminNewsPage.tsx) 页头增加工作台跳转按钮；在 [AdminNewsEditPage](../../../web/src/pages/admin/AdminNewsEditPage.tsx) 右侧栏为已落库资讯提供**证据溯源与修订**卡片，并按需拉取 [GET /news/{id}/revisions](../../../src/main/java/com/csust/eyediagnosis/controller/NewsController.java) 在抽屉中展示不可变修订历史与冻结快照。

## 3. 本地验证

- **L1 后端**：[NewsWorkbenchControllerSecurityTest](../../../src/test/java/com/csust/eyediagnosis/newsworkbench/NewsWorkbenchControllerSecurityTest.java) 与全部 `News*` 安全/服务单测。
- **L2 后端（真实 MySQL 8 + Redis 7）**：基于 `0d85290c7316` 的 WP-05 工作树运行 `verify-fast.sh --db`，30 个测试类共 160 项通过，零失败、零跳过。[NewsWorkbenchMySqlIntegrationTest](../../../src/test/java/com/csust/eyediagnosis/newsworkbench/NewsWorkbenchMySqlIntegrationTest.java) 的 5 项集成用例均通过 `flyway` 模式的 Surefire 跳过检查。
- **前端（`web/`）**：工作台查询及刷新统一使用 `queryKeys.admin.newsWorkbench`，修复页面读取未定义查询键导致的崩溃。类型检查、Lint、生产构建、CSS 边界审计通过。Vitest 全量 111 个文件、1,194 项通过，其中 [API 客户端](../../../web/src/lib/newsWorkbenchApi.test.ts)、[工作台](../../../web/src/pages/admin/AdminNewsWorkbenchPage.test.tsx)和[编辑页](../../../web/src/pages/admin/AdminNewsEditPage.test.tsx)共 29 项。
- **仓库脚本**：使用隔离的 MySQL 8.0.36 容器提供 CI 健康探针夹具，`scripts/tests` 全量 67 项通过，容器、卷和网络无残留。
- **未运行项（`NOT_RUN`）**：实时付费 LLM 调用、外部信源联网抓取、全量 L3（`scripts/verify.sh`）、托管 CI、Playwright 浏览器 E2E 与双主题视觉验收。
