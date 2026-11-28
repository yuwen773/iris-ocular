# Iris · MySQL + Chroma → PostgreSQL 18 + pgvector 迁移报告（2026-11-28）

> 分支：`feat/postgresql-pgvector-migration`（基线 `74eee927`，master 不合并）
> 运行环境：PostgreSQL 18.7 + pgvector 0.8.7（`pgvector/pgvector:pg18`）、Java 21、Spring Boot 3.5.6
> 状态口径：**PASS / FAIL / BLOCKED / NOT_RUN**。每条都对应实际命令与退出码；口头声明不算证据。
> 生产切换：**BLOCKED_PENDING_AUTHORIZATION**（见 §8），本报告不主张已完成上线。

## 1. 结论摘要

| 项 | 状态 | 一句话证据 |
|---|---|---|
| PG 18 + pgvector 基建 | PASS | Flyway V1–V7 在 6 个库 migrate + validate 成功（V6/V7 为本轮补的 shim 边界与 owner 表达式索引） |
| 关系数据持久化 | PASS | 59 表 / 93 行演练、逐表 digest、26 序列同步、0 未验证外键 |
| 业务不变量与并发 | PASS | 全量后端套件 1425 用例 0 失败（含 ControlCenter 多进程 7/7） |
| 向量迁移（Chroma→pgvector） | PASS | 300 块逐字节保真、overlap@10 = 1.000、隔离过滤不泄漏 |
| 备份恢复 | PASS | `pg_dump \| pg_restore` 到独立库，行数 + 向量 + Flyway 历史一致 |
| 最小权限账号 | PASS | 12 条拒绝探针（DDL/扩展/schema/迁移历史），DML 放行 |
| 全量后端套件（PG） | PASS | M7 复跑：`mvn test` 1418 tests / 0 fail / 0 err / 376 skipped / exit 0；`check_surefire_skips.py backend-unit` exit 0 |
| 跳测门禁契约 | PASS | `backend-unit` 与新增 `business` 契约均 exit 0；契约自身单测 21/21 |
| 业务契约重新基线（M7） | PASS | 11 个原 MySQL 业务套件在真实 PG 上 86/86，断言未降级（§5.10） |
| Flyway / 镜像可复现性 | PASS | Flyway 11.14.1 在 PG 18.7 迁移零警告；pgvector 镜像按 digest 钉死，静态门禁 23/23 |
| 旧 MySQL 门禁（M6） | PASS | 10 个 `if: ${{ false }}` 检查全部改在 PG 上执行；11 套件本机真实 PG **291/291，0 skipped**（§5.8） |
| CI（远端 HEAD） | PASS | run `46047803428`（HEAD `c403adcb`）：20/20 必需检查 success；`postgres-integration` / `vector-migration-drill` / `flyway-postgres` / `miniapp` 均 success；10 个 MySQL-only job 按设计 `skipped`（§5.7） |
| 独立评审 A/B | PASS（已整改） | A：BLOCK → 1 P0 + 3 P1 已修；B：FIX_P0_P1 → 无 P0，2 P1 已修（见 §9） |
| 生产切换 | BLOCKED | 需真实数据导出、分支保护变更、部署授权 |

## 2. 范围与不变量

保留：Java 21 / Spring Boot / Spring AI / Spring AI Alibaba / MyBatis Plus / Redis / React web /
微信小程序 / Python gRPC / 腾讯 COS / Agent 架构 / RAG 业务契约 / API 与权限边界。

改变：

- 业务关系库与 RAG 向量**同库异构**：一个 PostgreSQL 数据库，`diagnosis` schema 放 59 张业务表，
  `rag` schema 放 `iris_knowledge_vectors`，靠 schema + 角色权限做逻辑隔离。
- MySQL 与 Chroma **不再是运行依赖**（`spring-ai-chroma-store` 从生产依赖移除）。
- 时间语义：MySQL `datetime(0)` → `timestamptz(0)`，所有时刻按 UTC 存储、按会话时区呈现。

不做：长期双栈兼容；MySQL 与 PG 同时在线；对真实知识库重嵌入（会导致 id/相似度变化且属于付费调用）。

## 3. 关键架构决策

| 决策 | 取舍 | 依据 |
|---|---|---|
| 独立 PG Flyway 历史 `db/postgresql/V*.sql` | 不翻译 MySQL 迁移，按活库 dump 重写 V1 | MySQL 迁移含方言 DDL，逐条翻译会引入静默差异 |
| 标识符小写折叠 + 不加引号 | Java/SQL 保持 `camelCase` 写法，PG 折叠成小写 | 与 `map-underscore-to-camel-case: false` 共存，改动面最小 |
| MySQL 函数保留在 SQL，PG 侧补同名函数 | `date_format` / `utc_timestamp` / `utc_date` / `curdate` / `<=>` | 两条线 SQL 不分叉；补丁集中在迁移里可审 |
| `timestamptz` + `timestamptz(0)` | 时区信息不再靠字符串约定 | MySQL `datetime` 无时区，跨时区读数是真实缺陷 |
| 双角色 | `iris_migrator` 拥有 DDL，`iris_app` 仅 DML | 应用账号不能改 schema、不能读迁移历史 |
| `stringtype=unspecified` + 启动闸门 | 字符串参数可写入 `jsonb` | 缺失时 MyBatis 的 `jsonb` 写入全部失败；闸门让部署当场失败而不是运行期 |
| PgVectorStore(`TEXT` id / COSINE / HNSW) | id 与 Chroma 字符串 id 兼容；索引可控 | 文档 id 承载引用与去重语义，改 UUID 会破坏引用 |
| 向量导入走 JDBC、不走 `VectorStore.add` | 保留原 embedding，零模型调用 | 重嵌入 = 付费调用 + 相似度漂移 + 需要授权 |

## 4. 迁移物清单

