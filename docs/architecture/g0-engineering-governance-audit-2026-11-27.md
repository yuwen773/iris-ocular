# G0 · 工程治理与 CI/CD 减负审计（2026-11-27）

**性质**：只读审计 + 实施方案。本轮未修改 CI、未改分支保护、未删测试、未改产品代码、未合并未部署。
本文是提案，**结论都要所有者批准后才动手**。

**基线**：`git fetch` 后的 `origin/master` = `74eee927`（WP-08B.2 合并后）。仓库 `yuwen773/iris-ocular`，
public、379 MB。审计数据来自 GitHub API（2026-11-27 读取）与本机仓库实测。

---

## 1. 事实清单

### 1.1 门禁与保护（API 实测）

| 项 | 事实 |
| --- | --- |
| 工作流 | `verify.yml`（722 行，19 个 job id；`flyway-mysql` 是 2 腿矩阵 → 20 个状态上下文）+ `news-worker.yml`（33 行，path 过滤，**不是**必需 check） |
| 必需状态检查 | **22 个**：`backend-compile` `backend-unit` `collaboration-mysql` `docker-lifecycle` `docs-contract` `flyway-mysql (alphabetical)` `flyway-mysql (reversealphabetical)` `frontend` `graph-execution-mysql` `issue17…issue23-mysql`（7 个）`issue24-evaluation` `product-contract` `python` `watchdog` `web` `web-e2e-system-states` |
| 非必需但每次 PR 都跑 | `miniapp`（37s/次，**红了不挡合并**） |
| 分支保护 | `strict=true`（必须基于最新 master）、`required_approving_review_count=1`、`require_code_owner_reviews=true`、`dismiss_stale_reviews=true`、`enforce_admins=false`、禁止 force push / 删除、**无 ruleset** |
| 合并方式 | 仅 squash；合并后自动删分支；`allow_auto_merge=false` |
| CODEOWNERS | 迁移/安全/医疗链路/`agent/rag`/proto/算法质量与 MCP，全部 `@yuwen773`；**没有默认所有者**，也**不覆盖 `.github/**` 与 `scripts/`** |
| 触发面 | `verify.yml`：所有 `pull_request` + `push:master`；**无 `permissions:`、无 `concurrency:`**；12 个 job 设了 `timeout-minutes`（10–20），其余默认 360 分钟 |

### 1.2 规模

| 项 | 事实 |
| --- | --- |
| 近 30 天 Actions run | **443** 次；合并 PR **93** 个（≈3 个/天、≈4.8 次 run/PR） |
| 代码测试面 | Java 184 个测试类 / 1208 个 `@Test`；web 120 个测试文件；miniapp 8；news-worker 4；algorithm 69 个 pytest 文件；e2e 15 个 spec（约 64 条用例） |
| 本地验证 | `verify-fast.sh`（L0/L1/L2/L3）+ `verify.sh`（3 遍 Maven + 一次性 MySQL/Redis）；L3 不编排 Chroma、不跑前端/小程序/算法 |

### 1.3 实测成本（23 个真实 run，2026-11-25→11-27，含 4 次红）

529 个 job 实例、**46 734 job-秒 = 13.0 runner-小时**；单次 run 中位 **33.9 job-分钟**，
墙钟中位 **204s**，最长 912s，平均并行度 6.7×。按 443 run/月外推 ≈ **250 runner-小时/月**
（public 仓库，标准 runner 不计费；成本是队列与人的等待，不是账单）。

| job | 中位 | 占比 | 备注 |
| --- | --- | --- | --- |
| `python` | 189s | 9.3% | 每次装 torch 依赖；pip 缓存 |
| `backend-unit` | 179s | 8.7% | 全量 `mvn test` + 3 个 service |
| `web` | 141s | 7.2% | typecheck+test+build+lint+2 个契约脚本 |
| `web-e2e-system-states` | 128s | 6.7% | 只跑 1 个 spec（15 条） |
| `flyway-mysql` ×2 | 125s/115s | 11.6% | 同一批 11 个套件跑两遍（顺/逆序） |
| `issue17–24` ×8 | 68–106s | 27.6% | 每 job 一个 MySQL service，单类 `-Dtest=` |
| 其余 8 个 | 8–79s | 28.9% | |

