# SC-609 capture provenance and canonical frames

The complete authoritative scope and acceptance plan is [SC-609-DELIVERY-PLAN.md](SC-609-DELIVERY-PLAN.md). Felix task: `task_1790971893615_dult4y03`, source [SC-609](https://app.shortcut.com/leeuwbot/story/609). ChromaScape PR #45 and coordinated Micro6 PR #160 are merged into main. Deployment is a separate operation.

## Capture identity

`RecordingStartRequest` and additive `SESSION_START` fields carry `taskKey`, object `taskArgs`, and optional `runContext`. The server derives `settingsHash` with the same canonical hasher as SC-641. Null args mean unknown; `{}` means known empty. The server rejects scalar args, excessive size/depth, ambiguous normalized keys and sensitive credential keys before starting a recorder. Provided hashes never replace server derivation.

A profile-bound start resolves the exact historical profile version, verifies task/settings, and freezes the catalog, profile, schema, requirements, category, account and run/client/executor/allocation references. The recording row owns capture identity. Finalization updates video metadata, not capture identity. PostgreSQL rejects modifications to previously captured account/task/settings/context. A canonical tick-only capture receives a `capture_` recording identity and immutable metadata without starting another video recorder.

Manual profile-bound capture delegates to the same debug-session service. Micro6 forwards the executor's accepted settings and task-entry identity, rather than a display label or diagnostic configuration. Detection/plan/bootstrap paths capture available account/task metadata once; unavailable exact executed settings remain unknown. They do not manufacture a profile or account from heuristic task labels. Detected-recording fallback rows use the start-time account even if window selection later changes.

## Semantic contract and consumers

`src/main/resources/contracts/behavior-semantic-v1.json` owns feature IDs/order/dimensions/meaning, action IDs/order/meaning, masks and source kinds. `scripts/generate-behavior-contract.py --micro6-root <producer checkout>` copies that artifact into the producer. Consumers derive dimensions and compare the complete contract or its canonical digest; a mismatch fails closed.

`CanonicalTickFrame` freezes one received capture. Recording, replay, feature extraction and read-only scoring use its content hash. Missing observations remain JSON null. The legacy fishing-model adapter accepts fully known canonical features only; it does not convert missing telemetry into zero or false. Fishing datasets retain frame and contract digests and require a corresponding observed human semantic action. Existing legacy recordings remain readable but cannot silently become canonical training evidence.

Authorized endpoints:

- `GET /api/recordings/v1/sessions/{recordingId}/provenance`: immutable context, capture origin and eligibility.
- `GET /api/recordings/v1/sessions/{recordingId}/frames?afterSequence=0&limit=200`: bounded sequence traversal, canonical frames and derived observations. `training=true` additionally requires finalized complete capture quality.
- `POST /api/recordings/v1/sessions/{recordingId}/frames/{sequence}/score`: compares the supplied complete contract, validates score dimension and finite values, applies canonical legal masks, and returns a read-only recommendation with the same frame digest. It never dispatches an action or records success. Observed events and recommendations remain separate.
- Operator-authenticated `POST .../{recordingId}/provenance`: explicitly audited assignment for unknown finalized legacy provenance; requires actor and reason, records `INFERRED` origin, and never overwrites captured metadata. It cannot repair absent event-completeness evidence.

## Transport and quality

`SESSION_START` advertises tick versions `[2,3]` and ACK version `[1]`. A profile-bound session selects canonical v3; a legacy session selects v2. Version selection is fixed for that connection. V3 uses canonical UUID run/session/producer epoch, positive uint64 sequence, canonical lower-case SHA-256 content hash excluding `contentHash`, minimal run context, task state and structured source-aligned events.

Micro6's v3 outbox writes a frozen frame before enqueueing it. Files are written through forced temporary files and atomic rename. Linux files/directories restrict access to their owner; Windows inherits the user's ACL. Retained bytes survive producer restart, sequence/event counters are recovered from state and retained frames, and only validated durable cumulative ACKs remove frames. Restart recovery resolves the original session by immutable run/client/profile identity. The server commits frame and quality before its ACK. Duplicate identical frames are idempotent; conflicting sequences, gaps, repeated event IDs, event-sequence gaps and alignment failures reject deterministically.

Disconnect finalizes quality as incomplete. Recovery can drain the original retained outbox without restarting video, changing the capture identity or reopening the training gate. Recovering an absolute snapshot never repairs missed transient events. Explicit stop drains producer receipts before stop and closes the server quality record. Rejected frames add durable quality evidence. Producer overflow writes local terminal-quality evidence and fails closed.

**Current producer capability:** this adapter observes menu dispatches. Their automation/human origin is unknown; it never relabels them as human demonstrations. Complete script intent and transition verification are unsupported and explicitly counted as unknown/unsupported quality. Its current v3 captures are therefore training-ineligible. Downstream adapters must provide measured completeness and frozen semantic snapshots before training can pass. No live gameplay, client launch, dispatch, curation engine or allocation orchestration is added by this story.

Raw chat, other-player lists, widgets, packet/log/overlay streams and raw menu arguments are removed from canonical v3 persistence. Requested privacy redactions occur before hashing and outbox persistence. Provider attribution remains on the immutable context and is never converted into human origin. Legacy transport remains separately readable.

## Catalog compatibility

The original recording-session array route remains available. New `/api/recordings/v1/sessions` pages all recording rows, including video-only rows. `/api/replay/playback/v1/recordings` supplies linked tick evidence. Both use `(start_time DESC, id DESC)` keyset traversal, exact independent/combined account/task filters and limits clamped to 1–200. Recording-row account/script/task/args/hash are authoritative even when debug-session labels differ. The deprecated playback offset alias remains available.

The replay UI loads bounded video pages with Load more, provides account/task filters and account→task/all-account→task grouping, and expands exact settings/hash through DOM text nodes. Tick enrichment failure does not hide playable video. Loading/error/empty states are explicit, and stale requests cannot overwrite a newer filter selection.

## Migration and rollback

Normal additive migration V62 (renumbered from the colliding V33) adds nullable provenance, keyset indexes, append-only frames/audits and capture quality. An idempotent convergence runner applies guarded indexes/identity triggers after Hibernate has created recording tables on a fresh application database. Disposable current-schema V5 bumps the reviewed development identity to `sc609-current-v1`. Current-schema verification refuses stale identity, migration history or fingerprint; it never automatically resets a running user's database.

Apply normal migrations through the existing deployment procedure. For the dedicated disposable development profile, follow the explicit reviewed reset procedure in SC-665-CURRENT-SCHEMA.md. Never use the test reset target against production.

Rollback the coordinated application/producer commits and retain additive evidence tables/nullable columns. Do not delete captured frames or audit records. Reverting the current development schema requires an explicit disposable reset to that version. The v3 outbox must be preserved until evidence is reconciled; do not delete it to suppress a transport failure.

## Verification boundary

Actual verification results and the Fedora manual checklist are recorded in [SC-609-VERIFICATION.md](SC-609-VERIFICATION.md). The user selected Fedora-only delivery acceptance: a passing Fedora live capture/recovery and browser check is sufficient for completion. Windows qualification is outside this scope. Fedora backend/UI automated suites passed; the user supplied passing Micro6 compilation and full client test evidence. No application/client launch or shared database reset was performed by this verification session. CI monitoring and Felix remain skipped as requested.

Fedora 44 runtime/API acceptance was reported PASS on 2026-10-04, conditional on the run-context normalization and recordings principal fixes. Both are integrated separately from migration renames; the startup convergence resource also follows V62. Browser display, video and catalog multi-page checks remain explicit follow-up gaps. See the verification report for IDs and actual rerun test results.
