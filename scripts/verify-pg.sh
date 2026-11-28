#!/usr/bin/env bash
# ============================================================================
# Iris · PostgreSQL verification entry point (real PG 18 + pgvector)
#
# The MySQL-based scripts/verify.sh stays as legacy history for the retired
# line; this script is the PostgreSQL counterpart:
#
#   scripts/verify-pg.sh                # L1: PostgreSQL integration gates
#   scripts/verify-pg.sh --full         # L2: whole backend suite against PG
#   scripts/verify-pg.sh --keep         # keep the container for inspection
#
# It starts ONE disposable pgvector container through the shared docker test
# harness (run-id + eye.* labels, teardown on exit), bootstraps the least-
# privilege roles, migrates the real Flyway line, and runs Maven with the
# PostgreSQL test profile. It never touches the dev compose project, never
# prunes, and fails if any gate was skipped.
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# java.exe wants ';' on Windows; the JVM on CI wants ':'.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;; *) CP_SEP=':' ;; esac
# Some Windows setups ship a python3 shim that cannot start; prefer a working one.
if command -v python3 >/dev/null 2>&1 && python3 -c "pass" >/dev/null 2>&1; then PY=python3; else PY=python; fi

MODE="gates"
for arg in "$@"; do
  case "$arg" in
    --full) MODE="full" ;;
    --keep) export EYE_DOCKER_KEEP=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 3 ;;
  esac
done

# shellcheck source=scripts/lib/docker-images.env
. "$ROOT/scripts/lib/docker-images.env"
# shellcheck source=scripts/lib/docker-test-harness.sh
. "$ROOT/scripts/lib/docker-test-harness.sh"
eye_docker_init
eye_docker_trap_teardown
eye_docker_require_image "$PGVECTOR_IMAGE"

APP_PASSWORD="verify-app-password"
MIGRATOR_PASSWORD="verify-migrator-password"
DB="iris_verify"

echo "run-id: $EYE_DOCKER_RUN_ID"
NAME=$(eye_docker_container_name iris-pg)
eye_docker_run_named "$NAME" -d \
  -e POSTGRES_DB="$DB" \
  -e POSTGRES_PASSWORD=verify-superuser-password \
  -e IRIS_MIGRATOR_PASSWORD="$MIGRATOR_PASSWORD" \
  -e IRIS_APP_PASSWORD="$APP_PASSWORD" \
  -p 0:5432 "$PGVECTOR_IMAGE" >/dev/null
PG_PORT=$(eye_docker_host_port "$NAME" 5432)
echo "postgres: 127.0.0.1:$PG_PORT/$DB"

# Must probe TCP, not the unix socket: the entrypoint runs a temporary init server
# on the socket that reports ready and then shuts down, which makes the following
# psql fail with "the database system is shutting down". The real server only
# listens on 127.0.0.1 after init completes.
eye_docker_wait_ready 60 docker exec "$NAME" pg_isready -h 127.0.0.1 -U postgres -d "$DB"

# 全量套件里有登录/会话验收（ControlCenterAcceptanceTest、FullApplicationAcceptanceTest 等），
# 它们需要 Redis。CI 的 backend-unit job 带 redis service；本地必须同样提供一个，
# 否则那些用例会以 50000「系统错误」失败，看起来像数据库问题。
if [ "$MODE" = "full" ]; then
  eye_docker_require_image redis:7-alpine
  REDIS_NAME=$(eye_docker_container_name iris-redis)
  eye_docker_run_named "$REDIS_NAME" -d -p 0:6379 redis:7-alpine >/dev/null
  eye_docker_wait_ready 30 docker exec "$REDIS_NAME" redis-cli ping
  export SPRING_DATA_REDIS_PORT
  SPRING_DATA_REDIS_PORT=$(eye_docker_host_port "$REDIS_NAME" 6379)
  echo "redis: 127.0.0.1:$SPRING_DATA_REDIS_PORT"
fi

docker exec -i -e PGPASSWORD=verify-superuser-password "$NAME" \
  psql -U postgres -d "$DB" -v ON_ERROR_STOP=1 \
  -v migrator_password="$MIGRATOR_PASSWORD" -v app_password="$APP_PASSWORD" \
  -f - < scripts/postgresql/bootstrap-roles.sql >/dev/null
echo "roles: iris_migrator / iris_app bootstrapped"

# 业务回归套件（M7 从 MySQL 重新基线的那 11 个）要两个独立库：business_test 由套件
# 自己按 db/postgresql 迁移；coordination_poc 归最低权限角色 poc 所有，套件运行时建表。
# 只在 --full 下创建，L1 门禁不需要它们。
if [ "$MODE" = "full" ]; then
  docker exec -i -e PGPASSWORD=verify-superuser-password "$NAME" \
    psql -U postgres -d postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE DATABASE business_test;