**红的原因（4/23 = 17%）**：2 次真实失败（`flyway` 腿各 1 次，75 例中 5 败 / 71 例中 1 败，下一提交修复）；
2 次基础设施失败（`issue19-mysql` 两次：Maven Central **429**，`surefire-junit-platform:3.5.4` 拉不到，**0 条用例执行**）。
样本中 **0 次**是「代码正确但门禁误报」意义上的假红，但 **1/2 的红与测试本身无关**，代价是整轮 22 个上下文重跑。

### 1.4 审批事实

最近 20 个已合并 PR 中 **19 个提交了 0 条 review**（1 个有 1 条）。作者即唯一人工，GitHub 又不允许自审，
因此这些合并**只可能经 `--admin` 绕过 required review 与 CODEOWNERS**——「一次性例外」事实上已是默认路径。

---

## 2. 真实问题（按严重度）

| 级别 | 问题 | 证据 |
| --- | --- | --- |
| **P1** | **必需审批形同虚设，且被静默绕过**：95% 的合并没有评审记录，治理文档却仍宣称「需 1 人批准」。例外没有留痕规则，也没有区分风险等级。 | §1.4 |
| **P1** | **门禁自身不受保护**：`.github/workflows/**`、`scripts/check_*`、`pom.xml` 的 surefire 配置既不在 CODEOWNERS，也没有完整性检查。「改测试计划 / 伪造结果」只需一次普通提交，且不会被任何检查发现。 | CODEOWNERS 无 `.github/**`；`verify.yml` 由 PR 自身提供 |
| **P1** | **CI 与本地全量互不包含**：CI 从不跑 `VectorStoreConfigTest`、`RAGEvaluationTest`、`CoordinationMySqlTest`（env 门永远不开；`ControlCenterMultiProcessTest` 例外，它在 `backend-unit` 内 7/7 执行，只是用例数没进契约）；本地 L3 从不跑 web/miniapp/frontend/e2e/python/docs/flyway 顺序矩阵与 `product-contract` 的 3 个套件。两边都绿 ≠ 全部验过。 | 环境变量未设置；`verify.sh` 清单；2026-11-26 `backend-unit` 日志 |
| **P2** | **22 个必需上下文的粒度是「历史事故 × 1 job」**：`issue17…issue24` 8 个 job 占 27.6% runner 时间，每个都要单独起 Maven + MySQL；其中 `issue24-evaluation` 重跑 `PatientDraftTest`(41) 与 `PatientExplanationApiTest`(37)。 | §3 |
| **P2** | **重复执行**：78 条 Java 用例跑两遍；`scripts/tests` 的 `test_check_*.py` 在 4 个 job 里各跑一遍；`frontend` job 连续两次 `npm run lint:check`。 | §3.1 |
| **P2** | **strict=true + 22 上下文 = 每次合并都要 rebase 后全量重跑**；`allow_auto_merge=false`，人必须守着。样本里单个 PR 最多 6 次 run。 | §3.3 |
| **P2** | **测试规划与执行结果没有单一出口**：19 个 job 各自红/绿，没有「这次到底跑了哪些套件、有没有跳过」的汇总；`issue17` 还用另一套 `check_issue17_reports.py`。人只能逐 job 看。 | 两个 gate 实现 |
| **P3** | **规则文档过期**：`verification-levels.md` §7 写着「现有 CI 门禁一个也不删、不放宽」；同文件称「master 还没有协作代码」（早已合入）；`agent-worktree-conventions.md` 仍以 `origin/ui` 为合并目标（分支已删）；`CODING_STANDARDS.md` 说「现存 3 处 `new ObjectMapper()`」（实为 19 处）、「前端 lint 未进 CI」（已在 CI，且跑两遍）。 | 逐条实测 |
| **P3** | **`miniapp` 每次 PR 都跑却不必需**：一次小程序破坏可以绿灯合并。反过来它又消耗 1.8% 时间。 | §1.1 |
| **P3** | **无 `concurrency`**：连续 push 时旧 run 继续跑完（样本里出现过同分支 4–6 次 run），纯浪费。 | `verify.yml` |
| **P3** | **`CLAUDE.md` 与 `DESIGN.md` 重复**：约 90/250 行是设计系统复述，每个后端会话都要加载。 | 文件对照 |

