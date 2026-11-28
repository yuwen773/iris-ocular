#!/usr/bin/env python3
"""Iris · Chroma -> pgvector import tool (embeddings are moved, never recomputed).

The knowledge base's value is the vectors: re-embedding a real corpus would be a
paid model call, would change ids/similarities, and would need authorization. This
tool therefore reads the stored embeddings from Chroma and writes them verbatim
into rag.iris_knowledge_vectors (same COSINE space), preserving:

  * the chunk id      (citations, dedupe, delete-by-document all key on it)
  * the metadata JSON (owner isolation filters key on it; the V3 trigger
                       normalises numeric strings on write)
  * the embedding     (byte-for-byte floats, no model call anywhere)

Guards:
  * collection must exist and its declared dimension (if any) must match --dimensions;
  * every batch is checked for embedding width and id presence before insert;
  * source count vs target count and an id-set digest are compared at the end;
  * ON CONFLICT (id) DO NOTHING makes re-runs idempotent; --resume continues from
    the recorded offset; --dry-run only reports.

Usage:
  python scripts/migration/chroma_to_pgvector.py \
      --chroma-url http://127.0.0.1:8000 --collection iris_knowledge \
      --target-dsn "host=127.0.0.1 port=5432 dbname=iris user=iris_migrator password=..." \
      --dimensions 1024 --dry-run

Exit codes: 0 success, 2 verification failure, 3 precondition/usage failure.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

import psycopg

TENANT_PATH = "/api/v2/tenants/default_tenant/databases/default_database"


def fail(message: str, code: int = 3) -> "NoReturn":
    print(f"FATAL: {message}", file=sys.stderr)
    sys.exit(code)


def http_json(url: str, payload: dict | None = None, method: str | None = None) -> dict | list:
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"),
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode(errors="replace")[:300]
        fail(f"chroma request failed {url}: HTTP {exc.code} {detail}")


def collection_id(base: str, name: str) -> dict:
    collections = http_json(f"{base}{TENANT_PATH}/collections")
    for collection in collections:
        if collection.get("name") == name:
            return collection
    fail(f"collection {name!r} not found at {base}")


def fetch_batch(base: str, cid: str, offset: int, limit: int) -> dict:
    return http_json(f"{base}{TENANT_PATH}/collections/{cid}/get",
                     {"limit": limit, "offset": offset,
                      "include": ["embeddings", "documents", "metadatas"]})


def fail_on_batch_shape(batch: dict, offset: int, dimensions: int) -> None:
    ids = batch.get("ids") or []
    embeddings = batch.get("embeddings") or []
    if len(ids) != len(embeddings):
        fail(f"batch at offset {offset}: {len(ids)} ids but {len(embeddings)} embeddings")
    for embedding in embeddings:
        if embedding is not None and len(embedding) != dimensions:
            fail(f"batch at offset {offset}: embedding width {len(embedding)} != {dimensions}. "
                 "Importing mixed dimensions would silently corrupt similarity.")


def upsert_rows(pg, schema: str, table: str, batch: dict) -> int:
    ids = batch.get("ids") or []
    documents = batch.get("documents") or [None] * len(ids)
    metadatas = batch.get("metadatas") or [None] * len(ids)
    embeddings = batch.get("embeddings") or []
    rows = []
    for i, chunk_id in enumerate(ids):
        metadata = metadatas[i]
        rows.append((
            str(chunk_id),
            documents[i],
            None if metadata is None else json.dumps(metadata, ensure_ascii=False),
            embeddings[i],
        ))
    sql = (f"INSERT INTO {schema}.{table} (id, content, metadata, embedding) "
           f"VALUES (%s, %s, CAST(%s AS json), %s) "
           f"ON CONFLICT (id) DO NOTHING")
    with pg.cursor() as cur:
        cur.executemany(sql, rows)
    return len(rows)


def digest_ids(ids) -> str:
    digest = hashlib.sha256()
    for chunk_id in sorted(str(i) for i in ids):
        digest.update(chunk_id.encode())
        digest.update(b"\n")
    return digest.hexdigest()


def verify_batch_matches(pg, schema: str, table: str, batch: dict) -> list[str]:
    """Return the ids whose target row differs from the source batch.

    `ON CONFLICT (id) DO NOTHING` is deliberate (a re-run must never overwrite a
    newer production row), but it makes the id-set digest insufficient: a stale or
    divergent row with the same id would pass. Compare content, metadata (as
    parsed JSON, since json text formatting is not stable) and embedding (1e-6,
    same tolerance as the drill) for every source id.
    """
    ids = batch.get("ids") or []
    if not ids:
        return []
    documents = batch.get("documents") or [None] * len(ids)
    metadatas = batch.get("metadatas") or [None] * len(ids)
    embeddings = batch.get("embeddings") or [None] * len(ids)
    with pg.cursor() as cur:
        cur.execute(
            f"SELECT id, content, metadata::text, embedding::text FROM {schema}.{table} "
            f"WHERE id = ANY(%s)", ([str(i) for i in ids],))
        target = {row[0]: row[1:] for row in cur.fetchall()}
    mismatched = []
    for index, chunk_id in enumerate(ids):
        row = target.get(str(chunk_id))
        if row is None:
            mismatched.append(str(chunk_id))
            continue
        content, metadata_text, embedding_text = row
        if content != documents[index]:
            mismatched.append(str(chunk_id))
            continue
        if (json.loads(metadata_text) if metadata_text is not None else None) != metadatas[index]:
            mismatched.append(str(chunk_id))
            continue
        expected = embeddings[index]
        if expected is not None:
            actual = [float(v) for v in embedding_text.strip("[]").split(",")] if embedding_text else []
            if len(actual) != len(expected) or any(
                    abs(a - float(e)) > 1e-6 for a, e in zip(actual, expected)):
                mismatched.append(str(chunk_id))
    return mismatched


def target_id_digest(pg, schema: str, table: str, ids) -> tuple[int, str]:
    """Count and digest of the source ids that are present in the target.

    Scoped to the imported id set on purpose: the vector table also holds builtin
    corpus chunks, and a global count would turn an unrelated row into a false
    verification failure (or a false pass).
    """
    with pg.cursor() as cur:
        cur.execute(f"SELECT id FROM {schema}.{table} WHERE id = ANY(%s)", (list(ids),))
        present = [row[0] for row in cur.fetchall()]
    return len(present), digest_ids(present)


def main() -> int:
    parser = argparse.ArgumentParser(description="Chroma -> pgvector import (no re-embedding)")
    parser.add_argument("--chroma-url", required=True)
    parser.add_argument("--collection", required=True)
    parser.add_argument("--target-dsn", required=True)
    parser.add_argument("--schema", default="rag")
    parser.add_argument("--table", default="iris_knowledge_vectors")
    parser.add_argument("--dimensions", type=int, default=1024)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--state-file", default=None)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    for name in (args.schema, args.table):
        if not name.replace("_", "").isalnum():
            fail(f"invalid identifier {name!r}")

    base = args.chroma_url.rstrip("/")
    collection = collection_id(base, args.collection)
    declared = collection.get("dimension")
    if declared is not None and int(declared) != args.dimensions:
        fail(f"collection dimension {declared} != configured {args.dimensions}; "
             "changing the embedding model requires a re-embedding migration, not a flag")

    state = {"offset": 0, "imported": 0, "run": None}
    if args.state_file and os.path.exists(args.state_file):
        with open(args.state_file, encoding="utf-8") as handle:
            state = json.load(handle)
        if not args.resume:
            fail(f"state file {args.state_file} exists; pass --resume to continue it")
    if state.get("run") is None:
        state["run"] = hashlib.sha256(
            f"{args.collection}:{time.time()}".encode()).hexdigest()[:16]

    offset = int(state["offset"])
    if args.dry_run:
        total = 0
        while True:
            batch = fetch_batch(base, collection["id"], offset, args.batch_size)
            if not batch.get("ids"):
                break
            fail_on_batch_shape(batch, offset, args.dimensions)
            total += len(batch["ids"])
            offset += len(batch["ids"])
        print(f"PLAN collection={args.collection} chunks={total} dimensions={args.dimensions}")
        return 0

    with psycopg.connect(args.target_dsn) as pg:
        while True:
            batch = fetch_batch(base, collection["id"], offset, args.batch_size)
            ids = batch.get("ids") or []
            if not ids:
                break
            fail_on_batch_shape(batch, offset, args.dimensions)
            with pg.transaction():
                imported = upsert_rows(pg, args.schema, args.table, batch)
            state["imported"] += imported
            state["offset"] = offset = offset + len(ids)
            if args.state_file:
                os.makedirs(os.path.dirname(args.state_file) or ".", exist_ok=True)
                with open(args.state_file, "w", encoding="utf-8") as handle:
                    json.dump(state, handle, indent=2)
            print(f"OK offset={offset} imported={state['imported']}")

        source_ids = []
        offset = 0
        while True:
            batch = fetch_batch(base, collection["id"], offset, args.batch_size)
            ids = batch.get("ids") or []
            if not ids:
                break
            source_ids.extend(ids)
            offset += len(ids)

        target_rows, target_digest = target_id_digest(pg, args.schema, args.table, source_ids)
        source_digest = digest_ids(source_ids)
        print(f"VERIFY source_chunks={len(source_ids)} target_rows={target_rows}")
        if len(source_ids) != target_rows:
            print("VERIFY_FAIL row count mismatch", file=sys.stderr)
            return 2
        if source_digest != target_digest:
            print("VERIFY_FAIL id set digest mismatch", file=sys.stderr)
            return 2
        print(f"VERIFY id_digest={source_digest}")

        # 2) 同 id 但内容/元数据/向量不同：ON CONFLICT DO NOTHING 会保留旧行，
        #    只看 id 集合会在「部分跑过、目标已有旧数据」时假绿。
        offset = 0
        divergent: list[str] = []
        while True:
            batch = fetch_batch(base, collection["id"], offset, args.batch_size)
            ids = batch.get("ids") or []
            if not ids:
                break
            divergent.extend(verify_batch_matches(pg, args.schema, args.table, batch))
            offset += len(ids)
        if divergent:
            print(f"VERIFY_FAIL divergent_rows={len(divergent)} sample={divergent[:5]}", file=sys.stderr)
            return 2
        print("VERIFY content/metadata/embedding match")
        return 0


if __name__ == "__main__":
    sys.exit(main())
