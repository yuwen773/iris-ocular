# 分级验证：什么时候跑什么

**默认跑 L1。** 改一两个文件就启动 `scripts/verify.sh`（全量）既慢又浪费，
真实后果不是「更安全」，而是人和 Agent 开始用「少跑」换速度——那才是真正降低门禁。

入口只有一个：`scripts/verify-fast.sh`。`scripts/verify.sh` 不被它取代，仍是 L3，自己负责一次性服务栈的编排与资源回收。

---

## 1. 四个级别

| 级别 | 跑什么 | 典型耗时 | 什么时候用 |
|---|---|---|---|
| **L0** | git 差异摘要 + 受影响语言的编译/类型检查（`mvn test-compile`、`tsc -b`），**不执行任何用例** | 十几秒到 1 分钟（增量） | 只改了注释、文档、重命名；想先确认「它能不能编译」 |
| **L1**（默认） | 你显式点名的 Java 用例与/或 Vitest 文件 | 单个类 20–60s；web 单文件 5–15s | 绝大多数改动：改了一个类 / 一个组件，语义边界没跨出去 |
| **L2** | L1 + 一次性 MySQL 8 + Redis + Chroma（隔离端口） | 集成套件 1–5 分钟 | 改动涉及**数据库状态、schema/迁移、权限与角色、RAG 证据、Spring 上下文装配** |
| **L3** | `scripts/verify.sh`：全量 `mvn test` 三遍（单测 / MySQL 集成门禁 / 隔离库协作套件，集成套件清单以脚本里的两遍 `-Dtest` 列表为准）；一次性 MySQL + Redis 走 `scripts/lib/docker-test-harness.sh`（随机端口、命名卷、退出断言无残留） | 10 分钟以上 | 准备合并、准备交付、或 L1/L2 结果无法解释时 |

`--level` 不变时也可以叠加：`--static` 在任何级别前加一段 L0，`--db` 把 L1 升成 L2。

```bash
scripts/verify-fast.sh --level L0
scripts/verify-fast.sh --java SessionMessageQueueTest
scripts/verify-fast.sh --java 'SessionMessageQueueTest#anEvictedWriteNeverReportsSuccess'
scripts/verify-fast.sh --web src/lib/agentStream.test.ts
scripts/verify-fast.sh --java FlywayMySqlIntegrationTest --db
scripts/verify-fast.sh --level L3
```

**L1/L2 必须显式给出目标。** 不给 `--java` / `--web` 会直接报用法错误退出（exit 2），
不会「猜着跑一个大概相关的」——猜出来的绿比红灯更危险。

---

## 2. 升级到 L2 的判据（命中任一条就升）

1. **数据库状态参与断言**：写库、事务、并发、锁、幂等、雪花 id、软删除。
2. **schema / Flyway 迁移**：新增或修改 `V*.sql`、改实体与列的对应关系。
3. **权限与角色**：`@AuthCheck`、资源归属（ownership）、管理员作用域、跨角色可见性。
4. **安全**：口令、会话、越权读取、上传归属校验。
5. **RAG 证据链**：片段来源、文档代次（`indexVersion`）、引用是否真的来自检索结果。
6. **医学边界**：严重度语义、措辞、拒识、置信度口径、复核冻结。
7. **跨模块契约**：改 `BaseResponse` 形状、SSE 事件名、protobuf 字段。
8. **Spring 装配**：新增 Bean、改 `application*.yml`、改 `@Configuration`。

反过来，**纯函数、纯计算、纯映射、单类内部逻辑**留在 L1。

---

## 3. 影响映射表（人可直接复核）

左侧是「你动了什么」，右侧是「至少跑什么」。表里的名字都是仓库里真实存在的用例。

