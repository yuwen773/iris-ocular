# Iris 全仓库代码审查报告

> 日期：2026-11-23
> 基线：`HEAD = b5a493e4`（工作区干净；本次审查未修改任何源码）
> 范围：`src/`（Java 主代码 220 文件 + 测试 135 文件）、`web/src/`（276 个 ts/tsx，约 5.3 万行）、
> `algorithm/`（139 个 py，约 2.1 万行）、`src/main/resources/db/migration/`（V1–V31）、
> `scripts/`（47 个）、`.github/workflows/verify.yml`、`pom.xml`、`docker-compose.yml`、
> `evaluation/`；`frontend/`（Vue，已弃用）只做轻量扫描。
> 方法：按领域拆 7 个只读评审子代理并行审查，主会话对全部 P1 结论逐条回到源码复核。
> 标注：`[已复核]` = 主会话亲自读过对应代码；`[子代理]` = 分区评审结论，未逐行复核；
> `[需核实]` = 依赖运行环境或跨文件推断。
> 性质：一次性证据，不是现行契约；当前真源仍是代码、迁移与 `docs/` 活文档。

---

## 1. 摘要

### 1.1 发现分布

| 严重度 | 数量 | 含义 |
|---|---|---|
| **P0** | 0（1 条条件性） | 未发现已触发路径上的 P0；`ALG-01` 在 MCP 服务部署到可达网络时升级为 P0 |
| **P1** | 13 | 明确缺陷、隐私泄漏、门禁失真、数据/审计分裂 |
| **P2** | 37 | 潜在越权、资源泄漏、契约漂移、结构复制 |
| **P3** | 23 | 风格、可维护性、局部改进 |

### 1.2 最该先处理的十件事

| # | 编号 | 问题 | 区域 |
|---|---|---|---|
| 1 | `ALG-01` | Python MCP 服务无鉴权绑 `0.0.0.0`，可被 SSRF 与任意本地文件读取 | 算法 |
| 2 | `BE-02` | 医生复核「回放原图」端点在正常患者任务上恒 403（依赖协作准入行） | 后端 |
| 3 | `WEB-02` | 医生复核页窗口失焦即清空已填写的复核意见，无提示 | 前端 |
| 4 | `BE-01` | 新闻批量删/改绕过事务，状态与审计可分裂 | 后端 |
| 5 | `AG-01` | MCP 信号量在任务提交被拒时永久泄漏，服务最终只能重启 | Agent |
| 6 | `AG-02`/`AG-03` | MCP 凭据与会话标题（患者首句）进入运行日志 | Agent |
| 7 | `DE-01` | `verify-fast.sh` L0 在无 `node_modules` 时跳过 web 类型检查却报 PASS | 工程化 |
| 8 | `WEB-01` | 会话导出文件名用模型生成的会话标题（含症状文本），隐私契约实现是死代码 | 前端 |
| 9 | `ALG-02` | 质量门「模糊度」公式算错，`enforce` 模式会放过真正模糊的影像 | 算法 |
| 10 | `PD-01` | 未预期异常被记成 `INVALID_OUTPUT`，运维按停止原因排查会走错方向 | 患者域 |

### 1.3 一句话判断

工程纪律的上半身很强：状态机、租约/栅栏、证据快照、失败分类、迁移与 Docker 资源生命周期、
前端 XSS 与「读不到 ≠ 0」的纯函数层，都是真做了而不是文档里写写。
问题集中在**三条缝**：① 文档写下的能力在主流程里接不上线（`BE-02`、`WEB-01`）；
② 隐私与凭据在日志/文件名这两个"最后一公里"漏出（`AG-02`、`AG-03`、`WEB-01`）；
③ 本地验证与 CI 口径漂移，绿灯不总等于跑过（`DE-01`、`DE-03`）。

---

## 2. 审查方法

- **Standards 轴**：以 `CODING_STANDARDS.md`、`GLOSSARY.md`、`CLAUDE.md`、根 `DESIGN.md`
  与 `web/src/styles/tokens.css`、各领域 `docs/*.md` 为明文标准；工具能查的（编译、格式、
  lint）不报。另附 Fowler《重构》第 3 章坏味道基线，仅作判断项。
- **Spec 轴**：本仓库没有单一 spec，替换为「文档契约一致性」——检查代码是否忠实实现
  GLOSSARY / CLAUDE.md / DESIGN.md / `docs/<feature>.md` 的明文要求，分三类：
  文档要求但缺失/半实现、代码越界、看着实现了但实现错。
- **分区**：后端核心（认证/用户/诊断主链路）、患者域 7 包、Agent/协作/RAG/MCP、
  数据层与工程化、`web` 基础设施与组件、`web` 页面与样式、算法/gRPC/评测。
- **复核**：主会话对 13 条 P1 全部回源码确认；P2/P3 抽查。

---

## 3. Standards 轴

### 3.1 P1

#### BE-01 新闻批量写路径绕过事务，状态更新与审计可分裂 `[已复核]`
- 位置：`src/main/java/com/csust/eyediagnosis/service/NewsLifecycleService.java:117-135`、`:144-172`
- 证据：`softDelete`/`applyEdit` 上有 `@Transactional`，但 `softDeleteBatch`/`changeStatusBatch`
  内部用 `this.softDelete(id, actor)`、`this.applyEdit(...)` 自调用；Spring 代理不拦截自调用。
  批量方法自身也没有 `@Transactional`。