---

## 3. 覆盖与重复矩阵（按运行参数 / 环境 / 隔离 / 顺序 / 产物核对，不看名字）

### 3.1 重复

| 对象 | 出现在 | 证据 |
| --- | --- | --- |
| `PatientDraftTest`（41 例） | `issue19-mysql`、`issue24-evaluation` | 两处 `-Dtest=` 与两份契约 |
| `PatientExplanationApiTest`（37 例） | `issue21-mysql`、`issue24-evaluation` | 同上；`evaluate_agent_collaboration.py` 的 `SUITES` 硬编码这两类 |
| `python3 -m unittest discover -s scripts/tests -p "test_check_*.py"` | `docs-contract`、`issue17`、`issue18`、`issue19` | 同一条命令 4 次 |
| `npm run lint:check` | `frontend` job 内连续两次 | 相邻两行 |
| Flyway 11 个套件 | `flyway-mysql` 两腿 | 有意为之（顺序无关性），成本 2× |

### 3.2 覆盖缺口（CI 绿也证明不了）

| 套件 / 用例 | CI | 本地 L3 | 说明 |
| --- | --- | --- | --- |
| `VectorStoreConfigTest`（RAG 向量库装配） | 从不 | 仅当 `:8000` 有 Chroma 且设了 env | 11-20 后无人跑过 |
| `RAGEvaluationTest`（2 例阈值） | 从不 | 仅 `RUN_RAG_EVALUATION_TESTS` | 检索口径无回归网 |
| `CoordinationMySqlTest`（14 例） | 从不（`graph-execution` 跑的是另一个 `CoordinationPocTest`） | 第三遍 | |
| `ControlCenterMultiProcessTest`（7 例，7 个 JVM） | **已跑**：`backend-unit` 7/7，49.1s（2026-11-26 日志） | 第二遍 | 会在 CI 执行，但**用例数未进契约**——删掉几条不会被发现。G0 初稿误记为「从不执行」，以此更正 |
| `CollaborationImagesTest`(7)/`ProductProviderLedgerTest`(5)/`AgentSessionAdmissionTest`(8) | `product-contract` | 不跑 | 本地全量不是 CI 超集 |
| web / miniapp / frontend / e2e / python / docker | CI 有 | 不跑 | 同上 |
| e2e 其余 13 个 spec（≈49 条） | 只跑 `system-states-r2.spec.ts` | 不跑 | 真实后端/MySQL 类 spec 长期无执行记录 |

### 3.3 改动 → 实际触发的验证（样本实测）

- 改一个 Java 纯函数：仍要跑 22 个上下文（含 8 个 MySQL job、e2e、python）→ 33.9 job-分钟。
- 只改文档：同样 22 个（`docs-contract` 只占 8s，其余照跑）。
- 改 `web/`：`web` + `web-e2e-system-states` + `frontend`（Vue 弃用线）+ miniapp 也会跑。
- PR 期间任何新 push 都会让整轮重来；strict 保护下，合并前还要再重来一次。

---

## 4. 目标架构：五域 + 单一聚合门禁

### 4.1 域划分（并行 job / matrix）

| 域 | 内容（现 job） | 目标形态 |
| --- | --- | --- |
| **Quality** | `docs-contract`、`backend-compile` | 1 个 job（8s + 35s），契约脚本只跑一次 |
| **Backend** | `backend-unit`、`product-contract` | 2 个 job（unit 全量；product 4 套件保留独立 DB） |
| **Integration** | `issue17–23`（7）、`collaboration`、`graph-execution`、`flyway ×2` | **合并为 2 个 matrix job**（患者/临床域、Agent/协作域）+ flyway 2 腿；同一 service 内多 schema，保留每类独立库 |
| **AI** | `python`、`watchdog`、`news-worker`、`issue24-evaluation` | `python`（含 watchdog 作为 matrix 腿）+ `news-worker`（path 触发）+ 评测并入 Integration 的 Agent 腿 |
| **Frontend** | `web`、`frontend`（Vue 已弃用）、`miniapp`、`web-e2e-system-states` | `web`、`miniapp`、`e2e`(按 spec 分组)；`frontend` 降为 Nightly 或按 path 触发 |

