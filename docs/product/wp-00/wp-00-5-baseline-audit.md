# WP-00.5 增量审计：最新基线收敛与 WP-01 开发准备

**审计基线：** `5dc2eac50da33b5a0dd359499d291aa5066bd758`（`origin/master`，2026-11-23）
**对照基线：** `f42065e4c38a94f4d1acd81f7cdbe15ad9c7f683`（WP-00 原审计基线，2026-11-16）
**增量距离：** `f42065e4..HEAD` 共 **340** 个提交
**仓库：** `yuwen773/iris-ocular`（public；默认分支 `master`）
**审计方式：** 只读源码核对 + 逐项 `git grep`/文件打开；本轮**未**执行构建、测试、部署或迁移
**结论：** WP-01 **READY_FOR_IMPLEMENTATION（有条件）**；生产启用 **BLOCKED**（见 §10）

> 本文只写增量。WP-00 五份交付物中的原始结论与 `file:line` 证据**不删除、不改写**；
> 与现状冲突的地方，在各文档末尾追加「2026-11-23 增量勘误」，本文给出对应关系（§6）。

---

## 0. 结论摘要

| 项 | 结论 | 证据位置 |
|---|---|---|
| 最新 master | `5dc2eac5`；本地 `master` 引用停在 `c3808e4c`，落后 1 个提交 | `git rev-parse`；§1.1 |
| 工作区 | 干净；当前工作树 `t3/wp-00-5-baseline-audit` 恰好等于 `origin/master` | `git status --porcelain` 为空 |
| 迁移头 | **V31**（基线事实）；WP-01 从 **V35** 起（2026-11-24 改号，见 §12） | `src/main/resources/db/migration/` |
| 配置中心代码 | **零行**：无 `t_config_*` / `t_brand_asset` / `t_provider_*`，无 Controller | `git grep` 无命中 |
| Same-Origin | 已有 `SameOriginFilter`（全局、默认启用、有 10 个单测），但**无 CSRF Token**，且与 ADR D11 有 4 处语义差异 | `config/SameOriginFilter.java`；§3.4 |
| Agent 护栏 | 预算与递归步数上限**已真实接线**，有单测 | `DiagnosisAgent.java:122-174`；`AgentGuardrailWiringTest` |
| 视觉异常输出 | 旧的「合成 attention 结论」**已修复**：非法输出 → `INVALID_OUTPUT` 终态，不再假成功 | `EyeImageAnalyzerTool.java:237-262`；§3.6 |
| 多 Agent | 已在主干（`collaboration/` 5 文件 + `patientdraft/`）；默认关闭 | `application.yml` `collaboration.enabled: false` |
| 认证 | `UserRole` 扩到 4 个值，但可指派角色仍只有 `user/ban/admin`；`@AuthCheck` 仍只有两档语义 | `UserRole.java`；`UserServiceImpl.java:373-413` |
| UI 冻结 | **已结束**（2026-11-21 → Iris 2.2）；WP-04 不再需要解冻例外，但仍受 `DESIGN.md` 约束 | `DESIGN.md:227`；`docs/README.md` |
| COS 品牌资产 | **尚不存在**。品牌资源是 `web/public/brand/` 静态文件；COS 里没有品牌对象 | `web/public/brand/`；§3.9 |

---

## 1. 最新基线与变更清单

### 1.1 基线事实

```
HEAD                 5dc2eac50da33b5a0dd359499d291aa5066bd758
origin/master        5dc2eac50da33b5a0dd359499d291aa5066bd758
本地 master            c3808e4c  （落后 1 个提交，不要用它当基线）
当前分支              t3/wp-00-5-baseline-audit @ 5dc2eac5
工作区                clean
远端                  https://github.com/yuwen773/iris-ocular.git
开放 PR               #154（verify-fast Docker harness，DRAFT）
                      #152（docs 分层整理，DRAFT）
```

- 仓库已从旧路径更名为 **`iris-ocular`**，PRD 头部「对应项目：Iris · eyes-diagnosis」是过期元数据。
- 旧文档引用的 `ui` / `feat/system-config-center` / `6ba65478` 分支**不再是有效前提**：
  `feat/system-config-center` 不在本地与远端分支列表中；`ui` 已不存在；`6ba65478` 的内容
  已通过合并进入 `master`（见 §1.2）。
- WP-01 开工前应先把本地 `master` 与 `origin/master` 对齐，禁止以 `c3808e4c` 为基线分支。

### 1.2 实质变更（按域）

| 域 | 变更 | 证据 |
|---|---|---|
| 仓库 | 更名 `iris-ocular`；public | `git remote -v`；`gh repo view` |
| 迁移 | 新增 V18–V31（14 个文件）；V10–V14 仍是历史空洞；V15 为 legacy 基线 | `db/migration/`；`docs/flyway-migration-history-plan.md` |
| 安全 | 新增全局 `SameOriginFilter`；Session Cookie 显式 `SameSite=Lax` + `HttpOnly`；封禁账号登录与已登录会话双重拦截 | `config/SameOriginFilter.java`；`application.yml:6-11`；`UserServiceImpl.java:148,184` |
| 认证 | `UserRole` 新增 `DOCTOR`、`EVIDENCE_REVIEWER`；可指派集合仍为 `user/ban/admin`；`@AuthCheck` 实现未扩展 | `model/enums/UserRole.java`；`UserServiceImpl.java:373-413`；`aop/AuthInterceptor.java:60-63` |
| Agent | `ToolCallBudgetInterceptor` + `CompileConfig.recursionLimit` 真实接线；`CountingChatModel` 只计数；`agent.expose-reasoning` 显式开关 | `DiagnosisAgent.java:122-174,214-220`；`AgentConstants.java:11-29` |
| 视觉 | `EyeImageAnalyzerTool` 改为严格 JSON + 字段齐全 + `urgency` 枚举；失败走 `INVALID_OUTPUT`/`SYSTEM_ERROR` | `EyeImageAnalyzerTool.java:137-262` |
| 多 Agent | `collaboration/`（事件/游标/流/运行）与 `patientdraft/`（四 Agent 协作）进入主干；前后端开关默认关闭 | `collaboration/*.java`；`patientdraft/DraftAgents.java:47-88`；`application.yml` `collaboration.*` |
| 数据 | V28 工具审计表、V29 知识索引版本、V30 消息多版本、V31 判读取消运行表 | 各迁移文件头注释 |
| 管理端 | 新增数据分析页与第 6 个导航项；AdminNav 现为 6 项 | `web/src/components/admin/AdminNav.tsx:14-20`；`web/src/pages/admin/AdminDataAnalysisPage.tsx` |
| 设计 | Iris 2.1 冻结结束 → Iris 2.2；视觉规则以 `DESIGN.md` + `tokens.css` 为准 | `DESIGN.md:227`；`CLAUDE.md` |
| 配置 | 新增 `agent.tool-audit.*`、`eye-diagnosis.collaboration.*`、`eye-diagnosis.database.require-mysql8`、`rag.source-type.enabled`、`security.same-origin.additional-hosts`；删除 5 个死键 | `application.yml`；§4 |
| RAG | `chunk-size` 512→**800**，`chunk-overlap` →100；混合检索仍是「yml true / 代码默认 false」 | `application.yml:132-133`；`DocumentSearchTool.java:46` |
| 前端 | 构建期 env 从 1 个增到 4 个（`VITE_COLLABORATION_ENABLED`、`VITE_DESIGN_PREVIEW`、`VITE_E2E_REFRESH_HOOK`） | `git grep import.meta.env` |

