-- ============================================================================
-- Iris · Flyway afterMigrate callback (runs as the migrator, every migrate)
--
-- The migration history is a governance artifact: the runtime role must not be
-- able to rewrite, delete or forge it. ALTER DEFAULT PRIVILEGES in
-- scripts/postgresql/bootstrap-roles.sql hands iris_app DML on every table the
-- migrator creates — including flyway_schema_history. This callback takes that
-- access back after each migrate, so the restriction also holds on a fresh
-- database where bootstrap ran before Flyway created anything.
--
-- Guarded on both objects: the callback also runs when a cluster has no
-- iris_app (e.g. a throwaway superuser-only schema build).
-- ============================================================================
DO $$
BEGIN
    IF to_regclass('diagnosis.flyway_schema_history') IS NOT NULL
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iris_app') THEN
        EXECUTE 'REVOKE ALL ON diagnosis.flyway_schema_history FROM iris_app';
    END IF;
END
$$;