### 4.2 聚合门禁 `merge-gate`

一个新 job，唯一必需的宽松上下文（另外只保留 1–2 个「不可替代」的必需项，见 §7）：

1. `needs:` 上述所有域 job，`if: always()`；
2. 脚本读取 `needs.*.result`，**failed / cancelled / skipped（非预期）任一即红**，并打印每个域的实际结果；
3. 每个域 job 内部继续用现有 `check_surefire_skips.py` 契约（保留精确用例数与 skip 计数），**不弱化**；
4. 影响未知时回退全量：改动路径不在映射表内、或触及 `pom.xml` / `package.json` / `application*.yml` /
   `.github/**` / `scripts/**` / `src/test/**` / 迁移目录 / 共享配置 → 跑全部域，不做裁剪；
5. 防伪造：`gate-integrity` 子检查对本 PR 的每份报告做「本轮新生成」校验（复用 `check_surefire_result.py` 的思路），
   并核对 `.github/**`、`scripts/check_*`、surefire 配置的 diff——**命中即要求 owner 标签**，否则红；
6. 保护自身：把 `.github/workflows/**`、`scripts/check_*.py`、`scripts/verify*.sh`、`pom.xml` 的 surefire 段
   写进 CODEOWNERS，并加 `permissions: contents: read` 与 `concurrency`（同 PR 取消旧 run）；
7. 报告单一出口：`merge-gate` 输出一张「域 → job → 套件数/用例数/跳过数/耗时」表，附 artifact。

> 只做这一层「聚合 + 结果核对 + 自身保护」，**不新建调度系统、不改测试框架、不引入第三方门禁**。

---

## 5. 逐 job 处置建议

| job | 处置 | 理由 / 前置条件 |
| --- | --- | --- |
| `docs-contract` | **保留 + 去重**：`scripts/tests` 整套只在这里跑；其余 job 删除该步 | 8s，契约真源 |
| `backend-compile` | **并入 Quality**（与 docs 并行两步）或保留 | 35s，`-DskipTests` 只为早失败 |
| `backend-unit` | **保留** | 全量 unit + `contextLoads` 的唯一入口；加 `timeout-minutes: 20` |
| `product-contract` | **保留**（并入 Backend 域） | 独立库 + 4 套件契约 |
| `issue17-mysql` … `issue23-mysql` | **合并为 2 个 matrix job**（患者/任务/复核 vs 授权/解释/随访/草稿），每腿多 schema | 8 ×（35s 启动 + service）→ 2 ×；契约不变 |
| `issue24-evaluation` | **并入 Integration-Agent 腿**，或保留但**删掉两个重复 Java 套件**，改为消费 `issue19/21` 的 artifact | 78 例重复；评测脚本需要本轮报告 |
| `collaboration-mysql` | 并入 Integration-Agent 腿（matrix） | 33 例，75s |
| `graph-execution-mysql` | **保留独立**（触发器 + 3 类 + 进程死亡） | 106s；补上 `RealtimeGraphSpikeTest`/`CoordinationPocTest` 的契约计数 |
| `flyway-mysql` ×2 | **保留 2 腿**（顺序无关性是真实风险） | 11 套件 ×2；考虑只在「迁移/实体/JDBC」路径变化时跑第二腿 |
| `python` / `watchdog` | 合并为一个 job 的 matrix（`not slow` / `slow`） | 同一依赖缓存 |
| `news-worker` | 保留（path 触发），**改为必需 check 或明确豁免** | 现在既不挡合并，也没人看 |
| `web` | **保留**（typecheck/test/build/lint/fake-gate 脚本） | 产品前端主门 |
| `frontend`（Vue 弃用） | 降为 path 触发或 Nightly | 已弃用线；删掉重复 lint 步骤 |
| `miniapp` | **升为必需**或 path 触发但必需 | 37s；现在红了不挡 |
| `web-e2e-system-states` | 保留；把其余可离线 spec 编入 Nightly | 15 条/128s |
| `docker-lifecycle` | 保留（79s，脚本自测） | 保护测试资源生命周期 |

