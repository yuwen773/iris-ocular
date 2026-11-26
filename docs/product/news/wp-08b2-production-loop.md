# WP-08B.2 · 最小资讯生产闭环

WP-08B.0 / B.1 建立了统一模型调用层与证据契约（V45），但冻结结果只停在回执里：没有人工可写的证据账本，
精选结果无法进入人工编辑，Research Insight 也长期不可达。本轮把这一段补齐，并且只补这一段。

**范围**：人工证据核验账本（V46）、P05 单一模型策略 A 的生产命名空间调用、冻结回执到待审候选的
幂等转换、撤回后重新核验的闸门、十份真实资料的离线端到端。**不含**：真实供应商调用、双模型流水线、
自适应路由、多 Agent、语义聚合、NewsBench 扩量、自动发布、生产部署。

## 1. 人工证据核验账本

`t_news_evidence_verification`（V46）只允许管理员写入，行**只追加**，撤回是给同一行打 `revokedAt`。
生成列 `activeKey = IF(revokedAt IS NULL, CONCAT(materialRevisionId, ':', attribute), NULL)` 上的唯一索引，
把「同一资料修订、同一属性最多一条生效核验」交给数据库裁决，两名管理员并发登记不会都成功。

| 属性 | 允许的值 | 允许的核验方法 |
| --- | --- | --- |
| `PEER_REVIEW` | `VERIFIED` / `NOT_PEER_REVIEWED` | PubMed 记录、Europe PMC 记录、同行评审声明 |
| `INTEGRITY` | `VERIFIED` / `CONCERN` / `RETRACTED` | 撤稿声明、PubMed / Europe PMC 记录 |
| `STUDY_DESIGN` | `INTERVENTIONAL` / `OBSERVATIONAL` / `EXPANDED_ACCESS` / `OTHER_VERIFIED` | PubMed / Europe PMC / ClinicalTrials.gov 记录 |
| `CONTENT_SCOPE` | 仅 `LICENSED_RESULTS` | DOI 落地页、授权声明、PubMed / Europe PMC 记录 |
| `LICENSE` | `CC0` / `CC_BY` | 授权声明、DOI 落地页 |

写入前 `NewsEvidenceLedger.validate` 逐项校验：值与方法在允许集合内；URL 必须是 https、主机属于该方法
的固定集合（`pubmed.ncbi.nlm.nih.gov`、`europepmc.org`、`clinicaltrials.gov`、`doi.org`、
`retractionwatch.com`）；`sourceVersion` 是受限标识符；备注 ≤1024 字符且不含 `<`。两类越权直接拒收：

- `LICENSE` 只能**确认**资料快照里已声明的许可，声明为 UNKNOWN 或与之冲突时返回 `EVIDENCE_LICENSE_CONFLICT`；
- `CONTENT_SCOPE` 在资料没有可授权摘要时返回 `EVIDENCE_LICENSE_UNKNOWN`，在试验登记资料上返回
  `EVIDENCE_SCOPE_NOT_APPLICABLE`——登记记录没有「授权全文范围」这回事。

生效行的紧凑视图（属性、值、方法、URL、来源版本、核验人、核验时间）序列化后取 SHA-256，成为**证据叠加摘要**。
它同时进入模型调用身份（`Identity.evidenceOverlaySha256`）与候选行（`evidenceVerificationSha256`），
所以「这次付费调用当时看到的是哪一套核验」事后可复算。回放冻结输出时用的是身份里冻结的叠加，而不是当前账本。

## 2. Research Insight 的最小证据完成

路由门禁本身没有放松（`NewsEditorialEvidence.route` 未改）。Research Insight 仍要求四个条件**同时**成立：
内容范围是 `LICENSED_RESULTS`、许可已知且摘要非空、同行评审 `VERIFIED`、完整性 `VERIFIED`、研究设计非 UNKNOWN。

区别只在于这些属性现在可以由**人工核验记录**提供，而不再永远是 `UNKNOWN`：

- 模型写不出这些属性：`NewsEditorialEvidence.from` 只从快照与账本叠加推导，`Identity` 与候选都不接受模型给出的属性；
- 改枚举或降门槛不会生效：`route` 的条件与顺序未变，缺任何一项仍然只能得到 `RESEARCH_RADAR`；
- 核验必须能落到一条真实记录上：值、方法、主机三者互相约束，登记不到允许的主机就等于没核验。