| 类别 | 路径 |
|---|---|
| PG 迁移 | `src/main/resources/db/postgresql/V1__baseline.sql`、`V2__rag_and_compat.sql`、`V3__vector_metadata_normalization.sql`、`V4__date_format_literal_escaping.sql`、`V5__null_safe_equality_operator.sql` |
| Flyway 回调 | `src/main/resources/db/postgresql/afterMigrate.sql` |
| 角色与权限 | `scripts/postgresql/bootstrap-roles.sql`（含 boolean→smallint assignment cast）、`scripts/postgresql/docker-init/10-bootstrap.sh` |
| Flyway 运行器 | `scripts/postgresql/FlywayMigrate.java` |
| 关系迁移工具 | `scripts/migration/mysql_to_postgres.py`（只读源、dry-run、分批、断点续跑、PK 保留、序列同步、逐表 digest） |
| 向量迁移工具 | `scripts/migration/chroma_to_pgvector.py`、演练 `scripts/migration/chroma_vector_drill.py` |
| 关系演练 | `scripts/migration/run-drill.sh`、`scripts/migration/generate-drill-fixture.py` |
| 验证入口 | `scripts/verify-pg.sh`（L1 门禁；`--full` 全量） |
| 应用代码 | `VectorStoreConfig`、`LexicalCorpusService`、`BuiltinCorpusSeeder`、`PostgreSqlRequirement`、`InsertRaceGuard` |
| PG 测试 | `src/test/java/com/csust/eyediagnosis/postgres/Pg*IntegrationTest.java`（4 套件 / 27 用例）+ M7 重新基线的 11 个业务套件（86 用例，§5.10，入口辅助 `integration/LegacyGateSupport`） |
| 依赖与镜像 | `pom.xml` 的 `flyway.version=11.14.1`（PG 18 支持）；`scripts/lib/docker-images.env` 的 `PGVECTOR_DIGEST`（单一真源，CI/compose/verify-pg 同步） |
| CI | `.github/workflows/verify.yml`（`backend-unit`、`flyway-postgres`、`postgres-integration`、`vector-migration-drill`；原 10 个 MySQL-only job 已在 M6 改为在 pgvector 上执行，检查名不变——见 §5.8） |

## 5. 验收证据

### 5.1 基建与 schema

```
$ scripts/verify-pg.sh
run-id: test-20261127t181948z-c403adcb-1451-18e0
roles: iris_migrator / iris_app bootstrapped
MIGRATED=7 VERSION=7
VALIDATE=OK
postgres suites=4 tests=26 skipped=0 failures=0 errors=0
PASS: PostgreSQL verification (gates)
clean: run test-20261127t181948z-c403adcb-1451-18e0 has no leftover containers/volumes/networks
```
退出码 0。另在 `iris` / `iris_ci` / `iris_it` / `iris_drill` / `iris_restore` / `iris_vec_drill` 六库
执行过 migrate + validate，全部 `VALIDATE=OK`。

### 5.2 关系数据演练

```
$ scripts/migration/run-drill.sh
ok_tables=59 SEQUENCES synced=26 FOREIGN_KEYS orphan_rows=0
instant_utc=2026-11-20 10:00:00 identity_after_import=13 restore_ok
rag.iris_knowledge_vectors=3 PASS
```
退出码 0。逐表 `pk_digest` 与源一致（文本侧排序、`rtrim` 处理 `char(n)` 补空格）。

### 5.3 向量迁移演练

```
$ python scripts/migration/chroma_vector_drill.py --chroma-url http://127.0.0.1:18000 --target-dsn ...
SEED count=300
IMPORT ...
VERIFY source_chunks=300 target_rows=300
FIDELITY ok ids=3 embedding_tolerance=1e-6
OVERLAP chroma_vs_pg_exact@10=1.000
OVERLAP pg_exact_vs_pg_hnsw@10=1.000
VERIFY content/metadata/embedding match
LATENCY_MS chroma p50=4.5 p95=27.7
LATENCY_MS pg_exact p50=3.8 p95=5.1
LATENCY_MS pg_hnsw p50=2.3 p95=2.7
ISOLATION ok owners=['7']
PASS vector migration drill
```
退出码 0。**只使用合成数据**；真实语料导入需要授权（见 §8）。

导入校验新增「同 id 但内容/元数据/向量不同」检查（`ON CONFLICT DO NOTHING` 会保留旧行，
只看 id 集合会在部分重跑时假绿）。负例实测：把一行 content 改坏后重跑，
`VERIFY_FAIL divergent_rows=1 sample=['drill_chunk_00000']`、退出码 **2**；恢复数据后重跑 PASS。

### 5.4 备份与恢复

```
pg_dump iris_drill | pg_restore -d iris_restore
restore_ok: 行数与源一致；3 条向量行可检索；flyway_schema_history 版本一致
```

### 5.5 全量后端套件（真实 PG）

```
$ JAVA_HOME=corretto-21 mvn -B test     # SPRING_PROFILES_ACTIVE=ci, PG_IT_URL=...iris_ci
Tests run: 1421, Failures: 0, Errors: 0, Skipped: 383
exit=0
```

```
$ python scripts/check_surefire_skips.py target/surefire-reports backend-unit
skip gate passed: no unexpected skipped or dropped tests
exit=0
```

M6 复跑（2026-11-28 第二轮，含新加的 7 条用例）：

```
$ bash scripts/verify-pg.sh --full
Tests run: 1425, Failures: 0, Errors: 0, Skipped: 383
postgres suites=4 tests=27 skipped=0 failures=0 errors=0
PASS: PostgreSQL verification (full)
```

383 条跳过全部是契约声明的 opt-in 套件（MySQL-only、协作、评测等），逐条列在门禁输出里，
逐项处置见 §5.8。本轮删除了 Chroma 时代的 `VectorStoreConfigTest`（会向真实向量表写无主数据）
与 `SimilarityScoredVectorStoreTest`（包装类已删除，见 §6 R1），新增 `UtcTimestampsTest` 6 条与
pgvector 端到端分数用例 1 条，总数 1421 → 1425。

