# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Iris — **Spring Boot 3.5.6** (Java 21) + **React 19** 产品前端（`web/`）+
**微信小程序**患者端（`miniapp/`，Taro 4 + React，体验版）+
**Vue 3** 已弃用前端（`frontend/`，deprecated）+ **Python** algorithm service.
Base package: `com.csust.eyediagnosis`

> **本分支是 Java 线。** 仓库里另有一条 Python 后端线（FastAPI），在 `feature/python-backend`
> 分支，与本分支**不再互相合并**；`frontend/`、`algorithm/` 下的 ML 部分等共享目录各持一份，
> 改动需人工搬运。约定见 `docs/backend-split.md`。

## Navigation

- 领域命名、模块边界、易混术语 → `GLOSSARY.md`（架构评审与新增概念前读）。
- 找设计 / 计划 / 验收 / 迁移文档 → `docs/README.md`（按主题分组的索引）。
- 写前端样式 / 评审前端 diff → 仓库根 `DESIGN.md`（**Iris 2.2 · Adaptive Visual System**，
  官方 DESIGN.md 格式：YAML 令牌 + 八段规范）+ `web/src/styles/tokens.css`（**已实现的令牌事实**）。
  两者不一致时以 `tokens.css` 为准；规则变更史见 `docs/design/iris-2-rule-change-matrix.md`，
  实现偏差登记 Issue，规范里不再维护状态表。
- 改数据库 → `src/main/resources/db/migration/V*.sql` 是迁移真源；版本编号与历史决策见 `docs/flyway-migration-history-plan.md`，各目标库的升级路径见 `docs/architecture/migration-matrix.md`。
- 评审代码风格与判断类约定 → `CODING_STANDARDS.md`。
- 多工作树协作、Windows 进程操作、临时验证产物清理 → `docs/agent-worktree-conventions.md`。

## Build & Verify

后端构建脚本看 `pom.xml`，产品前端脚本看 `web/package.json`（`typecheck` / `test` / `build` / `lint`，
CI 里是根 `verify.yml` 的 `web` job），小程序看 `miniapp/package.json`（`typecheck` / `test` / `build:weapp` /
`gen:tokens`，CI 里是 `miniapp` job），Vue 已弃用线看 `frontend/package.json`，算法服务看
`algorithm/requirements.txt` 与 `run_mcp_server.py` / `grpc_server.py`；`docker-compose.yml` 起 ChromaDB :8000。

**日常改动走分级验证 `scripts/verify-fast.sh`**（L0 / L1 / L2 / L3，**默认 L1**）：
按改动范围点名要跑的用例，改一两个文件不必启动全量。涉及数据库状态、迁移、权限、
RAG 证据、Spring 装配时升到 L2（`--db`）；准备合并时才用 L3。规则与逐模块影响映射见
`docs/testing/verification-levels.md`。

**后端全量验证走 `scripts/verify-pg.sh`**（`--full` 跑完整 `mvn test`）：它通过 docker-test-harness 起一次性
`pgvector/pgvector:pg18`、bootstrap 最小权限角色、跑真正的 PG Flyway 线，注入 `SPRING_PROFILES_ACTIVE=ci`，
结束自动清理容器。旧的 MySQL 版 `scripts/verify.sh` 只作为历史线保留。JDK 21 由脚本设为
`C:\Users\yuwen\.jdks\corretto-21.0.7`；手工跑 Maven 时用 `JAVA_HOME=<该路径> mvn ...`。
本机没有常驻 Redis 时，验收套件需要 `SPRING_DATA_REDIS_PORT` 指向一个可用实例（登录/会话会用到）。

**Docker 测试资源统一走 `scripts/lib/docker-test-harness.sh`**（Run ID + `eye.owner` 标签 + 命名卷 + 统一 teardown）；遗留资源只读盘点与安全回收用 `scripts/docker-test-resources.sh`（默认 dry-run，**禁止全局 prune**）。根因、入口清单与历史资源清理方案见 `docs/docker-test-resources.md`。

Java 21 与本机 MySQL 实例的环境事实见主检出目录的 `CLAUDE.local.md`（不入库，工作树不自动继承）；Codex 侧同一份在 `~/.codex/AGENTS.md`。

**本地收尾**：工作树与测试服务的盘点/回收走 `scripts/worktree-hygiene.sh`（`report` / `reap` / `branches`）
与 `scripts/dev-servers.sh`（`list` / `stop`），规则见 `docs/agent-worktree-conventions.md` §收尾。

## Architecture

