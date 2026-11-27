-- ============================================================================
-- Iris · PostgreSQL line V3 — RAG metadata type normalization
--
-- Chroma stored every metadata value as a string; PostgreSQL keeps JSON types.
-- The isolation filter is numeric (`ownerUserId == <id>`, PgVectorStore renders it
-- as a jsonpath `@ == 7`), so a migrated chunk whose metadata says "7" instead of 7
-- becomes invisible to its owner — silent knowledge loss, the worst failure mode
-- for a medical knowledge base.
--
-- Rather than trusting each writer (import tool, seeder, document service) to get
-- the type right, the table enforces it: numeric-looking string values of the
-- identity fields are rewritten to JSON numbers on insert and update. Any other
-- value is left untouched and stays visible to the lexical channel.
-- ============================================================================
CREATE OR REPLACE FUNCTION rag.iris_normalize_vector_metadata() RETURNS trigger AS $$
DECLARE
    m jsonb;
    k text;
BEGIN
    IF NEW.metadata IS NULL THEN
        RETURN NEW;
    END IF;
    m := NEW.metadata::jsonb;
    FOREACH k IN ARRAY ARRAY['ownerUserId', 'documentId', 'chunkIndex'] LOOP
        IF jsonb_typeof(m -> k) = 'string'
           AND (m ->> k) ~ '^[0-9]{1,19}$' THEN
            m := jsonb_set(m, ARRAY[k], to_jsonb((m ->> k)::bigint));
        END IF;
    END LOOP;
    NEW.metadata := m::json;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rag_vector_metadata_normalize
    BEFORE INSERT OR UPDATE ON rag.iris_knowledge_vectors
    FOR EACH ROW EXECUTE FUNCTION rag.iris_normalize_vector_metadata();
