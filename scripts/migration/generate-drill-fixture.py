#!/usr/bin/env python3
"""Iris · synthetic fixture generator for the MySQL -> PostgreSQL migration drill.

Loads deterministic, clearly-synthetic rows into an ISOLATED MySQL instance so the
migration tool can be exercised end to end (batch, resume, verification, sequence
sync) without touching any real database. It never connects to production: the
caller passes the drill source explicitly, and the script refuses a database whose
name does not end in `_drill`.

Values are derived from column metadata, never from real data. Any column type the
generator does not understand is a hard error: the drill must not silently skip a
column kind the migration tool has to handle.

Usage:
  python scripts/migration/generate-drill-fixture.py \
      --host 127.0.0.1 --port 13306 --user root --password root --db diagnosis_drill \
      [--rows 5]
"""
from __future__ import annotations

import argparse
import sys

import pymysql

TABLES = [
    ("t_user", 5),
    ("t_agent_session", 5),
    ("t_agent_message", 12),
    ("t_diagnosis_task", 5),
    ("t_diagnosis_run", 5),
    ("t_doctor_review", 5),
    ("t_patient_authorization", 5),
    ("t_news_source", 3),
    ("t_news_material", 5),
    ("t_news_curation_candidate", 5),
    ("t_config_draft", 4),
    ("t_knowledge_document", 5),
    ("t_upload_task", 5),
    ("t_controlled_reference", 4),
    ("t_controlled_reference_chunk", 8),
    ("t_agent_collab_run", 4),
    ("t_agent_collab_event", 8),
]

# child table -> (child column, parent table, parent column)
FOREIGN_KEYS = {
    "t_controlled_reference_chunk": ("documentid", "t_controlled_reference", "id"),
    "t_agent_collab_event": ("runid", "t_agent_collab_run", "runid"),
}

INT_TYPES = {"tinyint", "smallint", "mediumint", "int", "integer", "bigint", "bit"}
DECIMAL_TYPES = {"decimal", "numeric", "float", "double"}
STR_TYPES = {"char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set"}
BINARY_TYPES = {"blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary"}
DATE_TYPES = {"date", "datetime", "timestamp", "time", "year"}
JSON_TYPES = {"json"}


def columns(conn, db, table):
    with conn.cursor() as cur:
        cur.execute(
            """SELECT column_name AS name, data_type AS data_type, column_type AS column_type,
                      is_nullable AS nullable, extra AS extra,
                      character_maximum_length AS max_len, column_key AS column_key
               FROM information_schema.columns
               WHERE table_schema=%s AND table_name=%s ORDER BY ordinal_position""",
            (db, table))
        return cur.fetchall()


def value_for(meta, index):
    data_type = meta["data_type"].lower()
    if data_type == "tinyint" and meta["column_type"].startswith("tinyint(1)"):
        return 0
    if data_type in INT_TYPES:
        return index
    if data_type in DECIMAL_TYPES:
        return 1.5
    if data_type in STR_TYPES:
        text = f"drill_{meta['name']}_{index}"
        return text[: int(meta["max_len"])] if meta["max_len"] else text
    if data_type in BINARY_TYPES:
        return b"drill"
    if data_type in DATE_TYPES:
        return "2026-11-20" if data_type == "date" else "2026-11-20 10:00:00"
    if data_type in JSON_TYPES:
        return '{"drill": true}'
    raise SystemExit(f"FATAL: unmapped column type {data_type} for {meta['name']}")


def insert_rows(conn, db, table, count, parent_keys):
    metas = columns(conn, db, table)
    pk = next((m for m in metas if m["column_key"] == "PRI"), None)
    if pk is None:
        raise SystemExit(f"FATAL: {table} has no primary key")
    fk = FOREIGN_KEYS.get(table)
    fk_names = {f[0] for f in FOREIGN_KEYS.values()}

    insertable = [m for m in metas
                  if not any(k in (m["extra"] or "").upper() for k in ("VIRTUAL GENERATED", "STORED GENERATED"))]
    names = [m["name"] for m in insertable]
    placeholders = ", ".join(["%s"] * len(names))
    sql = f"INSERT INTO `{table}` ({', '.join('`' + n + '`' for n in names)}) VALUES ({placeholders})"

    rows = []
    for i in range(count):
        pk_value = i + 1
        fk_value = None
        if fk:
            _, parent_table, parent_col = fk
            parent = parent_keys.get(parent_table, {}).get(parent_col)
            if not parent:
                raise SystemExit(f"FATAL: parent {parent_table}.{parent_col} not generated")
            fk_value = parent[i % len(parent)]
        row = []
        for meta in insertable:
            if fk and meta["name"].lower() == fk[0]:
                row.append(fk_value)
            elif meta["column_key"] == "PRI":
                row.append(pk_value)
            elif meta["name"].lower() in fk_names:
                row.append(pk_value)  # an unrelated same-named column
            else:
                row.append(value_for(meta, i + 1))
        rows.append(tuple(row))

    with conn.cursor() as cur:
        cur.executemany(sql, rows)

    if table == "t_agent_collab_run":
        return {table: {"runid": [r[names.index("runId")] for r in rows]}}
    if table == "t_controlled_reference":
        return {table: {"id": [r[names.index("id")] for r in rows]}}
    return {}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--db", required=True)
    parser.add_argument("--rows", type=int, default=0, help="override per-table row count")
    args = parser.parse_args()

    if not args.db.endswith("_drill"):
        raise SystemExit("FATAL: refusing to load fixture into a database whose name does not end in _drill")

    conn = pymysql.connect(host=args.host, port=args.port, user=args.user, password=args.password,
                           database=args.db, charset="utf8mb4", autocommit=False,
                           cursorclass=pymysql.cursors.DictCursor)
    total = 0
    try:
        parent_keys = {}
        for table, count in TABLES:
            count = args.rows or count
            parent_keys.update(insert_rows(conn, args.db, table, count, parent_keys))
            total += count
            print(f"LOADED {table:<32} rows={count}")
        conn.commit()
        print(f"LOADED total={total}")
        return 0
    except Exception as exc:
        conn.rollback()
        raise SystemExit(f"FATAL: {exc}")
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