- 问题：类注释承诺「状态迁移、审核落盘、审计、软删都在一个事务内」。实际批量路径里
  `updateById` 与 `newsAuditMapper.insert` 各自提交；审计插入失败时状态已改、审计丢失，
  `catch` 却把该 id 记为"失败"——调用方看到失败而库里状态已变，静默部分失败、审计链断裂。
- 修法：两个批量方法加 `@Transactional`，或把「单条迁移 + 审计」下沉到独立 bean /
  `TransactionTemplate` 逐条提交；并补批量路径测试。

#### BE-02 医生复核「回放原图」在文档声明的主流程里恒不可用 `[已复核]`
- 位置：`src/main/java/com/csust/eyediagnosis/controller/DoctorReviewController.java:59-70`；
  `src/main/java/com/csust/eyediagnosis/collaboration/CollaborationImages.java:26-31`
- 证据：`image()` → `images.readTask(review.taskId(), userId)` →
  `SELECT runId FROM t_agent_collab_run WHERE taskId=?`，`rows.size()!=1` 即 `Fault(403)`。
  全仓库 `INSERT INTO t_agent_collab_run` 只有 `CollaborationRunService.java:99`（准入接口，
  受 `eye-diagnosis.collaboration.enabled` 控制，默认 false）。
- 问题：`/api/diagnosis-tasks` 产生的普通患者任务从不写 `t_agent_collab_run`，所以医生复核
  任何普通任务时读原图必 403；即便有行，还需 `clinicianDetailEnabled`（默认 false）。
  而 `docs/patient-authorization-clinical-semantics.md:96` 把该端点当作「医生可回放原图」的
  依据，`docs/doctor-review.md:23-31` 的接口表里又没有它。`DoctorReviewApiTest` 对此零用例。
- 修法：`DoctorReviewController.image` 直接按 `t_diagnosis_task.objectKey/objectVersion` 读
  （医生授权已在上游校验），不要绕协作准入；并把端点补进 `doctor-review.md`。

#### BE-03 知识库上传泄漏临时文件，且用不可信原始文件名作临时文件后缀 `[已复核]`
- 位置：`src/main/java/com/csust/eyediagnosis/service/impl/KnowledgeBaseServiceImpl.java:98-137`、`:293-316`
- 证据：`CompletableFuture` 内 `tempFile` 无 `finally` 删除；命中重复（`:112-114` 直接 return）
  时 `processDocument` 从不被调，临时文件永不删除；`File.createTempFile("upload_", originalFilename)`
  把未清洗的文件名当后缀，含路径分隔符时抛 `IllegalArgumentException` 并被外层
  `catch(Exception)` 吞掉。
- 问题：每次重复上传/失败都在 `java.io.tmpdir` 留孤儿文件（仓库有过"临时文件把盘写满"的历史）；
  上传也没有大小上限，大文件整读进内存。
- 修法：`try/finally` 兜底删除，异步接管前明确所有权；后缀改用扩展名白名单值；加分片大小上限。

#### AG-01 MCP 信号量在任务提交被拒时永久泄漏 `[已复核]`
- 位置：`src/main/java/com/csust/eyediagnosis/agent/manager/MCPManager.java:146-166`
- 证据：`if (!gate.tryAcquire()) ...; Future<...> future = mcpExecutor.submit(...); try { ... } finally { gate.release(); }`
- 问题：线程池 `AbortPolicy`（队列 64）饱和时 `submit` 在 `try` 之外同步抛
  `RejectedExecutionException`，`finally` 不执行，permit 不归还。累计 `per-server-max`（默认 8）
  次拒绝后该 MCP 服务永久 `McpOverloadException`，只能重启。
- 修法：把 `submit` 纳入 `try`，或 `tryAcquire` 后立即进入 `try/finally`。

#### AG-02 MCP 凭据写入运行日志 `[已复核]`
- 位置：`MCPManager.java:238`、`:243`
- 证据：`log.info("成功解析 SSE URL: {}", config.getUrl())`；
  `log.warn("解析 deployConfig 失败: {}", configJson, e)`。
- 问题：`deployConfig` 可含 `authorization`/token，URL query 也可能带 token；类注释自己写明
  "query 里可能带 token"，解析失败时却把整份 JSON 打进日志，成功时打印完整 URL。
- 修法：只记 `hostOf(url)` 与异常类型；失败不打印原文。

#### AG-03 患者原文进入运行日志 `[已复核]`
- 位置：`src/main/java/com/csust/eyediagnosis/agent/service/TitleGenerationService.java:38`
- 证据：`log.info("会话标题已更新, sessionId={}, title={}", sessionId, title)`，`title` 由首条消息前 20 字拼成。
- 问题：与 `AgentController` 明文的「症状、病史等原文不得进入运行日志」直接冲突；任何首条消息
  都会把患者主诉片段写进日志。
- 修法：只记 `sessionId` 与长度，不记 title。

#### AG-04 STREAMABLE_HTTP 客户端被 initialize 两次 `[已复核]`
- 位置：`MCPManager.java:79`、`:121`、`:174`
- 证据：`getMcpSyncClient` 对 STREAMABLE_HTTP 先 `client.initialize()` 再返回；
  `fetchTools` 与 `doCallTool` 又各调一次。SSE 路径返回未初始化客户端。