### 1.3 被误认为「已变化」但事实未变

| 说法 | 事实 |
|---|---|
| 「模型客户端已可动态切换」 | 仍然全部在 Bean/`@PostConstruct` 时静态绑定；无 `@RefreshScope`、无 `ModelResolver` |
| 「模型 ID 可从仓库证明」 | `application.yml` 仍无 `spring.ai.*`；真实模型只在 `application-server.example.yml`（模板），生效值由未入库 profile 决定 |
| 「视觉 api-key 已改为环境变量」 | 仍为 `your-api-key-here`（`application.yml:129`），P1-05 未关闭 |
| 「`@AuthCheck` 已支持细粒度权限」 | 仍只有「登录级 / ADMIN」两档；`@AuthCheck(mustRole="doctor")` 会**对任意已登录用户放行**（见 §9 P1-04） |

---

## 2. 证据分级口径

本文每条结论标三种状态，不得混用：

| 状态 | 含义 | 本轮的判据 |
|---|---|---|
| **已确认** | 已读源码/迁移/测试，能给出 `file:line` | 代码存在且行为可从源码直接推出 |
| **待验证** | 代码事实成立，但运行时/部署侧取值无法从仓库证明 | 需要部署环境、真实 profile 或实测命令 |
| **未覆盖** | 本轮没有看，或看不了 | 明确写出范围，不声称已审计 |

**本轮未执行任何运行时验证**：没有跑 `mvn test`、`scripts/verify.sh`、前端套件或迁移。
下文所有「已确认」均为**静态代码事实**，不等于「测试已绿」。

---

## 3. 逐项处理既定变化

### 3.1 仓库更名：`iris-ocular`

**已确认。** `origin` 指向 `yuwen773/iris-ocular`，`gh repo view` 返回 `isPrivate: false`。
影响：

- PRD 头部「对应项目」、本文与旧文档中的仓库名需以 `iris-ocular` 为准；
- 仓库是 public，**任何提交进仓库的品牌资产都等于公开资产**；这与 ADR D5「未发布资产不得公开」
  的方向相反，WP-02 起品牌资产必须走 COS + 后端代理，不能把上传物直接放进 `web/public/`。

### 3.2 V29–V31 已占用：新迁移从 V32 起（编号已改为 V35，见 §12）

**已确认。** 迁移目录头为 `V31__diagnosis_run_cancellation.sql`；`V32*` 不存在。

- WP-01 核心表迁移命名为 `V35__control_center_core.sql`（原拟 V32，见 §12）；
- 仍遵守 `docs/flyway-migration-history-plan.md`：只新增、不改已执行迁移；
- V10–V14 是历史空洞（其他线曾占用），**不要**用它们补号；
- 部署前仍要按 `docs/architecture/migration-matrix.md` 核对目标库的 history。

**原 ADR D9「从 V29 起」作废**，改为 V32（2026-11-24 进一步改为 V35，见 §12 与 ADR 增量勘误）。

### 3.3 UI 冻结结束：Iris 2.2

**已确认。** `DESIGN.md:227` 明确「Iris 2.1 UI 冻结已于 2026-11-21 结束（进入 Iris 2.2）」；
`docs/README.md` 同口径。影响：

- ADR D10 与 phase0 P1-03 的「解冻例外」不再是 WP-04 的前置；**删除该门禁**；
- 但 `AdminNav` 新增第 7 项、`index.html` title/favicon 运行时改写仍要遵守 `DESIGN.md`：
  管理端场景（管理紫 + `--signal` 当前态、8–12px 圆角）、双主题、可访问性、`lucide-react` 静态导入；
- `DESIGN.md` 自身存在**版本漂移**：frontmatter 与标题仍写 `Iris 2.0`，正文写 2.2。这是文档缺陷（P2），
  不改变「现行规则以 `DESIGN.md` + `tokens.css` 为准」的事实。

### 3.4 SameOriginFilter 已入主干：统一严格校验与 CSRF

**已确认。** `config/SameOriginFilter.java` 是一个全局 `OncePerRequestFilter`，`@Order(1)`，
覆盖所有非 `GET/HEAD/OPTIONS` 请求（不只是 `/admin/control-center/**`），有 `SameOriginFilterTest` 10 个用例。

现状行为（源码可推出）：

| # | 行为 | 证据 |
|---|---|---|
| 1 | 缺 `Origin` 且缺 `Referer` → 拒绝 | `:44-50` |
| 2 | 比较 `host + 端口`；默认端口 80/443 归一 | `:43,90-100` |
| 3 | **不比较 scheme** | `originHost()` 只取 host+port，`:65-75` |
| 4 | `Origin: null` → 先返回 null，再回退 `Referer`；若 `Referer` 合法则**放行** | `:44-45,66` |
| 5 | 白名单来自 `security.same-origin.additional-hosts`，按 `host[:port]` 精确匹配 | `:33-34,51` |
| 6 | 比较基准是 `request.getServerName()`（即 Host 头）；仓库内**未启用** `ForwardedHeaderFilter` | `:43`；全仓无 `ForwardedHeaderFilter` |
| 7 | **没有 CSRF Token** | 全仓 `csrf` 仅命中本文件注释 |
| 8 | 失败返回 **HTTP 403** + `{code:40300,msg:"CROSS_ORIGIN_BLOCKED"}` | `:59-63` |
| 9 | Cookie 已显式 `SameSite=Lax`、`HttpOnly`，**未设 `Secure`** | `application.yml:6-11` |

与 ADR D11 的差异（D11 是实现不得自行选择的规则）：

| D11 规则 | 现状 | 判定 |
|---|---|---|
| 规则 2：scheme+host+port 严格比较 | 只比 host+port | **不满足** |
| 规则 4：`Origin: null` 一律拒绝 | `null` 时可回退 Referer | **不满足** |
| 规则 5：不得仅凭 Host 推断，代理须白名单 | 默认就是凭 Host；无 `ForwardedHeaderFilter` | **部分满足**（文档要求反代保留 Host） |
| 规则 7：`X-CSRF-Token` double-submit | 无 | **未实现** |
| 规则 8：生产 `Secure` | 未配置 | **待部署落实** |
| 规则 9：统一 `40300` + `reasonCode` | 有 `40300`，无 `reasonCode`，无审计 | **部分满足** |

