-- ============================================================================
-- Iris · PostgreSQL role bootstrap (run ONCE per cluster, as a superuser)
--
--   psql -v ON_ERROR_STOP=1 \
--        -v migrator_password='...' -v app_password='...' \
--        -f scripts/postgresql/bootstrap-roles.sql
--
-- Roles
--   iris_migrator  owns schema diagnosis/rag and runs Flyway. Has DDL there.
--   iris_app       runtime role. USAGE + DML only; no CREATE/ALTER/DROP,
--                  no extension install, no read/write of the migration history.
--
-- This script is idempotent. It never drops objects or rewrites migration
-- history. Passwords come from psql variables and are never stored here.
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS vector;

SELECT format('CREATE ROLE iris_migrator LOGIN PASSWORD %L', :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iris_migrator')
\gexec
SELECT format('ALTER ROLE iris_migrator WITH LOGIN PASSWORD %L', :'migrator_password')
\gexec

SELECT format('CREATE ROLE iris_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iris_app')
\gexec
SELECT format('ALTER ROLE iris_app WITH LOGIN PASSWORD %L', :'app_password')
\gexec

-- The migrator may create the schemas on a fresh database; iris_app never gets
-- CREATE on the database or the schemas. current_database() so the same script
-- bootstraps the dev database and the isolated CI/test databases.
DO $$
BEGIN
    EXECUTE format('GRANT CREATE ON DATABASE %I TO iris_migrator', current_database());
END
$$;

-- Only the migrator owns the schemas. iris_app never gets CREATE.
CREATE SCHEMA IF NOT EXISTS diagnosis AUTHORIZATION iris_migrator;
CREATE SCHEMA IF NOT EXISTS rag AUTHORIZATION iris_migrator;
ALTER SCHEMA diagnosis OWNER TO iris_migrator;
ALTER SCHEMA rag OWNER TO iris_migrator;

GRANT USAGE ON SCHEMA diagnosis, rag TO iris_app;
REVOKE CREATE ON SCHEMA diagnosis, rag FROM PUBLIC;
REVOKE CREATE ON SCHEMA diagnosis, rag FROM iris_app;

-- Runtime DML on everything that exists today.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA diagnosis TO iris_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA rag TO iris_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA diagnosis TO iris_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA rag TO iris_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA diagnosis TO iris_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rag TO iris_app;

-- …and on everything the migrator creates later.
ALTER DEFAULT PRIVILEGES FOR ROLE iris_migrator IN SCHEMA diagnosis
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO iris_app;
ALTER DEFAULT PRIVILEGES FOR ROLE iris_migrator IN SCHEMA rag
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO iris_app;
ALTER DEFAULT PRIVILEGES FOR ROLE iris_migrator IN SCHEMA diagnosis
    GRANT USAGE, SELECT ON SEQUENCES TO iris_app;
ALTER DEFAULT PRIVILEGES FOR ROLE iris_migrator IN SCHEMA rag
    GRANT USAGE, SELECT ON SEQUENCES TO iris_app;
ALTER DEFAULT PRIVILEGES FOR ROLE iris_migrator IN SCHEMA diagnosis
    GRANT EXECUTE ON FUNCTIONS TO iris_app;
ALTER DEFAULT PRIVILEGES FOR ROLE iris_migrator IN SCHEMA rag
    GRANT EXECUTE ON FUNCTIONS TO iris_app;

-- The migration history is governance, not application data. On a fresh
-- database the table does not exist yet; db/postgresql/afterMigrate.sql then
-- revokes the same access right after Flyway creates it.
DO $$
BEGIN
    IF to_regclass('diagnosis.flyway_schema_history') IS NOT NULL THEN
        EXECUTE 'REVOKE ALL ON diagnosis.flyway_schema_history FROM iris_app';
    END IF;
END
$$;

-- The application must not be able to install extensions or create schemas.
DO $$
BEGIN
    EXECUTE format('REVOKE CREATE ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CREATE ON DATABASE %I TO iris_migrator', current_database());
END
$$;

-- ---------------------------------------------------------------------------
-- MySQL coercion compatibility: boolean -> smallint/integer assignment casts.
--
-- The Java line keeps MySQL-era JDBC code, where a `boolean` parameter bound to
-- a TINYINT/SMALLINT flag column was coerced by the server (`isTest`,
-- `enabled`, `isDelete`). PostgreSQL has no boolean -> integer cast, so the
-- same binding fails with "column istest is of type smallint but expression is
-- of type boolean". Creating the cast on built-in types needs a superuser,
-- which is why it lives here (bootstrap) rather than in a Flyway migration.
--
-- AS ASSIGNMENT limits the coercion to INSERT/UPDATE targets, so an explicit
-- boolean comparison never silently changes meaning.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION iris_bool_to_smallint(boolean)
RETURNS smallint
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE WHEN $1 THEN 1::smallint ELSE 0::smallint END
$$;

DROP CAST IF EXISTS (boolean AS smallint);
CREATE CAST (boolean AS smallint) WITH FUNCTION iris_bool_to_smallint(boolean) AS ASSIGNMENT;

-- boolean -> integer 在 PostgreSQL 18 已是内建 cast（context = explicit），
-- 不能重复创建，也不能改成 assignment；所有 integer 标志列在 Java 侧都用 Integer 绑定，
-- 因此这里只补 smallint 这一条（tinyint(1) 在 V1 里映射为 smallint）。
