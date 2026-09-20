-- SC-1449: active account/client slot ownership. The V40 tuple uniqueness allows
-- another simultaneous live request for the same account/client with a higher fence.
-- This partial unique index holds at most one non-terminal row per (account, client);
-- terminal rows stay historical and never occupy the slot.

CREATE UNIQUE INDEX IF NOT EXISTS uq_gameplay_v2_gate_active_slot
    ON gameplay_v2_gate_request (account_id, client_id)
    WHERE state NOT IN ('CONFIRMED', 'REFUTED', 'UNVERIFIABLE', 'FAILED', 'STOPPED');