### 5.7 CI（远端）

首轮（`25eabef9`）24 个检查：20 绿，3 红：

| 检查 | 红因 | 修复 |
|---|---|---|
| `docs-contract` | `CLAUDE.md` 缺 steering 门禁要求的 `Current head is V<n>` | 恢复声明（MySQL 历史 V47 / PG 线当前版本） |
| `product-contract` | `ProductSchemaTest` 仍连 MySQL，而 MySQL 驱动已移除 | 迁移到真实 PG（pgvector service + PG Flyway），断言同一组表列；21/21 + skip 门禁 exit 0 |
| `docker-lifecycle` | compose 新增 postgres 后，镜像钉版检查仍假设只有一个 image 行 | 按服务解析，并新增 pgvector 钉版断言；22/22 |

最终候选 HEAD `c403adcb` 的 run `46047803428`：**20/20 必需检查 success**，另有
`postgres-integration`、`vector-migration-drill`、`flyway-postgres`、`miniapp` 四个可选 job success；
10 个 MySQL-only job（issue17–24、collaboration、graph-execution、issue24-evaluation）按设计 `skipped`，
已由 PG 等价门禁替代。其中 `backend-unit`、`product-contract`、`postgres-integration`、
`vector-migration-drill`、`flyway-postgres` 均为实际执行（非 NOT_RUN），跳测门禁在
`backend-unit` 内以 exit 0 通过。

此后若仅追加文档提交，最终交付 HEAD 指向最新一次文档提交，其 CI 结果以 PR #176 检查页为准；
上文 `c403adcb` 的代码内容与树不变。

### 5.6 本轮修复后重点复跑

| 套件 | 结果 |
|---|---|
| `ControlCenterPersistenceIntegrationTest` | 17/17 |
| `ControlCenterPublishIntegrationTest` | 18/18 |
| `ControlCenterAcceptanceTest` | 12/12 |
| `ControlCenterDisabledAcceptanceTest` | 3/3 |
| `ControlCenterPublishAcceptanceTest` | 6/6 |
| `ControlCenterMultiProcessTest`（独立 JVM） | 7/7 |
| `FullApplicationAcceptanceTest` | 10/10 |

### 5.8 M6：旧 MySQL 门禁的 PostgreSQL 收口

`verify.yml` 里 10 个 `if: ${{ false }}` 的 job（issue17–24、collaboration、graph-execution）
不再停用：检查名（分支保护的必需项）逐字保留，服务容器从 `mysql:8.0.36` 换成
`pgvector/pgvector:pg18`，每个 job 用 `psql CREATE DATABASE <gate>_test` 建一次性库，
环境变量从 `jdbc:mysql://…` 换成 `jdbc:postgresql://…`，并补 `PG_TEST_USER/PG_TEST_PASSWORD`
（一次性库用超级用户；生产/CI 的角色边界仍由 `bootstrap-roles.sql` 承担）。
`log_bin_trust_function_creators` 这类 MySQL 专用准备步骤删除；报告门禁脚本
（`check_issue17_reports.py`、`check_surefire_skips.py`）原样保留，契约条数不变。

测试侧新增 `src/test/java/com/csust/eyediagnosis/integration/LegacyGateSupport.java`：
URL 校验（只接受回环地址上的约定隔离库，MySQL/PostgreSQL 均可）、PG 侧按 `db/postgresql`
跑 Flyway 并装 `vector` 扩展、MySQL 兼容强制转换（与 `bootstrap-roles.sql` 同一组）、
以及 `NOW(6)`／`JSON_*`／`TIMESTAMPADD`／`DATABASE()`／`ENGINE=InnoDB`／故障注入触发器
两套方言写法。生产迁移 V1–V7 未为测试改动一行。

本机真实 PG 结果（11 个套件共用一次 `mvn test`）：

| 检查 | 套件（用例数） | 结果 |
|---|---|---|
| `issue17-mysql` | DiagnosisTaskApiTest 7 / DiagnosisTaskRecoveryTest 10 / CosDiagnosisTaskStorageTest 1 | 18/18 |
| `issue18-mysql` | ControlledReferenceApiTest 13 | 13/13 |
| `issue19-mysql` | PatientDraftTest 41 | 41/41 |
| `collaboration-mysql` | CollaborationRunTest 33 | 33/33 |
| `graph-execution-mysql` | GraphExecutionTest 37（+ RealtimeGraphSpikeTest / CoordinationPocTest 无库用例） | 37/37 |
| `issue20-mysql` | DoctorReviewApiTest 38 | 38/38 |
| `issue21-mysql` | PatientExplanationApiTest 37 | 37/37 |
| `issue22-mysql` | PatientAuthorizationApiTest 37 | 37/37 |
| `issue23-mysql` | PatientFollowUpApiTest 37 | 37/37 |

```
$ mvn -B -Dtest=DiagnosisTaskApiTest,…,PatientFollowUpApiTest -DfailIfNoTests=false test
Tests run: 291, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
$ python scripts/check_issue17_reports.py target/surefire-reports     # exit 0
$ python scripts/check_surefire_skips.py target/surefire-reports <issue17..23,collaboration,graph-execution>
                                                                      # exit 0 ×9
```

`issue17-mysql` 的服务健康检查也换成 PG 版本，负向对照改为
`scripts/tests/test_issue17_pg_health.py`（就绪库 exit 0；不可达端口、缺失库 exit ≠ 0）。
**密码负向对照不可用**：postgres 官方镜像对 `127.0.0.1` 是 trust，健康检查在容器内执行，
错误密码仍会成功；口令鉴权由套件自身从宿主机走 TCP/scram 覆盖，并在用例里写明。
`scripts/tests/test-docker-image-pins.sh` 同步改为「CI 不得再启动任何 MySQL 服务，且
pgvector 镜像必须钉版」，本机 23/23 PASS。