**结论与建议（WP-01 采用）：**

1. **不新建第二套 Origin 过滤器。** 在现有 `SameOriginFilter` 内增加「严格模式」分支，
   只对 `/admin/control-center/**` 生效，避免全站收紧影响既有上传/审核接口。
2. 严格模式补齐：scheme 比较（`request.getScheme()`；只有部署确认反代会覆写
   `X-Forwarded-Proto` 时才读该头）、`Origin: null` 直接拒绝、命中白名单
   `eye-diagnosis.control-center.allowed-origins`（独立于 `security.same-origin.additional-hosts`）。
3. **CSRF**：新增 `GET /admin/control-center/csrf` 下发与 Session 绑定的随机 token；
   写请求必须带 `X-CSRF-Token`；用常量时间比较；会话失效即失效。只作用于控制中心写端点。
4. **响应语义必须先定**（见 §6.2）：现有过滤器返回 HTTP 403，而 `web/src/lib/http.ts:95-96`
   在 `!response.ok` 时**先抛 `ApiError(403)`、不解析信封**，前端拿不到 `40300`。
   WP-01 必须在两种方案中选一个并写测试：
   - A：改 `http.ts`，非 2xx 且 `Content-Type: application/json` 时解析信封（推荐，收益全站）；
   - B：控制中心写端点统一 HTTP 200 + `code:40300`（与既有业务错误一致，但过滤器要特判）。
5. **不要改造 `@AuthCheck` 来承载配置权限**（见 §9 P1-04）。

### 3.5 Agent 模型与工具调用预算已有真实护栏

**已确认。** 旧清单「`maxIterations`/`modelCallLimit` 注入后无人读」**已过期**：

- `DiagnosisAgent.buildAgent` 每次请求新建 `ToolCallBudgetInterceptor(maxIterations)`，并挂到
  `ReactAgent` 的拦截器链（`DiagnosisAgent.java:122-145`）；
- `guardedCompileConfig(modelCallLimit)` 把递归步数上限设为 `modelCallLimit * 2 + 2`
  （`:167-174`），注释说明框架默认是 `Integer.MAX_VALUE`；
- `CountingChatModel` 只计数不改行为（`:217`），用于终止摘要；
- 工具失败边界与预算拒绝都有终态（`ToolFailureBoundaryInterceptor` + `ToolCallBudgetInterceptor`）；
- 单测：`AgentGuardrailWiringTest` 断言 `15*2+2=32`、`modelCallLimit=6` 时不超预算；
  `AgentBudgetExhaustionTest`、`ToolCallBudgetInterceptorTest` 存在。

**对配置中心的意义：** `agent.max-iterations` / `agent.model-call-limit` 现在是**真实运行时策略**，
不是死配置；它们仍应登记为 `STATIC_POLICY`（改了会改变单轮成本与医疗对话的终止行为），**不进 MVP 动态集**。

### 3.6 视觉模型异常输出的旧安全问题：已修复，需保留回归

**已确认修复。** 旧实现两个假成功入口（`{}` → 全空 DTO；解析异常 → `urgency=attention`）已删除：

- 只有「整段响应是一个 JSON 对象 + 七个字段齐全 + `urgency ∈ {normal,attention,urgent}`」才算成功
  （`EyeImageAnalyzerTool.java:237-262`）；
- 非法输出抛 `InvalidToolOutputException`，不落 `succeeded`；基础设施异常抛 `SYSTEM_ERROR`，
  注释明确「不返回看起来成功的兜底结果」（`:138-150`）；
- 回归：`EyeImageAnalyzerToolOutputValidationTest`、`AgentEyeImageInvalidOutputEndToEndTest`
  断言 SSE 终态 `failed`、`errorCategory=INVALID_OUTPUT`、历史终态 `failed`。

**处置：** `SEC-RUNTIME-01` 从「独立运行时风险」降为**已关闭（代码层）**，
但必须在 WP-05 回归里保留这两组测试；若后续改动视觉提示词或解析器，必须重跑。

### 3.7 多 Agent 已进入主干：补齐模型与配置调用点

**已确认。** `collaboration/` 5 个类（游标、事件、图片、运行、流）与 `patientdraft/` 已合入；
`collaboration.*` 全部默认 `false`，且协作 Controller/Bean 用 `@ConditionalOnProperty` 关闭。

模型调用增量（旧清单 §4.2 的「分支定点扫描」现已成为主干事实）：

| callSite | taskType | 说明 |
|---|---|---|
| `patientdraft/DraftAgents.java:47-88` | Chat（结构化输出） | `Transport` 包装注入的 `ChatModel`；`OpenAiChatModel`/`DashScopeChatModel` 走 `mutate().retryTemplate(maxAttempts=1)`；`maxTokens(4000)` 硬编码；严格 JSON（重复键/尾随内容/缺失字段全部拒绝） |
| `patientdraft/PatientDraftCoordinator.java:68-73` | Agent 编排 | 构造器注入同一个 `ChatModel`；`Budget(8, 90_000ms, 20_000ms)` 硬编码 |
| `collaboration/*` | 无直接模型调用 | 只写事件、游标、流；模型调用都在 `patientdraft` |
| `agent/DiagnosisAgent.java` | Chat/Agent | 仍注入单一 `ChatModel`，外加预算拦截器 |

配置增量：`eye-diagnosis.collaboration.enabled/admission-enabled/graph-enabled/patient-progress-enabled/clinician-detail-enabled`、
`eye-diagnosis.collaboration.cursor-key`（无默认值，仅在 `enabled=true` 时要求）、
`eye-diagnosis.draft.enabled`、`eye-diagnosis.draft.test-entry.enabled`。

**风险：** 协作 Controller 的 `identity()` 直接读 Session 里的 `User` 对象
（`CollaborationRunController.java:69-80`），**不重新查库**，因此不会命中封禁拦截
（`UserServiceImpl.getLoginUser` 的 ban 检查不会执行）。默认关闭，故列为 P2；启用前必须补。

### 3.8 Provider Revision 与发布版本的不可变关系

**已确认：代码尚不存在，契约必须原样保留。**

- 全仓无 `t_provider_connection*`、无 Provider Controller/Service；`git grep` 零命中。
- ADR D2/D7 的语义仍是要实现的**目标契约**，不是现状：
  连接元数据只能经草稿生成不可变修订行；发布快照保存 `connectionId → revisionId`；
  回滚复制引用；测试绑定 `revisionId`；**任何情况下不改模型客户端构造**。
