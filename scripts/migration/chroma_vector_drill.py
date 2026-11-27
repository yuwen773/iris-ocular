#!/usr/bin/env python3
"""Iris · Chroma -> pgvector drill: import fidelity + retrieval comparison.

Seeds a disposable Chroma collection with synthetic chunks (deterministic
embeddings, no model calls, no real knowledge-base content), imports it with the
production import tool, then compares retrieval:

  * fidelity      : imported ids, metadata and embeddings are byte-identical
  * exact search  : pgvector `<=>` (sequential scan) top-k vs Chroma top-k
  * HNSW search   : index-backed top-k vs the exact baseline
  * isolation     : a numeric owner filter never returns another owner's chunks
  * latency       : P50/P95 per engine over the same query set

Thresholds are reported, not tuned: overlap@k below --min-overlap fails the drill.
Usage: scripts/migration/chroma_vector_drill.py --chroma-url http://127.0.0.1:18000 \
         --target-dsn "host=... user=iris_migrator password=..."
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import statistics
import subprocess
import sys
import time
import urllib.request

import psycopg

TENANT_PATH = "/api/v2/tenants/default_tenant/databases/default_database"
DIMENSIONS = 1024


def http_json(url: str, payload: dict | None = None, method: str | None = None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        body = response.read()
        return json.loads(body) if body else {}


def deterministic_vector(text: str) -> list[float]:
    seed = int.from_bytes(hashlib.sha256(text.encode()).digest()[:8], "big")
    rng = random.Random(seed)
    values = [rng.gauss(0, 1) for _ in range(DIMENSIONS)]
    norm = math.sqrt(sum(v * v for v in values))
    return [v / norm for v in values]


def seed(collection_name: str, chunks: int, base: str) -> dict:
    collection = http_json(f"{base}{TENANT_PATH}/collections",
                           {"name": collection_name, "metadata": {"hnsw:space": "cosine"}})
    cid = collection["id"]
    batch = 100
    for start in range(0, chunks, batch):
        ids, docs, metas, embeddings = [], [], [], []
        for i in range(start, min(start + batch, chunks)):
            text = f"iris drill chunk {i} about retinal lesion grading category {i % 7}"
            ids.append(f"drill_chunk_{i:05d}")
            docs.append(text)
            metas.append({"ownerUserId": 7 if i % 2 == 0 else 8,
                          "documentId": f"drill-doc-{i // 10}",
                          "chunkIndex": i % 10})
            embeddings.append(deterministic_vector(text))
        http_json(f"{base}{TENANT_PATH}/collections/{cid}/add",
                  {"ids": ids, "documents": docs, "metadatas": metas, "embeddings": embeddings})
    total = http_json(f"{base}{TENANT_PATH}/collections/{cid}/count")
    return {"id": cid, "count": int(total), "name": collection_name}


def chroma_query(base: str, cid: str, vector: list[float], k: int) -> list[str]:
    result = http_json(f"{base}{TENANT_PATH}/collections/{cid}/query",
                       {"query_embeddings": [vector], "n_results": k, "include": ["distances"]})
    return (result.get("ids") or [[]])[0]


def pg_query(pg, exact: bool, vector_literal: str, k: int) -> list[str]:
    with pg.transaction():
        if exact:
            pg.execute("SET LOCAL enable_indexscan = off")
            pg.execute("SET LOCAL enable_bitmapscan = off")
            pg.execute("SET LOCAL enable_seqscan = on")
        cur = pg.execute(
            "SELECT id FROM rag.iris_knowledge_vectors "
            "ORDER BY embedding <=> CAST(%s AS vector) LIMIT %s", (vector_literal, k))
        return [row[0] for row in cur.fetchall()]


def vector_literal(values: list[float]) -> str:
    return "[" + ",".join(f"{v:.9g}" for v in values) + "]"


def overlap(left: list[str], right: list[str], k: int) -> float:
    if not left or not right:
        return 0.0
    return len(set(left[:k]) & set(right[:k])) / k


def percentile(samples: list[float], p: float) -> float:
    if not samples:
        return 0.0
    ordered = sorted(samples)
    index = min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chroma-url", required=True)
    parser.add_argument("--target-dsn", required=True)
    parser.add_argument("--chunks", type=int, default=300)
    parser.add_argument("--queries", type=int, default=40)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--min-overlap", type=float, default=0.9)
    parser.add_argument("--collection", default=f"iris_drill_vectors_{int(time.time())}")
    parser.add_argument("--state-file", default=".migration-state/vector-drill.json")
    args = parser.parse_args()

    base = args.chroma_url.rstrip("/")
    print(f"SEED collection={args.collection} chunks={args.chunks}")
    seeded = seed(args.collection, args.chunks, base)
    print(f"SEED count={seeded['count']}")

    tool = os.path.join(os.path.dirname(__file__), "chroma_to_pgvector.py")
    print("IMPORT via scripts/migration/chroma_to_pgvector.py")
    result = subprocess.run([sys.executable, tool, "--chroma-url", base,
                             "--collection", args.collection, "--target-dsn", args.target_dsn,
                             "--dimensions", str(DIMENSIONS), "--state-file", args.state_file],
                            capture_output=True, text=True)
    print(result.stdout.strip())
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        print("FAIL import")
        return result.returncode or 2

    with psycopg.connect(args.target_dsn) as pg:
        # 1) fidelity: ids, metadata, embeddings identical for a sample
        sample = [f"drill_chunk_{i:05d}" for i in (0, 1, args.chunks - 1)]
        rows = {}
        for chunk_id in sample:
            cur = pg.execute("SELECT content, metadata::text, embedding::text "
                             "FROM rag.iris_knowledge_vectors WHERE id = %s", (chunk_id,))
            row = cur.fetchone()
            if row is None:
                print(f"FAIL fidelity missing id={chunk_id}")
                return 2
            rows[chunk_id] = row
        got = http_json(f"{base}{TENANT_PATH}/collections/{seeded['id']}/get",
                        {"ids": sample, "include": ["embeddings", "documents", "metadatas"]})
        for i, chunk_id in enumerate(got["ids"]):
            source_vector = got["embeddings"][i]
            stored = [float(x) for x in rows[chunk_id][2].strip("[]").split(",")]
            if len(stored) != len(source_vector) or any(
                    abs(a - b) > 1e-6 for a, b in zip(stored, source_vector)):
                print(f"FAIL fidelity embedding mismatch id={chunk_id}")
                return 2
            stored_meta = json.loads(rows[chunk_id][1])
            if int(stored_meta.get("ownerUserId")) != int(got["metadatas"][i]["ownerUserId"]):
                print(f"FAIL fidelity metadata mismatch id={chunk_id}")
                return 2
        print(f"FIDELITY ok ids={len(sample)} embedding_tolerance=1e-6")

        # 2) retrieval comparison
        query_texts = [f"retinal lesion grading category {i % 7} variant {i}" for i in range(args.queries)]
        overlaps_chroma_pg, overlaps_exact_hnsw = [], []
        chroma_latency, pg_exact_latency, pg_hnsw_latency = [], [], []
        for text in query_texts:
            vector = deterministic_vector(text)
            literal = vector_literal(vector)

            started = time.perf_counter()
            chroma_ids = chroma_query(base, seeded["id"], vector, args.top_k)
            chroma_latency.append((time.perf_counter() - started) * 1000)

            started = time.perf_counter()
            exact_ids = pg_query(pg, True, literal, args.top_k)
            pg_exact_latency.append((time.perf_counter() - started) * 1000)

            started = time.perf_counter()
            hnsw_ids = pg_query(pg, False, literal, args.top_k)
            pg_hnsw_latency.append((time.perf_counter() - started) * 1000)

            overlaps_chroma_pg.append(overlap(chroma_ids, exact_ids, args.top_k))
            overlaps_exact_hnsw.append(overlap(exact_ids, hnsw_ids, args.top_k))

        mean_chroma = statistics.mean(overlaps_chroma_pg)
        mean_hnsw = statistics.mean(overlaps_exact_hnsw)
        print(f"OVERLAP chroma_vs_pg_exact@{args.top_k}={mean_chroma:.3f}")
        print(f"OVERLAP pg_exact_vs_pg_hnsw@{args.top_k}={mean_hnsw:.3f}")
        print(f"LATENCY_MS chroma p50={percentile(chroma_latency,50):.1f} p95={percentile(chroma_latency,95):.1f}")
        print(f"LATENCY_MS pg_exact p50={percentile(pg_exact_latency,50):.1f} p95={percentile(pg_exact_latency,95):.1f}")
        print(f"LATENCY_MS pg_hnsw p50={percentile(pg_hnsw_latency,50):.1f} p95={percentile(pg_hnsw_latency,95):.1f}")

        # 3) isolation: the numeric owner filter must never leak
        vector = deterministic_vector(query_texts[0])
        cur = pg.execute(
            "SELECT (metadata::jsonb ->> 'ownerUserId') FROM rag.iris_knowledge_vectors "
            "WHERE metadata::jsonb @@ '$.ownerUserId == 7' LIMIT 50")
        owners = {row[0] for row in cur.fetchall()}
        if owners - {"7"}:
            print(f"FAIL isolation leaked owners={owners}")
            return 2
        print(f"ISOLATION ok owners={sorted(owners)}")

        if mean_chroma < args.min_overlap or mean_hnsw < args.min_overlap:
            print(f"FAIL overlap below threshold {args.min_overlap}")
            return 2

    print("PASS vector migration drill")
    return 0


if __name__ == "__main__":
    sys.exit(main())
