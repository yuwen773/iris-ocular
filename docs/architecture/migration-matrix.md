# Flyway 迁移矩阵（Java 线）

本文件记录 `codex/realtime-product-acceptance` 与 `master` 的迁移历史整合方式。执行任何数据库升级前，先按目标库的 `flyway_schema_history` 对照本矩阵。

## 0. 数据库最低版本

| 能力 | 最低版本 | 原因 |
| --- | --- | --- |
| Flyway 迁移 | MySQL 8.0 | 与 CI / 部署基线一致 |
| 数据分析 `/api/stats/admin/analytics` | **MySQL 8.0** | 复核时延使用 CTE（`WITH latency AS …`）与窗口函数（`ROW_NUMBER() / COUNT() OVER`），5.7 不支持 |

启动门禁 `com.csust.eyediagnosis.config.MySql8Requirement` 在应用启动时读取实际连接版本，
低于 8.0 直接失败并提示数据源位置，避免等到统计请求才返回 `50000 系统错误`。
紧急情况下可用 `eye-diagnosis.database.require-mysql8=false` 关闭门禁，但统计接口仍然不可用。

本机若同时装有 3306（5.7）与 3307（8.0），开发数据源必须指向 **3307**。
CI 的 MySQL 作业使用 `mysql:8.0` / `mysql:8.0.36`，`scripts/verify.sh` 与
`scripts/verify-fast.sh` 默认 `mysql:8.0.26`；部署前确认目标实例版本 ≥ 8.0。

## 1. 已部署基线

2026-11-13 之前，两条线各自维护了一套版本号：

