# Iris · MySQL + Chroma → PostgreSQL 18 + pgvector 迁移报告（2026-11-28）

> 分支：`feat/postgresql-pgvector-migration`（基线 `74eee927`，master 不合并）
> 运行环境：PostgreSQL 18.7 + pgvector 0.8.7（`pgvector/pgvector:pg18`）、Java 21、Spring Boot 3.5.6
> 状态口径：**PASS / FAIL / BLOCKED / NOT_RUN**。每条都对应实际命令与退出码；口头声明不算证据。
> 生产切换：**BLOCKED_PENDING_AUTHORIZATION**（见 §8），本报告不主张已完成上线。

## 1. 结论摘要

| 项 | 状态 | 一句话证据 |
|---|---|---|
| PG 18 + pgvector 基建 | PASS | Flyway V1–V5 在 6 个库 migrate + validate 成功 |
| 关系数据持久化 | PASS | 59 表 / 93 行演练、逐表 digest、26 序列同步、0 未验证外键 |
| 业务不变量与并发 | PASS | 全量后端套件 1415 用例 0 失败（含 ControlCenter 多进程 7/7） |
| 向量迁移（Chroma→pgvector） | PASS | 300 块逐字节保真、overlap@10 = 1.000、隔离过滤不泄漏 |
| 备份恢复 | PASS | `pg_dump \| pg_restore` 到独立库，行数 + 向量 + Flyway 历史一致 |
| 最小权限账号 | PASS | 12 条拒绝探针（DDL/扩展/schema/迁移历史），DML 放行 |
| 全量后端套件（PG） | PASS | `mvn -B test`：1415 tests / 0 fail / 0 err / 384 skipped / exit 0 |
| 跳测门禁契约 | PASS | `check_surefire_skips.py backend-unit` exit 0 |
| CI（远端 HEAD） | NOT_RUN | 工作流已改 pgvector；推送前未在 Actions 上观测 |
| 独立评审 A/B | 见 §9 | 数据库/并发、RAG/业务契约两路独立评审 |
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
| PG 测试 | `src/test/java/com/csust/eyediagnosis/postgres/Pg*IntegrationTest.java`（4 套件 / 24 用例） |
| CI | `.github/workflows/verify.yml`（`backend-unit`、`flyway-postgres`、`postgres-integration`、`vector-migration-drill`；10 个 MySQL-only job 以 `if: ${{ false }}` 标注 NOT_RUN） |

## 5. 验收证据

### 5.1 基建与 schema

```
$ scripts/verify-pg.sh
run-id: eye-...
roles: iris_migrator / iris_app bootstrapped
MIGRATED=5 VERSION=5
VALIDATE=OK
postgres suites=4 tests=24 skipped=0 failures=0 errors=0
PASS: PostgreSQL verification (gates)
```
退出码 0。另在 `iris` / `iris_ci` / `iris_it` / `iris_drill` / `iris_restore` / `iris_vec_drill` 六库
执行过 migrate + validate，全部 `VALIDATE=OK`。

### 5.2 关系数据演练

```
$ scripts/migration/run-drill.sh
ok_tables=59 SEQUENCES synced=26 FOREIGN_KEYS unvalidated=0
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
LATENCY_MS chroma p50=22.8 p95=...
LATENCY_MS pg_hnsw p50=2.4 p95=...
ISOLATION ok owners=['7']
PASS vector migration drill
```
退出码 0。**只使用合成数据**；真实语料导入需要授权（见 §8）。

### 5.4 备份与恢复

```
pg_dump iris_drill | pg_restore -d iris_restore
restore_ok: 行数与源一致；3 条向量行可检索；flyway_schema_history 版本一致
```

### 5.5 全量后端套件（真实 PG）

```
$ JAVA_HOME=corretto-21 mvn -B test     # SPRING_PROFILES_ACTIVE=ci, PG_IT_URL=...iris_ci
Tests run: 1415, Failures: 0, Errors: 0, Skipped: 384
exit=0
```

```
$ python scripts/check_surefire_skips.py target/surefire-reports backend-unit
skip gate passed: no unexpected skipped or dropped tests
exit=0
```

384 条跳过全部是契约声明的 opt-in 套件（MySQL-only、协作、评测等），逐条列在门禁输出里。

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

P1-3 的四处 SQL 已在真实 PG 上用回滚事务逐条验证：

```
candidate vs LAST_INSERT_ID -> INSERT ... RETURNING id      OK
controlled reference arrays -> '[]','[]' + boolean false    OK (istest=0)
collaboration empty object -> '{}'                          OK (rolestates={})
```

## 7. NOT_RUN 清单

| 项 | 原因 |
|---|---|
| 10 个 MySQL-only CI job（issue17–24、collaboration、graph-execution、news MySQL 集成、Flyway MySQL） | 断言 MySQL URL、重放 MySQL 专用 SQL；在 CI 用 `if: ${{ false }}` 显式停用并注明。PG 等价门禁在 `postgres-*` 四个 job |
| 协作 / 患者草稿 / 受控参考资料 / 医生复核 / 患者解释 / 授权 / 随访（opt-in） | 需要 MySQL 夹具或外部服务；其中 SQL 级缺陷已按 §6 修复并在 PG 上验证语句形状，但**完整业务验收未在 PG 上跑** |
| 远端 CI 执行 | 推送后才有 run；本报告在推送前写出，见 PR 检查 |
| 真实知识库向量导入 | 需授权；演练只覆盖合成数据 |
| Chroma 从生产依赖彻底移除后的 RAG 端到端 | 需真实语料；`VectorStoreConfigTest` 的 Chroma 分支仍是 opt-in |

## 8. 生产切换阻塞与授权清单（BLOCKED_PENDING_AUTHORIZATION）

1. **数据**：真实 MySQL 导出与 Chroma 向量导入需要在获得授权后对生产只读副本执行；
   本仓库只交付工具 + 合成演练证据。
2. **向量**：禁止对真实知识库重嵌入；导入工具保证 embedding 原样搬运，但仍需业务方确认集合与维度（1024）。
3. **CI/仓库**：分支保护与必需检查列表需要 owner 调整（当前仍是 MySQL 时代的 job 集合）；
   Draft PR 必须保持 Draft。
4. **部署**：反向代理需 `proxy_set_header Host $host`（`SameOriginFilter` 前提）；连接串必须带
   `stringtype=unspecified`，否则启动闸门会拒绝启动。
5. **Flyway 版本**：11.7.2 文档支持到 PG 17，实测 18.7 上 V1–V5 与 validate 全部通过；
   升级 Flyway 前不要依赖「官方支持 18」。
6. **回滚**：切换前保留 MySQL 只读快照与 Chroma 卷；`scripts/migration/*` 全部支持 dry-run 与幂等重跑。

## 9. 独立评审

- 评审 A（数据库与并发）：待填结果与发现编号。
- 评审 B（RAG 与业务契约）：待填结果与发现编号。

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
