#!/usr/bin/env bash
# ============================================================================
# Iris · relational migration + backup/restore drill (disposable containers only)
#
# Runs the full rehearsal in one shot against ISOLATED disposable instances:
#
#   1. MySQL: (re)create <db>_drill from the current schema, load synthetic rows
#   2. PostgreSQL: (re)create <db>_drill, bootstrap roles, run db/postgresql
#   3. Dry-run the migration plan, then the real load (resume-capable state file)
#   4. Verify: row counts, primary-key digests, sequence sync, FK validation,
#      spot checks (instant, unicode, JSON)
#   5. Backup/restore: pg_dump the drill database and restore it into a second
#      isolated database; compare counts and validate the Flyway history
#
# SAFETY: every database name must end in `_drill` / `_restore`; the script never
# drops volumes and never connects to anything but the containers named below.
# It is a rehearsal tool. It is NOT the production cutover.
#
# Usage: scripts/migration/run-drill.sh
# Env:  MYSQL_CONTAINER=mig-mysql MYSQL_PORT=13306 PGDATA_CONTAINER=mig-pg PG_PORT=15432
#       IRIS_FLYWAY_PASSWORD=migrator-password IRIS_APP_PASSWORD=app-password
# ============================================================================
set -euo pipefail

# All container files are streamed over stdin/stdout: no /tmp paths in docker
# arguments, so the host shell's path rewriting cannot bite (and it breaks the
# Maven launcher if disabled globally).

MYSQL_CONTAINER="${MYSQL_CONTAINER:-mig-mysql}"
PG_CONTAINER="${PG_CONTAINER:-mig-pg}"
MYSQL_PORT="${MYSQL_PORT:-13306}"
PG_PORT="${PG_PORT:-15432}"
MYSQL_DB="${MYSQL_DB:-diagnosis_drill}"
PG_DB="${PG_DB:-iris_drill}"
PG_RESTORE_DB="${PG_RESTORE_DB:-iris_restore}"
FLYWAY_PASSWORD="${IRIS_FLYWAY_PASSWORD:-migrator-password}"
APP_PASSWORD="${IRIS_APP_PASSWORD:-app-password}"
STATE_DIR="${STATE_DIR:-.migration-state}"
EVIDENCE="${STATE_DIR}/evidence"
JAVA_BIN="${JAVA_BIN:-java}"
MVN_BIN="${MVN_BIN:-mvn}"
PYTHON_BIN="${PYTHON_BIN:-python}"

[[ "$MYSQL_DB" == *_drill ]] || { echo "FATAL: MYSQL_DB must end in _drill"; exit 3; }
[[ "$PG_DB" == *_drill ]] || { echo "FATAL: PG_DB must end in _drill"; exit 3; }
[[ "$PG_RESTORE_DB" == *_restore ]] || { echo "FATAL: PG_RESTORE_DB must end in _restore"; exit 3; }

mkdir -p "$EVIDENCE"
LOG="$EVIDENCE/drill-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

step() { printf '\n=== %s ===\n' "$*"; }
die() { echo "FAIL: $*"; exit 1; }

msql() { docker exec "$MYSQL_CONTAINER" sh -c "mysql -uroot -proot -N -e \"$1\"" 2>/dev/null; }
psql_super() { docker exec -e PGPASSWORD=postgres "$PG_CONTAINER" psql -U postgres -v ON_ERROR_STOP=1 -Atc "$1"; }
psql_db() { docker exec -e PGPASSWORD=postgres "$PG_CONTAINER" psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -Atc "$2"; }

step "1. MySQL source schema ($MYSQL_DB)"
msql "DROP DATABASE IF EXISTS $MYSQL_DB; CREATE DATABASE $MYSQL_DB CHARACTER SET utf8mb4;"
docker exec "$MYSQL_CONTAINER" sh -c \
  "mysqldump -uroot -proot --no-data --skip-add-drop-table diagnosis 2>/dev/null | mysql -uroot -proot $MYSQL_DB"
echo "tables=$(msql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$MYSQL_DB'")"

step "1b. Synthetic fixture"
"$PYTHON_BIN" scripts/migration/generate-drill-fixture.py \
  --host 127.0.0.1 --port "$MYSQL_PORT" --user root --password root --db "$MYSQL_DB" | tail -1

step "2. PostgreSQL target ($PG_DB)"
psql_super "DROP DATABASE IF EXISTS $PG_DB;" >/dev/null
psql_super "CREATE DATABASE $PG_DB;" >/dev/null
docker exec -i -e PGPASSWORD=postgres "$PG_CONTAINER" psql -U postgres -d "$PG_DB" \
  -v ON_ERROR_STOP=1 -v migrator_password="$FLYWAY_PASSWORD" -v app_password="$APP_PASSWORD" \
  -f - < scripts/postgresql/bootstrap-roles.sql >/dev/null