- 问题：两条传输行为不一致；重复 `initialize` 在 MCP SDK 中非幂等，可能重复握手/抛错，
  导致 streamable MCP 工具加载失败而 SSE 正常。`[需核实 SDK 具体异常]`
- 修法：只在 `getMcpSyncClient` 统一初始化一次，删掉调用方。

#### PD-01 未预期异常被记成 `INVALID_OUTPUT`，掩盖故障类别 `[已复核]`
- 位置：`src/main/java/com/csust/eyediagnosis/patientdraft/PatientDraftCoordinator.java:93`
- 证据：`catch (Exception e) { log.error(...); restrict(taskId, "INVALID_OUTPUT", claimed.lease()); }`
- 问题：`docs/patient-draft.md:40` 明令 `INVALID_OUTPUT` 只表示输出无法解析/不满足契约，
  「运维只看停止原因即可区分凭据、配额和网络问题与模型输出问题」。DB 死锁、NPE、落库阶段
  Jackson 失败都会被写成"模型输出不合格"，与同文件 `transportFailure()` 的精细分类相互抵消。
- 修法：兜底 reason 改为独立 `INTERNAL_ERROR`（或复用 `transportFailure`），只有经过
  `validate*` 的失败才用 `INVALID_OUTPUT`。

#### DE-01 `verify-fast.sh` 的 L0 对 web 类型检查是 fail-open `[已复核]`
- 位置：`scripts/verify-fast.sh:150-156`、`:161`、`:320`
- 证据：`if [[ -x web/node_modules/.bin/tsc ]] ... else echo "跳过 web 类型检查：web/node_modules 不存在"`；
  该分支不 `record_stage`，`run_static` 仍 `return $rc_any`（初值 0），主流程打印
  `RESULT: PASS` 并 `exit 0`。
- 问题：默认验证级别 L0 自称覆盖"受影响语言的编译/类型检查"，但 web-only 改动在无
  `node_modules` 时未做任何类型检查却报 PASS——这正是本地默认路径。
- 修法：web 变更且 tsc 不可执行时 `record_stage "web-typecheck" 1` 或 exit 2，
  除非显式 `--allow-missing-web-deps`。

#### WEB-01 导出文件名的隐私契约已实现却没接线 `[已复核]`
- 位置：`web/src/lib/sessionExport.ts`、`web/src/pages/AgentPage.tsx:541-551`
- 证据：`sessionExport.ts` 注释明写 "never interpolate the session title, message content,
  user name or date-derived private text into a file that leaves the browser"，
  `exportDescriptor` 只产出 `会话导出-<id>.<ext>`；但全仓仅 `sessionExport.test.ts` 引用它。
  `AgentPage` 自带 `safeFileName`/`downloadText`，用 `session.title` 拼下载名。
- 问题：会话标题由模型从患者症状生成，用户"导出对话"会把症状文本写进磁盘与分享链。
  测试覆盖的是一段生产死代码，制造"已合规"的假象。
- 修法：`AgentPage` 改调 `sessionExport`，删本地 `safeFileName`/`downloadText`。

#### WEB-02 医生复核页窗口失焦即清空冻结材料与已填意见 `[已复核]`
- 位置：`web/src/pages/DoctorReviewsPage.tsx:110-125`
- 证据：`window.addEventListener("blur", clear)`，`clear` 里 `setDetail(null); setNote(""); setList([])`。
- 问题：`blur` 在任何窗口失焦时触发（切窗口查资料、开 devtools、系统对话框）。医生正在填
  长复核意见时失焦，材料关闭、列表清空、`note` 被丢弃且无提示。`docs/doctor-review.md`
  只要求"切换身份会清理材料"，没有"失焦即清"。
- 修法：删掉 `blur` 监听，改为监听会话身份变化；确需防偷窥，用遮罩 + 点击恢复，不能销毁输入。

#### ALG-01 MCP 服务无鉴权绑定 `0.0.0.0`，开放 SSRF 与任意本地文件 `[已复核]`（部署即 P0）
- 位置：`algorithm/mcp_server/config.py:9`、`algorithm/mcp_server/server.py:48`、
  `algorithm/mcp_server/image_handler.py:52-70`
- 证据：`host: str = "0.0.0.0"`；`mcp.run(transport="streamable-http", host=config.host, ...)`；
  `_resolve_url` 对任意 `http(s)://` 直接 `client.get(url)`，`_resolve_filepath` 对任意本地路径直接返回。
- 问题：无鉴权，任何可达调用方可让服务抓内网/元数据地址（`169.254.169.254`）或把宿主机
  任意文件当输入；外网大响应先下载全量再判 50MB（判断在 `resp.content` 之后），可内存打爆。
- 修法：加 URL 主机白名单/禁私网段，路径限制到允许目录；流式下载并先限长；MCP 前加鉴权，
  默认绑定 `127.0.0.1`。

#### ALG-02 质量门模糊度指标实现错误 `[已复核]`
- 位置：`algorithm/eye_diagnosis/quality.py:32-37`
- 证据：`return float(np.var(gray * np.fft.ifft2(np.fft.fft2(gray) * np.fft.fft2(k)).real))`，
  即"灰度 × 拉普拉斯响应"的方差，而非拉普拉斯响应本身的方差。
