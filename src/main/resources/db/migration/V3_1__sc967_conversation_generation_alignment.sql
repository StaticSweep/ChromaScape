-- SC-967: align pre-existing routine_assistant_conversations with the baseline shape.
--
-- V1_1 creates routine_assistant_conversations through CREATE TABLE IF NOT EXISTS, which is a
-- no-op on databases where Hibernate already created the table before Flyway was adopted. On a
-- database whose Hibernate-managed shape predates the generation lease columns, V1_1 cannot add
-- them and V4's expired-lease-recovery index would fail. This migration adds the missing
-- generation columns idempotently so both drifted and fresh databases reach the same shape
-- before V4 runs. V4 itself adds generation_message and generation_attempts.

ALTER TABLE routine_assistant_conversations ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE routine_assistant_conversations ADD COLUMN IF NOT EXISTS next_event_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE routine_assistant_conversations ADD COLUMN IF NOT EXISTS generation_token VARCHAR(80);
ALTER TABLE routine_assistant_conversations ADD COLUMN IF NOT EXISTS generation_state VARCHAR(20);
ALTER TABLE routine_assistant_conversations ADD COLUMN IF NOT EXISTS generation_lease_expires_at TIMESTAMP;