"$MVN_BIN" -o -q resources:resources
CP_FILE="$EVIDENCE/.classpath.txt"
"$MVN_BIN" -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
IRIS_FLYWAY_PASSWORD="$FLYWAY_PASSWORD" "$JAVA_BIN" -cp "target/classes;$(cat "$CP_FILE")" \
  scripts/postgresql/FlywayMigrate.java "jdbc:postgresql://127.0.0.1:$PG_PORT/$PG_DB?currentSchema=diagnosis" \
  | grep -E "MIGRATED|VALIDATE"

DSN="host=127.0.0.1 port=$PG_PORT dbname=$PG_DB user=iris_migrator password=$FLYWAY_PASSWORD"
STATE_FILE="$STATE_DIR/relational-drill.json"
rm -f "$STATE_FILE"

step "3a. Dry run"
"$PYTHON_BIN" scripts/migration/mysql_to_postgres.py \
  --source-host 127.0.0.1 --source-port "$MYSQL_PORT" --source-db "$MYSQL_DB" \
  --source-user root --source-password root --target-dsn "$DSN" --source-timezone UTC --dry-run | tail -1

step "3b. Real load (verified per table, resumable)"
"$PYTHON_BIN" scripts/migration/mysql_to_postgres.py \
  --source-host 127.0.0.1 --source-port "$MYSQL_PORT" --source-db "$MYSQL_DB" \
  --source-user root --source-password root --target-dsn "$DSN" --source-timezone UTC \
  --state-file "$STATE_FILE" > "$EVIDENCE/drill-load.txt" \
  || die "migration load failed; see $EVIDENCE/drill-load.txt"
echo "ok_tables=$(grep -c '^OK' "$EVIDENCE/drill-load.txt")"
grep -E "SEQUENCES|FOREIGN_KEYS" "$EVIDENCE/drill-load.txt"

step "4. Post-load checks (as the low-privilege app role)"
psql_db "$PG_DB" "SELECT 'users=' || count(*) FROM diagnosis.t_user"
psql_db "$PG_DB" "SELECT 'instant_utc=' || to_char(min(createTime) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM diagnosis.t_user"
psql_db "$PG_DB" "SELECT 'fk_rows=' || count(*) FROM diagnosis.t_controlled_reference_chunk"
# 12 explicit ids were imported; the widened identity sequence must not reuse 1..12.
identity=$(psql_db "$PG_DB" "INSERT INTO diagnosis.t_agent_message (sessionId, role, content, createdTime) VALUES ('drill-session', 'user', 'post-import row', now()) RETURNING id" | head -1)
echo "identity_after_import=$identity"
[[ "$identity" -gt 12 ]] || die "identity sequence not synced after import: $identity"
psql_db "$PG_DB" "SELECT 'history=' || count(*) FROM pg_class WHERE relname = 'flyway_schema_history'" >/dev/null

step "5. Backup and restore into $PG_RESTORE_DB"
# Give the backup a vector payload too: a dump that "works" because rag was empty
# proves nothing about the vector table (extension type, index, metadata JSON).
psql_db "$PG_DB" "INSERT INTO rag.iris_knowledge_vectors (id, content, metadata, embedding)
  SELECT 'drill_vec_' || n, 'drill vector ' || n,
         CAST(('{\"ownerUserId\":7,\"documentId\":\"drill\",\"chunkIndex\":' || n || '}') AS json),
         array_prepend(1.0::real, array_fill(0.0::real, ARRAY[1023]))::vector
  FROM generate_series(1, 3) AS n" >/dev/null
psql_super "DROP DATABASE IF EXISTS $PG_RESTORE_DB;" >/dev/null
psql_super "CREATE DATABASE $PG_RESTORE_DB;" >/dev/null
docker exec -e PGPASSWORD=postgres "$PG_CONTAINER" sh -c "pg_dump -U postgres -Fc -d $PG_DB"   | docker exec -i -e PGPASSWORD=postgres "$PG_CONTAINER" sh -c "pg_restore -U postgres -d $PG_RESTORE_DB --no-owner --no-privileges"

for table in t_user t_agent_message t_diagnosis_task t_controlled_reference_chunk; do
  a=$(psql_db "$PG_DB" "SELECT count(*) FROM diagnosis.$table")
  b=$(psql_db "$PG_RESTORE_DB" "SELECT count(*) FROM diagnosis.$table")
  [[ "$a" == "$b" ]] || die "restore mismatch $table: $a != $b"
  echo "restore_ok $table=$a"
done
restored_vectors=$(psql_db "$PG_RESTORE_DB" "SELECT count(*) FROM rag.iris_knowledge_vectors")
echo "restore_ok rag.iris_knowledge_vectors=$restored_vectors"
[[ "$restored_vectors" == "3" ]] || die "vector rows lost in backup/restore: $restored_vectors"
history=$(psql_db "$PG_RESTORE_DB" "SELECT count(*) FROM diagnosis.flyway_schema_history")
echo "restore_ok flyway_history_rows=$history"

step "RESULT"
echo "PASS: relational migration drill + backup/restore completed"
echo "log: $LOG"