- 问题：真实眼底图乘积方差远超阈值 12，几乎只在近常数图上触发 `blur`；配
  `QUALITY_GATE_MODE=enforce` 时会大量放过真正模糊的输入，污染 `QUALITY_FAIL` 证据。
  `fundus_coverage` 实为"总均值/中心均值"，命名也不符。
- 修法：改为 `np.var(laplacian(gray))` 并按真实数据重标阈值；coverage 另算。

### 3.2 P2

| 编号 | 位置 | 问题 | 修法 |
|---|---|---|---|
| BE-04 | `aop/AuthInterceptor.java:47-62` | `@AuthCheck` 只对 ADMIN 生效；`mustRole="doctor"`/`"evidence_reviewer"` 时任何登录用户放行（当前无端点使用，属定时炸弹） | 改为 `mustRoleEnum` 非空即精确匹配，或白名单化 mustRole |
| BE-05 | `service/impl/UserServiceImpl.java:342-350` | 复诊申请对不存在记录 `diagnosis==null` → NPE → 500 而非 404 | 先判空抛 `NOT_FOUND`，复用 `getOwnedDiagnosis` |
| BE-06 | `controller/UserController.java:180-186` | `/user/update` 用 `BeanUtils.copyProperties` 可写入任意 `userRole`，与 `/user/add`、`/update/role/batch` 的白名单口径不一致 | 复用 `ASSIGNABLE_ROLES` 校验，或从 DTO 移除 userRole |
| BE-07 | `common/PageRequest.java:23` 及各使用点 | `pageSize` 无上限，`pageSize=1000000` 可放大查询与序列化负载 | 提供 `boundedPageSize()`（≤100）或 MP 分页插件 `maxLimit` |
| BE-08 | `exception/GlobalExceptionHandler.java:28-32` | 业务异常（参数错/未登录/无权限）按 ERROR 打堆栈，稀释真实故障信号 | 按 code 分级（4xx 类 WARN/INFO） |
| BE-09 | `UserController:228`、`DiagnosisController:124`、`NewsController:96` | 缓存 (反)序列化走 hutool `JSONUtil`，与 `CODING_STANDARDS`「JSON 统一走 `JsonUtils`」漂移；`toBean(..., Page.class)` 丢泛型 | 统一 `JsonUtils`，带 `TypeReference` |
| PD-02 | `docs/patient-authorization.md:36` | 文档写"专项 35 项"，`scripts/check_surefire_skips.py:58` 声明 37，实点 37 | 文档改 37 或删硬编码数 |
| PD-03 | `patientdraft/PatientDraftTestService.java:147-153` | 播种资料写 `isTest=0,'CURATED_PUBLIC','APPROVED'`，上传者=审核者；`documentVersion` 是 source 哈希而非文件字节 SHA-256，绕开受控资料治理 | 保留 `isTest=1` 并在检索层单独放行；版本改文件字节哈希 |
| PD-04 | `patientdraft/PatientDraftCoordinator.java:125-137` | 20s 模型调用期间约 1000 次 `guarded()` 事务，对两行 `FOR UPDATE` 忙等 | 租约校验降到秒级，或轮询 200–500ms |
| AG-05 | `agent/interceptor/TextMcpModelInterceptor.java:34-50`、`agent/mcp/McpToolCallbackProvider.java:27-32` | GLOSSARY 的"会话覆盖 → 全局启用 → 禁用"中，覆盖层仍硬过滤 `status=1`，无法覆盖未启用服务；禁用只能靠软删 | 明确文档口径，或覆盖层不做 status 过滤 |
| AG-06 | `MCPManager.java:110-113`、`McpToolCallbackProvider.java:46-48` | `getTools` 捕获全部异常返回空列表，用户已启用服务但工具悄悄消失 | 至少按 serverCode + 失败类别 WARN，并把"部分未加载"回传 |
| AG-07 | `application.yml:141-142`、`agent/tools/DocumentSearchTool.java:404-425` | 混合检索默认开启；BM25 语料用 `similaritySearch("*", topK 1000)` 近似"全部文档"，超过 1000 片段静默丢失词法召回；缓存按 userId 只增不减 | 按 owner 元数据直取或独立 BM25 索引；缓存加驱逐 |
| AG-08 | `agent/mapper/AgentMessageMapper.java:41-43`、`agent/service/ChatHistoryService.java:227-248` | 历史遗留轮次 `adoptLegacyAnswers` 只写 `variantId` 不写 `selectedVariantId`；取消重生成后新旧答案同屏 | adopt 时同时写 `selectedVariantId` |
| AG-09 | `agent/service/ChatHistoryService.java:453`、`:500` | 单条坏 payload 直接抛 `SYSTEM_ERROR`，整页历史 500 | 该行降级为原文 + WARN |
| AG-10 | `controller/AgentController.java:95-120` | 聊天 SSE 无 `X-Accel-Buffering: no`、无整体超时/心跳（协作 SSE 有） | 补齐响应头与超时 |
| DE-02 | `V4__agent_message_outbox.sql:17`、`V15__baseline_legacy_tables.sql:50`、`V27__bootstrap_agent_history.sql:2,24` | V27 版本最高但两表已被 V4/V15 先建，`CREATE TABLE IF NOT EXISTS` 全部 no-op：`ascii_bin`、`DATETIME(6)`、复合索引从未生效；跨表 collation 不一致（当前未触发 1267） | 新增 V32 用 `ALTER` 补索引/精度/collation；`migration-matrix.md` 标注 V27 对空库无效 |
| DE-03 | `scripts/verify.sh:133` | 注释自称复刻 CI 的 backend-unit + flyway-mysql，但不调 `check_surefire_skips.py`；本地"全绿"可包含整类被跳过 | pass1/2/3 后各调一次 skip 门禁 |
| DE-04 | `scripts/verify-fast.sh:167-172`、`:183`、`:188` | `docker rm -f` 不带 `-v` 漏匿名卷；容器无 `eye.*` 标签，资源盘点看不到；镜像默认 `mysql:8.0.26`/`redis:7-alpine` 与钉版 `8.0.36`/`7.2.4` 漂移 | 复用 `docker-test-harness.sh`，镜像取自 `docker-images.env` |
| DE-05 | `application.yml:32` | `spring.flyway.schemas: diagnosis` 固定，与 JDBC URL 库名解耦；改库名时迁移会建到错误 schema 且不报错 | `schemas: ${DB_SCHEMA:diagnosis}` 并在部署文档钉住 |
| DE-06 | `application.yml:95`、`agent/service/ToolAuditService.java:40,44` | `enabled=true` 而未设 `AGENT_TOOL_AUDIT_DIGEST_KEY` 时静默使用仓库内公开常量密钥 | enabled=true 且密钥为空/默认值时启动 fail-fast |
| WEB-03 | `web/src/lib/http.ts:99-100`、`web/src/lib/reviewApi.ts:88` | 非 2xx 之外的 502 HTML/204 空体/`null` 时 `response.json()` 无守卫，抛 TypeError 绕过 `ApiError`，40100/40101 广播失效 | `json()` 包 try/catch 转 `ApiError`，并判对象 |
| WEB-04 | `web/src/lib/session.tsx:165-174` | `signOut` 中 `await logoutApi()` 失败未兜住，本地仍显示已登录并产生未处理 rejection | 失败（尤其 40100）也执行本地 `adopt(anon)` |
| WEB-05 | `web/src/pages/CollaborationPage.tsx:52-58` | 断网/5xx/超时与真正的 401/403 压成同一句"未获授权或已失效" | 按 `ApiError.status` 分流 |
| WEB-06 | `web/src/pages/AgentPage.tsx:188-198`、`components/agent/SessionList.tsx:437-439` | 会话列表拉取失败只 `console.warn`，侧栏渲染"暂无历史会话"，把"读不到"说成"没有" | 置错误态 + 重试，成功才允许空态 |
| WEB-07 | `web/src/styles/hardcoded-colors.test.ts`、`components/ElectricBorder.tsx:505` 等 | 硬编码色 ratchet 只扫 `*.css`，TSX 是盲区；`ElectricBorder` 默认 `#d9541f`（=`--paper-signal`）被当装饰色复用，违反"橙只做语义" | TSX 纳入 ratchet（允许 `var(--…)`），默认色改令牌或中性 |
| WEB-08 | `web/src/pages/admin/AdminDashboardPage.tsx:328,344,353,436,512-528` | 页头称"读数来自同一时间窗"，但 `knowledgeFailed`/`newsByStatus` 后端是全站累计；同一读数在数据分析页已标"全站累计"，两页说法相反 | 加"全站"chip 或移出窗口面板 |
| WEB-09 | `web/src/pages/AgentPage.tsx:750-781` | 手写第二套图片弹层：`aria-modal=true` 但无焦点陷阱/inert/滚动锁 | 改用 `@/components/ui/dialog` |
| WEB-10 | `web/src/pages/ProfilePage.tsx:295` | 同一条判读记录在 `/records` 显示归一化中文病名、在 `/profile` 显示原始英文标签（`diabetes`） | 共用 `canonicalClassName(item.result) ?? item.result` |
| WEB-11 | `web/src/pages/admin/Admin{Users,News,Knowledge,Diagnosis}Page.tsx:498-606/549-648/572-701/763-870` | 四个管理列表页约 450 行筛选/删除/批量脚手架逐页复制，已出现漂移（`AdminKnowledgePage:228` 的 `onReset` 与其他三页不同） | 抽 `AdminFilterBar` + 共享删除/批量 hook |
| WEB-12 | `web/src/styles/admin.css`/`admin-redesign.css`/`admin-visual.css`/`admin-polish-analysis.css` | `.feature-card` 75 条规则散在 4 个 CSS 文件，实际外观依赖 import 顺序；README 又规定 redesign 只读 | 全量收进 `admin.css`，其余删净 |
| WEB-13 | `fault.css:532,440`、`admin.css:115`、`admin-redesign.css:1788`、`brand.css:951`、`agent.css:2387,2411` | 9/9.5/10/10.5px 标签七处，低于 `DESIGN.md` 11px 下限且不在已知债务登记里 | 提到 11px 或补登记 |
| ALG-03 | `algorithm/checkpoints/registry.json:5`、`eye_diagnosis/registry.py:28-33` | `allow_sha256: []`，默认启动必抛 `RuntimeError`；而 registry 又被 `docs/product/wp-00/configuration-inventory.md` 列为需单独审批 | 启动错误点明"须登记权重 SHA"，或把允许清单外置到部署侧 |
| ALG-04 | `algorithm/requirements.txt:1-2`、`eye_disease_model.py:523-527,152` | `torch>=1.7.0` 但代码用 `weights_only=True`（≥1.13）与 `weights='DEFAULT'`（较新 torchvision）；无上界，不可复现 | 钉兼容区间，或与 `requirements-p1.txt` 一致 |
| ALG-05 | `eye_disease_model.py:18-27` vs `p1/audit.py:53-62` vs `eye_diagnosis/frozen_eval.py:113` | 服务侧 softmax 类序 N,A,C,D,G,H,M,O；p1 侧 sigmoid 类序 N,D,G,C,A,H,M,O；`frozen_eval` 按服务侧类序索引，评 p1 产物会张冠李戴 | `frozen_eval` 校验权重来源/类序，registry 写 `class_order` |
| ALG-06 | `grpc_server.py:84`、`eye_disease_model.py:73,107`、`mcp_server/tools.py:16-21` | 服务启动先 `pretrained=True` 下载 ImageNet 权重再覆盖；MCP 默认权重路径含日期目录，换批次即找不到 | serving 用 `pretrained=False`；MCP 路径改配置项 |
| ALG-07 | `mcp_server/tools.py:64-82`、`mcp_server/diagnosis.py:35-56`、`algorithm/README.md:205-215` | MCP `diagnose` 只做 softmax + 启发式 reliability（50–95），无 quality/OOD/abstain/model_version/calibration；gRPC 是 `round(top1*100)` + 安全门；同一 `reliability_score` 两套语义 | MCP 复用 `eye_diagnosis` 安全链路，或明确标注"非临床通道" |
| ALG-08 | `.github/workflows/verify.yml:593-608` | 已弃用的 Vue 线仍作硬门禁，且 `npm run lint:check` 连写两遍 | 删除该 job 或标 deprecated 不阻塞；去重 |