预计：必需上下文 **22 → 1（`merge-gate`）+ 3（`web`、`miniapp`、`gate-integrity`）**；
单 PR runner 时间 **34 → ≤20 job-分钟**（去重 78 例、8 个 MySQL job 合并、lint 去重、discover 4→1）。

---

## 6. R0–R3 风险分层（不可自证低风险）

| 层 | 例子 | 必需测试 | 设计评审 | 独立评审 | 所有者授权 | 发布验收 |
| --- | --- | --- | --- | --- | --- | --- |
| **R0** 文档/样式/文案 | `docs/**`、注释、纯 copy、依赖升级（patch） | Quality 域 | 否 | 否 | 否 | 否 |
| **R1** 常规功能 | 单页 UI、纯函数、管理端只读接口 | Quality + Frontend/Backend 域 | 视情况 | 否 | 否 | 否 |
| **R2** 数据/并发/外部模型/预算 | 迁移、事务、租约、预算闸门、真实模型调用、新闻精选 | 全部域 + Integration 真实 MySQL + 迁移空库用例 | **是** | **是**（或双轴代理评审记录在 PR） | **是**（一句话授权 + 范围） | 合并前 L3 或 CI 全绿 + 回滚说明 |
| **R3** 患者权限/医疗安全/敏感数据/生产部署/CI 安全 | `security/`、`diagnosistask/`、医生复核、封禁、`agent/rag`、proto、`.github/**`、部署脚本 | 全部域 + Nightly 全套 + 人工验收清单 | **是** | **是**（第二身份或明确指定的评审人） | **是** | 版本级验收 + 证据目录 |

**判定规则**：分层由**改动路径**机器判定（文件 → 层映射表入库，`scripts/` 里一个函数即可），
PR 模板自动填层；人对映射表有异议时只能**上调**，不能下调；映射表本身属 R3。

---

## 7. 三层验证与审批方案

### 7.1 三层

| 层 | 何时 | 内容 | 要求 |
| --- | --- | --- | --- |
| **PR（按影响）** | 每次 push | 五域中的受影响域；未知回退全量 | `merge-gate` 绿；R2 起加 CODEOWNERS 评审 |
| **Nightly（全量）** | 每日 | 全部 Java（含 3 个 CI 从不跑的套件；`ControlCenterMultiProcessTest` 已在 `backend-unit` 内执行）、web 全量 e2e（含真实后端 spec）、miniapp、algorithm 全量、news-worker、flyway 双序 | 红即在次日阻断新 PR 合并（或标记 `nightly-red` 需 owner 解除） |
| **Release（验收）** | 打 tag | Nightly 全套 + 部署演练 + 迁移矩阵 + 验收清单 | 全部证据入 `docs/release/`；不通过不发版 |

**医学安全 / 权限 / 台账类**（`diagnosistask`、`doctorreview`、`patient*`、`security`、NEWS 预算与回执）
**不得只依赖 Nightly**：只要 PR 触及这些路径，Integration 域必须在该 PR 内跑完对应真实 MySQL 套件。

### 7.2 审批（与保护设置配套，不改风险边界）

**建议采用「CODEOWNERS 按路径 + 单一聚合门禁」**：

- `required_approving_review_count: 0`；`require_code_owner_reviews: true`；
  CODEOWNERS 扩展为 **R2/R3 路径 + `.github/**` + `scripts/check_*`**（`newscuration/` 迁入选 R2）；
