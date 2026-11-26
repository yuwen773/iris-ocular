# WP-04 · 审核与发布、证据快照绑定与提交后缓存失效

WP-04 在 WP-02 原始资料池（`t_news_material_revision`）与 WP-03 精选候选（`t_news_curation_candidate`）之上，完成编辑建稿、内容修订留痕、服务层统一发布门禁与事务提交后 Redis 缓存失效（`V42__news_editorial_publish_and_revisions.sql`）。

## 1. 证据绑定与不可变内容修订

- **`t_news` 扩展列**：新增 `curationCandidateId`、`materialRevisionId`、`evidenceSnapshotSha256`、`currentRevisionNo`（`DEFAULT 0`）与 `publishedRevisionNo`。既有手工建稿与发布入口保留兼容；人工创建的文章上述三项证据绑定列保持 `NULL`。
- **从精选候选建稿（`POST /news/from-candidate/{candidateId}`）**：仅接受 `CURATED_ACCEPTED` 或 `CURATED_NEEDS_REVIEW` 且无违规项、模型回执为 `SUCCEEDED`、原始资料修订摘要一致且冻结快照 SHA-256 校验通过的候选；仅生成 `DRAFT` 或 `PENDING_REVIEW`（由 `submitForReview` 控制），禁止自动直接发布。
- **不可变内容修订（`t_news_revision` 与 `GET /news/{id}/revisions`）**：创建、候选建稿、编辑、状态流转、封面更新及逻辑删除均顺序追加不可变修订行，保存当期正文、`contentSha256`、操作人、审批意见与冻结原始证据快照 `evidenceSnapshotJson`。既有文章的证据绑定字段创建后禁止修改或替换。

## 2. 服务层统一发布门禁

由 [NewsLifecycleService](../../../src/main/java/com/csust/eyediagnosis/service/NewsLifecycleService.java) 集中把关单篇新建（`applyEdit` -> `create`）、单篇编辑/审批（`applyEdit`）及批量状态变更（`changeStatusBatch`）：

1. **禁止未过门禁发布**：候选状态为 `REJECTED_EVIDENCE_VIOLATION`、`CURATED_REJECTED`、`NEEDS_RECONCILIATION`，或快照 SHA-256 / 原始资料修订摘要 / 模型回执状态不匹配时，任何发布入口均直接拒绝且不写库。
2. **人工复核强制**：`CURATED_NEEDS_REVIEW` 候选禁止从 `DRAFT` 跳过 `PENDING_REVIEW` 直接发布，进入 `PUBLISHED` 时必须由已认证操作人填写非空审核意见 `note`。
3. **医学安全与防注入底线**：复用 [NewsEvidenceVerifier](../../../src/main/java/com/csust/eyediagnosis/newscuration/NewsEvidenceVerifier.java) 统一规则，所有待发布及已发布状态下修改的正文/标题/摘要一律拦截违禁临床承诺与提示词注入指令。

## 3. 提交后缓存失效与撤回可见性保障

由 [NewsListCacheService](../../../src/main/java/com/csust/eyediagnosis/service/NewsListCacheService.java) 与 [NewsController](../../../src/main/java/com/csust/eyediagnosis/controller/NewsController.java) 协同实现：

- **事务内推进 DB 纪元、提交后失效 Redis**：写操作按规范锁顺序最后推进 `t_news_cache_epoch`（`scope='news_list'`），同一事务内的批量更新合并为单次 `afterCommit()` Redis 世代递增（`diagnosis:newsListGeneration`）；事务回滚时不触发 Redis 变更。
- **Redis 失效失败时的撤回可见性（Fail-Closed）**：若 `afterCommit()` 时 Redis 递增失败或 Redis 暂不可用，MySQL 中的下架/软删已安全提交且不会抛 500 掩盖撤回事实；后续读请求检测 `dbEpoch > redisEpoch` 后直接使用数据库纪元绕过旧缓存键并尽最大努力回写修复 Redis 世代，确保已撤回或已删除资讯绝不通过旧缓存继续对外暴露；Redis 完全不可达时自动降级直读 MySQL。

## 4. 验证范围

- **L1**：[NewsListCacheServiceTest](../../../src/test/java/com/csust/eyediagnosis/service/NewsListCacheServiceTest.java)、[NewsLifecycleServiceTest](../../../src/test/java/com/csust/eyediagnosis/service/NewsLifecycleServiceTest.java) 与全套现有 `News*` 控制器/服务单测。
- **L2（真实 MySQL 8 + Redis 7）**：[NewsEditorialPublishMySqlIntegrationTest](../../../src/test/java/com/csust/eyediagnosis/service/NewsEditorialPublishMySqlIntegrationTest.java) 8 个端到端集成用例，覆盖 V42 迁移、候选建稿、多入口发布门禁、手工编辑/批量流转/软删、触发器回滚零缓存失效、Redis 失效注入与全宕机下撤回立即不可见、批量部分失败与不可变修订历史。
- **未运行项（`NOT_RUN`）**：真实付费 LLM 在线评测、真实外部信源联网采集与全量 L3 (`scripts/verify.sh`)。
