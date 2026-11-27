-- ============================================================================
-- Iris · PostgreSQL line V2 — RAG vector store and MySQL-dialect shims
--
-- * rag schema holds the pgvector table. The table is created here, by the
--   controlled migration line, NOT by Spring AI at startup
--   (PgVectorStore.initializeSchema=false). Startup never drops history.
-- * 1024 dimensions = DashScope text-embedding-v3 default output size, the
--   model configured for this deployment. Changing the model means a new
--   migration or a re-embedding job; it is not a code-level switch.
-- * COSINE distance matches the Chroma collection configuration
--   (hnsw:space=cosine), so similarity thresholds keep their meaning:
--   pgvector cosine distance = 1 - cosine similarity, same as Chroma.
-- * id is text: existing chunk ids ("builtin_<slug>_chunk_<n>",
--   "doc_<docId>_<version>_chunk_<n>") are preserved verbatim, so citations,
--   dedupe and upsert keep working without an ID remapping table.
-- ============================================================================
CREATE SCHEMA IF NOT EXISTS rag;

CREATE TABLE rag.iris_knowledge_vectors (
    id        text PRIMARY KEY,
    content   text,
    metadata  json,
    embedding public.vector(1024)
);

-- HNSW index for the cosine operator. Exact search is always available as the
-- fallback path (SET LOCAL enable_indexscan=off) for the retrieval comparison.
CREATE INDEX idx_rag_iris_knowledge_vectors_hnsw
    ON rag.iris_knowledge_vectors USING hnsw (embedding public.vector_cosine_ops);

-- Owner/document-scoped reads (hybrid lexical channel, delete-by-document).
CREATE INDEX idx_rag_iris_knowledge_vectors_metadata
    ON rag.iris_knowledge_vectors USING gin ((metadata::jsonb) jsonb_path_ops);

-- ----------------------------------------------------------------------------
-- MySQL SQL-dialect shims.
--
-- Iris' persistence layer has ~140 call sites that format timestamps in SQL
-- (ledger receipts, worker leases, analytics day buckets). Rewriting every one
-- of them would touch security-relevant code paths for no semantic gain, so the
-- dialect names live on as schema-scoped functions with MySQL's exact output.
-- They are deterministic, read-only and take no privileges beyond EXECUTE.
--
-- UTC is the formatting time zone for timestamptz inputs: ledger columns were
-- already written in UTC by UTC_TIMESTAMP() under MySQL.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION iris_mysql_date_format_pattern(fmt text) RETURNS text AS $$
    SELECT replace(replace(replace(replace(replace(replace(replace(replace(replace(
               fmt,
               '%Y', 'YYYY'), '%y', 'YY'), '%m', 'MM'), '%d', 'DD'), '%H', 'HH24'),
               '%i', 'MI'), '%s', 'SS'), '%f', 'US'), '%b', 'Mon');
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION date_format(value timestamptz, fmt text) RETURNS text AS $$
    SELECT CASE WHEN value IS NULL THEN NULL
                ELSE to_char(value AT TIME ZONE 'UTC', iris_mysql_date_format_pattern(fmt)) END;
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION date_format(value timestamp, fmt text) RETURNS text AS $$
    SELECT CASE WHEN value IS NULL THEN NULL
                ELSE to_char(value, iris_mysql_date_format_pattern(fmt)) END;
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION date_format(value date, fmt text) RETURNS text AS $$
    SELECT CASE WHEN value IS NULL THEN NULL
                ELSE to_char(value::timestamp, iris_mysql_date_format_pattern(fmt)) END;
$$ LANGUAGE sql IMMUTABLE;

-- MySQL UTC_TIMESTAMP([fsp]) returns a UTC wall-clock DATETIME; the column type
-- here is timestamptz, so return the absolute instant truncated like MySQL.
CREATE OR REPLACE FUNCTION utc_timestamp() RETURNS timestamptz AS $$
    SELECT date_trunc('second', now());
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION utc_timestamp(fsp int) RETURNS timestamptz AS $$
    SELECT now();
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION utc_date() RETURNS date AS $$
    SELECT (now() AT TIME ZONE 'UTC')::date;
$$ LANGUAGE sql STABLE;

-- MySQL CURDATE()/NOW() equivalents that some call sites use.
CREATE OR REPLACE FUNCTION curdate() RETURNS date AS $$
    SELECT (now() AT TIME ZONE 'UTC')::date;
$$ LANGUAGE sql STABLE;
