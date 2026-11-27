# CI 覆盖映射（G1 基线 2026-11-27）

一页纸回答：**哪个 job 跑什么、靠什么环境、证明什么风险域、什么时候跑**。
数字来自 `scripts/check_surefire_skips.py` 的契约（用例数被门禁钉死）与
2026-11-26 的 `backend-unit` 实跑日志（1388 例、384 跳、0 失败）。

## 1. Java 套件 → job

| 套件 | 用例 | job（必需？） | 环境 | 风险域 |
| --- | --- | --- | --- | --- |
| 全量非隔离单测 + `contextLoads` + 配置中心 7 个跨进程用例 | 1388 | `backend-unit`（是） | MySQL `diagnosis_ci`、Redis、Chroma:8000、`ci` profile | 全局装配、权限、医疗语义 |
| `DiagnosisTaskApiTest` / `DiagnosisTaskRecoveryTest` / `CosDiagnosisTaskStorageTest` | 7 / 10 / 1 | `issue17-mysql`（是） | 库 `issue17_test` | 患者任务、恢复 |
| `ControlledReferenceApiTest` | 13 | `issue18-mysql`（是） | 库 `issue18_test` | 受控资料、引用授权 |
| `PatientDraftTest` | 41 | `issue19-mysql`（是） | 库 `issue19_test` | 患者草稿、权限 |
| `DoctorReviewApiTest` | 38 | `issue20-mysql`（是） | 库 `issue20_test` | 医生复核冻结 |
| `PatientExplanationApiTest` | 37 | `issue21-mysql`（是） | 库 `issue21_test` | 分层解释、可见性 |
| `PatientAuthorizationApiTest` | 37 | `issue22-mysql`（是） | 库 `issue22_test` | 保存授权 |
| `PatientFollowUpApiTest` | 37 | `issue23-mysql`（是） | 库 `issue23_test` | 随访 |
| `CollaborationRunTest` | 33 | `collaboration-mysql`（是） | 库 `collaboration_test` | 多 Agent 协作 |
| `GraphExecutionTest` / `RealtimeGraphSpikeTest` / `CoordinationPocTest` / `CoordinationMySqlTest` | 37 / 12 / 4 / 14 | `graph-execution-mysql`（是） | 库 `graph_execution_test`、`coordination_poc`；触发器权限 | 执行图、进程死亡、租约 |
| Flyway + 11 个 MySQL 集成套件 | 79 | `flyway-mysql` ×2（是） | 空库 migrate + `diagnosis_ci_*` 一次性库；顺/逆序两遍 | 迁移、落库、预算与台账 |
| `ProductSchemaTest` / `CollaborationImagesTest` / `ProductProviderLedgerTest` / `AgentSessionAdmissionTest` | 1 / 7 / 5 / 8 | `product-contract`（是） | 库 `product_acceptance_test` | 产品验收、准入 |
| 评测脚本（`evaluate_agent_collaboration` / `evaluate_rag_query_expansion`） | — | `issue24-evaluation`（是） | **无 Java、无 MySQL**；报告由 `issue19/21` 产物提供 | 脚本化评测（非医疗语义裁决） |

## 2. 环境门禁套件：实际在哪跑

| 套件 | 门禁 | 需要的真实服务 | 2026-11-27 实际状态 | 归口 |
| --- | --- | --- | --- | --- |
| `CoordinationMySqlTest`（14） | `COORDINATION_POC_URL` | MySQL + `poc` 账号 | **G1 起在 `graph-execution-mysql` 内执行**（CI 其余 job 仍跳过） | 已补齐 |
| `ControlCenterMultiProcessTest`（7） | 无 env；`assumeTrue(库名含 ci/test)` | MySQL `diagnosis_ci`、Redis、能 fork JVM | **在 `backend-unit` 内执行（7/7，49s）**；G0 报告误记为「从不执行」，已更正 | 已覆盖 |
| `VectorStoreConfigTest`（1） | `RUN_CHROMA_INTEGRATION_TESTS=true` | Chroma + **真实 DashScope embedding 密钥** | CI 从未执行；`application-ci.yml` 的密钥是占位符，联网即失败 | G2/Nightly（需 secret） |
| `RAGEvaluationTest`（2） | `RUN_RAG_EVALUATION_TESTS=true` | 已配置的 provider + 医疗语料 | 同上 | G2/Nightly（需 secret） |
| `ProductSchemaTest` 等 4 套 | — | MySQL | `product-contract` | 已覆盖 |

结论：**没有密钥时，RAG 的两条评估只能停在 Release 验收（所有者提供密钥）或带 secret 的 Nightly**；
不把它们硬塞进 PR 门禁，也不为此新建服务。其余两条已在 G1 补齐或确认已覆盖。

## 3. 非 Java 面

| 面 | job（必需？） | 内容 | 说明 |
| --- | --- | --- | --- |
| 文档/契约 | `docs-contract`（是） | 设计文档契约、steering 文档、概率刻度、**CI 完整性**、`scripts/tests/test_check_*.py` | G1 起是全仓唯一的门禁自测入口 |
| 算法 | `python`（是） | `pytest -m "not slow"`、proto 生成、冻结评估 CLI 冒烟 | 不装 torch 之外的额外依赖 |
| 看门狗 | `watchdog`（是） | 5 个 P1 子进程/watchdog 用例 | 仅 Linux |
| 编译 | `backend-compile`（是） | `mvn clean package -DskipTests` | 早失败 |
| 产品前端 | `web`（是） | typecheck、vitest、build、lint、e2e fixme 预算、gate 自测、协作契约 | — |
| 弃用前端 | `frontend`（是） | lint、vitest、build（Vue） | 已弃用；G1 只删了重复 lint |
| 小程序 | `miniapp`（**否**） | tokens 生成校验、typecheck、test、build | 每 PR 都跑但不挡合并，是已知缺口 |
| 浏览器 | `web-e2e-system-states`（是） | `system-states-r2.spec.ts` 15 条 | 其余 13 个 spec 无 CI 执行，归 Nightly |
| 容器资源 | `docker-lifecycle`（是） | 镜像钉版、生命周期、回收脚本自测 | 保护测试资源 |
| 资讯 Worker | `news-worker`（**否**，path 触发） | ruff、pytest、CLI | 不挡合并 |

## 4. 本地三层与 CI 的对应

- `scripts/verify-fast.sh --db`（L2）：单类 + 一次性 MySQL/Redis/Chroma，对应任一 `issueNN` job 的单类验证。
- `scripts/verify.sh`（L3）：pass 1 ≈ `backend-unit`；pass 2 ≈ `flyway-mysql`（无顺/逆序矩阵、额外含
  `ControlCenterMultiProcessTest`）；pass 3 ≈ `issue17–23` + `collaboration` + `graph`（含 `CoordinationMySqlTest`）。
  它**不覆盖**：前端/小程序/算法/docs 契约/`product-contract` 的 3 个套件/flyway 顺序矩阵。
- 因此「本地 L3 绿」与「CI 全绿」是互补关系，不能互相代替；准备合并仍以 CI 为准。

## 5. 维护规则

- 新增 env 门禁套件 → 必须同时进 `check_surefire_skips.py` 的契约（否则 `backend-unit` 的 `scope=all` 会因跳过而红）。
- 新增 job 或改 `-Dtest=` → 同步本表；`docs-contract` 不检查本表，靠评审。
- 删除任何检查前，先在 `docs/testing/verification-levels.md` 记录它证明了什么、由谁接替。