### 5.8.1 CI 实测（HEAD `15441012`，run `46106099912`）

10 个移植后的 job 在 GitHub Actions 上真实执行并通过，日志里的关键行：

| 检查 | 日志实证 |
|---|---|
| `issue17-mysql` | `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`；门禁输出 `DiagnosisTaskApiTest: 7 passed, 0 skipped` / `DiagnosisTaskRecoveryTest: 10 passed, 0 skipped` / `CosDiagnosisTaskStorageTest: 1 passed, 0 skipped` |
| `collaboration-mysql` | `Database: jdbc:postgresql://127.0.0.1:5432/collaboration_test (PostgreSQL 18.7)` → Flyway V1–V7 → `Tests run: 33, …, Skipped: 0`；`ok: CollaborationRunTest (33 cases, 0 skipped)` / `skip gate passed` |
| `graph-execution-mysql` | 三个套件合计 `Tests run: 53, …, Skipped: 0`；`ok: GraphExecutionTest (37 cases, 0 skipped)`；服务端可见注入的触发器报错 `synthetic checkpoint fault` |
| 其余 7 个 | 同样走 `check_surefire_skips.py <scope>`，日志均为 `skip gate passed: no unexpected skipped or dropped tests` |

`issue17` 的门禁是**精确计数 + 零跳过**（7/10/1，任一 skipped 或漏跑即 exit 1），所以
「环境变量写错 → 整类跳过 → job 假绿」这条路被堵死；`issue17` 日志里还能看到健康检查负向
对照真的在跑（`database "iris_gate_missing" does not exist`）。同一次 run 里
`backend-unit`、`postgres-integration`、`flyway-postgres`、`vector-migration-drill`、
`product-contract`、`miniapp`、`docs-contract`、`watchdog`、`backend-compile`、
`frontend`、`web-e2e-system-states`、`docker-lifecycle` 均 success。

### 5.9 跳过用例的处置（按用例标识去重，M7 口径）

`mvn test`（不设任何 opt-in 变量，真实 PG 18.7 + Redis）实测：
**1418 tests / 0 failures / 0 errors / 376 skipped / exit 0**，`scripts/check_surefire_skips.py
target/surefire-reports backend-unit` exit 0。376 条跳过逐套件归类如下，没有一条的理由是
「跑不了 MySQL 所以不跑」：

| 组 | 用例数 | 处置 |
|---|---|---|
| `*_TEST_URL` 组的 11 个套件（协作 33 / Graph 37 / 患者草稿 41 / 医生复核 38 / 解释 37 / 授权 37 / 随访 37 / 受控参考 13 / 判读任务 10+4） | 287 | **已在 PG 上跑**：CI 的 10 个 job 都会设置对应变量（§5.8）；本地不设变量时按契约整类跳过（`LegacyGateSupport.assumeIsolated`，用 Assumptions 而非异常） |
| 业务回归套件（M7 从 MySQL 重新基线，§5.10） | 86 | **已在 PG 上跑**：本机真实 PG 86/86；CI `postgres-integration` 的第二段（`business` 契约） |
| `ProductSchemaTest` | 1 | 在 `product-contract` job 以 PG 运行，非跳过 |
| `RAGEvaluationTest` | 2 | 需真实语料 + 付费嵌入，**BLOCKED**（未授权），保持 opt-in |

与旧口径的 **383 − 376 = 7** 差额，全部来自删除的 `FlywayMySqlIntegrationTest`
（它断言的 MySQL 历史已退役，等价门禁见 §7；不是把断言删掉换绿）。

### 5.10 M7：业务契约在 PostgreSQL 上重新基线

M6 遗留的 93 条跳过（79 条 `FLYWAY_TEST_URL` + 14 条 `CoordinationMySqlTest`）逐套件分类的结果是：
**86 条仍是有效业务契约**（不是 MySQL 历史依赖），已重跑；**7 条**（`FlywayMySqlIntegrationTest`）
断言的正是退役的 MySQL 历史本身，归档并由 CI 的等价门禁承担；**0 条**属于「已被等价覆盖」
或「业务已退役」。没有删除断言、没有降低断言、没有加无条件 `@Disabled`——删掉的只有 MySQL 版本本身。

| 套件（新名） | 用例 | 本机真实 PG | CI |
|---|---|---|---|
| `newsingestion.NewsIngestionPostgresTest` | 13 | 13/13 | `postgres-integration` |
| `newsingestion.NewsCollectionSchedulePostgresTest` | 6 | 6/6 | 同上 |
| `newscuration.NewsCurationPostgresTest` | 18 | 18/18 | 同上 |
| `service.NewsEditorialPublishPostgresTest` | 8 | 8/8（真实 PG + 真实 Redis） | 同上 |
| `newsworkbench.NewsWorkbenchPostgresTest` | 13 | 13/13 | 同上 |
| `service.DiagnosisRunPostgresIntegrationTest` | 8 | 8/8 | 同上 |
| `upload.state.UploadTaskMapperPostgresIntegrationTest` | 3 | 3/3 | 同上 |
| `agent.service.ToolAuditPostgresIntegrationTest` | 1 | 1/1 | 同上 |
| `agent.service.ToolTerminalPersistencePostgresIntegrationTest` | 1 | 1/1 | 同上 |
| `integration.AdminAnalyticsPostgresIntegrationTest` | 1 | 1/1 | 同上 |
| `poc.CoordinationPostgresTest` | 14 | 14/14（子 JVM 崩溃/续跑/围栏） | 同上（`COORDINATION_POC_URL`） |

移植方式：入口统一改成 `LegacyGateSupport.assumeIsolated(BUSINESS_TEST_URL, "business_test")`，
库由套件自己按 `db/postgresql` 迁移（不再 `CREATE DATABASE` MySQL 库、不再跑 `db/migration`）。
**夹具方言**逐条替换，断言一行未改：