**Backend**: Controller → Service → Mapper (MyBatis Plus). `BaseResponse<T>{code:0, data, msg}` unified response. Auth: Spring Session + Redis, `@AuthCheck(mustRole=...)` AOP. MyBatis Plus `map-underscore-to-camel-case: false`.

**Agent System** (`agent/`): Core AI chatbot on Spring AI + Spring AI Alibaba.
- `DiagnosisAgent` — `ReactAgent` with streaming `Flux<MessageDTO>`, SSE endpoint `/api/agent/chat/stream`
- `tools/EyeImageAnalyzerTool` — DashScope Qwen-VL-Plus vision analysis (`@Tool`)
- `tools/DocumentSearchTool` — ChromaDB RAG retrieval (`@Tool`)
- `manager/MCPManager` — MCP client factory (STDIO/SSE/Streamable HTTP), tool discovery cached in Redis 5min
- `interceptor/TextMcpModelInterceptor` — MCP server resolution priority: session override → user enabled → disabled
- `rag/` — Document parsing + 512-word chunking → ChromaDB via DashScope embeddings

**gRPC + Python Algorithm** (`algorithm/`): EfficientNet-B4 model for 8-class eye disease classification. Protobuf in `src/main/proto/diagnosis.proto`, compiled by `protobuf-maven-plugin` (Java) and `algorithm/generate_proto.py` (Python). Java client: `grpc/DiagnosisGrpcClient`.

**Frontend (product)** (`web/`): React 19 + TypeScript + Vite + Tailwind v4 + React Router. API 层手写于 `web/src/lib/api.ts`（统一 `ApiError` / `BaseResponse`），Agent 聊天用 `@microsoft/fetch-event-source` 消费 SSE。2026-11-10 起由 `git subtree` 纳入本仓库，原独立仓库 `eyes-diagnosis-web` 已归档。

**Frontend (deprecated)** (`frontend/`): Vue 3 + TypeScript + Ant Design Vue 4 + Tailwind CSS v4 + Pinia + ECharts. API clients generated via `@umijs/openapi`. **已弃用（deprecated，2026-11-10 起）**：不再开发、不再作为设计参考，仅保留可运行以作回溯；新功能与修复一律进 `web/`。

**Mini Program (patient)** (`miniapp/`): Taro 4 + React + TypeScript + Sass。tabBar 四页（判读 / 记录 / 咨询 / 我的），
判读走同步 `/api/diagnosis/diagnosis`，Agent 用 `wx.request` 的 `enableChunked` 消费 SSE。
样式令牌由 `scripts/gen-tokens.mjs` 从 `web/src/styles/tokens.css` 生成到 `src/styles/tokens.scss`（勿手改）。
后端配套：`miniapp/` 包提供 `/api/mp/auth/*` Bearer 令牌通道（`MiniAppTokenFilter` @Order(0)、
`t_user_wechat` 绑定表、V35 迁移），绕过 `SameOriginFilter` 对小程序请求的 403；Cookie 通道语义不变。
规则与未决项见 `docs/product/wechat-miniapp/README.md`。

## API Endpoints