| 版本 | master 基线（生产/主线） | Java 线原迁移 | 整合后 |
| --- | --- | --- | --- |
| V1–V9 | 既有安全/缓存/MCP 迁移 | 相同 | 不变 |
| V10 | 无 | `V10__diagnosis_task.sql` | `V18__diagnosis_task.sql` |
| V11 | 无 | `V11__controlled_reference.sql` | `V19__controlled_reference.sql` |
| V12 | 无 | `V12__patient_draft.sql` | `V20__patient_draft.sql` |
| V13 | 无 | `V13__doctor_review.sql` | `V21__doctor_review.sql` |
| V14 | 无 | `V14__patient_authorization.sql` | `V22__patient_authorization.sql` |
| V15 | `V15__baseline_legacy_tables.sql` | `V15__bootstrap_user_table.sql`（未跟踪） | 采用 master V15；删除重复 bootstrap |
| V16 | `V16__diagnosis_evidence_and_audit.sql` | `V16__patient_draft_message_timing.sql` | 采用 master V16；Java 线重排为 V23 |
| V17 | `V17__news_business_model.sql` | `V17__autonomous_draft_workflow.sql` | 采用 master V17；Java 线重排为 V24 |
| V18 | 无 | `V18__collaboration_events.sql` | `V25__collaboration_events.sql` |
| V19 | 无 | `V19__graph_execution.sql` | `V26__graph_execution.sql` |
| V20 | 无 | `V20__bootstrap_agent_history.sql` | `V27__bootstrap_agent_history.sql` |
| V28 | 无 | 无 | `V28__agent_tool_audit.sql`（普通聊天工具调用结构化审计，默认关闭持久化） |
| V29 | 无 | 无 | `V29__knowledge_index_version.sql`（`t_knowledge_document` 增加可空 `indexVersion`，标记一次文档处理的服务端 UUID） |
| V30 | 无 | 无 | `V30__agent_message_variants.sql`（`t_agent_message` 增加可空 `replyToId`、`variantId`、`selectedVariantId` 与索引 `idx_agent_message_reply`；旧行全部为 NULL，读侧按「无版本」处理） |
| V31 | 无 | 无 | `V31__diagnosis_run_cancellation.sql`（新增 `t_diagnosis_run`：一次判读运行的 PENDING / DONE / CANCELLED 状态，「取消判读」与落库的仲裁点；不改动任何既有表） |
| V32 | 无 | 无 | `V32__knowledge_candidate_index_version.sql`（`t_knowledge_document` 增加可空 `pendingIndexVersion`、`pendingUpdatedTime`：文档更新先写候选版本，成功后再原子切换 `indexVersion` 与文件元数据；候选失败则文档行仍完整描述旧文件、新对象被删除） |
| V33 | 无 | 无 | `V33__diagnosis_task_restrict_reason.sql`（`t_diagnosis_task` 增加可空 `restrictReason`：任务进入 `RESTRICTED` 时记录准入拒绝的原因码） |
| V34 | 无 | 无 | `V34__diagnosis_evidence_semantics.sql`（`t_diagnosis` 与 `t_inference_cache` 各增加六个可空列：`classificationProbability`、`calibrationVersion`、`imageQualityState`、`imageQualityVersion`、`imageQualityCalibrationState`、`patientConclusionAllowed`——把分类概率/校准/图像质量/能否面向患者呈现拆成互不冒充的字段） |
| V35 | 无 | 无 | `V35__miniapp_wechat_binding.sql`（新增 `t_user_wechat`：`(appid, openid)` 唯一键 → `userId` 的小程序身份绑定表，供 Bearer 令牌通道首登建号；不改动任何既有表，`session_key` 不落库） |
| V36 | 无 | 无 | `V36__control_center_core.sql`（配置中心核心：新增 `t_config_draft`、`t_config_release`、`t_config_active_pointer`、`t_config_request`、`t_config_audit_event` 五张表，并预置活跃指针单例行 `(1, NULL)`；不改动任何既有表，不建外键） |
| V37 | 无 | 无 | `V37__control_center_publish_approval.sql`（发布审批：新增 `t_config_publish_request`，把「申请时的草稿 revision + 载荷摘要 + 验证摘要 + 期望活跃版本」钉死；唯一键 `(actorId, requestId)`；不改动任何既有表，不建外键） |
| V38 | 无 | 无 | `V38__user_feedback.sql`（新增 `t_user_feedback`：用户反馈的正文、分类、关联对象、状态机、回复与私有截图 key；不改动任何既有表） |
| V39 | 无 | 无 | `V39__user_notification.sql`（新增 `t_user_notification`：站内通知的类型、发送时定稿的文案、关联指针与已读时间；不改动任何既有表） |
| V40 | 无 | 无 | `V40__news_ingestion_pool.sql`（WP-02 新增资讯采集调度与资料池 9 张新表：`t_news_source`、`t_news_source_cursor`、`t_news_collection_task`、`t_news_collection_lease`、`t_news_collection_batch`、`t_news_batch_item`、`t_news_worker_receipt`、`t_news_material`、`t_news_material_revision`；不改动任何既有表） |
| V41 | 无 | 无 | `V41__news_curation_and_receipts.sql`（WP-03 新增资讯精选与付费调用台账 4 张新表：`t_news_curation_budget`、`t_news_model_call_receipt`、`t_news_curation_candidate`、`t_news_bench_run`；不改动任何既有表） |
| V42 | 无 | 无 | `V42__news_editorial_publish_and_revisions.sql`（WP-04 为 `t_news` 追加可空/带默认值的证据绑定与修订序号列 `curationCandidateId`、`materialRevisionId`、`evidenceSnapshotSha256`、`currentRevisionNo`、`publishedRevisionNo`，并新增不可变修订表 `t_news_revision` 与持久缓存纪元表 `t_news_cache_epoch`） |
| V43 | 无 | 无 | `V43__news_collection_task_event.sql`（WP-07G 新增采集窗口任务的操作审计表 `t_news_collection_task_event`：记录 `action`、`fromStatus`、`toStatus`、操作者与备注；不改动任何既有表） |