- `DATE_SUB(UTC_TIMESTAMP(6), INTERVAL n SECOND)` → `now() - INTERVAL 'n second'`；`UTC_TIMESTAMP(6)` → `now()`
- `CREATE TRIGGER … SIGNAL SQLSTATE '45000'` → `LegacyGateSupport.createFailTrigger*`（plpgsql `RAISE EXCEPTION`；PG 侧幂等重建，避免上一轮失败留下的触发器污染下一次运行）
- 每类自建 MySQL 库 → 共享 `business_test` + 每类 `setUp` 定点 `DELETE`（跨套件残留曾导致「只允许手工补采已提交窗口」的假失败）
- `information_schema` 断言按 schema `diagnosis` 取表/取列（PG 上 `table_schema` 不是库名）
- 分页插件 `DbType.MYSQL` → `DbType.POSTGRE_SQL`

**这一轮同时暴露了 6 类生产缺陷**（§6 R7–R12：布尔列按 int 读、`CAST AS SIGNED`、`SUM(boolean)`、
未定型 `? IS NULL`、MyBatis `Map` 别名大小写、MyBatis-Plus 生成主键 `keyColumn`）。它们都不是测试夹具问题：
其中 `AgentToolAudit` 的生成主键缺陷会让审计行**静默丢失**，只有在真实 PG 上跑业务套件才看得见。

CI 侧不新增 job、不改检查名，全部折叠进既有 `postgres-integration`：bootstrap 建 `business_test`、
`coordination_poc`（`poc` 角色）并起 Redis 服务；第一段仍是 `Pg*IntegrationTest` 及其门禁，第二段跑上面
11 个套件，收尾用 `check_surefire_skips.py … business`（契约：11 套件 86 用例，零跳过；单元测试 21/21）。

同时固定了两项可复现性前提：

- **Flyway 11.14.1**：11.7.2 在 PG 18.7 上每次都打「latest supported version of PostgreSQL is 17」。
  逐版本 `javap` 核对 `PostgreSQLDatabase` 的支持上限常量后（11.13.0 = 17，11.14.0 = 18），只抬
  `flyway.version` 到 11.14.1（该线最后的补丁版）。真实 PG 18.7 空库实测：`MIGRATED=7 VERSION=7` /
  `VALIDATE=OK` / **0 条警告**；二次 migrate `MIGRATED=0`；篡改 `flyway_schema_history` 的 checksum 后
  `exit=1` 且报 `Migration checksum mismatch`。
- **镜像 digest**：`pgvector/pgvector:pg18@sha256:38681dc6…`（linux/amd64，CI 与本机 Docker 同架构），
  `verify.yml` 15 处、`docker-compose.yml`、`docker-images.env`、`verify-pg.sh` 一处不漏；
  `scripts/tests/test-docker-image-pins.sh` 23/23 PASS。

## 6. 本轮发现的移植缺陷与修复

全量套件最初在 PG 上有 4 类失败；每一个都是 MySQL 语义假设，而不是测试环境问题。