| Controller | Path | Purpose |
|------------|------|---------|
| UserController | `/api/user/*` | Auth, CRUD |
| MiniAppAuthController | `/api/mp/auth/*` | 小程序：`login`（code→Bearer 令牌，同源校验的**按路径唯一**豁免项）、`logout` |
| AdminStatsController | `/api/stats/*` | 管理端：`overview` 供仪表盘（当前态/环比），`analytics` 供数据分析（复核、拒识归因、模型与质量、数据质量自检；复核维度为 HISTORICAL_ONLY） |
| DiagnosisController | `/api/diagnosis/*` | Upload image, gRPC diagnosis, page query, cancel an in-flight run |
| DiagnosisTaskController / PatientAuthorizationController / PatientExplanationController / PatientFollowUpController | `/api/diagnosis-tasks/*` | 患者影像任务、保存授权、分层解释与随访 |
| AgentController | `/api/agent/*` | SSE chat stream, session/history |
| AgentImageUploadController | `/api/agent/image/*` | Authenticated agent image upload and deletion |
| CollaborationRunController | `/api/agent/collaboration-runs/*` | 多 Agent 协作运行：准入、授权快照/SSE、取消（默认关闭） |
| McpServerController | `/api/mcp-server/*` | MCP server CRUD |
| KnowledgeBaseController | `/api/knowledge/*` | RAG document management |
| NewsController | `/api/news/*` | News CRUD |
| NewsWorkerLeaseController | `/api/internal/news/v1/leases/*` | 内部资讯采集 Worker 租约领取、批次上报、完成与失败回执（机器凭证鉴权） |
| NewsWorkbenchController | `/api/admin/news-workbench/*` | 管理员资讯引擎工作台：任务追踪、信源与预算管理、候选证据审查、调用对账与评测历史 |
| NewsCollectionScheduleController | `/api/admin/news-workbench/scheduling` | 管理员读取自动采集状态、进度和 Worker 领取观测 |
| ControlledReferenceController | `/api/controlled-references/*` | 受控参考资料上传、治理与检索 |
| DoctorReviewController | `/api/doctor-reviews/*` | 医生复核冻结草稿 |
| PatientDraftTestController / PatientDraftSeedController | `/api/patient-draft-test/*` | 仅管理员、需显式开启的草稿校验 |
| UserFeedbackController / AdminUserFeedbackController | `/api/feedback/*`、`/api/admin/feedback/*` | 用户反馈：提交 / 我的反馈 / 截图；管理端台账、状态流转与回复（产品意见通道，**非**医疗记录；开关 `eye-diagnosis.feedback.enabled`） |
| NotificationController | `/api/notifications/*` | 站内通知：我的通知分页 / 未读数 / 标记已读 / 全部已读（接收人恒为会话用户；通知是旁路，写失败不阻断业务） |
| ControlCenterController / ControlCenterCsrfController | `/api/admin/control-center/*` | 配置中心草稿/发布/审批/回滚 + CSRF 令牌；默认关闭（`eye-diagnosis.control-center.enabled=false`） |

## Database (PostgreSQL 18 + pgvector)

> **本分支（`feat/postgresql-pgvector-migration`）已迁到 PostgreSQL**：不再是 MySQL + ChromaDB。
> MySQL 的 `db/migration/V*.sql` 保留为历史记录（Current head is V47），**不要**再往上加迁移；
> PostgreSQL 线是 `db/postgresql/V*.sql`，当前到 V5。
> 迁移决策、验收证据、本轮修复的移植缺陷与生产切换授权清单见
> `docs/architecture/postgresql-pgvector-migration-report-2026-11-28.md`。

Schema: `src/main/resources/db/postgresql/V*.sql`（独立 PG Flyway 历史，authoritative）。业务关系数据在
`diagnosis` schema；RAG 向量在 `rag.iris_knowledge_vectors`；同一库、按 schema 与角色逻辑隔离。
连接串必须带 `stringtype=unspecified`（`jsonb` 参数依赖它，缺失时启动闸门 `PostgreSqlRequirement` 直接拒绝启动）。
Least-privilege 角色 `iris_migrator`（DDL）/ `iris_app`（DML）由 `scripts/postgresql/bootstrap-roles.sql`
建立——**Flyway 之前必须跑一次**（schema、授权与 MySQL 兼容 cast 都在里面）。Never edit an applied migration; add a new one.
Key tables: `t_user`, `t_user_wechat`, `t_config_draft`, `t_config_release`, `t_config_active_pointer`, `t_config_request`, `t_config_audit_event`, `t_config_publish_request`, `t_user_feedback`, `t_user_notification`, `t_diagnosis`, `t_diagnosis_task`, `t_agent_session`, `t_agent_message`, `t_agent_tool_audit`, `t_mcp_server`, `t_knowledge_document`, `t_news`, `t_news_audit`, `t_news_revision`, `t_news_cache_epoch`, `t_news_source`, `t_news_source_cursor`, `t_news_collection_task`, `t_news_collection_task_event`, `t_news_collection_schedule`, `t_news_worker_poll`, `t_news_material`, `t_news_material_revision`, `t_news_curation_budget`, `t_news_curation_budget_guard`, `t_news_curation_lifetime_budget`, `t_news_curation_candidate_event`, `t_news_model_call_receipt`, `t_news_evidence_verification`, `t_news_curation_candidate`, `t_news_bench_run`, `t_controlled_reference`, `t_patient_draft_run`, `t_doctor_review`, `t_patient_authorization`, `t_diagnosis_run`.

**移植注意**（都是 MySQL 语义假设，PG 上会炸）：「插入 → 撞唯一键 → 同事务回读」必须走
`common/InsertRaceGuard`（savepoint），直接 catch 后事务已是 `25P02`；`jsonb` 列的 `queryForMap`
返回 `PGobject`，不能强转 `String`；`<=>`、`date_format`、`utc_timestamp` 等由 `db/postgresql` 补丁提供；
本分支 SQL 不再使用 `JSON_OBJECT()` / `JSON_ARRAY()` / `LAST_INSERT_ID()`。

