# SC-810 source provenance and signing decision

Status: accepted for SC-810 implementation

The Micro6 producer is the authority for executable source. A task manifest is generated from a
clean, pinned repository tree and contains separate repository commit/tree, build, launched
artifact, manifest, and content-addressed snapshot identities. ChromaScape may persist and display
only a manifest that passes all identity checks; a branch, tag, current checkout, or caller supplied
URL is never a source of truth.

The manifest checksum is SHA-256 over canonical UTF-8 JSON after removing `source_manifest_hash`,
`source_snapshot_id`, and `signature`. The snapshot ID is `srcsnap_` plus the first 24 hexadecimal
characters of that checksum. The signature is Ed25519 over canonical UTF-8 JSON after removing only
`signature`, and includes the already populated checksum and snapshot ID. The signature carries a
key ID and the hash of the signed bytes. Public keys are configured out of band and are never
accepted from a request.

Legacy `micro6-source-manifest/v1` documents remain readable only as provenance-unknown data. They
cannot establish patch eligibility or replace a verified run binding. New production documents use
`task-source-manifest/v1`, require a clean tree, a real SHA-256 artifact digest, a bounded closure,
and an Ed25519 signature.

Source reads are read-only, allowlisted by the verified manifest, independently authorized for
metadata and content, bounded, and audited. Redacted, pruned, generated-only, unavailable, partial,
and mismatch states are stable contract outcomes; none may fall back to another revision.