| # | 缺陷 | 现象 | 修复 |
|---|---|---|---|
| P0-1 | 「插入 → 撞唯一键 → 同事务回读」在 PG 上不可用 | `25P02 current transaction is aborted` 取代预期的 40900/幂等重放 | 新增 `common/InsertRaceGuard`：插入放进 savepoint（`PROPAGATION_NESTED`），撞键只回滚到 savepoint；无事务时退化为单语句。改造 7 处：`ChatMessageWriter`、`ControlCenterIdempotencyService`、`ControlCenterDraftService`、`ControlCenterReleaseTransactions`、`MiniAppAuthService`、`NewsWorkbenchService`、`ControlCenter*` 测试夹具 |
| P0-2 | MySQL 空安全等值 `<=>` 在 PG 不存在 | 发布/回滚条件切换指针报 `operator does not exist: bigint <=> bigint`，审批链整体不可用 | `V5__null_safe_equality_operator.sql` 补 `<=>(bigint,bigint)` 运算符 |
| P0-3 | `jsonb` 列经 `queryForMap` 返回 `PGobject`，代码按 `String` 强转 | 医生复核冻结抛 `ClassCastException`，连带患者解释、授权、投影三条验收失败 | `DoctorReviewService.freeze` 用 `Objects.toString(...)` 还原 JSON 文本 |
| P1-1 | 测试 SQL 使用 MySQL 专用 `JSON_UNQUOTE(JSON_EXTRACT(...))`、`jsonb LIKE` | 验收用例报语法错误 | 改为 PG 的 `payload ->> '...'`、`payload::text LIKE` |
| P1-2 | 测试以 `innodb_lock_wait_timeout` 设置锁等待 | 子进程 `SET SESSION innodb_lock_wait_timeout` 让连接初始化失败，报成「向量表不存在」 | 改为 `SET lock_timeout = 2000`；`CannotAcquireLockException` 映射不变（PG `55P03`） |
| P1-3 | 生产 SQL 残留 `JSON_OBJECT()` / `JSON_ARRAY()` / `LAST_INSERT_ID()` | 对应链路（协作准入、受控参考资料上传、资讯候选落库、草稿测试种子）在 PG 上必失败；这些套件是 opt-in，默认不跑所以套件绿了也看不出来 | 改用 `'{}'` / `'[]'` 字面量；`LAST_INSERT_ID()` → `INSERT ... RETURNING id`（不用 `lastval()`：并发下会取到别人的 id） |
| P1-4 | Java `boolean` 写入 `smallint` 标志列 | `column "istest" is of type smallint but expression is of type boolean` | 在 bootstrap 里建 boolean→smallint 的 **assignment** cast（PG 18 内建 boolean→integer 是 explicit，不能复用）；SQL 保持 `isTest` 原样 |
| P0-4 | 判读缓存 `InferenceCacheMapper.upsert` 仍是 MySQL `ON DUPLICATE KEY UPDATE` | 开启判读缓存时（`cacheEnabled=true`）每次写缓存都是语法错误，整个 `/api/diagnosis` 返回 50000；该路径没有 PG 用例，全量套件绿也看不出来 | 改为 `ON CONFLICT (imagehash, modelversion, weightsha256, preprocessversion, inferencepolicyversion) DO NOTHING`，真实 PG 上回滚事务验证冲突行数为 1、无异常 |
| P1-5 | 资讯工作台候选列表用 `JSON_LENGTH` | 候选列表查询在 PG 报 `function json_length(jsonb) does not exist`，管理员打开即 50000 | 改为 `jsonb_typeof(...)='array'` 保护的 `jsonb_array_length`；两个取证脚本同步 |
| P1-6 | 生产连接没有 `lock_timeout` | PG 默认无限等锁；配置中心 `SELECT … FOR UPDATE` 争用会永久挂起，`CONFIG_CONCURRENT_CONFLICT` 的「锁等待超时」分支在 PostgreSQL 上不可达 | Hikari `connection-init-sql: SET lock_timeout = '50s'`（与 MySQL 默认 50s 对齐），server 示例同步 |
| P1-7 | 关系迁移假设单一源时区 | MySQL 线混用 `UTC_TIMESTAMP()`/`UTC_DATE()`（45 处）与 JDBC/默认时区写入的本地墙钟；单一 `--source-timezone` 必然让其中一类偏移 8 小时（若会话为 Asia/Shanghai） | 新增 `--column-timezone TABLE.COLUMN=ZONE` 逐列覆盖；真实切换前必须逐列分类（见 §8） |
| P2-1 | `utc_timestamp(fsp)` 忽略精度；`date_format` 未映射 `%` 码不加引号 | `UTC_TIMESTAMP(0)` 仍是微秒精度；`%M/%p/%T` 等码可与相邻 token 合并成错误格式 | `V6__date_time_shim_edges.sql`：按 fsp 截断，补齐常见码，未映射码按字面量加引号；逐函数实测 |
| P1-8 | `Document.score` 口径反转：Chroma 写 `1 - distance`，`PgVectorStore` 写 `distance` | 侧栏「相关度」把最好的命中显示成 0.02、最差的 1.0；`retrievalScore` 也反向且量纲不同 | 新增 `agent/rag/SimilarityScoredVectorStore` 包装：存储边界换算成 0..1 相似度（截断），`DocumentSearchTool` 与既有用例语义不变；3 条单测。**M6 复核发现该前提不成立，包装层已删除，见 R1** |
| P1-9 | `PgVectorStore` 建 bean 时校验表，依赖 bean 创建顺序「碰巧」晚于 Flyway | 空库首次启动时顺序变化即 `IllegalStateException`；整改前在空库实测为通过（说明只是顺序巧合） | 给 `pgVectorStore` 加 `@DependsOnDatabaseInitialization`，把顺序变成契约 |
| P2-2 | 向量列维度与配置 `dimensions` 未比对 | 改配置后要到第一次 `add()`（付费嵌入之后）才报错 | 启动时比对 `pg_attribute.atttypmod`（pgvector 直接存维度，实测确认）；不一致拒绝启动；2 条单测 |
| P2-3 | 词法通道 owner 查询全表扫（GIN 不服务 `->>`） | 每个用户每分钟全表扫向量表，上限 2 万块 | V7 函数索引 `(metadata ->> 'ownerUserId')`，查询表达式与索引逐字一致，EXPLAIN 实测走 Bitmap Index Scan |
| P2-4 | Chroma 退役残留：`IrisRagGenerationProbe.java`（已无法编译）、`VectorStoreConfigTest`（会向真实向量表写无主数据）、文档仍指 MySQL 验证路径 | 误导或污染 | 删除探测器与其 ps1/演示测试（回归由 PG 套件承担）；`check_surefire_skips` 同步；CLAUDE.md 与迁移矩阵标注「本分支只认 verify-pg.sh」 |
| P2-5 | `BuiltinCorpusSeeder` 直接拼接 schema/table | 配置值可把任意 SQL 带进语句（与 `LexicalCorpusService` 不一致） | 加同样的标识符校验 |
| R1 | **P1-8 的修复本身是缺陷**：Spring AI 1.1.2 的 `PgVectorStore$DocumentRowMapper` 写入的是 `1.0 - distance`（字节码实测：`dconst_1 … f2d … dsub`），`SimilarityScoredVectorStore` 又取一次 `1 - score` | 生产 RAG 相关度整体翻转：同文命中 `score = 0.0`，最不相关的是 `1.0`；`DocumentSearchTool` 的排序与「检索分」展示随之反向 | 删除包装类与其单测，`VectorStoreConfig` 直接返回 `PgVectorStore`；新增 `PgVectorStoreIntegrationTest.appScoreIsOneMinusDatabaseDistance`：同一次查询既走应用路径又直接问数据库要 `<=>` 距离，断言 `score = 1 - distance` 且同文命中为 1.0 |
| R2 | 管理端 `operations()` 的聚合 Map 直接进 JSON，列名在 PG 折叠成小写 | `runtimeKind/stopReason/runCount` 变成 `runtimekind/stopreason/runcount`，管理端接口契约在 PG 上改名 | SQL 别名加引号（`AS "runtimeKind"`），MySQL 与 PG 同义；`CollaborationRunTest` 用 `keySet()` 钉死 |
| R3 | `getObject(column, LocalDateTime.class)` 读 `timestamptz` | pgjdbc 抛 `Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime`：`ControlledReferenceService`（复核时间）与 `NewsIssueService`（发布时间）在 PG 上必 500 | 新增 `common/UtcTimestamps.read`：按 UTC 还原成墙钟，保证 MySQL 时代写入的时间字符串逐字一致；2 处接线 + `UtcTimestampsTest` 6 条（含「按会话时区换算会不一样」的反例） |
| R4 | 迁移后的隔离套件在子 JVM 里重建 schema | 崩溃恢复/跨 JVM 重放用例的子进程一启动就把父进程刚写入的夹具表 `DROP` 掉（MySQL 路径「表已存在就跳过」，PG 路径会 `DROP … CASCADE` 再建），父进程随后读到空表 → `ACCESS_DENIED` | `isolatedDatabase(boolean initialize)`：父 JVM 建库，子 JVM 只连接；`CollaborationReplayWorker` 与 `GraphExecutionTest.RecoveryWorker` 传 `false` |
| R5 | 套件里的 MySQL 方言在 PG 上是语法错误：`NOW(6)`、`JSON_SET`、`TIMESTAMPADD`、`SIGNAL SQLSTATE` 触发器、`DATABASE()` | 逐条报 `function now(integer) does not exist`、`syntax error at or near "SIGNAL"` 等 | 全部集中在 `LegacyGateSupport` 里按数据库生成对应写法（PG 用原生 `jsonb_set`/`CURRENT_TIMESTAMP`/`RAISE EXCEPTION`/`current_schema()`），**不为 MySQL 函数做仿真**——测试断言的是数据库真实行为 |
| R6 | 有条件故障注入的 plpgsql 函数漏 `RETURN NEW`（条件不匹配时落到 `END`） | PG 报 `control reached end of trigger procedure without RETURN`：注入从「只让 EVIDENCE:1 这一行失败」退化成「所有行都失败」。用例照样绿，因为断言只看「抛错 + 回滚」——**验得比它声称的少**。由 CI 日志发现（`fail_graph_checkpoint()` 与 `synthetic checkpoint fault` 先后出现） | `LegacyGateSupport.createTrigger` 条件分支补 `RETURN NEW`；`GraphExecutionTest.checkpointCommitFailure…` 增加前提自检：非目标节点 `EVIDENCE:0` 必须在触发器在位时写入成功。真实 PG 实测 37/37 |
| R7 | `boolean` 表达式列按 int 读（M7） | 资讯采集租约 `leaseActive`、精选熔断 `circuitBroken`、工作台预算在 PG 是 `boolean`，`rs.getInt(...) == 1` 抛 `Bad value for type int : t`：采集租约续租、精选预约与工作台预算读取全挂 | 改 `rs.getBoolean(...)`；pgjdbc 对 int2/int4 仍按 `!= 0` 返回，MySQL 时代语义不变 |
| R8 | `CAST(x AS SIGNED)`（M7） | PG 无 `signed` 类型，报 `type "signed" does not exist`：工作台 5 处、`NewsLifecycleService` 2 处的 status/featured 读数 | 去掉 CAST（列本身是 smallint）；需要整型的表达式用 `CASE WHEN … THEN 1 ELSE 0 END`——MySQL 不接受 `CAST(x AS INTEGER)`，所以这不是「换成另一种 CAST」能解决的 |
| R9 | `SUM(status = 'FAILED')`（M7） | PG 没有 `sum(boolean)`，调度状态接口整条查询失败 | `SUM(CASE WHEN … THEN 1 ELSE 0 END)`，计数语义逐字一致 |
| R10 | 未定型参数 `? IS NULL`（M7） | `could not determine data type of parameter $3`：精选按 `logicalCallSha256` 的幂等查询在 PG 上直接失败 | `?::text IS NULL`；MySQL 侧同一表达式照常成立 |
| R11 | MyBatis `Map` 返回的别名大小写（M7） | `selectVariants` 的 `replyToId/variantId/firstId` 与 `summarizeOwned` 的 `toolName/toolStatus/cnt` 在 PG 折叠成小写；MyBatis 给的是普通 `HashMap`（不像 Spring `ColumnMapRowMapper` 的 `LinkedCaseInsensitiveMap`），消息变体重放读到空、管理端工具审计 JSON 键名改名 | 别名加引号 `AS "toolName"`，两种数据库同义——与 R2 同一条策略。**推论有限度**：`JdbcTemplate.queryForList/queryForMap` 的读点不必改（Spring 的 `LinkedCaseInsensitiveMap` 兜住），`CollaborationRunTest` 33/33 在 PG 上复核过 |
| R12 | MyBatis-Plus `IdType.AUTO` 的 `keyColumn`（M7） | `AgentToolAudit` 的字段名是 `auditId`，MP 把它当 `keyColumn` 交给 pgjdbc，拼成 `RETURNING "auditId"`（带引号 → 大小写敏感），而列实际是未加引号的 `auditid`：插入报 `column "auditId" does not exist`，`ToolAuditService` 按设计吞掉审计写失败，**审计行静默丢失** | `@TableId(value = "auditid", type = IdType.AUTO)`；其余 AUTO 主键的字段名都是 `id`（已折叠），无需改动。探针实测 `PROBE_OK id=4` 后删除 |

