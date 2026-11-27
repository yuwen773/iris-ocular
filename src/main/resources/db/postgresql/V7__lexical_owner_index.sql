-- ============================================================================
-- Iris · PostgreSQL line V7 — index the lexical channel's owner predicate
--
-- LexicalCorpusService reads the whole owner corpus on every cache refresh:
--   WHERE (metadata ->> 'ownerUserId') IN (<user>, 0)
-- The V2 GIN index serves jsonpath filter expressions (`metadata::jsonb @@ ...`),
-- not this text extraction, so the query was a full scan of the vector table per
-- user per minute (max-corpus-chunks=20000).
--
-- Expression must match the query text exactly for the planner to use it, so the
-- service drops the ::jsonb cast and queries `metadata ->> 'ownerUserId'`, the
-- same expression indexed here. The cast is unnecessary: `->>` exists for json.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_iris_knowledge_vectors_owner
  ON rag.iris_knowledge_vectors ((metadata ->> 'ownerUserId'));
