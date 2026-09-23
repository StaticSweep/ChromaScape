-- SC-1431.5: allow null bootstrap prompt provenance and persist provider session/turn identity.
--
-- V48 added observation_digest and prompt_digest as NOT NULL DEFAULT ''. A bootstrap proposal
-- intentionally carries no provider prompt, so prompt_digest must be nullable; the validator still
-- requires a valid prompt digest for provider sources. Provider-bound orchestrations also gain the
-- durable provider session and turn identity so two distinct provider turns with identical decision
-- and provenance content can never resolve as the same exact replay.

ALTER TABLE ab_snapshot_binding
    ALTER COLUMN prompt_digest DROP NOT NULL,
    ALTER COLUMN prompt_digest DROP DEFAULT,
    ADD COLUMN provider_session_id VARCHAR(128),
    ADD COLUMN provider_turn_id VARCHAR(128);

-- Provider identity is all-or-nothing: a bootstrap binding carries neither value, and a provider
-- binding must carry both as bounded identifiers.
ALTER TABLE ab_snapshot_binding
    ADD CONSTRAINT ab_binding_provider_identity_check CHECK (
        (provider_session_id IS NULL AND provider_turn_id IS NULL)
        OR (provider_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND provider_turn_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
    );