CREATE ROLE poc LOGIN PASSWORD 'poc';
CREATE DATABASE coordination_poc OWNER poc;
SQL
  echo "business databases: business_test / coordination_poc"
fi

mvn -o -q resources:resources
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/verify-pg-classpath.txt

# Two Windows traps, both hit on this machine:
# 1) `java` on PATH can be the Oracle javapath shim, which exits 127 for any
#    argument — prefer $JAVA_HOME/bin/java when it exists.
# 2) The full dependency classpath is ~29 KB and exceeds the Windows
#    CreateProcess command-line limit (~32 KB once the rest of the command is
#    added), which bash reports as exit 127 with no output. Passing it through
#    a Java @argfile avoids the limit; forward slashes keep argfile escaping out.
JAVA_BIN=java
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi
"$PY" - <<'PY'
import io
bs = chr(92)
cp = "target/classes;" + io.open('target/verify-pg-classpath.txt', encoding='utf-8').read().strip().replace(bs, '/')
io.open('target/verify-pg-argfile.txt', 'w', encoding='utf-8', newline='\n').write('-cp\n' + cp + '\n')
PY
IRIS_FLYWAY_PASSWORD="$MIGRATOR_PASSWORD" "$JAVA_BIN" @target/verify-pg-argfile.txt \
  scripts/postgresql/FlywayMigrate.java \
  "jdbc:postgresql://127.0.0.1:$PG_PORT/$DB?currentSchema=diagnosis" | grep -E "MIGRATED|VALIDATE"

URL="jdbc:postgresql://127.0.0.1:$PG_PORT/$DB?currentSchema=diagnosis,rag,public&stringtype=unspecified"
export SPRING_PROFILES_ACTIVE=ci
export SPRING_DATASOURCE_URL="$URL" SPRING_DATASOURCE_USERNAME=iris_app SPRING_DATASOURCE_PASSWORD="$APP_PASSWORD"
export IRIS_FLYWAY_USER=iris_migrator IRIS_FLYWAY_PASSWORD="$MIGRATOR_PASSWORD"
export PG_IT_URL="$URL" PG_IT_APP_USER=iris_app PG_IT_APP_PASSWORD="$APP_PASSWORD"
export PG_IT_MIGRATOR_USER=iris_migrator PG_IT_MIGRATOR_PASSWORD="$MIGRATOR_PASSWORD"

# --full 还要跑 11 个 business 套件（CI 里是 postgres-integration 的第二段）。
# 它们用超级用户按 db/postgresql 自建 schema，所以给 PG_TEST_* 而不是 iris_app。
if [ "$MODE" = "full" ]; then
  export BUSINESS_TEST_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/business_test"
  export COORDINATION_POC_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/coordination_poc"
  export COORDINATION_POC_USER=poc COORDINATION_POC_PASSWORD=poc
  export PG_TEST_USER=postgres PG_TEST_PASSWORD=verify-superuser-password
fi

# Stale XMLs from an earlier run would otherwise be counted again (and can make a
# separately-run skip gate read a suite that no longer exists). Always start clean.
rm -rf target/surefire-reports

set +e
if [ "$MODE" = "full" ]; then
  mvn -B test
else
  mvn -B -Dtest='Pg*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test
fi
MAVEN_EXIT=$?
set -e

"$PY" - <<'PY'
import glob, sys
from xml.etree import ElementTree
reports = glob.glob("target/surefire-reports/TEST-com.csust.eyediagnosis.postgres.*.xml")
if len(reports) < 4:
    sys.exit(f"expected >=4 postgres suites, found {len(reports)} (did they skip?)")
tests = skipped = failures = errors = 0
for report in reports:
    root = ElementTree.parse(report).getroot()
    tests += int(root.get("tests", "0")); skipped += int(root.get("skipped", "0"))
    failures += int(root.get("failures", "0")); errors += int(root.get("errors", "0"))
print(f"postgres suites={len(reports)} tests={tests} skipped={skipped} failures={failures} errors={errors}")
if skipped or failures or errors:
    sys.exit("postgres gates did not all pass")
PY

if [ "$MODE" = "full" ]; then
  # 业务套件的计数/零跳过契约：跳过一条 business 用例就不再算「全量绿」。
  "$PY" scripts/check_surefire_skips.py target/surefire-reports business
fi

if [ "$MAVEN_EXIT" -ne 0 ]; then
  echo "FAIL: maven exited $MAVEN_EXIT" >&2
  exit "$MAVEN_EXIT"
fi
echo "PASS: PostgreSQL verification ($MODE)"
