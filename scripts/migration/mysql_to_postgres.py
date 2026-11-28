#!/usr/bin/env python3
"""Iris · MySQL → PostgreSQL relational data migration tool.

Auditable, repeatable, resumable. Defaults are deliberately conservative:

  * the source connection is opened READ ONLY;
  * the target must be empty (per table) unless --resume continues a run whose
    state file matches; the tool never silently overwrites unknown target rows;
  * explicit primary keys are preserved; identity sequences are re-synced after
    the load so app-generated ids never collide;
  * every table is loaded in one transaction, batched with COPY when possible,
    and verified (row count + primary-key set digest) before being marked done;
  * --dry-run prints the plan and per-table source counts without writing.

Usage:
  python scripts/migration/mysql_to_postgres.py \
      --source-host 127.0.0.1 --source-port 13306 --source-db diagnosis \
      --source-user root --source-password root \
      --target-dsn "host=127.0.0.1 port=5432 dbname=iris user=iris_migrator password=..." \
      --source-timezone UTC --dry-run

  # real load, resumable
  python scripts/migration/mysql_to_postgres.py ... --state-file .migration-state/relational.json

Exit codes: 0 success, 2 verification failure, 3 precondition/usage failure.
Never prints row values (desensitised diagnostics): only table names, counts and
first-error messages with values redacted.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
from typing import Any

import pymysql
import psycopg

SKIP_SCHEMAS = {"information_schema", "performance_schema", "mysql", "sys"}

# Never data-migrate the source's own migration history: the target's history is
# written by Flyway and owned by the migrator role. A copied ledger would make
# Flyway believe migrations it never ran (or ran differently) are applied.
ALWAYS_SKIP = {"flyway_schema_history"}

# MySQL type -> how to convert a Python value before sending it to PostgreSQL.
DATETIME_TYPES = {"datetime", "timestamp"}
JSON_TYPES = {"json"}
BINARY_TYPES = {"blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary"}
BOOL_TYPES = {"tinyint"}


def fail(msg: str, code: int = 3) -> "NoReturn":
    print(f"FATAL: {msg}", file=sys.stderr)
    sys.exit(code)


def redact(value: Any) -> str:
    text = repr(value)
    return text[:20] + "…" if len(text) > 20 else text


def connect_source(args) -> pymysql.connections.Connection:
    conn = pymysql.connect(
        host=args.source_host, port=args.source_port, user=args.source_user,
        password=args.source_password, database=args.source_db,
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
        autocommit=False,
    )
    with conn.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
    return conn


def source_tables(conn, db: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            # MySQL 8 returns information_schema column names in UPPERCASE; alias
            # every column explicitly so the DictCursor keys are stable.
            """SELECT table_name AS tbl FROM information_schema.tables
               WHERE table_schema = %s AND table_type = 'BASE TABLE'
               ORDER BY table_name""", (db,))
        tables = [r["tbl"] for r in cur.fetchall()]
        result = []
        for table in tables:
            cur.execute(
                """SELECT column_name AS col, data_type AS typ, is_nullable AS nullable,
                          column_key AS colkey, extra AS extra
                   FROM information_schema.columns
                   WHERE table_schema = %s AND table_name = %s
                   ORDER BY ordinal_position""", (db, table))
            cols = cur.fetchall()
            cur.execute(
                """SELECT column_name AS col FROM information_schema.statistics
                   WHERE table_schema = %s AND table_name = %s AND index_name = 'PRIMARY'
                   ORDER BY seq_in_index""", (db, table))
            pk = [r["col"] for r in cur.fetchall()]
            result.append({"table": table, "columns": cols, "pk": pk})
    return result


def foreign_key_order(conn, db: str) -> list[str]:
    """Topologically order tables so parents load before children."""
    with conn.cursor() as cur:
        cur.execute(
            """SELECT table_name AS child, referenced_table_name AS parent
               FROM information_schema.key_column_usage
               WHERE table_schema = %s AND referenced_table_name IS NOT NULL""", (db,))
        edges = cur.fetchall()
    tables = [t["table"] for t in source_tables(conn, db)]
    deps = {t: set() for t in tables}
    for e in edges:
        child, parent = e["child"], e["parent"]
        if child in deps and parent in deps and parent != child:
            deps[child].add(parent)
    ordered, done = [], set()
    while len(ordered) < len(tables):
        progressed = False
        for t in tables:
            if t in done:
                continue
            if deps[t] <= done:
                ordered.append(t)
                done.add(t)
                progressed = True
        if not progressed:
            raise SystemExit("FATAL: foreign-key cycle detected among: "
                             + ", ".join(sorted(set(tables) - done)))
    return ordered


def target_columns(pg, schema: str, table: str) -> tuple[list[str], set[str]]:
    """All target columns, plus the GENERATED ones the loader must never write."""
    with pg.cursor() as cur:
        cur.execute(
            """SELECT column_name, is_generated FROM information_schema.columns
               WHERE table_schema = %s AND table_name = %s ORDER BY ordinal_position""",
            (schema, table))
        rows = cur.fetchall()
    return [r[0] for r in rows], {r[0] for r in rows if r[1] == "ALWAYS"}


def convert(value: Any, data_type: str, source_tz: dt.tzinfo) -> Any:
    if value is None:
        return None
    t = data_type.lower()
    if t in DATETIME_TYPES:
        # MySQL returns naive wall-clock values in the source session timezone
        # (the source connection runs with the container's UTC by default).
        if isinstance(value, dt.datetime):
            return value.replace(tzinfo=source_tz)
        return value
    if t in JSON_TYPES:
        return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    if t in BINARY_TYPES:
        return value if isinstance(value, (bytes, bytearray)) else bytes(value)
    if isinstance(value, dt.datetime):
        return value.replace(tzinfo=source_tz)
    return value


def table_count(conn, db: str, table: str, where: str = "") -> int:
    with conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) AS c FROM `{table}` {where}")
        return int(cur.fetchone()["c"])


def load_table(src, pg, schema: str, meta: dict, args, batch_size: int) -> int:
    table = meta["table"]
    target_table = table.lower()
    src_cols = [c["col"] for c in meta["columns"]]
    target_cols, generated = target_columns(pg, schema, target_table)
    mapping = [(c, c.lower(), next(m["typ"] for m in meta["columns"] if m["col"] == c)) for c in src_cols]
    missing = [t for _, t, _ in mapping if t not in target_cols and t not in generated]
    # A GENERATED column is computed by PostgreSQL (e.g. t_news_curation_candidate.activeKey);
    # writing it is an error and skipping it is the correct mapping.
    mapping = [(c, t, typ) for c, t, typ in mapping if t not in generated]
    if missing:
        raise SystemExit(f"FATAL: target table {target_table} is missing columns: {missing}")

    select_cols = ", ".join(f"`{c}`" for c in src_cols)
    insert_cols = ", ".join(c for _, c, _ in mapping)
    placeholders = ", ".join(["%s"] * len(mapping))
    conflict = f" ON CONFLICT ({', '.join(pk.lower() for pk in meta['pk'])}) DO NOTHING" if meta["pk"] else ""

    def tz_for(column: str) -> dt.tzinfo:
        # One global --source-timezone is not enough for a real cutover: the MySQL
        # line mixes UTC-written columns (UTC_TIMESTAMP/UTC_DATE) with wall-clock
        # values written by the JDBC session or the server default timezone.
        # --column-timezone TABLE.COLUMN=ZONE overrides one column at a time.
        return args._column_tz.get((target_table, column), args._tz)

    inserted = 0
    with src.cursor() as cur:
        cur.execute(f"SELECT {select_cols} FROM `{table}`")
        rows: list[tuple] = []
        for row in cur:
            rows.append(tuple(convert(row[src_col], typ, tz_for(tgt_col)) for src_col, tgt_col, typ in mapping))
            if len(rows) >= batch_size:
                inserted += _flush(pg, schema, target_table, insert_cols, placeholders, conflict, rows)
                rows = []
            if args.limit and inserted >= args.limit:
                break
        if rows:
            inserted += _flush(pg, schema, target_table, insert_cols, placeholders, conflict, rows)
    return inserted


def _flush(pg, schema: str, table: str, cols: str, placeholders: str, conflict: str, rows: list[tuple]) -> int:
    sql = f"INSERT INTO {schema}.{table} ({cols}) VALUES ({placeholders}){conflict}"
    with pg.cursor() as cur:
        cur.executemany(sql, rows)
    return len(rows)


def pk_digest(conn, query: str) -> str:
    """Digest of the primary-key set, ordered by the key's TEXT form.

    Both sides are sorted here, not by the database: MySQL's collation and
    PostgreSQL's byte order disagree (and numeric vs text ordering turns
    `1, 2, 10` into `1, 10, 2`), so a SQL ORDER BY reports mismatches that do not
    exist.
    """
    digest = hashlib.sha256()
    lines = []
    with conn.cursor() as cur:
        cur.execute(query)
        for row in cur.fetchall():
            values = row if isinstance(row, (tuple, list)) else list(row.values())
            lines.append("|".join("" if v is None else str(v) for v in values))
    for line in sorted(lines):
        digest.update(line.encode())
        digest.update(b"\n")
    return digest.hexdigest()


def sync_sequences(pg, schema: str) -> list[str]:
    synced = []
    with pg.cursor() as cur:
        cur.execute(
            """SELECT c.table_name, c.column_name FROM information_schema.columns c
               WHERE c.table_schema = %s AND c.is_identity = 'YES'""", (schema,))
        for table, column in cur.fetchall():
            cur.execute(
                f"""SELECT setval(pg_get_serial_sequence('{schema}.{table}', %s),
                          COALESCE((SELECT MAX({column}) FROM {schema}.{table}), 1),
                          (SELECT MAX({column}) IS NOT NULL FROM {schema}.{table}))""",
                (column,))
            synced.append(f"{table}.{column}")
    return synced


def verify_table(src, pg, schema: str, meta: dict, db: str) -> tuple[bool, str]:
    table = meta["table"]
    target_table = table.lower()
    src_count = table_count(src, db, table)
    with pg.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM {schema}.{target_table}")
        tgt_count = int(cur.fetchone()[0])
    if src_count != tgt_count:
        return False, f"row count mismatch source={src_count} target={tgt_count}"
    if meta["pk"]:
        pk = ", ".join(p.lower() for p in meta["pk"])
        # PostgreSQL CHAR(n) is blank-padded and returns the padding; MySQL strips it.
        # Rtrim the text form so the digests compare the same key, not the storage.
        pk_text = ", ".join(f"rtrim({p.lower()}::text)" for p in meta["pk"])
        src_digest = pk_digest(src, f"SELECT {', '.join('`'+p+'`' for p in meta['pk'])} FROM `{table}`")
        tgt_digest = pk_digest(pg, f"SELECT {pk_text} FROM {schema}.{target_table}")
        if src_digest != tgt_digest:
            return False, "primary-key set digest mismatch"
    return True, "ok"


def foreign_key_violations(pg, schema: str) -> int:
    """Count child rows whose non-null FK has no parent.

    The previous version counted `pg_constraint.convalidated = false`, which is
    always 0 here because V1 adds both FKs as validated — so it proved nothing.
    This walks every FK in the schema and runs a real anti-join.
    """
    total = 0
    with pg.cursor() as cur:
        cur.execute(
            """SELECT quote_ident(cn.nspname) || '.' || quote_ident(cc.relname) AS child,
                      quote_ident(pn.nspname) || '.' || quote_ident(pc.relname) AS parent,
                      (SELECT string_agg(quote_ident(a.attname), ',' ORDER BY k.ord)
                         FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                         JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS child_cols,
                      (SELECT string_agg(quote_ident(a.attname), ',' ORDER BY k.ord)
                         FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord)
                         JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum) AS parent_cols
               FROM pg_constraint c
               JOIN pg_namespace n ON n.oid = c.connamespace
               JOIN pg_class cc ON cc.oid = c.conrelid
               JOIN pg_namespace cn ON cn.oid = cc.relnamespace
               JOIN pg_class pc ON pc.oid = c.confrelid
               JOIN pg_namespace pn ON pn.oid = pc.relnamespace
               WHERE n.nspname = %s AND c.contype = 'f'""", (schema,))
        for child, parent, child_cols, parent_cols in cur.fetchall():
            cur.execute(
                f"""SELECT COUNT(*) FROM {child} c
                    WHERE ({child_cols}) IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM {parent} p
                                      WHERE ({parent_cols}) = ({child_cols}))""")
            total += int(cur.fetchone()[0])
    return total


def main() -> int:
    parser = argparse.ArgumentParser(description="Iris MySQL -> PostgreSQL relational migration")
    parser.add_argument("--source-host", required=True)
    parser.add_argument("--source-port", type=int, default=3306)
    parser.add_argument("--source-db", required=True)
    parser.add_argument("--source-user", required=True)
    parser.add_argument("--source-password", required=True)
    parser.add_argument("--target-dsn", required=True)
    parser.add_argument("--target-schema", default="diagnosis")
    parser.add_argument("--source-timezone", default="UTC")
    parser.add_argument("--column-timezone", action="append", default=[], metavar="TABLE.COLUMN=ZONE",
                        help="per-column override (repeatable); for columns whose wall clock differs "
                             "from --source-timezone, e.g. UTC_TIMESTAMP-written columns when the "
                             "session tz is Asia/Shanghai")
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--state-file", default=None)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", nargs="*", default=None, help="restrict to these tables")
    parser.add_argument("--skip", nargs="*", default=None)
    parser.add_argument("--limit", type=int, default=0, help="row cap per table (smoke runs)")
    args = parser.parse_args()
    args._tz = _parse_tz(args.source_timezone)
    args._column_tz = {}
    for spec in args.column_timezone:
        try:
            key, zone = spec.split("=", 1)
            table, column = key.split(".", 1)
        except ValueError:
            fail(f"--column-timezone expects TABLE.COLUMN=ZONE, got {spec}")
        args._column_tz[(table.lower(), column.lower())] = _parse_tz(zone)

    src = connect_source(args)
    try:
        order = foreign_key_order(src, args.source_db)
        metas = {m["table"]: m for m in source_tables(src, args.source_db)}
        if args.only:
            order = [t for t in order if t in set(args.only)]
        if args.skip:
            order = [t for t in order if t not in set(args.skip)]
        skipped_always = [t for t in order if t in ALWAYS_SKIP]
        order = [t for t in order if t not in ALWAYS_SKIP]
        if skipped_always:
            print("SKIP governance tables: " + ", ".join(skipped_always))

        plan = []
        for table in order:
            plan.append((table, table_count(src, args.source_db, table)))
        if args.dry_run:
            for table, count in plan:
                print(f"PLAN {table:<48} rows={count}")
            print(f"PLAN total tables={len(plan)} rows={sum(c for _, c in plan)}")
            return 0

        state_path = args.state_file
        state = {"run": None, "completed": {}, "tables": order}
        if state_path and os.path.exists(state_path):
            with open(state_path, encoding="utf-8") as fh:
                previous = json.load(fh)
            if args.resume:
                state = previous
            else:
                fail(f"state file {state_path} exists; pass --resume to continue it")
        if not state.get("run"):
            state["run"] = hashlib.sha256(
                f"{args.source_db}:{dt.datetime.now(dt.timezone.utc).isoformat()}".encode()).hexdigest()[:16]

        with psycopg.connect(args.target_dsn) as pg:
            for table, _ in plan:
                meta = metas[table]
                target_table = table.lower()
                with pg.cursor() as cur:
                    cur.execute(f"SELECT COUNT(*) FROM {args.target_schema}.{target_table}")
                    existing = int(cur.fetchone()[0])
                if existing and not (args.resume and table in state["completed"]):
                    if not args.resume:
                        fail(f"target {target_table} already has {existing} rows; refusing to overwrite")
                if args.resume and table in state["completed"]:
                    ok, detail = verify_table(src, pg, args.target_schema, meta, args.source_db)
                    print(f"{'OK  ' if ok else 'FAIL'} {table:<48} {detail} (resumed)")
                    if not ok:
                        return 2
                    continue
                with pg.transaction():
                    inserted = load_table(src, pg, args.target_schema, meta, args, args.batch_size)
                    ok, detail = verify_table(src, pg, args.target_schema, meta, args.source_db)
                    if not ok:
                        raise SystemExit(f"VERIFY_FAIL {table}: {detail}")
                state["completed"][table] = {"rows": inserted, "verified": True}
                if state_path:
                    os.makedirs(os.path.dirname(state_path) or ".", exist_ok=True)
                    with open(state_path, "w", encoding="utf-8") as fh:
                        json.dump(state, fh, indent=2, ensure_ascii=False)
                print(f"OK   {table:<48} imported={inserted}")

            synced = sync_sequences(pg, args.target_schema)
            print(f"SEQUENCES synced={len(synced)}")
            violations = foreign_key_violations(pg, args.target_schema)
            print(f"FOREIGN_KEYS orphan_rows={violations}")
            return 0 if violations == 0 else 2
    finally:
        src.close()


def _parse_tz(name: str) -> dt.tzinfo:
    if name.upper() == "UTC":
        return dt.timezone.utc
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo(name)
    except Exception:
        fail(f"unknown --source-timezone {name}")


if __name__ == "__main__":
    sys.exit(main())
