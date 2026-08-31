-- Inline the JSON that Hibernate hid in pg_largeobject, and reclaim the large objects.
--
-- Every JSON column in the routine schema was mapped @Lob on a String field. On PostgreSQL that
-- makes Hibernate write the document into pg_largeobject and store only the object id in the
-- column, so a TEXT column whose contract is a multi-kilobyte identity document literally held the
-- six characters "106540". The consequences were not cosmetic:
--
--   * reading such a column outside a transaction throws "Unable to access lob stream";
--   * a LIKE predicate against one binds through the large-object API and throws "Large Objects
--     may not be used in auto-commit mode", or "cannot execute lo_creat() in a read-only
--     transaction" inside a read-only one, and matches nothing even where it executes;
--   * SQL over the column is silently wrong. V6 reads
--     identity_json::text::jsonb #>> '{task,task_key}', which parses the object id as a JSON
--     number and always yields NULL, falling through to its hardcoded default;
--   * and nothing ever called lo_unlink, so pg_largeobject grew without bound.
--
-- The entities now map these columns with @JdbcTypeCode(SqlTypes.LONGVARCHAR), which is what the
-- Flyway declarations always said they were. This migration brings existing rows over.
--
-- A row is converted only when its value is an integer that is actually a live large object id, so
-- rows already holding JSON are untouched and the migration is safe to run against a clean
-- database. The guard is exact rather than heuristic: no valid JSON document, and no value any of
-- these columns is contracted to hold, is a bare integer.

CREATE TEMPORARY TABLE sc968_inlined_lobs (large_object_id oid PRIMARY KEY) ON COMMIT DROP;

DO $migration$
DECLARE
    target RECORD;
BEGIN
    FOR target IN
        SELECT c.table_name, c.column_name
          FROM information_schema.columns c
          JOIN information_schema.tables t
            ON t.table_schema = c.table_schema
           AND t.table_name = c.table_name
         WHERE c.table_schema = current_schema()
           AND t.table_type = 'BASE TABLE'
           AND c.data_type = 'text'
           -- Every affected column is either a *_json column or one of the two text documents that
           -- do not carry the suffix. Restricting by name keeps the scan away from unrelated text.
           AND (c.column_name LIKE '%\_json'
                OR c.column_name IN ('patch_text', 'generation_message'))
         ORDER BY c.table_name, c.column_name
    LOOP
        -- Remember the ids first: once the column is rewritten the pointer is gone.
        EXECUTE format($sql$
            INSERT INTO sc968_inlined_lobs (large_object_id)
            SELECT DISTINCT candidate.large_object_id
              FROM (
                    SELECT (CASE WHEN %2$I ~ '^[0-9]{1,10}$' THEN (%2$I)::bigint END)
                               AS large_object_id
                      FROM %1$I
                   ) candidate
             WHERE candidate.large_object_id IS NOT NULL
               AND candidate.large_object_id <= 4294967295
               AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m
                            WHERE m.oid = candidate.large_object_id::oid)
            ON CONFLICT DO NOTHING
        $sql$, target.table_name, target.column_name);

        EXECUTE format($sql$
            UPDATE %1$I owner
               SET %2$I = convert_from(lo_get(candidate.large_object_id::oid), 'UTF8')
              FROM (
                    SELECT ctid AS row_id,
                           (CASE WHEN %2$I ~ '^[0-9]{1,10}$' THEN (%2$I)::bigint END)
                               AS large_object_id
                      FROM %1$I
                   ) candidate
             WHERE owner.ctid = candidate.row_id
               AND candidate.large_object_id IS NOT NULL
               AND candidate.large_object_id <= 4294967295
               AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m
                            WHERE m.oid = candidate.large_object_id::oid)
        $sql$, target.table_name, target.column_name);
    END LOOP;
END
$migration$;

-- Reclaim the storage. Only ids this migration actually inlined are unlinked, so any large object
-- owned by something outside this schema is left alone.
SELECT lo_unlink(large_object_id)
  FROM sc968_inlined_lobs
 WHERE EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = large_object_id);