## 3. 单模型策略 A

一次 P05 联合调用完成评分与生成，复用 B1 的同一套客户端、回执、预算与熔断（`NewsCurationService.runModel`）。
`runModel` 在准备调用时读取该资料修订的生效叠加重算摘要，并把它冻结进调用身份。Java 决定三件事：
路由（`eligibleType` 由证据决定）、内容形态（P02 / P03 / P04 的章节与字段）、以及输出是否越界。
P05 输出里出现与研究无关的类型、改写登记事实、或包含临床承诺与注入标记时，`validatedOutput` 返回 `null`。

## 4. 冻结回执 → 待审候选

`NewsCurationService.materializeCandidate(callId, actor)` 只接受同时满足下列条件的回执：

1. `state = SUCCEEDED`，且冻结响应与逻辑调用摘要都已写入；
2. 身份命名空间是 `production`、阶段 `JOINT`、Prompt `P05`，资料修订与回执一致——实验命名空间的结果一律拒绝；
3. 身份冻结的证据叠加摘要等于该资料修订**当前**的生效摘要，否则 `EVIDENCE_VERIFICATION_STALE`；
4. 冻结响应仍能通过当前契约校验（`validatedOutput`），否则 `FROZEN_OUTPUT_INVALID`。

转换**只做转换，不发明判断**：候选状态只会是 `CURATED_NEEDS_REVIEW`（有内容）、`CURATED_REJECTED`（无内容）
或 `REJECTED_EVIDENCE_VIOLATION`（生成文本命中临床承诺 / 注入 / 无依据疗效），永不写 `CURATED_ACCEPTED`。
候选行记录冻结快照、快照摘要、证据叠加 JSON 与摘要、`callId`、`materializedAt` / `materializedBy`，
以及编辑体裁 `contentType`；`category` 留空，由人工编辑时填写——分类是编辑判断，不是模型输出。

幂等与防覆盖：同一 `callId` 重复转换返回同一候选（`created=false`），不再请求模型；同一资料修订与策略版本
已经有另一条调用生成的候选时返回 `CANDIDATE_ALREADY_MATERIALIZED`，避免覆盖已进入人工编辑的内容。

## 5. 人工编辑与发布前的复核

候选可以随时进入草稿（`createDraftFromCandidate`），但**建稿与发布都要过同一道闸门**
（`NewsLifecycleService.lockAndVerifyCandidate`）。对带冻结身份的回执，闸门额外要求：

- 身份属于 `production` + `JOINT`，且资料修订一致；
- 候选绑定的 `evidenceVerificationSha256` 与身份冻结值完全一致；
- 账本**此刻**的生效摘要仍等于该值——核验被撤回或重新登记后，旧冻结结果立即不可用于建稿，
  提示「证据核验已撤回或变更，需重新核验后才能建稿」。

重建核验会追加新行（时间戳不同，摘要也不同），所以旧回执不会自动恢复可用：必须重新发起一次调用并冻结新结果。
所有发布路径仍是人工操作，发布前冻结证据、违规列表与人工审核检查保持原样。

## 6. 管理端接口

