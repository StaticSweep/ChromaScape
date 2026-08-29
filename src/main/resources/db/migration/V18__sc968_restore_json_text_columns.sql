-- Restore the JSON columns to text after Hibernate narrowed them to varchar(32600).
--
-- V17 replaced the @Lob mapping on these String fields. The first replacement used
-- @JdbcTypeCode(SqlTypes.LONGVARCHAR), which the PostgreSQL dialect renders as varchar with the
-- dialect's maximum length rather than as text -- and because spring.jpa.hibernate.ddl-auto is
-- update, Hibernate then ALTERed every one of these columns from text to varchar(32600) at
-- startup. The next capture tick batch larger than that bound failed with SQLSTATE 22001, the
-- server answered 422, and the client's capture entered FAILED with CAPTURE_WRITE_REJECTED.
--
-- The entities now say columnDefinition = "text" outright, so Hibernate stops rewriting them. This
-- widens any column an affected startup already narrowed.
--
-- 32600 is Hibernate's bound, not a number any hand-written migration would choose, so matching on
-- it converts exactly the columns that were damaged and leaves every deliberately bounded varchar
-- -- session ids, hashes, states -- alone.

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
           AND c.data_type = 'character varying'
           AND c.character_maximum_length = 32600
         ORDER BY c.table_name, c.column_name
    LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE text',
                       target.table_name, target.column_name);
    END LOOP;
END
$migration$;