| 改动位置 | 至少 L1 | 升级条件 |
|---|---|---|
| `web/src/pages/**`、`web/src/components/**` | 同目录 `*.test.tsx`，例如 `AgentPage.test.tsx`、`RecordsPage.test.tsx`、`RecordSheet.test.tsx`、`ChatInput.test.tsx` | 视觉/布局改动 **不因此升级**，但要按 `DESIGN.md` 跑**双主题人工验收**——脚本替代不了 |
| `web/src/lib/agentStream*`、SSE 消费 | `agentStream.test.ts`、`agentStream.hook.test.tsx` | 事件顺序/终态帧语义 → 加后端 `AgentControllerHistoryTest` |
| `web/src/lib/api*`、错误处理 | `api.auth.test.ts`、`faults.test.ts` | 后端响应形状变了 → L2 |
| Agent 流式输出（后端） | `controller/AgentControllerHistoryTest` | 终态帧落库、断连复用 → L2 |
| 工具执行与持久化 | `tools/DocumentSearchToolOwnershipTest`、`service/ChatMessageWriterTest` | 真的写库（`t_agent_message`、`t_agent_tool_audit`）→ L2 |
| 消息队列 / 恢复 | `queue/SessionMessageQueueTest`、`service/ChatHistoryServiceOwnershipTest`、`SessionPinExposureTest` | 涉及真实事务与租约 → L2 |
| RAG 检索与来源 | `rag/Bm25SegmenterTest`、`TextChunkerServiceTest`、`RrfFusionRankResetTest`、`tools/DocumentSearchToolOwnershipTest` | 连真实 Chroma / 改 embedding 口径 → L2（`--db` 会起 Chroma） |
| RAG 评估 | `rag/RAGEvaluationTest` | 阈值与题目集变更 → 记录前后数字，不只求绿 |
| Flyway 迁移 `src/main/resources/db/migration/V*.sql` | —— | **一律 L2**：`migration/FlywayMySqlIntegrationTest`（空库 migrate）。已应用过的迁移不可改，只能加新的 |
| 患者权限 / 医生复核 | `service/DiagnosisOwnershipTest`、`DiagnosisHumanReviewTest`、`DiagnosisHumanReviewAuditTest`、`DiagnosisEvidenceTest`、`DiagnosisAuthorFallbackTest` | 真实角色矩阵与跨用户可见性 → L2 |
| 认证与账号 | `security/PasswordServiceTest`、`controller/UserControllerOwnershipTest` | 会话存储、Redis → L2 |
| 会话与流式鉴权 | `service/UserSessionLifecycleTest`、`service/UserAdminProtectionTest`、`agent/service/AccountStreamGuardTest` | 改 `@AuthCheck` 语义、SSE 终态或封禁联动 → L2 |
| 启动基线与 gRPC 链路 | `config/StartupSafetyGuardTest`、`grpc/GrpcHostsTest` | 改 TLS/明文策略、回环判定或 `server`/`prod` 默认值 → 加 `application-server.example.yml` 对照 |
| MCP 准入与影像引用 | `agent/service/ImageReferenceSignerTest`、`AgentImageReferenceResolverTest`、`AgentAttachmentTrustTest` | 改密钥、有效期或 URL 白名单 → 同步算法侧 `mcp_server` 用例 |
| 判读证据 / 概率口径 | `service/DiagnosisEvidenceTest`、`service/InferenceCacheTest` | 改 proto 字段、落库列或校准门控 → L2 + 前端 `web/src/lib/domain.test.ts` |
| 小程序客户端 `miniapp/src/lib/**` | `cd miniapp && npm run typecheck && npm test`（纯函数改动要补 `tests/*.test.ts`） | 改请求/错误口径、SSE 解析或历史回放 → 同时跑后端对应 Java 用例；改令牌文件 → `npm run gen:tokens` 后核对生成物 |
| 小程序令牌通道 `src/main/java/**/miniapp/**`、`MiniAppConstant` | `miniapp/MiniAppAuthServiceTest`、`MiniAppTokenFilterTest`、`MiniAppTokenServiceTest`、`WeChatCode2SessionClientTest`、`config/SameOriginFilterTest` | 改过滤器顺序、同源豁免或 `getLoginUser` 分支 → **一律 L2**（`--db`），并重跑 `FlywayMySqlIntegrationTest` |
| 小程序页面 `miniapp/src/pages/**`、`components/**`、`styles/**` | 无对应单测时至少 `npm run typecheck && npm run build:weapp` | 视觉改动按 `DESIGN.md` 跑**双主题人工验收**；改 `base.scss` 令牌引用 → 确认 `tokens.scss` 仍是生成物 |
| 公开影像孤儿审计 | `upload/audit/PublicImageOrphanAuditorTest` | 改前缀或归一规则 → 用例必须用**真实写入形态**的 key（`/public/…`，带前导斜杠） |
| 新闻 / 管理端可见性 | `controller/News*Test`、`service/News*Test`、`AdminScopeVisibilityTest` | 发布生命周期涉及真实状态列 → L2 |
| 用户反馈（`t_user_feedback`、`/feedback`、`/admin/feedback`） | `constant/UserFeedbackStatusTest`、`service/UserFeedbackLifecycleServiceTest`、`controller/UserFeedbackOwnershipTest`、`lib/userFeedbackDomain.test.ts`、`pages/FeedbackPage.test.tsx`、`pages/admin/AdminFeedbackPage.test.tsx` | 新增迁移 + 写库 + 状态机 + 归属/越权 + 截图上传归属 → **L2**（`FlywayMySqlIntegrationTest --db`） |
| 站内通知（`t_user_notification`、`/notifications`、顶栏铃铛） | `service/NotificationServiceTest`、`controller/NotificationOwnershipTest`、`service/UserFeedbackLifecycleServiceTest`（通知触发）、`lib/notificationDomain.test.ts`、`components/NotificationBell.test.tsx`、`pages/NotificationsPage.test.tsx` | 新增迁移 + 写库 + 事务提交后写入 + 接收人归属 → **L2**（`FlywayMySqlIntegrationTest --db`、`EyeDiagnosisApplicationTests --db`） |
| 上传状态机 | `upload/state/UploadStateMachineTest`、`UploadStatusTest`、`UploadCompensationServiceTest` | 真实表 + 补偿任务 → L2 |
| 应用装配 / 配置 | `EyeDiagnosisApplicationTests`（`contextLoads`）——**它需要 DB + Redis + Chroma** | 直接 L2 |
| Python 算法服务 | `algorithm/` 下 `python -m pytest ... -m "not slow"`（CI 的 `python` job 同口径） | 含子进程/watchdog → 那几条在 Linux 上跑（CI `watchdog` job） |
| 工作树与运维脚本 `scripts/**` | `python -m unittest discover -s scripts/tests`；shell 改动先 `bash -n` | 会动 Docker 资源 → 只跑 dry-run，别在共享主机上做真实回收 |

