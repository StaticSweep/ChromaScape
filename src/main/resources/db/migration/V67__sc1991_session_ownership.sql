-- Legacy rows have no proven owner and stay inaccessible through the authenticated API.
ALTER TABLE xrsps_sessions ADD COLUMN owner_id TEXT;
ALTER TABLE xrsps_sessions DROP CONSTRAINT xrsps_sessions_idempotency_key_key;
CREATE UNIQUE INDEX xrsps_sessions_owner_idempotency ON xrsps_sessions(owner_id, idempotency_key);