- WP-01 **不要**提前建 Provider 表或写 Provider API；留给 WP-03，避免半成品表结构锁死契约。

### 3.9 COS 品牌资产：现状与可验证边界

**已确认：当前没有 COS 品牌资产。**

- 品牌资源是前端静态文件：`web/public/brand/`（`iris-favicon.ico`、`iris-app-tile-180.png`、
  `iris-mono-*`、`iris-optical-*`），由 `Wordmark.tsx` / `AdminNav.tsx` / `index.html` 直接引用。
- ADR D5 的「品牌资产存 COS + 后端代理」是**未来实现**，不是现状。
- COS 客户端没有设置对象 ACL：`CosClientConfig.cosClient()` 只配 region 与凭据
  （`config/CosClientConfig.java:51-58`）；对象权限继承 bucket ACL，**仓库无法证明 bucket 是私有还是公有**（待验证）。
- 现有后台对账只扫 `agent-chat/` 前缀（`UploadCompensationService.java:37,70`），
  `CosManager.listObjects(prefix)` 也按显式前缀列举（`CosManager.java:146-160`）。
  因此未来品牌对象只要**不放在 `agent-chat/` 下**，就不会被补偿任务误删；这一点必须在 WP-02 写成常量与测试。
- 仓库是 public 这一事实放大风险：品牌资产若继续放 `web/public/`，上传即可被匿名读取；
  WP-02 必须实现「只被草稿引用的资产对外 404」。

**WP-02 开工前必须拿到（待验证）：** bucket 实际 ACL、生命周期规则、CDN/反代缓存策略、
是否存在公开读域名。仓库内没有这些证据。

---

## 4. 配置增量审计（CFG-AUDIT-01 delta）

### 4.1 新增/变更的配置键（相对 WP-00 清单）

| configKey | 来源 | 消费者 | 分类 | 说明 |
|---|---|---|---|---|
| `agent.expose-reasoning` | `application.yml` | `AgentConstants.java:29` | STATIC_POLICY | reasoning 是否展示/落库，默认 false |
| `agent.tool-audit.enabled` | `application.yml` | `ToolAuditService.java:36` | STATIC_POLICY | 默认 false；开启需 V28 |
| `agent.tool-audit.digest-key` | `application.yml` | `ToolAuditService.java:40,52-54` | SECRET_REF | **默认值与 fallback 都是已知 dev 字面量**（§9 P1-01） |
| `eye-diagnosis.collaboration.*`（5 个开关） | `application.yml` | `@ConditionalOnProperty` / `CollaborationRunService.java:34` | STATIC_POLICY | 全部 false |
| `eye-diagnosis.collaboration.cursor-key` | 代码 | `CollaborationCursor.java:28` | SECRET_REF | 无默认；`enabled=true` 时必填 |
| `eye-diagnosis.draft.enabled` / `draft.test-entry.enabled` | 代码 | `PatientDraftRunner` / `PatientDraftTestService` | STATIC_POLICY | 未在 yml 出现，默认 false |
| `eye-diagnosis.database.require-mysql8` | `application.yml` | `MySql8Requirement.java` | STATIC_POLICY | 启动期 MySQL 8 校验 |
| `eye-diagnosis.rag.source-type.enabled` | `application.yml` | `SourceTypePolicy` | STATIC_POLICY | 默认 false；开启只收窄 |
| `security.same-origin.additional-hosts` | 代码 / `@Value` | `SameOriginFilter.java:33` | STATIC_POLICY | 同源白名单（`host[:port]`） |
| `agent.queue.await-timeout-ms` | 代码 | Agent 队列 | DEPLOY_ONLY | 无 yml |
| `eye-diagnosis.rag.chunk-size` / `chunk-overlap` | `application.yml` | `TextChunkerService.java:16,19` | DYNAMIC（非 MVP） | 512→**800** / 100 |

### 4.2 已删除的死键（WP-00 M2/M3/M4/M5 部分关闭）

**已确认**：`application.yml` 中不再出现
`eye-diagnosis.inference.hash-algorithm`、`eye-diagnosis.rag.evaluation.enabled`、
`agent.max-iterations`、`agent.model-call-limit`、Chroma `tenant-name`/`database-name`。
原清单中「死 yml」条目应标记为**已移除**（不是「已接通」）。

仍存在的缺口：

| # | 缺口 | 现状 |
|---|---|---|
| M1 | Chroma collection 三处不一致 | 代码默认 `eye-diagnosis-knowledge`（`VectorStoreConfig.java:23`），server 示例 `diagnosis-knowledge`（`application-server.example.yml:35`） |
| M6 | `agent.queue.*` / `document.executor.*` 无 yml | 仍只有代码默认值 |
| M10 | 混合检索默认值相反 | yml `true`（`application.yml:147`），代码默认 `false`（`DocumentSearchTool.java:46`） |
| M12 | tracked yml 含凭据占位字面量 | `application.yml:129` 仍是 `your-api-key-here` |
| M13 | MCP 超时硬编码旁路 | 未复核，沿用旧结论 |
| — | `eye-diagnosis.rag.rerank.model` | 仍在 yml（`:144`），仍无 Java 消费者（死键） |
| — | `spring.ai.*` | 仍只在 server 示例；`application.yml` 无 |

### 4.3 前端构建期变量

**已确认**：从 1 个增到 4 个。

| 变量 | 位置 | 用途 |
|---|---|---|
| `VITE_API_BASE_URL` | `web/src/lib/http.ts:16` | API 基址，默认 `/api` |
| `VITE_COLLABORATION_ENABLED` | `navLinks.ts:16-17`、`CollaborationPage.tsx:21` | 协作入口 |
| `VITE_DESIGN_PREVIEW` | `App.tsx:83` | 设计预览路由 |
| `VITE_E2E_REFRESH_HOOK` | `main.tsx:21` | E2E 钩子 |

**不属于 MVP 动态配置**：它们都是构建期常量，配置中心不应承诺运行时改。

### 4.4 Cookie / Session

| 项 | 现状 |
|---|---|
| `SameSite` | `lax`（已显式） |
| `HttpOnly` | `true` |
| `Secure` | **未配置**（待部署；生产 HTTPS 时必须设置） |
| `max-age` | 30 天 |
| 会话存储 | Spring Session + Redis（server 示例） |

---

## 5. 模型调用增量审计（CFG-AUDIT-02 delta）

### 5.1 主干调用点（相对 WP-00 §1 的新增）