> **协作 / 多 Agent 运行时**：相关代码已随 #117 / #172 进入 `master`，对应用例在 CI 的
> `collaboration-mysql`、`graph-execution-mysql` 与本地 L3 第三遍执行；改动按「工具执行与持久化 +
> 消息队列/恢复」两行处理，且必须 L2。

---

## 4. 结果怎么读

脚本最后固定打印一行：

```
RESULT: PASS — L1
RESULT: FAIL — 未通过：java
RESULT: TIMEOUT — 未通过：java(TIMEOUT)
```

- **退出码**：`0` 通过、`1` 失败、`124` 超时、`2` 用法错误。超时**不是**通过——那个阶段什么都没证明。
- **日志**：`target/verify-fast/<run-id>/` 下按阶段分开存，报错时脚本会把尾部直接打到终端。
- **代码 SHA**：每行结果都带 `sha=<12 位>` 和 `tree=clean|dirty`，对照 CI 时不会有歧义。
- **没有测试算失败**：Java 侧 `check_surefire_result.py` 会核对本轮新生成的报告，覆盖
  「类名写错」「整类 `@Disabled`」「只有上一轮留下的陈旧 XML」三种伪装成功；
  Web 侧拦 `No test files found` 与「全部 skipped」。
- **环境没起来 ≠ 测试失败**：L2 的环境准备失败会单独报 `环境准备失败`，不要当成代码红灯去改代码。

## 5. 执行纪律（给 Agent 和人）

- **禁止**用「轮询日志」等结果：
  `until grep -q "EXIT=" log; do sleep 20; done` 这种写法分不清「还在跑」和「进程已经挂了」，
  而且它会一直占着会话。要等就盯**进程本身**（`scripts/verify-fast.sh` 里 `run_bounded` 就是这么做的）。
- **每个阶段都有上限**：默认 900s，`--timeout` 可调。超时先 TERM 再 KILL，
  只杀本次运行自己的进程树。
- **只清理自己创建的资源**：`--db` 起的容器名带本次 run id，退出即删；
  复用到 :8000 上已有的 Chroma 时不删它。
- **同一时间只允许一个 Agent 监督全量运行**。两个 Agent 同时跑 L3 会抢端口和 `.m2`、`target/`，
  得到的失败没有诊断价值。
- **不许为了变绿改业务代码**：测试红了先判断是「代码错」还是「用例错」还是「环境错」，
  三种结论要如实写出来。删测试、加 `@Disabled`、放宽断言都不算修好。

## 6. 不因分级而放松的东西

L1 省的是**范围**，不是**严格度**。以下与级别无关，永远不变：

- 权限、越权、安全相关断言不因「只跑一个类」而降级；
- 医学措辞、严重度语义、拒识口径、证据链要求；
- 已应用的 Flyway 迁移不可修改；
- 准备合并时必须有一次 L3（或等价的 CI 全绿），不能用一串 L1 拼出「已验证」。

## 7. CI 层优化（G1 起进入实施）

2026-11-27 的 G0 审计（`docs/architecture/g0-engineering-governance-audit-2026-11-27.md`）给出了实测基线：
22 个必需上下文、单 PR 中位 33.9 job-分钟、95% 的合并没有评审记录。所有者接受该审计并批准 G1：
**只消除已证实的重复、补齐已确认的覆盖缺口、给 CI 自身加最小防护**，不改分支保护、不删必需检查。

G1 做了与没做的事：

