# Iris Java 后端修复交接（2026-11-16）

本轮从 `ui` 基线出发，处理三类后端问题：**配置 fail-fast**、**流式错误契约**、
**MCP 连接与工具发现拆成两个深模块**。三条分支全部在本地，**未 push、未开 PR、未合并、未部署**。

工作树：`D:\Work\code\eye-diagnosis\.claude\worktrees\backend-fix`

## 1. 分支与基线

| 工作包 | Branch | HEAD | Parent（基线） | 相对 Parent 提交数 | 变更文件 | 远端 |
|---|---|---|---|---|---|---|
| A 配置 fail-fast + 流式错误契约 | `fix/backend-issues` | `d505ddf9` | `ui` @ `9d3c5317` | 2 | 13（+345/−12） | 未推送 |
| B 流式错误契约（多 Agent 线） | `fix/stream-error-contract` | `8408c94a` | `feat/iris21-multiagent-integration` @ `6ba65478` | 1 | 9（+323/−4） | 未推送 |
| C8 MCP 两个深模块 | `fix/mcp-seam` | `bc3c42f1` | `ui` @ `9d3c5317` | 1 | 18（+1092/−323） | 未推送 |

Ancestry 检查（`git merge-base --is-ancestor <parent> <branch>`）：三条全部 exit 0。

两点须知：

- **`ui` 在本轮期间前进到 `cf9e9351`**（`1a4df433` web 字体产物去重 + `cf9e9351` web 前端评审报告）。
  三条分支基于 `9d3c5317`，与这两个提交无文件交集，合并时不会冲突。
- **B 为什么单独一条**：PR #87 重写了 `AgentController.onErrorResume` 与 `web/src/lib/api.ts`。
  同样的错误契约修复必须以多 Agent 线为基线，否则合入 #87 时会被覆盖。

## 2. 改了什么

### A1. 密钥缺失在启动期失败（`d505ddf9`）

- `src/main/resources/application.yml`：`vision-recognition.api-key` 从占位符 `your-api-key-here`
  改为 `${DASHSCOPE_API_KEY}`（无默认值），模型名 `qwen-vl-plus` → `qwen3-vl-plus`。
- 实测证据：不设环境变量启动，在 `eyeImageAnalyzerTool` bean 处抛
  `PlaceholderResolutionException`，点名 `eye-diagnosis.vision-recognition.api-key`；
  改前应用能正常启动，直到第一个真实请求才 401。
- `src/test/resources/application-ci.yml` 补占位符，CI 不依赖外部环境变量。
- `application-server.example.yml`：`rerank` 缩进 4→6，原来绑到 `spring.ai.rerank.*`（死键），
  实际应为 `spring.ai.dashscope.rerank.*`；模型名同步。
- `README.md` 注明 `cp application-dev.yml application-local.yml` 只能跑一次（会覆盖已有密钥）。

### A2 / B. 流式错误带上机器可读码（`33a3887f` / `8408c94a`）

- 新增 `agent/AgentStreamError.java`：沿 cause 链找 `WebClientResponseException`，
  按 host 限定 DashScope；401 / `invalid_api_key` → `50301`，403 / `AllocationQuota` → `50302`，
  其余模型服务失败 → `50303`，无匹配 → `50000`（SYSTEM_ERROR）。
- `AgentController.onErrorResume` 发 `event: error` + `{code,msg}` JSON；日志只记
  `requestId + errorType + faultCode`，不落用户原文（保留 #87 的脱敏口径）。
- 前端：`web/src/lib/api.ts` 的 `readStreamError` 解析 `{code,msg}`（旧后端纯文本仍兼容），
  `web/src/lib/faults.ts` 增加 50301–50303 分支。
- 证据链：后端 SSE `{code,msg}` → `readStreamError` → `ApiError(code)` →
  `AgentPage` 的 `faultFromError` → 503xx 文案。

### C8. MCP 拆成两个深模块（`bc3c42f1`）

删除 `agent/manager/MCPManager.java`（253 行）与无人使用的 `McpTimeoutException` /
`McpOverloadException`（全仓 0 处 `catch`/`throw`）。

新增：

| 文件 | 角色 |
|---|---|
| `agent/mcp/McpClientFactory` | 解析部署配置、白名单校验、选传输（SSE / Streamable HTTP）、统一 `initialize()` 握手 |
| `agent/mcp/McpSession` / `SdkMcpSession` | seam + SDK 适配器；SDK 类型不再外泄到 registry 与测试 |
| `agent/mcp/McpToolRegistry` | 工具发现（Redis 5 分钟缓存）、调用（并发额度 / 超时）、缓存失效 |
| `agent/mcp/McpFailure` / `McpSessionException` | 失败分类与带分类的连接异常 |