M6 的验收语义也修了一处：套件入口一度用抛异常校验环境变量，`mvn test` 不设变量时
203 条「opt-in 跳过」变成红色错误；改为 `assumeIsolated`（未设置 → Assumptions 跳过，
设置错库 → 失败），子 JVM 仍用 `requireIsolated`（必须失败）。`backend-unit` 跳测门禁 exit 0。

P1-3 的四处 SQL 已在真实 PG 上用回滚事务逐条验证：

```
candidate vs LAST_INSERT_ID -> INSERT ... RETURNING id      OK
controlled reference arrays -> '[]','[]' + boolean false    OK (istest=0)
collaboration empty object -> '{}'                          OK (rolestates={})
```

## 7. NOT_RUN 清单

| 项 | 原因 |
|---|---|
| `FlywayMySqlIntegrationTest` 的 7 条（原 MySQL 历史套件） | **归档（明确记录）**：这 7 条断言的对象是已退役的 `db/migration` MySQL 历史本身（首轮 apply、二次幂等、checksum 被篡改即拒）。PG 线已有等价且更强的门禁：`flyway-postgres` 空库全量 migrate + validate、本轮新增的「二次 migrate `MIGRATED=0`」与「篡改 `flyway_schema_history` 必须 exit ≠ 0 且报 `checksum mismatch`」。业务套件不再依赖 MySQL 历史，套件随之删除 |
| `RAGEvaluationTest`（2 条） | 需要真实语料与付费嵌入，未经授权不运行（M7 维持 BLOCKED） |
| 远端 CI 执行 | 首轮 run `46042136133`（HEAD `25eabef9`）：24 检查中 20 绿、3 红（docs-contract / product-contract / docker-lifecycle，均为契约脚本未随迁移更新），已在 `41f8cb4e` 修复；最终 HEAD 的 run 见 §5.7 |
| 真实知识库向量导入 | 需授权；演练只覆盖合成数据 |
| 真实语料的 RAG 端到端 | 需真实语料与付费嵌入（未授权）。合成语料上的检索、过滤、隔离、分数口径已在 `PgVectorStoreIntegrationTest`（11 条）里覆盖 |

