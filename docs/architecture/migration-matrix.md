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
| V35 | 无 | 无 | 无（编号由 Iris Control Center WP-01D 占用，该 PR 尚未合并，见 §1.1） |
| V36 | 无 | 无 | 无（同上） |
| V37 | 无 | 无 | `V37__user_feedback.sql`（新增 `t_user_feedback`：用户反馈的正文、分类、关联对象、状态机、回复与私有截图 key；不改动任何既有表） |
| V38 | 无 | 无 | `V38__user_notification.sql`（新增 `t_user_notification`：站内通知的类型、发送时定稿的文案、关联指针与已读时间；不改动任何既有表） |

整合后的唯一链为 **V1–V9 + V15–V27**，其后追加 **V28、V29、V30、V31、V32、V33、V34** 与本分支的 **V37、V38**（V35、V36 留给在途的 Iris Control Center）。目录里不再存在同号迁移。
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
V37、V38 同样只新增表（`t_user_feedback`、`t_user_notification`），既有表一行未改：
升级前已存在的用户、判读与资讯数据在升级后行为与升级前一致；这两个功能在空历史库上
就是两张空表。

### 1.1 编号协调：V35/V36 与 V37/V38（2026-11-24）

本分支（`t3/add-feedback-feature`）新增 **V37**（用户反馈）与 **V38**（站内通知）。
V35–V36 已被在途的 Iris Control Center 占用，它们不在本分支的目录里：

| 编号 | 占用方 | 脚本 |
| --- | --- | --- |
| V35 / V36 | Iris Control Center WP-01D（PR #164，未合并） | `V35__control_center_core.sql`、`V36__control_center_publish_approval.sql` |

V32–V34 已随 PR #156 合入 master，不再是占用方。本分支取「当前所有分支最大版本 +1」
= V37、V38 以避开与 Control Center 的撞号，由此产生一条顺序约束：

- **`spring.flyway.out-of-order` 保持 `false`**，所以一旦某个库先应用了 V37/V38，
  随后到达的 V35/V36 会被 Flyway 判为 out-of-order 而拒绝启动。
- 因此**建议先合并 V35/V36 所在的分支，再合并本分支**；若本分支先合，
  后续分支必须在同一发布窗口内重编号到 V39+，不能靠打开 out-of-order 掩盖。
- 重编号成本很低：改文件名 + `FlywayMySqlIntegrationTest` 里的版本与迁移数断言，
  脚本内容不含自身版本号（V35→V37 已按此演练过一次）。

## 2. 目标库 → 动作

| 目标库状态 | 识别方式 | 动作 |
| --- | --- | --- |
| 空库 | 无 `flyway_schema_history` | 正常启动，Flyway 顺序执行 V1–V9、V15–V34、V37、V38；等价于 `FlywayMySqlIntegrationTest#migratesAnEmptyMySqlDatabaseThroughTheCurrentSafetySchema` |
| master 生产/主线库 | history 最高版本 V17，V15–V17 checksum 与 master 一致 | 直接启动，追加执行 V18–V34、V37 与 V38；V15 的 `CREATE TABLE IF NOT EXISTS` 不会改动既有 `t_user`；等价于 `#preservesExistingAccountsWhenUpgradingFromMasterV17` |
| Java 线旧开发库 | history 含 V10–V20，且 V10–V14 的 checksum 对应原脚本 | **重建**。这些库不在生产序列；旧文件已从 `db/migration` 移出，Flyway 会把 V10–V14 判为 missing。不要用 `repair`/`ignoreMigrationPatterns` 掩盖，按 `db/migration` 全量重建后再灌测试数据 |
| 迁移中途库 | history 最高版本在 V18–V34 之间，或为 V37/V38，且校验和一致 | 直接启动续跑；Flyway 只执行缺失的高版本。**先应用过 V37/V38 的库不能再接受 V35/V36**（见 §1.1） |
| `flyway_schema_history` 校验和不一致 | `Validate failed: checksum mismatch` | 停止发布，先比对实际脚本与 history；不得修改已应用脚本或删除 history 强行通过 |

`spring.flyway.out-of-order` 必须保持 `false`。本整合方案不依赖 out-of-order，也不允许把它当作跳过版本缺口的开关。

### 2.1 回滚方向（谁能在什么库上起服务）

上表只讲「往前走」，回滚方向单独有两条实测结论（隔离 MySQL schema 上真跑，
见 `FlywayMySqlIntegrationTest`）：

| 库的 history | 更旧的 jar（只认到 V17）能否直接启动 | 依据 |
| --- | --- | --- |
| 升到 V34 / V38 之后（master V17 线上来的） | **能启动**。V18–V34、V37、V38 对它记为 `FUTURE_SUCCESS`，Flyway 默认 `ignoreMigrationPatterns=*:future` 放行，迁移数 0；对既有表只有 V22 加的一列可空 `patientTaskId`、V29 的一列可空 `indexVersion`、V30 的三列可空版本字段、V32 的两列可空候选版本字段、V33 的一列可空 `restrictReason`、V34 的六列可空证据字段，以及 V31 的 `t_diagnosis_run`、V37 的 `t_user_feedback`、V38 的 `t_user_notification` 三张新表 | `#oldMigrationSetAcceptsFutureVersionsWithoutCertifyingArtifactSafety` |
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
   - 升至本分支（V38）时另需 `t_user_feedback`、`t_user_notification`；
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

三个用例分别覆盖：master V17 → V38、空库 → V38、V23 → V24 历史行标注不改写冻结消息。
另有两个回滚方向用例（旧迁移集合能否接受 future 版本、history 含无法解析版本时是否拒绝启动，见 §2.1）。
测试会为每次运行创建并删除独立 schema，不触碰现有库。

## 5. 约束

- 已应用迁移的内容不得修改；只能追加更高版本。
- 新迁移必须大于 V38（master 为 V34，本分支加 V37/V38）；跨分支编号协调见 §1.1。
- 不得删除或手工编辑 `flyway_schema_history` 来绕过冲突。
- 历史冻结脚本 `scripts/product_acceptance_inputs.py` 记录过已删除的 `V15__bootstrap_user_table.sql` 作为 `additionalDependency`；该字段只描述 2026-11-12 的输入快照，不参与当前迁移链。重新封存验收证据时应改为引用 master V15，而不是恢复旧文件。
