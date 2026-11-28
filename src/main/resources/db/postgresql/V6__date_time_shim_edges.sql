-- ============================================================================
-- Iris · PostgreSQL line V6 — finish the MySQL date/time shims
--
-- Two silent-corruption edges found in independent review A:
--
--   1. utc_timestamp(fsp) returned now() with full microsecond precision for any
--      fsp, so UTC_TIMESTAMP(0) was not second-granular. All current call sites
--      pass 6 or nothing, but a future fsp 0–5 would shift boundary comparisons.
--   2. iris_mysql_date_format_pattern emitted unknown %codes unquoted, so a
--      format using %M/%p/%T could merge adjacent tokens exactly like the "TH"
--      bug V4 fixed. Map the common MySQL codes and quote anything unmapped, so
--      an unsupported code shows up as a literal instead of a silently wrong
--      timestamp.
--
-- Both functions are CREATE OR REPLACE with unchanged signatures, so existing
-- callers and grants are unaffected. V2/V4 stay untouched (checksum stability).
-- ============================================================================

CREATE OR REPLACE FUNCTION utc_timestamp(fsp int) RETURNS timestamptz AS $$
    SELECT CASE
        WHEN fsp <= 0 THEN date_trunc('second', now())
        WHEN fsp <= 3 THEN date_trunc('milliseconds', now())
        ELSE date_trunc('microseconds', now())
    END;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION iris_mysql_date_format_pattern(fmt text) RETURNS text AS $$
DECLARE
    result text := '';
    i int := 1;
    n int := length(fmt);
    ch text;
    code text;
BEGIN
    WHILE i <= n LOOP
        ch := substr(fmt, i, 1);
        IF ch = '%' AND i < n THEN
            i := i + 1;
            code := substr(fmt, i, 1);
            result := result || CASE code
                WHEN 'Y' THEN 'YYYY'
                WHEN 'y' THEN 'YY'
                WHEN 'm' THEN 'MM'
                WHEN 'c' THEN 'FMMM'
                WHEN 'd' THEN 'DD'
                WHEN 'e' THEN 'FMDD'
                WHEN 'H' THEN 'HH24'
                WHEN 'k' THEN 'FMHH24'
                WHEN 'h' THEN 'HH12'
                WHEN 'I' THEN 'HH12'
                WHEN 'l' THEN 'FMHH12'
                WHEN 'i' THEN 'MI'
                WHEN 's' THEN 'SS'
                WHEN 'f' THEN 'US'
                WHEN 'b' THEN 'Mon'
                WHEN 'M' THEN 'FMMonth'
                WHEN 'W' THEN 'FMDay'
                WHEN 'a' THEN 'Dy'
                WHEN 'j' THEN 'DDD'
                WHEN 'p' THEN 'AM'
                WHEN 'T' THEN 'HH24:MI:SS'
                WHEN 'r' THEN 'HH12:MI:SS AM'
                WHEN '%' THEN '"%"'
                -- Unmapped codes are quoted, never concatenated into the
                -- pattern: a bare letter can merge with a neighbouring token.
                ELSE '"%' || code || '"'
            END;
        ELSE
            result := result || '"' || ch || '"';
        END IF;
        i := i + 1;
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