接口承诺：`McpToolRegistry.discover/call` **从不抛异常**，失败以
`McpFailure{kind,detail}` 返回；`kind ∈ {NOT_CONFIGURED, FORBIDDEN, UNREACHABLE,
TIMEOUT, OVERLOADED, REMOTE_ERROR}`。

修复的 5 类问题：

1. **permit 泄漏**：`mcpExecutor.submit` 原在 `try` 之外，线程池拒绝时 `finally` 不执行，
   该服务的额度永久少一个。现已移进 `try`，并有回归测试
   `callReleasesThePermitWhenTheExecutorRejectsTheTask`（用「只拒绝第一次提交」的
   executor 替身确定性制造拒绝，不依赖线程池时序）。
2. **静默跳过**：`McpToolCallbackProvider` 的空 `catch` 改为记录
   `serverCode + kind + detail`；单个服务失败仍不影响其他服务。
3. **缓存永不过期**：`updateMcp` / `deleteMcp` 主动 `invalidate(userId, code)`。
   原来 `invalidateToolsCache` 全仓 0 个调用方，改配置后最多 5 分钟仍用旧工具。
4. **失败形态不一**：工具发现失败、握手失败、超时、过载、服务端 `isError` 现在都是
   `McpFailure`。原来有的抛 `BusinessException`（打断整轮 Agent 对话）、有的吞成空列表、
   有的返回 `isError=true` 文本。
5. **并发闸门无界**：`perServerGates` 原来随服务数无限增长；改为按服务编码哈希分
   64 条固定 `Semaphore`，内存有上界。哈希碰撞只会更早报繁忙，不会超额放行。

行为变化（需要在评审时确认是有意的）：

- MCP 工具调用失败**不再中断 Agent 回合**，而是把可读的失败文本交给模型换路。
- `McpServerService.callTool` 的「服务不存在」从 `IllegalArgumentException` 改为
  `BusinessException(NOT_FOUND_ERROR)`。
- 两种传输的握手时机统一为「工厂内握手一次」；原 Streamable 路径会握手两次。

文档同步：`CLAUDE.md`（Agent System 与 Key Patterns 两处）、`GLOSSARY.md`（MCP 服务条目）、
`src/README.md`（目录说明）。

## 3. 验证

`fix/mcp-seam` @ `bc3c42f1`，`scripts/verify.sh`（一次性 MySQL 8 + Redis，`SPRING_PROFILES_ACTIVE=ci`）：

| 验证 | 结果 |
|---|---|
| pass 1/2：全量单测（含 `contextLoads`） | 191 用例，0 失败，0 错误，3 跳过，BUILD SUCCESS |
| pass 2/2：MySQL 集成门禁（空库 Flyway + 上传任务 CRUD） | 4 用例，0 失败，BUILD SUCCESS |
| `scripts/verify.sh` 总退出码 | `VERIFY_EXIT=0` |
| MCP 专项 | `McpToolRegistryTest` 9、`McpClientFactoryTest` 4、`McpToolCallbackProviderTest` 4、`McpConnectionPolicyTest` 2（后两者为既有） |
| 回归测试稳定性 | `McpToolRegistryTest` 连跑 5 次全绿 |
| 容器清理 | 无残留 `eye-diagnosis-verify-*` |

新增 13 个用例（registry 9 + factory 4），其余为既有用例改写。

**一次失败与结论**：第一次 verify 的 pass 2 报 `expected: <12> but was: <23>`
（`FlywayMySqlIntegrationTest`）。原因是切换分支后 `target/classes/db/migration`
残留多 Agent 线的 `V18–V28`，与迁移本身无关；`mvn clean` 后消失。
`scripts/verify.sh` **不会自己 clean**，跨分支复用工作树时必须先清。

## 4. 未做 / 后续

- 三条分支均未 push、未开 PR、未合并、未部署（按仓库规则，需明确批准才动）。
- PR #87（多 Agent 线）在默认 profile 下 `mvn test` 起不来：`spring.autoconfigure.exclude`
  只写在 `application-ci.yml`，本地 `local,dev` 下 `AgentController` bean 名冲突。
  这是 #87 自身缺陷，B 未改动它。
- 架构评审的其余候选（C1–C18）尚未动，报告在
  `C:\Users\yuwen\AppData\Local\Temp\architecture-review-20261116-215901.html`。
  建议的下一批：C9（流断了但话说了一半）、C10（`GET /api/user/get` 返回密码哈希）、
  C11（测试构造 seam）、C13（最贵的两条路径 CI 默认不验证）。

## 5. 继续工作时的操作提醒

- 工作树 `backend-fix` 不继承主检出的 `application-local.yml` / `application-dev.yml`（gitignored）。
- 跨分支复用工作树：先 `JAVA_HOME=C:/Users/yuwen/.jdks/corretto-21.0.7 mvn -o clean`，再跑 verify。
- 控制台是 GBK，中文在工具输出里会花；文件本身是 UTF-8，别照终端显示改文件。