### 3.3 P3

- `controller/NewsController.java:116`：缓存前缀 `"diagnosis:listNewsVOByPage "` 末尾多一个空格。
- `agent/service/ToolAuditService.java:76-77`：终态行 `startedAt=finishedAt`，无法从单行算时长。
- `agent/rag/DocumentParser.java:46-51`：保留 `new URL(fileUrl).openStream()`，是 SSRF 原语；
  当前调用方都传本地路径，但接口一旦接用户 URL 即成漏洞。
- `patientexplanation/PatientExplanationService.java:18`：`json` 字段声明后未使用；同包多处
  `new ObjectMapper()`，与 `CODING_STANDARDS` 的"现存 3 处"清单不符（实际 ≥9 处）。
- `patientfollowup/PatientFollowUpService.java:48`：RESTRICTED 分支返回请求原始 `id`，
  其余分支返回规范 `taskId`，大小写不一致时前端拿到两个 id。
- `diagnosistask/DiagnosisTaskService.java:43-45`：只限文件 10MB，却允许 4000 万像素解码
  （单请求约 160MB 堆），并发提交是内存放大面。
- `patientdraft/PatientDraftTestService.java:90`：过滤 `status IN (... 'PUBLISHED')`，
  而任务状态机里没有 `PUBLISHED`。