- 删除重复执行：`scripts/tests` 的 `test_check_*.py` 从 4 处收敛到 `docs-contract` 一处；
  `issue24-evaluation` 不再重跑 `PatientDraftTest` / `PatientExplanationApiTest`（78 例），
  改为消费 `issue19` / `issue21` 的产物，并用 commit + run + SHA-256 绑定来源；
  `frontend` job 的重复 `lint:check` 删除。
- 补齐覆盖：`CoordinationMySqlTest` 接入 `graph-execution-mysql`（此前 CI 从不执行）；
  `graph-execution` 的契约从 1 个套件扩到 4 个（37/12/4/14）。
- 保护自身：`ci-change-approved` 标签闸门 + CODEOWNERS 覆盖 CI 路径；
  信任模型与残余风险见 `docs/testing/ci-trust-model.md`。
- 覆盖映射见 `docs/testing/ci-coverage-map.md`。

**实测（两次全绿 run）**：`issue24-evaluation` 92s→**12s / 10s**（稳定 −82s，不再起 Maven/MySQL）；
`graph-execution-mysql` 106s→134s / 143s（稳定 +30~37s，新增的 `CoordinationMySqlTest` 14 例本体 19.0s）；
`issue19` 68→88s / 71s、`issue21` 76→98s / 85s、`docs-contract` 8→8s / 9s、`frontend` 62→54s / 70s
全部落在 run 间波动里，不作为结论。单次 run 合计 38.0 与 **33.9 job-分钟**（最终 HEAD `0bcd9739`），
而 23 run 样本的中位也是 33.9——**只能说「没有变慢」**：没改动过的 `backend-unit`/`web`/`flyway`
两次之间就差 121s，比去重省下的约 80s 还多，单次 run 不能对中位数下结论。去重看的是**执行次数**：
78 条重复 Java 用例 → 0；`test_check_*.py` 4 处 → 1 处（`issue19` 那处第一轮漏删，由 `cd031e03` 补上，
最终 HEAD 日志里只出现 1 次）。覆盖不退化：`backend-unit` 仍 1388 例 / 384 跳 / 0 失败。

仍然有效的老规矩：**现有必需检查一个也不提前删、不放宽**；聚合门禁（`merge-gate`）要到 G2/G3
验证通过、G5 才提交正式切换方案。RAG 的两条评估（需要真实 embedding 密钥）仍不进 PR 门禁。

## 8. 验收实测（2026-11-19，本机 Windows + Git Bash）

用三个真实场景量了一遍，基线 `a0efaf46`（`chore/verification-levels` 的起点）。
「启动开销」指与环境无关的固定成本，与用例本身的耗时分开记。

| 场景 | 命令 | 实际耗时 | 用例数 | 目标真的跑了吗 | 结果 |
|---|---|---|---|---|---|
| **React 组件** | `--web src/components/AgentComposer.test.tsx` | 3.9s（其中 Vite 启动约 2.5s） | 1 个文件 | 是（报告里列出文件与用例） | `PASS`，exit 0 |
| **Java 纯逻辑** | `--java SessionMessageQueueTest` | 17.6s（含首次全量 `test-compile`） | 2 个用例 | 是（Surefire 核对通过） | `PASS`，exit 0 |
| **落库集成（L2）** | `--java FlywayMySqlIntegrationTest --db` | 41.4s（MySQL+Redis 就绪约 20s，用例 19s） | 1 个用例 | 是（真实 MySQL 8.0.26 上 migrate） | `PASS`，exit 0 |
| 静态（L0） | `--level L0` | 14.2s | 0（只 `test-compile`） | — | `PASS`，exit 0 |

反例（这几条比正例更重要，全部按预期变红）：

| 场景 | 结果 |
|---|---|
| Java 类名写错（`--java NoSuchTestClass`） | `RESULT: FAIL`，exit 1，理由「本轮没有产生任何报告……测试没有执行」 |
| Web 文件不存在（`--web src/lib/no-such.test.ts`） | `RESULT: FAIL`，exit 1，`No test files found` |
| 超过阶段上限（`SessionMessageQueueTest --timeout 5`） | `RESULT: TIMEOUT`，exit 124，无残留 java 进程 |

没有重复跑的东西：本次验收没有触发 L3，也没有额外跑一遍 `mvn test`；L2 只跑了指定的
那一个集成类。`--db` 起的容器在退出后已全部删除（`docker ps -a` 无残留），
`:8000` 上已有的 Chroma 被复用而没有重建。

一次性的环境成本要单独看：新建工作树后 `web/` 需要 `npm ci`（本次 6 分 37 秒），
`mvn` 首次要把主代码与测试代码编译一遍；这两笔只在**新工作树**付一次，不是每次 L1 的成本。

