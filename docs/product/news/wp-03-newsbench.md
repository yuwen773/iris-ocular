# WP-03 · 资讯精选、证据规则、付费调用回执与离线 NewsBench

WP-03 在 WP-02 可见原始资料池（`t_news_material_revision`）之上交付资讯精选（curation）、证据与许可校验、付费模型调用台账（reserve-before-IO）、未知结果恢复策略以及离线 NewsBench 评测基线（`V41__news_curation_and_receipts.sql`）。

> 本阶段刻意**不触碰** `t_news` / `t_news_audit`、`NewsController` / `NewsLifecycleService`（属于 WP-04），也不改动既有诊断 / Agent 模型构造路径；默认配置 `eye-diagnosis.news-curation.enabled: false`，且默认模型客户端为 fail-closed 的 `DisabledNewsCurationModelClient`。

## 1. 证据分级与校验规则

由 [NewsCurationPromptBuilder](../../../src/main/java/com/csust/eyediagnosis/newscuration/NewsCurationPromptBuilder.java) 与 [NewsEvidenceVerifier](../../../src/main/java/com/csust/eyediagnosis/newscuration/NewsEvidenceVerifier.java) 执行：

1. **可见性与快照冻结**：只允许读取整窗原子完成后写入 `t_news_material_revision` 的可见修订；未提交的 `t_news_batch_item` 直接拒绝（`REVISION_NOT_VISIBLE`）。
2. **摘要许可防御剥离**：仅当 `abstractLicense` 显式为 `CC0` 或 `CC-BY` 且摘要非空时保留摘要；否则在快照与提示词构造阶段强制剥离为 `null`，并将证据等级降为 `METADATA_ONLY`。
3. **严格模式解析**：复用无类型强转的 `newsProtocolObjectMapper`（禁用重复键、未知字段、尾随 token 与数字字符串强转）；`ACCEPT`/`NEEDS_REVIEW` 必须含非空标题、摘要、分类、局限性（`limitations`）及唯一声明（`claims`）。
4. **逐字引文绑定（Quote Grounding）**：每条 `claim` 必须显式标注来源字段（`title`、`abstract` 或 `trial`），且 `quote` 必须作为精确子串逐字出现在冻结证据快照的对应字段中（`title`/`abstract` 至少 6 个非空白字符，`trial` 至少 4 个）；无许可摘要引用触发 `UNLICENSED_ABSTRACT_QUOTE`，仅元数据未声明局限触发 `METADATA_ONLY_MISSING_LIMITATION`。
5. **医学边界与试验状态一致性**：拦截确诊、开药/处方、保证治愈、替代医生、`100%` 有效等违规医疗表达（`PROHIBITED_CLINICAL_CLAIM`）；针对 `clinicaltrials-gov` 记录，非 `COMPLETED` 或无 `PHASE3`/`PHASE4` 阶段不得宣称"已证实临床疗效"或"III 期完成"（`TRIAL_STATUS_MISMATCH`）。
6. **提示词注入防御**：系统提示词将所有外部字段标记为 `UNTRUSTED_DATA`；校验器拦截输出文本中的越权指令残留（`PROMPT_INJECTION_ARTIFACT`）。任何违反规则的输出状态落入 `REJECTED_EVIDENCE_VIOLATION`，不得晋升为接受候选。

## 2. 付费调用回执与未知结果恢复策略

由 [NewsCurationService](../../../src/main/java/com/csust/eyediagnosis/newscuration/NewsCurationService.java) 实现，跨事务统一按 `t_news_curation_budget -> t_news_model_call_receipt -> t_news_curation_candidate` 顺序加锁，所有租约/截止时间比较均基于 MySQL `UTC_TIMESTAMP(6)`：

- **Reserve-Before-IO**：发出任何外部模型请求前，先在事务内校验 `(materialRevisionId, policyVersion)` 幂等状态、每日上限（`dailyMaxCalls` / `dailyMaxTokens` / `dailyMaxCostMicros`）与熔断标志，原子预扣预算并写入 `t_news_model_call_receipt(state='RESERVED')`；随后在独立短事务中转为 `DISPATCHED` 并记录 `dispatchedAt` 与 `deadlineAt`。
- **实际成本结算与鉴权熔断**：外部单次调用（严禁隐式重试）返回 `SUCCEEDED` 时按实际 tokens/cost 结算并释放预扣差额；返回明确失败（`FAILED_DEFINITIVE`）释放预扣额度；遇到鉴权失败（`authFailure=true`）立即拉闸 `circuitBroken=1`，阻止后续无效请求，需人工 `resetCircuitBreaker()` 后才可恢复。
- **未知付费结果保守占用（Unknown-Outcome Hold）**：当 `DISPATCHED` 调用发生超时、连接重置或未知异常时，状态落为 `OUTCOME_UNKNOWN`，候选记录标记为 `NEEDS_RECONCILIATION`，**预扣额度保持占用不释放**，且同一 `(materialRevisionId, policyVersion)` 在对账前禁止再次发起付费调用。
- **超时回收与人工对账**：
  - `reclaimStaleCalls()`：过期仍为 `RESERVED` 的调用判定从未出站，转为 `ABORTED_PRE_IO` 并释放预算；过期仍为 `DISPATCHED` 的调用一律转为 `OUTCOME_UNKNOWN` 并保守占用预算。
  - `reconcileUnknownCall()`：支持三种显式核销路径——`CONFIRMED_CHARGED_WITH_OUTPUT`（按实结算、校验证据并落定候选状态）、`CONFIRMED_CHARGED_NO_OUTPUT`（转为 `FAILED_CHARGED` 并按实扣费）、`CONFIRMED_NOT_CHARGED`（转为 `FAILED_UNCHARGED`、释放预扣并允许后续显式重试）。

## 3. NewsBench 离线评测基线

冻结评测集位于 [`contracts/news/newsbench/v1.dataset.json`](../../../contracts/news/newsbench/v1.dataset.json)（18 条用例覆盖 `pubmed`、`europe-pmc` 与 `clinicaltrials-gov`，含 11 条 `TRIAGE` 正反用例与 7 条覆盖全部违规码的 `ADVERSARIAL` 攻击用例），由 [NewsBenchRunner](../../../src/main/java/com/csust/eyediagnosis/newscuration/NewsBenchRunner.java) 验证：

- **离线门禁验证（L1 + L2 MySQL）**：18/18 用例通过，`precisionBps=10000`、`recallBps=10000`、`evidenceCatchBps=10000`、`licenseComplianceBps=10000`、`injectionCatchBps=10000`、`gatePassed=true`（具体执行数据见 [NewsBenchOfflineTest](../../../src/test/java/com/csust/eyediagnosis/newscuration/NewsBenchOfflineTest.java) 与 [NewsCurationMySqlIntegrationTest](../../../src/test/java/com/csust/eyediagnosis/newscuration/NewsCurationMySqlIntegrationTest.java)）。
- **未运行项（NOT_RUN）**：真实付费 LLM 在线评测、真实外部信源联网采集与仓库全量 L3 (`scripts/verify.sh`) 仍未运行，需后续独立授权后执行。