整合链为 **V1–V9 + V15–V43**。当前 master 为 V39，本分支追加 V40–V43。
V28 只新增 `t_agent_tool_audit`，不修改此前任何已应用脚本；该表独立于 `t_agent_message`，
不存原始工具参数、Prompt 或患者文本。V29 只给既有表加一列可空列，不改写任何既有行；
历史行的 `indexVersion` 为 NULL，**在按版本准入的检索里等于「不能证明处理完整」**，
必须重新处理后才可能重新成为证据（见 `docs/release/upgrade-rollback-manual.md` §2）。
V30 只给 `t_agent_message` 加三列可空列与一个索引，不改写任何既有行；旧历史没有
`replyToId`/`variantId`，读侧按「单版本、无重新生成」显示，行为与升级前一致。
V31 只新增 `t_diagnosis_run` 一张表，不碰任何既有表；升级前已落库的判读记录没有
对应的运行行，行为与升级前一致（取消接口对它们回 `NOT_FOUND`，不会误删医疗记录）。
V32 只给 `t_knowledge_document` 加两列可空列：既有行 `pendingIndexVersion` 为 NULL，
读侧按「没有候选版本」处理，检索行为不变。V33 只给 `t_diagnosis_task` 加一列可空列：
既有 `RESTRICTED` 任务的原因码为 NULL，前端退回只显示「受限」。
V34 只给 `t_diagnosis` 与 `t_inference_cache` 各加六个可空列，既有行全部为 NULL，
读侧按「旧口径」处理。
V36 只新增配置中心五张表并插入一行活跃指针，不碰任何既有表；配置中心由
`eye-diagnosis.control-center.enabled` 控制，**默认关闭**，升级后不开启即与升级前行为一致。
V37 只新增 `t_config_publish_request` 一张空表，不碰任何既有表；审批与发布能力同样受
`eye-diagnosis.control-center.enabled` 控制，且发布人白名单默认为空——升级本身不会
让任何人获得发布权。
V38、V39 同样只新增表（`t_user_feedback`、`t_user_notification`），既有表一行未改：
升级前已存在的用户、判读与资讯数据在升级后行为与升级前一致；这两个功能在空历史库上
就是两张空表。

> **V36 列注释漂移（WP-01D 审计 A-08，不改迁移）**：`t_config_request.action` 的列注释
> 只列了 `DRAFT_CREATE / DRAFT_UPDATE / DRAFT_VALIDATE`，而 WP-01C 起实际还会写入
> `PUBLISH_REQUEST / PUBLISH_APPROVE / PUBLISH_REJECT / ROLLBACK`（列宽 `VARCHAR(32)` 足够）。
> 同表 `resultCode` 的注释「首次执行的结果码（0 表示成功）」也不准确：失败时整行随事务
> 回滚，因此该列**只会是 0**（失败不消耗 `requestId` 是设计目标）。两处都只是注释，不影响
> 行为；**已应用的迁移不得改 checksum**，故在此登记而不修改 V36。

> **配置中心迁移的编号变更（2026-11-24，已处理两次）**：WP-01 分支最初把这两条迁移写作
> V32 / V33，当时（审计基线 `5dc2eac5`，迁移头 V31）这两个号确实空闲。但 master 已前进到
> `c90b0caa`，V32–V34 被占用（`V32__knowledge_candidate_index_version.sql`、
> `V33__diagnosis_task_restrict_reason.sql`、`V34__diagnosis_evidence_semantics.sql`）。
> 第一次改号为 V35 / V36；同日核对在途分支时发现**小程序后端分支（PR #160）也占用 V35**
> （`V35__miniapp_wechat_binding.sql`），同号两脚本会让 Flyway 直接启动失败，
> 因此第二次改号为 **V36 / V37**，**DDL 内容一字未改**，两个迁移文件头部留了
> 「重编号记录」注释以便追溯。
> 若目标库已跑过本分支旧编号的 V32/V33 或 V35/V36（本地/演示库），按重建处理，
> **不得 `repair` 掩盖**。
> 详见 `docs/product/wp-01/wp-01d-final-audit.md` §8.4。

### 1.1 编号协调：V35–V42 的全局分配（2026-11-24）

同一时刻有多条分支在飞，Flyway 版本号必须全局唯一且**逐号递增**（`out-of-order=false`）。
当前分配如下，任何分支要新增迁移前先对照本表取号：

| 版本 | 归属 | 状态 |
| --- | --- | --- |
| V35 | 小程序后端微信绑定（PR #160，`V35__miniapp_wechat_binding.sql`） | 已合入 master |
| V36 / V37 | 配置中心核心五表 + 发布审批单（PR #164） | 已合入 master |
| V38 / V39 | 用户反馈 + 站内通知（本分支，PR #166） | 本分支 |
| V40+ | 新闻摄取与回执（PR #168，V40–V42 已提交，WP-07G 再追加 V43） | 在途 |

V32–V34 已随 PR #156 合入 master。V35 最早被反馈分支占用（当时叫
`V35__user_feedback.sql`），随后让给小程序分支；配置中心也让开 V35 后取 V36/V37，
反馈分支因此落到 V38/V39。由此产生一条顺序约束：