`NewsWorkbenchController` 新增四个只对管理员开放的端点（读接口 `Cache-Control: no-store`）：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/news-workbench/materials/{materialRevisionId}/evidence-verifications` | 查看核验历史（含已撤回行） |
| POST | `/api/admin/news-workbench/materials/{materialRevisionId}/evidence-verifications` | 登记一条核验 |
| POST | `/api/admin/news-workbench/evidence-verifications/{verificationId}/revoke` | 撤回一条核验 |
| POST | `/api/admin/news-workbench/receipts/{callId}/materialize` | 冻结回执转待审候选（幂等） |

四个端点都在控制器安全测试里覆盖：非管理员得到 `NO_AUTH_ERROR` 且不触达服务层，读接口带 `no-store`，
写接口需要同源 `Origin`，服务层业务拒绝原样映射为统一响应体。候选列表与详情新增 `contentType` 字段。

## 7. 十份真实资料

`src/test/resources/news/curation/b2/real-materials.json`，2026-11-26 采集，五类齐全：

| 类别 | 份数 | 来源与要点 |
| --- | --- | --- |
| Research Insight | 3 | Europe PMC 开放获取记录（CC BY），四项核验齐备后路由到 Insight：正常眼压性青光眼针刺 RCT、进展期青光眼治疗五年随访、SEE 用药辅导 RCT |
| Research Radar | 2 | CC BY 摘要可引用、但未登记同行评审：AMD 预后模型系统综述、甲状腺自身抗体与 AMD 横断面研究 |
| Trial Watch | 2 | ClinicalTrials.gov 登记事实（经 WHO ICTRP 镜像取得）：青光眼引流装置对比研究（招募中）、布罗鲁珠单抗三期试验（已完成） |
| 撤稿 | 1 | PLOS ONE 撤稿论文，登记 `INTEGRITY=RETRACTED` 后路由为 SKIP |
| 仅题录 | 1 | 未声明开放许可的综述条目，只能以题录生成 Radar |
| 与眼科无关 | 1 | 术后芳香疗法试验，模型脚本输出判为 SKIP，不生成内容 |

采集方式与限制如实记录在文件头：Europe PMC 直连可用；ClinicalTrials.gov 直连在本环境被网络策略阻断，
登记事实经 WHO ICTRP 的官方镜像页取得，`registryRefreshedOn` 字段保留镜像页的刷新日期。
`scriptedOutput` 是离线测试的脚本化模型输出，**不是**真实供应商调用结果。

`NewsRealMaterialsOfflineE2ETest` 回放这十份资料：证据路由与类别一一对应；去掉人工核验后同一份资料
只能落到 Radar（证明 Insight 来自核验而不是改枚举）；脚本化输出通过 P05 契约；被判 SKIP 的资料内容必须为空，
注入非空内容会被契约拒收。

## 8. 验证

| 层 | 内容 | 结果 |
| --- | --- | --- |
| L1 | `NewsEvidenceLedgerTest`（4 例）、`NewsWorkbenchControllerSecurityTest`（7 例）、`NewsRealMaterialsOfflineE2ETest`（2 例） | 通过 |
| L2 | 真实 MySQL 8：`NewsCurationMySqlIntegrationTest` 16 例、`NewsWorkbenchMySqlIntegrationTest` 11 例、`NewsEditorialPublishMySqlIntegrationTest` 8 例 + L1 用例（7 类 53 例全部实跑，`verify-fast.sh --db`，`RESULT: PASS — L2`） | 通过 |
| 迁移 | V46 在一次性 schema 上完整迁移（V1→V46，`flyway_schema_history` 最新版本 46、success=1） | 通过 |
| HTTP 回环 | 真实 Spring Boot 进程（一次性 MySQL 8 + Redis + 真实 Tomcat，`:18111`）上用 curl 走完四个端点：管理员会话登录、读接口 `Cache-Control: no-store`、登记五类核验、重复登记与越权 URL 被拒、撤回后 `active=false` 且历史保留、二次撤回报错、未知回执转换返回「模型调用回执不存在」、匿名请求 `40100 未登录`、跨源 POST `40300 CROSS_ORIGIN_BLOCKED` | 通过 |
| 浏览器 E2E | 本切片无任何前端文件改动，四个端点暂无 UI 消费者（`web/` 中无引用），因此不新增浏览器用例；端点行为以上述 HTTP 回环与控制器安全测试覆盖 | 不适用 |

## 9. 未启用与后续条件

- **真实供应商调用 0 次**：本轮所有模型输出都是脚本化夹具。付费联调需要单独授权，方案见
  [WP-08B.2 付费联调方案](wp-08b2-paid-integration-plan.md)。
- **生产开关仍为关闭**：`eye-diagnosis.news-curation.enabled`、`eye-diagnosis.news-ingestion.enabled`、
  自动采集调度默认 `false`。「合并进主干」不等于「在生产启用」。
- **仍未做的规模工作**：NewsBench 扩量、双模型对照、自适应路由、语义聚合、自动发布。
- **登记数据来源限制**：ClinicalTrials.gov 直连在本环境不可用，正式 Worker 需要在具备正常公网出站的环境复核。