**命名规范**: 数据库列名统一使用**小驼峰**命名（如 `userId`, `fileName`, `isDelete`），与 Java 实体类字段保持一致。列名在 SQL 里不加引号（PG 折叠为小写）；MyBatis Plus 配置 `map-underscore-to-camel-case: false`，不会自动转换下划线。

## External Services

PostgreSQL 18 (+pgvector) | Redis (session + cache) | DashScope (LLM + embeddings + vision) | Tencent COS (file storage) | Python gRPC (image classification)

## Key Patterns

- **Agent streaming**: `DiagnosisAgent.stream()` → SSE events (ASSISTANT/REASONING/TOOL_REQUEST/TOOL_CONFIRM), terminal `event: "done"`
- **MCP integration**: User config → `MCPManager` creates client → discover tools → inject as `ToolCallback[]` into ReactAgent
- **RAG pipeline**: Upload → `DocumentParser` → `TextChunkerService` → ChromaDB → `DocumentSearchTool` retrieval
- **Protobuf codegen**: `src/main/proto/diagnosis.proto` → `protobuf-maven-plugin` → generated Java stubs; Python bindings via `algorithm/generate_proto.py`
- **协作工作台（多 Agent）**: 前端入口由构建期变量 `VITE_COLLABORATION_ENABLED` 控制（`web/src/components/shell/navLinks.ts`），未设为 `true` 时导航不出现协作入口；后端能力由 `eye-diagnosis.collaboration.*` 控制，默认全 `false`。**两者必须同时开启**协作才真正可用；本分支（R1.2）不开启、不部署、不做临床启用。

## Design Context

### Users
混合用户群——眼科医生（临床检查）、医院管理员（记录管理/系统运维）、普通患者（AI 自助诊断）。三种角色技术熟练度和使用场景差异大，界面需根据角色调整复杂度。

### Brand Personality
**冷静、精密、克制**；Iris 2.0 起加一句：**同一套品牌身份，四种表达强度**。
判读纸面（白纸 + 墨字 + 发丝线）+ 衬线标题 + 等宽读数传达"这是一台可信的临床仪器"；
首页/品牌层用钴蓝 + 银蓝光学材质表达"这是一个能把复杂视觉信息讲清楚的团队"。
温度靠文案与留白表达，不靠暖色调。

### Aesthetic Direction
**Iris 2.2 · Adaptive Visual System**。设计真源是仓库根 `DESIGN.md`（目标规范），
令牌实现是 `web/src/styles/tokens.css`（已实现的事实）。
`web/`（React）是产品前端，本文件的规则都指它；`frontend/`（Vue）**已弃用（deprecated）**，
仍跑旧的青绿 `--iris-*`，**不再作为设计参考，新代码不要写进 `frontend/`**。
写 `web/` 页面、组件、样式，或评审前端 diff 前 → 读根 `DESIGN.md`
（四场景、令牌、组件、双主题、可访问性、数据真实性）。后端任务不需要读。

### 四种体验场景（写前端先判断自己在哪个场景）
`DESIGN.md` 的 **Overview** 定义四场景，**Layout** 里有**逐路由归属表**（每个真实路由
属于哪个场景、用什么容器类），**Components** 按组件直述变体与签名组件。
**没有"全站通用"的一套控件外观**；也**不是每个页面都套 KPI + 台账**。

| 场景 | 页面 | 主色 | 圆角 | 气质 |
|---|---|---|---|---|
| **Brand** | `LandingPage`/`LoginPage`/`RegisterPage`/`NewsPage`/`NewsDetailPage` | 钴蓝 `--brand-primary` | 24–38px | 精致、清透、留白足 |
| **Management** | `pages/admin/*` | 管理紫 `--admin-primary` 主操作 + `--signal` 当前态 | 8–12px | 成熟 SaaS、信息密度高 |
| **Intelligence** | `AgentPage`、未来的影像工作台 | 运行态蓝/青、类别紫 | 12px | 专业、看得见协作 |
| **Clinical** | `DiagnosisPage`/`RecordsPage`/`ProfilePage`/`RecordSheet` | 墨色主 CTA + `--signal` 语义 | 0 | 克制、纸面稳定 |

登录/注册走品牌层双栏，个人中心与资讯是资料/编辑式排版——都不以 KPI + 台账开头。

### 规则入口（写 / 评审 `web/` 前读这几处，规则正文不在本文件）