- **`spring.flyway.out-of-order` 保持 `false`**，所以一旦某个库先应用了高版本，
  随后到达的低版本会被 Flyway 判为 out-of-order 而拒绝启动。
- 因此**合并顺序必须是 V35（小程序）→ V36/V37（配置中心）→ V38/V39（反馈）**；
  不能靠打开 out-of-order 掩盖。若某个库已经越过顺序，**重建库**。
- **新闻线还没开 PR，它开 PR 前必须把 V39–V41 整体后移到 V40–V42**（它的 V39 与本分支的
  V39 同号，同号两脚本会让 Flyway 启动失败）。按「谁先合谁保留」，本分支已进入评审、
  新闻线仍在开发，故由新闻线让号。新闻线随后开了 PR #168，并在 WP-07G 追加 V43，
  实际占用 **V40–V43**。
- 重编号成本很低：改文件名 + `FlywayMySqlIntegrationTest` 里的版本与迁移数断言，
  脚本内容不含自身版本号（本分支已按此从 V35 改到 V37，再改到 V38/V39，演练过两次）。

## 2. 目标库 → 动作

| 目标库状态 | 识别方式 | 动作 |
| --- | --- | --- |
| 空库 | 无 `flyway_schema_history` | 正常启动，Flyway 顺序执行 V1–V9、V15–V43；等价于 `FlywayMySqlIntegrationTest#migratesAnEmptyMySqlDatabaseThroughTheCurrentSafetySchema` |
| master 生产/主线库 | history 最高版本 V17，V15–V17 checksum 与 master 一致 | 直接启动，追加执行 V18–V43；V15 的 `CREATE TABLE IF NOT EXISTS` 不会改动既有 `t_user`；等价于 `#preservesExistingAccountsWhenUpgradingFromMasterV17` |
| Java 线旧开发库 | history 含 V10–V20，且 V10–V14 的 checksum 对应原脚本 | **重建**。这些库不在生产序列；旧文件已从 `db/migration` 移出，Flyway 会把 V10–V14 判为 missing。不要用 `repair`/`ignoreMigrationPatterns` 掩盖，按 `db/migration` 全量重建后再灌测试数据 |
| 当前 master 存量库 | history 到 V39，且 V39 为站内通知 | 只执行资讯引擎 V40–V43 |
| 旧资讯开发库 | history 使用资讯采集 V39、精选 V40、审核 V41 | 与正式链同号异义，隔离测试库重建。若有须保留的数据，先单独设计导出和迁移 |
| 迁移中途库 | history 最高版本在 V18–V43 之间且校验和一致 | 直接启动续跑；Flyway 只执行缺失的高版本 |
| `flyway_schema_history` 校验和不一致 | `Validate failed: checksum mismatch` | 停止发布，先比对实际脚本与 history；不得修改已应用脚本或删除 history 强行通过 |

`spring.flyway.out-of-order` 必须保持 `false`。本整合方案不依赖 out-of-order，也不允许把它当作跳过版本缺口的开关。

### 2.1 回滚方向（谁能在什么库上起服务）

上表只讲「往前走」，回滚方向单独有两条实测结论（隔离 MySQL schema 上真跑，
见 `FlywayMySqlIntegrationTest`）：

| 库的 history | 更旧的 jar（只认到 V17）能否直接启动 | 依据 |
| --- | --- | --- |
| 升到 V43 之后（master V17 线上来的） | **能启动**。V18–V43 对它记为 `FUTURE_SUCCESS`，Flyway 默认 `ignoreMigrationPatterns=*:future` 放行，迁移数 0；对既有表只有 V22 加的一列可空 `patientTaskId`、V29 的一列可空 `indexVersion`、V30 的三列可空版本字段、V32 的两列可空候选版本字段、V33 的一列可空 `restrictReason`、V34 的六列可空证据字段，以及 V31 的 `t_diagnosis_run`、V35 的 `t_user_wechat`、V36 的配置中心五表（空表）、V37 的审批单表（空表）、V38 的 `t_user_feedback`、V39 的 `t_user_notification` 及 V40/V41 的资讯资料池与精选表等新表，V42 追加资讯可空列、带默认值的修订号和新表 | `#oldMigrationSetAcceptsFutureVersionsWithoutCertifyingArtifactSafety` |
| history 里含本 jar 解析不了、且低于其已知最高版本的版本（如旧 release 线 V10–V14） | **不能**。Flyway 判 missing 并拒绝启动 | `#aJarRejectsAHistoryThatContainsVersionsItDoesNotResolve` |