| callSite | taskType | 说明 |
|---|---|---|
| `agent/DiagnosisAgent.java:122-150` | Chat/Agent | 单一 `ChatModel` + 预算拦截器 + 递归上限；`CountingChatModel` 包装 |
| `patientdraft/DraftAgents.java:47-88` | Chat（结构化） | 注入 `ChatModel`；`maxTokens=4000`；严格 JSON；no-retry |
| `patientdraft/PatientDraftCoordinator.java:68-73` | Agent 编排 | 复用同一 `ChatModel`；调用预算 `8/90s/20s` 硬编码 |
| `agent/tools/EyeImageAnalyzerTool.java:90-104` | Vision | 仍是 `@PostConstruct` 手工构造 `OpenAiApi`+`OpenAiChatModel`+`ChatClient`，无替换缝隙 |
| `agent/config/VectorStoreConfig.java:35,40` | Embedding | 仍是单例 Bean 构造时捕获 `EmbeddingModel` |
| `agent/tools/DocumentSearchTool.java` | Embedding/Rerank | 仍 `required=false` 注入 |

### 5.2 结论不变的部分

- **无 Model Router、无动态模型解析**；`grep` 无 `@RefreshScope`、无 `ModelResolver`。
- 视觉与 gRPC 客户端仍是**构造后不可替换**。
- 同一 DashScope 账号仍有三条凭据来源（`spring.ai.openai.api-key`、`spring.ai.dashscope.api-key`、
  `eye-diagnosis.vision-recognition.api-key`），可静默分叉。
- `TitleGenerationService` 仍是截断占位，不是模型调用。

### 5.3 新的运行时护栏

| 护栏 | 值/位置 | 影响 |
|---|---|---|
| 工具调用预算 | `agent.max-iterations`（默认 10）→ `ToolCallBudgetInterceptor` | 超预算后工具不再执行，交还模型收尾 |
| 模型调用预算 | `agent.model-call-limit`（默认 15）→ `recursionLimit=2n+2` | 框架级硬上限 |
| 视觉输出校验 | `EyeImageAnalyzerTool.parseAnalysisResult` | 非法输出 → `INVALID_OUTPUT`，不假成功 |
| 工具失败边界 | `ToolFailureBoundaryInterceptor` + `DefaultToolExecutionExceptionProcessor(true)` | 异常先逃出再由边界转错误，观察者不见假 success |
| 工具审计持久化 | `agent.tool-audit.enabled`（默认 false） | 开启前必须换掉 dev 摘要密钥 |

**对配置中心的约束不变：** Provider 连接只存元数据；连接测试不得复用生产模型客户端；
第一期不改任何模型构造代码。

---

## 6. 过期契约修订报告

已直接落在各文档末尾的「2026-11-23 增量勘误」；本节列出改动点与理由。

### 6.1 `config-center-adr.md`

| 决策 | 过期内容 | 修订 |
|---|---|---|
| 头部 | 基线 `f42065e4`、`feat/system-config-center` | 标注历史基线；现行基线 `5dc2eac5` |
| D6 | 「`UserRole` 只有 user/admin」 | 现有 4 个枚举值，但可指派仍只有 `user/ban/admin`；发布者 = **admin 且 userId 在白名单** |
| D9 | 「本分支止于 V17，从 V29 起」 | **V29–V34 已占用 → V35** |
| D10 | 「UI 冻结，需解冻例外」 | 冻结已于 2026-11-21 结束；不需要例外，按 Iris 2.2 执行 |
| D11 | 「本分支没有任何 CSRF/Same-Origin 过滤器；配置中心自带」 | 主干已有 `SameOriginFilter`；改为**在现有过滤器上加严格模式 + CSRF**，不建第二套 |
| D5 | 「品牌资产存 COS」 | 仍是目标；现状品牌资产在 `web/public/brand`，COS 里没有品牌对象；WP-02 前验证 bucket ACL/生命周期 |
| D2/D7 | Provider 修订不可变 | **不变**，仍是权威契约 |

### 6.2 `mvp-api-contract.md`

| 位置 | 过期/缺口 | 修订 |
|---|---|---|
| §1 写操作防护 | 「本分支目前没有 CSRF/Origin 过滤器」 | 已有过滤器；缺 CSRF Token；补严格模式说明 |
| §1 错误语义 | 「业务错误一律 HTTP 200 + 非 0 code」 | `SameOriginFilter` 返回 **HTTP 403**；前端 `http.ts:95-96` 不解析非 2xx 信封 → `40300` 丢失。必须先定 A/B 方案 |
| §1 授权 | `@AuthCheck(mustRole="admin")` + `@ConfigPermission` | `@AuthCheck` 不能扩成细粒度（P1-04）；`@ConfigPermission` 独立 AOP，内部用 `getLoginUser` |
| §5 错误码 | 409xx / 40301 / 42900 | `ErrorCode` 目前**没有** 409 段；WP-01 需新增枚举常量 |
| §6 安全清单 | 依赖 D11 九条 | 按 §3.4 的现状重写：scheme、`Origin: null`、CSRF、reasonCode |
| §8 差距表 | 「无任何过滤器」 | 改为「有全局过滤器，缺 CSRF 与控制中心严格模式」 |

### 6.3 `phase0-review.md`

| 条目 | 过期内容 | 修订 |
|---|---|---|
| §1/§8 范围 | 只覆盖 `f42065e4`；多 Agent 未合并 | 现覆盖 `5dc2eac5`；多 Agent 已入主干，本文件为其补审 |
| 准入 #6 | 迁移 V29、认证只有 user/admin、冻结 tag | V35；角色 4 个但可指派 3 个；冻结已结束 |
| P0-02 | 「仓库内没有任何 CSRF/Same-Origin 过滤器」 | 前提已失效：过滤器存在；遗留问题改为「缺 CSRF + 严格模式」 |
| P1-03 | 解冻例外 | **关闭**（冻结结束） |
| P1-04 | 迁移版本 | **关闭**：V35 |
| P1-05 | tracked yml 占位 api-key | **仍开放**（`application.yml:129`） |
| P1-06 | 本地 profile 真实凭据 | **仍开放**（本工作树无该文件；主检出存在） |
| P1-12 | 多 Agent 合并前补审 | **已执行**（本文 §3.7/§5） |
| SEC-RUNTIME-01 | 视觉合成 attention | **已关闭（代码层）**，保留回归测试 |
| SEC-RUNTIME-02 | gRPC 明文/无鉴权 | **仍开放**（`DiagnosisGrpcClient.java:30`；`grpc_server.py:176`） |
| P2-07 | 工具审计 dev 密钥只在分支 | 已在主干，且 `ToolAuditService` 还有 **fallback 常量**（§9 P1-01） |

### 6.4 `configuration-inventory.md` / `model-callsite-inventory.md`

- 原文保留；追加「2026-11-23 增量：新基线 `5dc2eac5`」段落，列 §4/§5 的 delta；
- 原文的基线/行号可按历史读取，不要求逐行回填。

### 6.5 PRD