| 要判断的事 | 读 |
|---|---|
| 路由属于哪个场景、用什么容器类、什么版式骨架 | `DESIGN.md` → Layout → 路由归属 |
| 组件外观（按钮 / 卡片 / 图表 / 导航 / 弹层 / 表格 / 表单 / 状态标记） | `DESIGN.md` → Components |
| 语义色、等宽读数、圆角令牌梯、阴影与发丝线、纸面原语 | `DESIGN.md` → Colors / Typography / Shapes / Elevation & Depth；令牌真值看 `web/src/styles/tokens.css`（与 DESIGN.md 冲突时以它为准） |
| 动效预算（氛围 / 状态）与 `prefers-reduced-motion` | `DESIGN.md` → Components → Named Rules（No-Invented-State） |
| 图标 | `web/DESIGN.md` 规则 16（`lucide-react` 静态命名导入） |
| 品牌首屏构图 / 公开路由共用外壳 | `DESIGN.md` → Layout / Components → Navigation；`web/DESIGN.md` |

三条不可让渡的判据（正文仍在 `DESIGN.md`）：

- **场景先定**：Brand / Management / Intelligence / Clinical 四选一，它决定圆角、主色与密度。
- **三套语义色互不顶替**：`--signal` 管当前态、`--brand-*` 管品牌身份、`--risk-*` 管医学严重度。
- **严重度永远带文字标签**，颜色不是唯一信息载体。

### UI 冻结（已结束）

**Iris 2.1 UI 冻结已于 2026-11-21 结束**（进入 Iris 2.2），首页 / 品牌层、管理端、
知识库、新闻工作台恢复正常迭代。冻结基线 tag `iris-ui-v2.1-freeze-20261115`
（`80f53f7f55955c2d30a1a9c98479dcb87682051b`）保留不动；冻结期间的历史放行登记见
`docs/design/iris21-ui-freeze-exceptions.md`。

### 参考实现
`web/src/styles/tokens.css`（全部令牌）、`web/src/components/RecordSheet.tsx`（记录纸）、
`web/src/pages/LandingPage.tsx`、`web/src/pages/DiagnosisPage.tsx`。
`web/src/components/brand/HeroFusion.tsx` / `OpticFlow.tsx`（品牌层首屏）与 `--brand-*` /
`.iris2` / `--radius-xl|2xl` 均已在 `tokens.css` 有定义，可直接引用。
Vue 版的 `HomePage.vue` 等三页已弃用，仅作历史参考。

### 设计文档
| 文档 | 何时读 |
|---|---|
| `DESIGN.md` | 写 `web/` 页面/组件/样式，或评审前端 diff（**目标规范**）。旧版（2026-11-21 重写前）见 `git show 34e94c9a:DESIGN.md` |
| `web/src/styles/tokens.css` | 查令牌真值（**已实现的事实**，与 DESIGN.md 冲突时以它为准） |
| `docs/design/iris-2-rule-change-matrix.md` | 想知道旧「白色仪器」规则改成了什么、为什么改 |
| `docs/design/iris-2-implementation-roadmap.md` | 想知道首页/管理端/协作台各自的实施顺序与门禁 |
| `docs/design/iris-2-r2-5-product-shell-audit.md` | 想知道 R2.5 为什么统一外壳、逐页现状与目标、PR 拆分 |
| `docs/design/IRIS_2_DESIGN_REFERENCE_BRIEF.md` + `docs/design/assets/` | 查视觉参考来源与取舍（**灵感，不是实现**） |

### Priority Areas
1. **诊断流程**：登录、首页、图片上传、诊断结果——用户最先接触的核心路径
2. **用户中心**：个人中心、诊断历史、通知——高频使用功能
3. **Agent 智能对话**：AI 对话、会话管理、资源面板——最复杂的交互界面

### Design Principles
1. **克制 = 信任**（Clinical 场景）：骨白与墨色为主，彩色只做语义，界面读起来像一份可信的判读报告
2. **角色自适应**：医生看专业数据，患者看通俗解读，同一系统不同复杂度
3. **橙只做语义**：信号橙标记"重点/当前"，铺满就失去意义；品牌钴蓝也不做大面积填充
4. **场景决定表达强度**：Brand / Management / Intelligence / Clinical 各有各的密度与材质，
   但安全、医学措辞、可访问性、统计口径、证据链**不因场景放松**
5. **流畅过渡**：状态变化有流畅动效，传递系统响应性和可靠性
6. **双主题一致性**：浅色/深色传达相同品牌性格，非简单颜色翻转
7. **不虚构**：界面上的每个数字都能追到后端与口径；设计稿里有 ≠ 产品里有