- `DoctorReviewsPage.tsx:55`：`<div aria-label="冻结 Vision 结果">` 无 role，`aria-label` 无效。
- `AdminNewsEditPage.tsx:465-483`：`role="tablist"` 缺方向键与 `aria-controls`/`tabpanel`。
- `LandingPage.tsx:356-414`：原生 `<dialog>` 灯箱，与 Base UI 并存的第二套弹层（规范债）。
- `ProfilePage.tsx:31-34,164`：`ROLE_LABEL` 只有 admin/user，`ban`/`doctor` 显示原文。
- `RecordsPage.tsx:91-103`：复诊提交后未失效 `records.stats`，"待复核"读数停在旧值。
- `AdminDashboardPage.tsx:273`：空积压仍挂"去复核"CTA，与数据分析页的相反取舍。
- `web/src/styles/README.md`：文件表缺 `diagnosis.css`/`system-states.css`/`collaboration.css`/
  `roundtable.css`/`admin-visual.css`；`fonts-subset.css` 的字符清单在 gitignored 的
  `.shots/all-chars.txt`，字体子集不可复现。
- `web/src/lib/color.ts`：死代码，`ElectricBorder.tsx:318` 有私有副本；图表注释仍称 `--signal`。
- `web/src/test/setup.ts`：`matchMedia` 钉成 `matches:false`，reduced-motion 分支无覆盖。
- `V16:17-22`、`V17:29-45`、`V22:22-23`、`V30:13-18`：既有大表 `ALTER` 未带
  `ALGORITHM=INPLACE, LOCK=NONE`。
- `scripts/check_steering_docs.py:30`：controller 正则只认 `@RequestMapping("/x")` 精确形。
- `algorithm/grpc_server.py:162-166`：非法图像命中通用 `INTERNAL` 并回显 `str(e)`。
- `algorithm/grpc_server.py:105-111`：`evaluate_quality` 与 `predict_logits` 各自重新解码一次图像。
- `scripts/check_probability_scale.py:17`：只校验 `PatientDraftCoordinator`，
  `DoctorReviewService.java:173` 的 0..100 门无防护。