- 头部「对应项目：Iris · eyes-diagnosis」→ `iris-ocular`；
- 「Iris 2.1 UI 冻结继续有效」→ 冻结已结束，Iris 2.2；
- 「基线 `f42065e4`」→ 历史基线；WP-00.5 基线 `5dc2eac5`。

---

## 7. WP-01 详细开发计划

### 7.1 范围

**做：** 强类型 Config Registry（仅 6 个 `brand.*` 键）、单例草稿 + revision 乐观锁、
不可变发布快照 + 单活跃指针、回滚生成新版本、同事务审计、`requestId` 幂等、
服务端动作权限、控制中心写端点安全（Origin 严格模式 + CSRF）、错误码。

**不做：** 品牌资产上传/公开资源端点（WP-02）、Provider 表与连接测试（WP-03）、
前端页面（WP-04）、任何模型客户端改动、任何 `V1–V31` 修改。

### 7.2 数据表（V35）

| 表 | 关键列 | 约束 |
|---|---|---|
| `t_config_draft` | `id`, `revision`, `status`, `payload` JSON, `updatedBy`, `updateTime`, `validationRevision`, `validationResult` JSON | 单行（`id=1`）；`revision` 单调递增 |
| `t_config_release` | `id`, `releaseVersion`, `schemaVersion`, `payload` JSON, `digest`, `rollbackFromReleaseId`, `createdBy`, `createdTime` | `UNIQUE(releaseVersion)`；只插入 |
| `t_config_active_pointer` | `id`（恒 1）, `releaseId`, `updatedTime` | 单行；发布时 `SELECT ... FOR UPDATE` |
| `t_config_request` | `id`, `actorId`, `requestId`, `requestDigest`, `action`, `resultCode`, `resultPayload` JSON, `createdTime` | `UNIQUE(actorId, requestId)`；`requestId` 用 `utf8mb4_bin` |
| `t_config_audit_event` | `id`, `eventId`, `actorId`, `action`, `targetId`, `requestId`, `beforeVersion`, `afterVersion`, `result`, `reasonCode`, `payload` JSON, `occurredAt` | `UNIQUE(eventId)`；只追加 |

命名与既有库一致：列名小驼峰、`utf8mb4`、`ENGINE=InnoDB`；不建外键（与 V30 同口径）。

### 7.3 Java 模块（新包 `com.csust.eyediagnosis.controlcenter`）

> 不要放进 `config/`：该包全是 Spring `@Configuration`/`@ConfigurationProperties`，
> 混入业务会破坏现有目录语义。

| 层 | 类 |
|---|---|
| `registry/` | `ConfigKey`、`ConfigType`、`ConfigDefinition`、`ConfigRegistry`、`ConfigValueValidator` |
| `model/entity/` | `ConfigDraft`、`ConfigRelease`、`ConfigActivePointer`、`ConfigRequest`、`ConfigAuditEvent` |
| `model/enums/` | `DraftStatus`、`ConfigAction`、`ConfigErrorReason` |
| `model/dto/` | `DraftSaveRequest`、`DraftValidateRequest`、`PublishRequest`、`RollbackRequest`、`AuditQuery` |
| `model/vo/` | `RegistryVO`、`ActiveVO`、`ReleaseVO`、`DiffVO`、`PublishResultVO` |
| `mapper/` | 5 个 MyBatis Plus Mapper |
| `service/` | `ControlCenterDraftService`、`ControlCenterReleaseService`、`ControlCenterIdempotencyService`、`ControlCenterAuditService`、`ConfigPayloadCodec`（规范化 JSON + digest） |
| `security/` | `ConfigPermission`（注解）、`ConfigPermissionAspect`、`CsrfTokenService`、`ControlCenterOriginGuard`（供过滤器调用的严格判定） |
| `controller/` | `ControlCenterController`（`/admin/control-center/**`）、`ControlCenterCsrfController`（`/admin/control-center/csrf`） |

