-- Repair legacy replay_tick_batch_receipts tables that predated Flyway ownership.
-- V35 used CREATE TABLE IF NOT EXISTS, so an existing Hibernate-created UUID id column could
-- survive without the gen_random_uuid() default required by the native insertIfAbsent query.
DO $$
BEGIN
    IF to_regclass('replay_tick_batch_receipts') IS NOT NULL THEN
        ALTER TABLE replay_tick_batch_receipts
            ALTER COLUMN id SET DEFAULT gen_random_uuid();
    END IF;
END $$;