- 输入图像像素无上限（PIL 解压炸弹）。
- `frontend/src/components/agent/message/AssistantMessage.vue:17`、
  `frontend/src/pages/admin/NewsManagePage.vue:32`：`v-html` 渲染模型/资讯内容（弃用线，
  仍可运行；存储型 XSS）。

---

## 4. Spec 轴：文档契约一致性

> 本仓库没有单一 spec，本节检查"代码是否忠实实现文档写下的行为"。
> 每条给出文档原句位置与代码位置。

### 4.1 文档要求但代码缺失 / 只做了一半

| 编号 | 文档 | 代码现实 |
|---|---|---|
| SPEC-01 | `docs/patient-authorization-clinical-semantics.md:96` 把 `/doctor-reviews/{id}/image` 当作医生回放原图的依据 | 普通患者任务上恒 403（`BE-02`）；`docs/doctor-review.md` 接口表还缺这个端点 |
| SPEC-02 | `CODING_STANDARDS.md:40-42`「JSON 统一走 `JsonUtils`；仅 `JsonConfig` 可构造 `ObjectMapper`；现存 3 处」 | 实际 ≥9 处 `new ObjectMapper()`，另有 hutool `JSONUtil` 直用（`BE-09`） |
| SPEC-03 | `docs/controlled-references.md:8,12,14`「上传默认标识为测试资料」「上传者不能批准自己的资料」「`documentVersion` 是原文件字节的 SHA-256」 | 草稿播种写 `isTest=0`、上传者=审核者（`PD-03`） |
| SPEC-04 | `docs/patient-draft.md:40` `INVALID_OUTPUT` 只表示输出不合格 | 兜底异常也写 `INVALID_OUTPUT`（`PD-01`） |
| SPEC-05 | `GLOSSARY.md`「MCP 服务：会话覆盖 → 全局启用 → 禁用」 | 覆盖层仍过滤 `status=1`，无法覆盖未启用服务；"禁用"只能软删（`AG-05`） |
| SPEC-06 | `DESIGN.md:415`「弹层统一 Base UI：Portal + 焦点陷阱 + inert + 滚动锁…不手写第二套」 | `AgentPage` 图片预览与 `LandingPage` 灯箱各一套（`WEB-09`、P3） |
| SPEC-07 | `DESIGN.md:444` / `web/DESIGN.md:58` 规则 12：全量与窗口口径必须写在标签上 | 管理仪表盘把全站累计挂在"近 N 天"面板下（`WEB-08`） |
| SPEC-08 | `DESIGN.md:419`「错误 > 空 > 加载」；「缺省显示 — 不是 0」 | 会话列表失败渲染成"暂无历史会话"（`WEB-06`） |
| SPEC-09 | `docs/doctor-review.md:34` 只要求"切换身份会清理材料" | 窗口失焦也清空，且销毁已填意见（`WEB-02`） |
| SPEC-10 | `algorithm/README.md` 把 50–95 描述成 `reliability_score` 口径 | gRPC 实际回 0–100；MCP 回 50–95，同一字段两套语义（`ALG-07`） |
| SPEC-11 | `docs/architecture/migration-matrix.md` 把 V27 当 agent 表真源 | V27 对空库是 no-op，真 schema 来自 V4/V15（`DE-02`） |
| SPEC-12 | `docs/patient-authorization.md:36`「专项 35 项，零跳过」 | 真源 `check_surefire_skips.py` 声明 37（`PD-02`） |

### 4.2 代码做了文档没要求、且与契约冲突（越界）

- `WEB-01`：`sessionExport` 的隐私契约已写好，生产路径却绕过它，用会话标题拼文件名——
  这是"文档禁止的行为在代码里真实发生"。
- `ALG-01`：MCP 服务默认 `0.0.0.0` 且接受任意 URL/本地路径；文档只描述其为推理工具，
  没有授权它成为通用抓取器/文件读取器。
- `WEB-02`：失焦清空不是任何文档要求的行为，且与"冻结草稿不可丢失"的语义相反。

### 4.3 看着实现了但实现是错的

- `ALG-02`：`_laplacian_variance` 名字与实现不符（乘了 `gray`）。
- `DE-02`：V27 看起来是"agent 历史表引导迁移"，实际全部 no-op。
- `PD-02`：测试计数在文档与门禁脚本之间漂移，文档版本是错的。
- `WEB-10`：同一病名在两条页面走两套归一化，记录页对、个人中心错。
- `AG-08`：注释声称"取消后旧答案保持隐藏"，实际 `selectedVariantId` 为 null 时新旧同屏。

---

## 5. 结构性问题与坏味道（判断项）

> 仓库明文标准优先于坏味道基线；以下均为 judgement call，不是硬违规。

1. **`DocumentSearchTool` 的 Divergent Change**（`agent/tools/DocumentSearchTool.java`）：
   一个文件承担向量检索、BM25、RRF、重排、准入、缓存六件事。混合检索默认开启后，
   其中"BM25 语料是近似子集"的实现缺陷被放大（`AG-07`）。
2. **管理端四页的 Duplicated Code**（`WEB-11`）：状态不变式已收进 `useListWorkspace`，
   视图层仍是四份近 100 行的筛选台与删除/批量流程，且已经漂移。
3. **`.feature-card` 的散落定义**（`WEB-12`）：同一组件 75 条规则跨 4 个 CSS 文件，
   修改一处会在另一页翻车。