### 7.4 API（WP-01 子集）

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/admin/control-center/registry` | CONFIG_READ |
| GET | `/admin/control-center/active` | CONFIG_READ |
| GET | `/admin/control-center/draft` | CONFIG_READ |
| POST | `/admin/control-center/drafts` | CONFIG_EDIT |
| PUT | `/admin/control-center/drafts/{id}` | CONFIG_EDIT |
| POST | `/admin/control-center/drafts/{id}/validate` | CONFIG_VALIDATE |
| POST | `/admin/control-center/drafts/{id}/publish` | CONFIG_PUBLISH |
| GET | `/admin/control-center/releases` | CONFIG_READ |
| GET | `/admin/control-center/releases/{id}` | CONFIG_READ |
| GET | `/admin/control-center/releases/{id}/diff` | CONFIG_READ |
| POST | `/admin/control-center/releases/{id}/rollback` | CONFIG_ROLLBACK |
| GET | `/admin/control-center/audits` | CONFIG_AUDIT_READ |
| GET | `/admin/control-center/csrf` | 任意已登录 admin |

### 7.5 权限

- 新增 `@ConfigPermission(ConfigAction)`，独立 AOP；**不复用** `@AuthCheck`。
- AOP 内部：`userService.getLoginUser(request)`（**封禁账号会被拒绝**）→ 角色必须 `admin`
  → `CONFIG_PUBLISH`/`CONFIG_ROLLBACK` 还要求 `userId ∈ eye-diagnosis.control-center.publisher-user-ids`。
- 部署配置（新增）：
  - `eye-diagnosis.control-center.enabled`（默认 false）
  - `eye-diagnosis.control-center.publisher-user-ids`（默认空）
  - `eye-diagnosis.control-center.allowed-origins`（默认空，严格模式白名单）

### 7.6 事务与并发

**发布/回滚（一个事务）：**

1. `SELECT * FROM t_config_active_pointer WHERE id=1 FOR UPDATE`（串行化发布者）；
2. 校验 `expectedActiveReleaseId == 当前 releaseId`；
3. 校验草稿 `revision == 请求 revision` 且 `validationRevision == revision` 且状态 `VALIDATED`；
4. 插入 `t_config_release`（`releaseVersion = max+1`）；
5. 条件更新指针；
6. 插入 `t_config_audit_event`；
7. 插入 `t_config_request`（唯一键冲突 → 走幂等重放，见下）；
8. 提交。

**草稿变更：** `UPDATE t_config_draft SET revision=revision+1, payload=?, status='DRAFT', validationRevision=NULL WHERE id=1 AND revision=?`；
受影响行数 0 → `40900`。审计同事务。

**校验：** 计算字段级结果，写 `validationRevision`/`validationResult` + 审计，同事务。

### 7.7 幂等

- `requestDigest = SHA-256(method + path + 规范化 JSON body)`；`ConfigPayloadCodec` 负责稳定键序与数字规范。
- 先尝试插入 `t_config_request`；`DuplicateKeyException` 时按 `(actorId, requestId)` 读回：
  - digest 相同 → 返回 `resultPayload`，`replayed: true`；
  - digest 不同 → `40908`。
- 发布/回滚缺 `requestId` → `40000`。
- 注意：唯一键冲突在 MySQL 会**回滚当前语句**，实现必须先做「读-尝试-读」，或把幂等插入放在业务写之前并捕获异常；两种路径都要有集成测试。

### 7.8 错误码（`ErrorCode` 新增）

`40301 CONFIG_PERMISSION_DENIED`、`40900 CONFIG_VERSION_CONFLICT`、`40901 CONFIG_NOT_VALIDATED`、
`40902 CONFIG_STALE_VALIDATION`、`40903 CONFIG_SCHEMA_UNSUPPORTED`、`40904 CONFIG_UNKNOWN_KEY`、
`40905 CONFIG_DEPLOY_PROTECTED`、`40906 CONFIG_SECRET_REF_INVALID`、`40907 CONFIG_ASSET_INVALID`、
`40908 CONFIG_IDEMPOTENCY_MISMATCH`、`42900 CONFIG_TEST_RATE_LIMITED`。
（`40000/40100/40101/40300/40400/50000` 已存在。）

### 7.9 文件变更范围

| 类型 | 路径 |
|---|---|
| 新增 | `src/main/resources/db/migration/V35__control_center_core.sql` |
| 新增 | `src/main/java/com/csust/eyediagnosis/controlcenter/**`（上表） |
| 修改 | `src/main/java/com/csust/eyediagnosis/exception/ErrorCode.java`（只加枚举） |
| 修改 | `src/main/java/com/csust/eyediagnosis/config/SameOriginFilter.java`（严格模式分支） |
| 修改 | `src/main/resources/application.yml`（3 个部署键，默认关闭/空） |
| 修改（可选，方案 A） | `web/src/lib/http.ts`（非 2xx JSON 信封解析） |
| 新增测试 | `src/test/java/com/csust/eyediagnosis/controlcenter/**`、`config/ControlCenterOriginGuardTest`、`config/CsrfTokenServiceTest` |
| 不改 | 任何模型客户端、Provider/资产代码、`V1–V31`、`web/src/pages/admin/**` |

---

## 8. WP-01 验收矩阵

| AC | 场景 | 测试（建议类名） | 证据级别 |
|---|---|---|---|
| AC-01-01 | 空库启动返回完整默认品牌 | `ConfigRegistryDefaultTest`、`ControlCenterActiveEmptyTest` | 单测 + 集成 |
| AC-01-02 | 未知 key 拒绝 | `ConfigValueValidatorTest#unknownKey` | 单测 |
| AC-01-03 | 类型/枚举/超长字段错误 | `ConfigValueValidatorTest` | 单测 |
| AC-01-04 | 部署保护键不可写 | `ConfigValueValidatorTest#deployProtected` | 单测 |
| AC-01-05 | 不兼容 schema 拒绝 | `ConfigPayloadCodecTest#schemaVersion` | 单测 |
| AC-02-01 | 草稿不改变公开读取 | `PublishFlowIntegrationTest#draftInvisible` | MySQL 集成 |
| AC-02-02 | 编辑后校验失效 | `DraftStateMachineTest` | 单测 |
| AC-02-03 | 发布后读新版本 | `PublishFlowIntegrationTest#publishVisible` | MySQL 集成 |
| AC-02-04 | 并发过期 revision | `PublishConcurrencyIntegrationTest` | MySQL 集成（两连接/FOR UPDATE） |
| AC-02-05 | 重复发布不产生重复版本 | `IdempotencyIntegrationTest#replay` | MySQL 集成 |
| AC-02-06 | 回滚生成新版本、历史不变 | `RollbackIntegrationTest` | MySQL 集成 |
| AC-02-07 | 事务失败指针不变 | `PublishFlowIntegrationTest#rollbackOnFailure` | 集成 + 故障注入 |
| AC-02-08 | 发布/回滚有审计 | `AuditAtomicityIntegrationTest` | MySQL 集成 |
| AC-03-01 | 匿名/普通用户读管理配置被拒 | `ConfigPermissionAspectTest` | MockMvc |
| AC-03-02 | 非白名单 admin 不能发布/回滚 | `ConfigPermissionAspectTest` | MockMvc |
| AC-03-03 | 直连 API 不能绕过前端 | 同上 | MockMvc |
| AC-03-04 | 审计字段完整 | `AuditAtomicityIntegrationTest` | 集成 |
| AC-03-05 | 审计/异常不含明文密钥 | `ConfigAuditRedactionTest` | 单测 |
| AC-03-06 | Origin/CSRF 失败不产生写入 | `ControlCenterOriginGuardTest`、`CsrfTokenServiceTest`、`ControlCenterWriteBlockedIntegrationTest` | 单测 + MockMvc |
| AC-07-02/05 | 状态与差异（后端字段） | `ReleaseDiffTest` | 单测 |
| — | 封禁账号不能操作配置 | `ConfigPermissionAspectTest#bannedUser` | MockMvc |

**测试基线：** 后端起 `spring.profiles.active=ci` + MySQL 8（`scripts/verify-fast.sh --db` 或 `scripts/verify.sh`）；
集成测试必须建独立 schema（现有门禁用 `FLYWAY_TEST_*`），不得复用 `diagnosis`。

---

## 9. 风险分级

### P0（阻断 WP-01 契约定稿，必须先裁决）

| # | 风险 | 证据 | 处理 |
|---|---|---|---|
| P0-01 | Same-Origin 与 ADR D11 语义不一致：不比较 scheme、`Origin: null` 可回退 Referer、无 CSRF | `SameOriginFilter.java:43-75` | 采用 §3.4 方案：过滤器加严格模式 + CSRF；更新 ADR D11 |
| P0-02 | 非 2xx 响应信封丢失：过滤器返回 HTTP 403，前端 `http.ts` 不解析 body | `SameOriginFilter.java:59-63`；`web/src/lib/http.ts:95-96` | 二选一（A 改 http.ts / B 控制中心 HTTP 200），写测试 |
| P0-03 | 配置权限载体：不能靠 `@AuthCheck` 扩展 | `AuthInterceptor.java:60-63`；`UserRole.java` | 新建 `@ConfigPermission` AOP；不动 `@AuthCheck` |

### P1（WP-01 实现前或上线前必须关闭）

| # | 风险 | 证据 |
|---|---|---|
| P1-01 | `ToolAuditService` 默认摘要密钥 + fallback 都是已知 dev 字面量 | `ToolAuditService.java:40-54` |
| P1-02 | tracked yml 视觉 api-key 仍是 `your-api-key-here` | `application.yml:129` |
| P1-03 | 本地 `application-local.yml` 含真实凭据（主检出，未入库） | 旧 WP-00 P1-06，仍未关闭 |
| P1-04 | `@AuthCheck(mustRole="doctor")` 对任意已登录用户放行 | `AuthInterceptor.java:60-63`（只有 ADMIN 分支） |
| P1-05 | Cookie 未设 `Secure` | `application.yml:6-11` |
| P1-06 | 反代必须保留 Host；仓库无 `ForwardedHeaderFilter` | `SameOriginFilter.java:43`；release notes §3.5 |
| P1-07 | gRPC 明文、无鉴权、`0.0.0.0:50051` | `DiagnosisGrpcClient.java:30`；`grpc_server.py:176` |
| P1-08 | COS bucket ACL/生命周期无法从仓库证明 | §3.9（待验证） |
| P1-09 | `ErrorCode` 无 409 段，需新增 | `ErrorCode.java` |

### P2（登记，不阻断）

| # | 风险/缺口 |
|---|---|
| P2-01 | 混合检索默认值相反（yml true / code false） |
| P2-02 | Chroma collection 名不一致（M1） |
| P2-03 | `rag.rerank.model` 死键 |
| P2-04 | `agent.queue.*` / `document.executor.*` 无 yml |
| P2-05 | 协作 Controller 直接读 Session，绕过封禁拦截（默认关闭） |
| P2-06 | `GlobalExceptionHandler` 以 ERROR 记录完整异常，未来若异常消息带敏感值会入日志 |
| P2-07 | `DESIGN.md` 标题仍写 Iris 2.0，与 2.2 口径漂移 |
| P2-08 | 本地 `master` 引用落后 `origin/master` |
| P2-09 | 本轮未跑任何测试/构建，结论为静态代码事实 |

---

## 10. READY / BLOCKED 结论

### WP-01 开发：**READY_FOR_IMPLEMENTATION（有条件）**

**理由（已确认）：**

1. 迁移版本确定：V31 是头，V35 空闲（`db/migration/`）。
2. 配置中心零代码，不存在需要先拆掉的半成品（`git grep` 零命中）。
3. 数据层先例可用：`NewsLifecycleService` 的「业务 + 审计同事务」模式仍在
   （`NewsLifecycleService.java:48,100,233-243`）。
4. 鉴权底座可用：`getLoginUser` 已封禁感知；`ErrorCode` 可扩展（`UserServiceImpl.java:169-189`）。
5. Agent/视觉/多 Agent 的运行时事实已重新登记，WP-01 不需要改它们。

**放行条件（全部满足才可开工）：**

- P0-01 / P0-02 / P0-03 在 WP-01 首个 PR 前定稿并写入 ADR 与 API 契约；
- 本地 `master` 先对齐 `origin/master`（`5dc2eac5`），不要从 `c3808e4c` 分支；
- 确认 `eye-diagnosis.control-center.enabled` 默认 `false`，未启用时零新 Bean、零新路由；
- 不使用 Provider/品牌资产表；WP-01 只交付核心配置底座。

### 生产启用：**BLOCKED**

| 阻断 | 证据 |
|---|---|
| CSRF 未实现 | 无 token 端点、无 `X-CSRF-Token` 校验（§3.4） |
| 细粒度权限未实现 | 只有 `user/admin` 两档 + 未接线的角色枚举（§3.4/P0-03） |
| 审计/幂等表不存在 | 无 `t_config_*`（§7.2 待建） |
| COS 品牌资产访问控制未验证 | §3.9 |
| 视觉/推理运行时风险未全关闭 | SEC-RUNTIME-02 仍开放 |

> 「合并 ≠ 启用」：本结论不放行任何生产开关，也不授权部署。

---

## 11. 本轮未执行 / 下轮门禁

**本轮未执行：**

- 未跑 `mvn test`、`scripts/verify.sh`、`scripts/verify-fast.sh`、前端 `typecheck/test/build`；
- 未跑 Flyway 迁移到任何数据库；
- 未检查部署环境（nginx/systemd/环境变量/COS bucket ACL）；
- 未读取 gitignored 的 `application-local.yml` / `application-dev.yml` / `application-server.yml`。

**下一轮（WP-01 首个 PR）必须补：**

1. `scripts/verify-fast.sh --db` 或 `scripts/verify.sh` 作为基线绿；
2. V35 在空 MySQL 8 上从 callback 起完整 migrate；
3. 控制中心 Origin/CSRF 的负例集成测试（八条用例逐条）；
4. 发布并发/幂等/事务失败三条集成测试；
5. 若采纳方案 A，`http.ts` 的非 2xx 信封解析要有前端单测。

---

## 12. 迁移编号勘误（2026-11-24）

本文写于基线 `5dc2eac5`（迁移头 V31），当时 `V32*` 不存在，WP-01 的两条迁移原拟编号为
**V32（配置中心核心五表）/ V33（发布审批单）**。2026-11-24 合并前核对 master `c90b0caa`
发现：master 已前进到迁移头 **V34**，V32–V34 已被占用——

| 已被占用 | 文件 |
| --- | --- |
| V32 | `V32__knowledge_candidate_index_version.sql`（知识库候选索引版本） |
| V33 | `V33__diagnosis_task_restrict_reason.sql`（判读任务受限原因） |
| V34 | `V34__diagnosis_evidence_semantics.sql`（判读证据语义） |

带着重复版本号合入会让 Flyway 在启动时直接失败（同一版本两脚本），因此改号：

| 原编号 | 新编号 | 文件 |
| --- | --- | --- |
| V32 | **V35** | `V35__control_center_core.sql` |
| V33 | **V36** | `V36__control_center_publish_approval.sql` |

**迁移内容没有任何改动**，只是编号；两个文件头部都留了「重编号记录」注释以便追溯。
本文正文已随改号更新（§0 摘要、§3.2、§6.1、§6.3、§7.2、§7.9、§11）；
§3.2 里「`V32*` 不存在」是 2026-11-23 当时为真的**基线事实**，按审计记录要求不改写。

配套变更：`docs/architecture/migration-matrix.md` 的 V32/V33 行改为 V35/V36；
`FlywayMySqlIntegrationTest` 的迁移数断言 16→19、迁移头 33→36、V31 升级用例改为
「V31 → V36 执行 5 条」（V32–V34 是 master 的三条）。完整清单与门禁复跑结果见
`docs/product/wp-01/wp-01d-final-audit.md` §8.4。

## 13. 分支更名（2026-11-24）

本文 §0 与 §1 里出现的分支名 `t3/wp-00-5-baseline-audit` 是 2026-11-23 审计当时的名称，
按审计记录要求**不改写**。该工作树于 2026-11-24 为提交 WP-01 评审而更名为
**`feat/control-center-wp01`**；同一分支，提交历史连续，未做任何变基或改写。