## 8. 生产切换阻塞与授权清单（BLOCKED_PENDING_AUTHORIZATION）

1. **数据**：真实 MySQL 导出与 Chroma 向量导入需要在获得授权后对生产只读副本执行；
   本仓库只交付工具 + 合成演练证据。
   - **时区分类（必做）**：先在源库确认 `serverTimezone` 与写入口径，把「SQL 里 `UTC_TIMESTAMP()`/`UTC_DATE()`
     写入的列」和「JDBC / `DEFAULT CURRENT_TIMESTAMP` 按会话时区写入的列」分开，逐列用
     `--column-timezone TABLE.COLUMN=ZONE` 指定；只给一个全局 `--source-timezone` 会让其中一类整体偏移。
2. **向量**：禁止对真实知识库重嵌入；导入工具保证 embedding 原样搬运，但仍需业务方确认集合与维度（1024）。
3. **CI/仓库**：分支保护与必需检查列表需要 owner 调整（当前仍是 MySQL 时代的 job 集合）；
   Draft PR 必须保持 Draft。
4. **部署**：反向代理需 `proxy_set_header Host $host`（`SameOriginFilter` 前提）；连接串必须带
   `stringtype=unspecified`，否则启动闸门会拒绝启动。
5. **Flyway 版本**：11.7.2（Boot 3.5.6 默认）在 PG 18.7 上每次迁移都打
   「latest supported version of PostgreSQL is 17」警告，官方未验证 18。M7 用 `javap` 逐版本
   核对 `PostgreSQLDatabase` 的支持上限常量，把 `flyway.version` 抬到 **11.14.1**（11.14.0 起该常量
   为 18，11.14.1 是这一线的最后一个补丁版）；只动 Flyway 两个 artifact，不动 Spring Boot / Spring AI 栈。
   PG 18.7 实测：V1–V7 migrate + validate 全过，零警告（§5.10）。
6. **镜像**：CI 与本机测试入口统一钉 `pgvector/pgvector:pg18@sha256:38681dc6…`（linux/amd64 单架构清单），
   `scripts/tests/test-docker-image-pins.sh` 23/23 校验 `verify.yml` / `docker-compose.yml` /
   `docker-images.env` / `verify-pg.sh` 四处一致。
7. **回滚**：切换前保留 MySQL 只读快照与 Chroma 卷；`scripts/migration/*` 全部支持 dry-run 与幂等重跑。

## 9. 独立评审

- 评审 A（数据库与并发）：**BLOCK → 已整改**。发现 1 个 P0（`InferenceCacheMapper` 的 `ON DUPLICATE KEY`）、
  3 个 P1（`JSON_LENGTH`、生产缺 `lock_timeout`、迁移工具单一源时区）、5 个 P2（撞键回读事务前提、生成列触发器、
  `utc_timestamp(fsp)`、`date_format` 未映射码、迁移工具外键「验证」恒为 0、Flyway 默认账号）。P0/P1 全部修复并
  在真实 PG 上验证；P2 中 V6 两个 shim 与 FK 检查已修，其余以注释/文档固化。复评结论见 PR 检查与 §5.7。
- 评审 B（RAG 与业务契约）：**FIX_P0_P1 → 已整改，无 P0**。2 个 P1：`Document.score` 由相似度变成距离
  （侧栏相关度反向）、`PgVectorStore` 建 bean 与 Flyway 的顺序依赖；均已修复（存储边界换算包装 +
  `@DependsOnDatabaseInitialization`，空库实测 + 单测）。P2：导入缺内容/元数据/向量一致性校验（已加，负例退出码 2）、
  维度漂移（启动即拒绝）、词法通道全表扫（V7 索引）、Chroma 退役残留（探测器与演示测试删除、文档改口）、
  seeder 标识符校验（已加）；owner 过滤（含 19 位雪花号与系统语料）与词法 SQL 新增真实 PG 用例。

## 10. 复现步骤

```bash
# 1) 一次性 PG（或复用 scripts/verify-pg.sh 的容器）
docker run -d --name mig-pg -e POSTGRES_PASSWORD=postgres -p 15432:5432 pgvector/pgvector:pg18

# 2) 角色 + schema（必须；boolean cast 也在这里）
docker exec -i mig-pg psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -v migrator_password=... -v app_password=... -f - < scripts/postgresql/bootstrap-roles.sql

# 3) Flyway（独立 PG 历史）
java -cp target/classes:$(cat target/verify-pg-classpath.txt) scripts/postgresql/FlywayMigrate.java \
  'jdbc:postgresql://127.0.0.1:15432/iris?currentSchema=diagnosis'

# 4) 验证
scripts/verify-pg.sh            # L1 门禁
scripts/verify-pg.sh --full     # 全量后端套件
scripts/migration/run-drill.sh  # 关系数据演练
python scripts/migration/chroma_vector_drill.py --chroma-url ... --target-dsn ...
```