4. **"协作准入行"成了隐式能力开关**（`BE-02`）：患者域与协作域靠"有没有
   `t_agent_collab_run` 行"表达边界，导致一个文档化能力在主流程里静默 403。
   应显式化前置条件，而不是让调用方猜。
5. **Speculative Generality**：`DoctorReviewController` 的协作影像分支、`DocumentParser`
   的 HTTP 抓取入口、`UserRole` 里无人使用的 DOCTOR/EVIDENCE_REVIEWER——都为尚不存在的
   使用方式留了钩子，其中一个已经变成缺陷（`BE-02`）。
6. **Primitive Obsession / Mysterious Name**：`reliability_score` 在两套协议里语义不同；
   `fundus_coverage` 实为均值比；`documentVersion` 有时是源文本哈希、有时应是文件字节哈希。

---

## 6. 建议的修复批次

| 批次 | 内容 | 为什么先做 |
|---|---|---|
| **B0 · 安全与隐私（本周）** | `ALG-01`、`AG-02`、`AG-03`、`WEB-01`、`DE-06` | 凭据/患者原文/症状文本的泄漏面；改动小、可独立验证 |
| **B1 · 数据与门禁（本周）** | `BE-01`、`BE-03`、`DE-01`、`DE-03`、`DE-02`（V32） | 审计链、临时文件、绿灯真实性；都影响"能不能信结果" |
| **B2 · 主流程缺陷（下一批）** | `BE-02`、`WEB-02`、`PD-01`、`WEB-06`、`WEB-10` | 文档声明的能力不可用 / 用户输入被销毁 / 错误被掩盖 |
| **B3 · 可用性与授权** | `AG-01`、`AG-04`、`BE-04`、`BE-06`、`BE-07` | 泄漏与潜在越权；`BE-04` 是下一次加医生端点的前置 |
| **B4 · 契约与结构** | `SPEC-01`–`SPEC-12`、`WEB-11`、`WEB-12`、`AG-07`、`ALG-05` | 文档-代码收口与重复实现收敛；可分批进 PR |

---

## 7. 未覆盖与需复核

- 本轮为**只读静态审查**，未运行 `mvn test`、未起 MySQL/Redis/Chroma、未跑前端测试与构建，
  因此"测试是否真绿""迁移在真实库的行为"不在本报告证据内。
- `DE-02` 的 V27 失效与 collation 风险是静态推断，建议在隔离库 `SHOW CREATE TABLE`
  复核后再写 V32。
- `AG-04` 依赖 MCP SDK 对重复 `initialize()` 的具体行为，标 `[需核实]`。
- `BE-02` 若另有路径为任务创建 `t_agent_collab_run` 行，应降级为 P2；当前 `git grep`
  只找到 `CollaborationRunService:99` 一处。
- 未审：`web/src/components/**` 的逐组件实现细节（只从页面调用点看）、e2e/视觉快照、
  双主题实机对比、`algorithm/p1/` 内部实现（只查类序接口）、部署件（无 Dockerfile/nginx，
  线上 TLS/鉴权无法从代码证明）。
- 统计口径正确性（如 `avgConfidence` 是否需 ×100）只做了跨页一致性核对，未逐条回后端 SQL 验算。

---

## 8. 附：分区评审员原始判断

### 8.1 后端核心
强项：`DiagnosisEvidence` 一次归一化写三处、诊断记录不引用共享缓存、取消用 CAS +
`TransactionTemplate`、归属校验与可见范围到位。最该先修：`NewsLifecycleService` 批量自调用
事务失效；`@AuthCheck` 只真正区分 ADMIN。

### 8.2 患者域
强项：租约/递增令牌/额度预扣、`fencedUpdate` 栅栏、`DraftMailbox` 白名单路由、
`withValidatedEvidence` 固定锁序；负例测试覆盖比文档承诺更细。最该先修：协作准入行
与影像读取的隐式耦合。

### 8.3 Agent / 协作 / RAG / MCP
强项：失败语义（`ToolFailureBoundaryInterceptor`、indexVersion 代际闸、`ToolResourceContext`）。
最该先修：MCP 运维安全（信号量、凭据日志、双初始化）集中在同一文件。

### 8.4 数据层与工程化
强项：`docker-test-harness.sh` + `docker-test-resources.sh` 的资源生命周期；
`check_surefire_skips.py`/`check_issue17_reports.py` 是真门禁。最该先修：迁移链里
"写进文档却 no-op"的 V27；本地验证与 CI 口径漂移。

### 8.5 web 基础设施与组件
强项：XSS 架构（markdown-it `html:false` + DOMPurify 分级）、证据/隐私模型 fail-closed、
SSE epoch/seq 门与 reducer 不可变更新。最该先修：`sessionExport` 死代码 + 生产隐私旁路。

### 8.6 web 页面与样式
强项：四场景路由归属无一错配；`noConclusionView`/`reviewView`/`resolveScopedStats` 的
"读不到 ≠ 0"纯函数层是全仓最好的部分。最该先修：仪表盘口径混排与会话列表把失败画成空。

### 8.7 算法 / gRPC / 评测
强项：跨语言概率尺度门禁（读两端源码而非 fixture）、`torch.load(weights_only=True)`、
`frozen_eval` 对 manifest 的 val 集/去重校验、评测语料全合成且 gold 不进角色上下文。
最该先修：MCP 服务暴露面与质量门模糊指标。
