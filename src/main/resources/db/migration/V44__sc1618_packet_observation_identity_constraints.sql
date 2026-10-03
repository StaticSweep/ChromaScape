-- SC-1618: enforce the v2 identity relationship at the database boundary.
-- V43 intentionally introduced nullable packet_sequence so GAP observations could be backfilled.
-- Keep V43 immutable: this migration rejects any existing row that violates the wire contract and
-- prevents future retries from changing the meaning of an observation identity.

ALTER TABLE packet_observations
    ADD CONSTRAINT chk_packet_observations_identity_v2
    CHECK (
        (
            packet_schema = 'rsprox-packet/v1'
            AND packet_sequence IS NOT NULL
        )
        OR (
            packet_schema = 'rsprox-packet/v2'
            AND (
                (
                    decode_status = 'GAP'
                    AND synthetic = TRUE
                    AND packet_sequence IS NULL
                )
                OR (
                    decode_status <> 'GAP'
                    AND packet_sequence IS NOT NULL
                )
            )
        )
    );
