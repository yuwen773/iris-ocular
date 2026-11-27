-- ============================================================================
-- Iris · PostgreSQL line V4 — correct MySQL DATE_FORMAT literal handling
--
-- V2 translated %codes with plain string replacement, which left literal text
-- unquoted. PostgreSQL's to_char then read parts of it as patterns: in the ledger
-- format '%Y-%m-%dT%H:%i:%s' the literal "T" merges with the HH24 token into the
-- ordinal suffix "TH", producing "2026-11-27THH24:01:57" instead of
-- "2026-11-27T01:01:57". Silent, plausible-looking, wrong.
--
-- This version walks the format character by character: %codes become to_char
-- patterns, everything else is emitted as a quoted literal, so arbitrary
-- separators (T, commas, CJK text) survive verbatim.
-- ============================================================================
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
                WHEN 'd' THEN 'DD'
                WHEN 'H' THEN 'HH24'
                WHEN 'h' THEN 'HH12'
                WHEN 'i' THEN 'MI'
                WHEN 's' THEN 'SS'
                WHEN 'f' THEN 'US'
                WHEN 'b' THEN 'Mon'
                ELSE code
            END;
        ELSE
            result := result || '"' || ch || '"';
        END IF;
        i := i + 1;
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