- 只把 `merge-gate`（+ `gate-integrity`、`web`、`miniapp`）设为必需 check；
- **R0/R1**：门禁绿即可合并，不再需要 `--admin`；
- **R2/R3**：仍需 CODEOWNERS 评审。单人维护下两种落地方式，二选一并写进治理文档：
  1. 第二 GitHub 身份（或指定代理评审）提交真实 review；
  2. 明确「所有者自审」为合法路径：PR 正文写授权句 + 评审清单，合并时**允许** `--admin`，
     但 `merge-gate` 仍必须绿，且审计脚本记录该次为例外（数量按周报出）。
- **禁止**：为绕过红门禁使用 `--admin`；本轮不修改保护设置，先由所有者选择再动。

---

## 8. 目标量化（以 §1 为基线）

| 指标 | 现状 | 目标 |
| --- | --- | --- |
| 必需状态上下文 | 22 | 1–4 |
| 单 PR runner 时间（中位） | 33.9 job-分钟 | ≤20（−41%） |
| 墙钟 p50 / p90 | 204s / 912s | ≤180s / ≤420s |
| 重复执行的 Java 用例 | 78 | 0 |
| CI 从不执行的套件 | 3 | 0（Nightly 兜底） |
| 红的原因中「与测试无关」占比 | 50%（2/4） | <20%（依赖预取 + 429 重试策略） |
| 无评审合并占比 | 95% | ≤15%（只余 R3 例外，且留痕） |
| 规则文档过期条目 | ≥5 | 0（每季度一条命令核对） |

---

## 9. 路线图与并行验收 / 回滚

| 阶段 | 动作 | 验收 | 回滚 |
| --- | --- | --- | --- |
| **G1（1 周）** | 只做无风险项：删 `frontend` 重复 lint；`scripts/tests` 4→1；加 `concurrency` + `permissions` + 缺失的 timeout；`miniapp` 升必需；Nightly 骨架跑齐 3 个缺口套件 | 同一 commit 上新旧 job 结果一致 | 单文件 revert |
| **G2（1–2 周）** | 合并 `issue17–23` 为 2 个 matrix job；`issue24` 去重（消费 artifact）；`merge-gate` 以「附加 check」上线，**旧 22 个仍必需** | 连续 10 个 PR：gate 与旧检查结论 100% 一致 | 删 gate job 即可，保护未动 |
| **G3** | 打开影响裁剪 + 未知回退；把 `.github/**`、`scripts/check_*` 加入 CODEOWNERS 与完整性检查 | 人为构造「改测试计划」PR 必须红 | 恢复全量触发 |
| **G4** | 保护设置切换：必需 22 → `merge-gate`+3；审批模型按 §7.2 由所有者选定 | 5 个 R0/R1 PR 无需 `--admin` 合并；1 个 R2 PR 走评审 | 一次性把 22 个上下文加回（API 脚本化） |
| **G5** | 发布验收层落地；旧 job 定义保留 30 天后删除 | 一次 tag 发版全流程 | 保留 tag 作为回滚点 |

并行验收原则：**G2/G3 期间新旧两套同时跑**，连续 10 个 PR 结论一致才进入下一阶段；
任何阶段出现「新门禁放过旧门禁拦下的东西」立即回滚该阶段。

---

## 10. 未确认项（UNKNOWN，不猜）

- Actions 缓存命中率与按 job 计的队列等待时间（API 不提供，需解析日志；本轮未做）。
- 本地 L3 当前实际耗时（11-20 实测 6m37s，此后新增 news 套件，**未重测**）。
- e2e 13 个未执行 spec 中哪些已失效（需逐条跑，属 Nightly 首轮工作）。
- `miniapp` 是否曾红过（样本全绿，无法证明现在能挡住破坏）。
- `frontend`（Vue）是否还有使用者（弃用声明是文档事实，非代码事实）。
- `CoordinationMySqlTest` 与 `CoordinationPocTest` 的覆盖是否重叠（未逐例比对）。
- 所有者对 §7.2 两种审批落地方式的选择——**这是本轮唯一必须由人决定的分叉**。

---

**本轮未做的事**：未改任何 workflow、未动分支保护、未删测试、未合并、未部署、未提交。
本文件为未跟踪文档，供讨论。