> 该用例的名字是刻意写成的：它只证明**迁移解析**对 future 版本放行，
> **不证明旧产物安全**。第二轮独立审查用真实旧 JAR 实测确认，旧 master 产物能启动，
> 但会恢复正式复核写入与推理输出等不安全行为——所以它不能作为回退候选（见
> `docs/release/upgrade-rollback-manual.md` §1、§5）。

所以「代码回滚」在 master V17 升上来的库上**技术上**可行（新表在旧代码下不再写入），
但这**不等于允许切过去**：旧 master 产物会恢复不安全的正式复核写入与推理输出
（§2.1 上表注解）。**要回到 V17 的 schema 形态**只能恢复到升级前备份的新库并切流；
路径与演练要求见 `docs/release/upgrade-rollback-manual.md` §4。V10–V14 这类历史不提供回滚路径，只能重建。

## 3. 发布前检查

1. 导出目标库 history：
   ```sql
   SELECT installed_rank, version, description, type, checksum, success
   FROM flyway_schema_history ORDER BY installed_rank;
   ```
2. 确认最高版本与上表一致；`success=0` 的行必须先人工处理。
3. 确认目标库 `t_user` 的实际列；master V15 只做 `CREATE TABLE IF NOT EXISTS`，不会补齐旧表缺列。
4. 在数据库副本上先跑一次迁移，核对：
   - `flyway_schema_history` 只追加，不重写既有行；
   - 协作相关表存在：`t_diagnosis_task`、`t_controlled_reference`、`t_patient_draft_run`、`t_patient_draft_message`、`t_doctor_review`、`t_patient_authorization`、`t_collaboration_event`、`t_graph_execution`；
   - 升至本分支（V39）时另需 `t_user_feedback`、`t_user_notification`；
   - 既有 `t_user` / `t_news` 行数与抽样内容不变。
5. 迁移完成后再启动应用；应用侧协作总开关仍默认关闭。

## 4. 演练命令

本机 MySQL 8（3307）：

```bash
FLYWAY_TEST_URL='jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
FLYWAY_TEST_USERNAME=root FLYWAY_TEST_PASSWORD='<本地密码>' \
JAVA_HOME=/c/Users/yuwen/.jdks/corretto-21.0.7 \
mvn -o -B -Dtest=FlywayMySqlIntegrationTest test
```

正向覆盖 master V17 → V43、空库 → V43、V23 → V24 冻结历史行、V31 → V43 和当前 master V39 → V43。
另有两个回滚方向用例（旧迁移集合能否接受 future 版本、history 含无法解析版本时是否拒绝启动，见 §2.1）。
测试会为每次运行创建并删除独立 schema，不触碰现有库。

## 5. 约束

- 已应用迁移的内容不得修改；只能追加更高版本。
- 新迁移必须大于 V43；跨分支编号协调见 §1.1。
- 不得删除或手工编辑 `flyway_schema_history` 来绕过冲突。
- **改过迁移文件名后必须 `mvn clean` 再跑门禁。** Maven 的 resources 插件只复制、不清除，
  `target/classes/db/migration/` 会留下改名前的旧脚本（例如改名前的
  `V32__control_center_core.sql`），Flyway 启动时直接报
  `Found more than one migration with version 32`（并把两个脚本都列成 offender）。
  这类失败是**构建残留**，不是 schema 冲突：CI 是全新检出，不会命中；
  本地复跑前先 `mvn -o clean`。（2026-11-24 WP-01 改号时实测命中过一次，
  清理后同一批门禁全绿。）
- 历史冻结脚本 `scripts/product_acceptance_inputs.py` 记录过已删除的 `V15__bootstrap_user_table.sql` 作为 `additionalDependency`；该字段只描述 2026-11-12 的输入快照，不参与当前迁移链。重新封存验收证据时应改为引用 master V15，而不是恢复旧文件。
