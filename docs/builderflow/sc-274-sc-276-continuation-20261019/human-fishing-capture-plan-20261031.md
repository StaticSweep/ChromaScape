# SC-274 human Fishing capture continuation — 31 October 2026

Scope: complete a bounded, context-bound manual TROUT capture with the user on the Windows host, then exercise published curation and the real semantic compiler. This increment does not adopt a production signer, manufacture provider-origin evidence, close historical recording gaps, or change SC-276/SC-2201 qualification.

## Original evidence and current acceptance

Preserve `recording_f370c9fb`, its raw ticks, context, quality and recovered video. It contains genuine fish/XP changes but remains `CAPTURE_INCOMPLETE`: the old producer explicitly did not observe intent/transition completeness. Do not backfill its counters or relabel it.

Shortcut SC-274 was fetched in this session and remains In Review. A successful manual Fishing demonstration addresses only part of its original acceptance. Provider-origin first input, bank/travel world evidence, privacy, negative cases and reproducibility remain independently assessed. No acceptance amendment or Done transition is implied.

## Implementation sequence

1. Add a separate read-only manual lure observer to the isolated Micro6 capture source. Keep the existing bot semantic-dispatch acknowledgment contract unchanged.
2. Restrict observation to operator-declared HUMAN / FISHING_TRAINING / TROUT. Require a prior idle snapshot, an exact recognized lure NPC and menu option, an unconsumed event, no known automation dispatch, and a subsequent pole-cast animation within the bounded tick window. Record rejection/overflow/gap/ambiguity as incomplete evidence.
3. Transport a versioned `humanCapture` witness and derive CLIENT_EVENT semantic events. They explicitly state `dispatched=false`, `humanObserved=false` and unknown automation origin: these are client observations, not hardware-origin attestation. Known bot action mixing remains ineligible.
4. Validate the same contract in ChromaScape. Compare inventory aggregates with raw slots, retain null for missing measurements, validate target identity and observed animation, and refuse relabeling. Track cumulative counters, observer identity and a terminal no-pending boundary durably.
5. Read canonical frames directly for manual compiler conversion. Do not insert synthetic rows into the bot action table or expand its source allowlist. Require complete context/quality, contiguous frames/events, exact before/after evidence, bounded import, no observer restart and complete transition inside the curation range. Link exact frame sequence/digest and event ID into manifest source evidence; use a separate converter version/checksum domain.
6. Verify unit, source-to-consumer wire, PostgreSQL persistence/recreated-reader, rejection-without-write and existing converter/compiler regression lanes. The wire fixture is explicitly `AUTHORED_OBSERVER_TEST`; it is never genuine capture or fidelity evidence.
7. Commit reviewed source and build/sign the clean client artifact with the existing approved development signer. Freeze source commit, artifact digest, manifest/bundle identity and tests. Never overwrite the JAR used by the logged-in client.
8. Deploy only the owned idle Windows ChromaScape capture service. Activate the new client using its supported saved-profile handoff, fresh real Launcher allocation, owned bridge/grant and a new profile version pinned to the new artifact. Preserve shared services, unrelated clients, DBs, fences and the original recording.
9. Verify calibration, actual session/context/source identity and a bounded idle start/stop before asking the user to fish. If any required field remains unknown, diagnose it first.
10. Ask the user for a short manual session: start while idle with fly-fishing rod, feathers and inventory room; make one Lure interaction on the trout/salmon spot; allow several catches; avoid inventory/menu/walk/bank interactions during this bounded witness. The user performs game interaction and login when required. Stop via the normal flow and verify finalization.
11. Verify raw and canonical tick continuity, calibration, explicit stop, zero pending/dropped/unknown, actual trout/salmon and XP deltas, media/index health, context/profile/source hashes and roles/privacy. On failure preserve the capture and report its precise gate; do not promote it.
12. Publish curation including the idle baseline and observed transition, compile twice from the same immutable inputs, compare full bytes/checksums, then run the supported bounded native import. Record remaining world/cache/handler gaps rather than supplying invented original state.
13. Reconcile each original SC-274 acceptance criterion against named evidence. Keep provider-origin, bank/travel, privacy or other unmet criteria open. Synchronize completion only after real workflow validation and successful tracker writes.

## Given/When/Then checks

- Given a recorded idle baseline and exact unconsumed lure target, when the pole-cast transition is observed, then emit one separately sourced CLIENT_EVENT with links to both states and no bot dispatch claim.
- Given consumed, automated, ambiguous, replaced, late, unsupported, missing or overflow input, when a frame is finalized, then preserve explicit incomplete evidence and refuse training/causal promotion.
- Given contiguous frames with accounted observations, when normal explicit stop drains with no pending intents, then eligibility may pass this manual observation contract. Stopping early, restarting the observer or inflating counters must not pass.
- Given identical immutable context, frames, curation and compiler identity, when compiled twice, then output/checksum must agree. Changing or removing required evidence must fail with a stable diagnostic.
- Given the old incomplete capture, when viewed or reassessed, then preserve playback and historical quality without upgrading eligibility.

## Linked rules

- `C:\Dev\IdeaProjects\AGENTS.md`: shared delivery/outbox contract, canonical project identities, no invented tracker success.
- `C:\Dev\IdeaProjects\.worktrees\sc274-completion\AGENTS.md`: native Windows operations and owned restart boundaries.
- `C:\Dev\IdeaProjects\.worktrees\sc274-capture-quality-20261031\AGENTS.md`: focused client builds, revision-241 injected client and credential handling.
- `capture-quality-repair-20261031.md`: prior clock/video repair, original evidence and approved development signer boundary.

## Risks and rollback

Manual origin is operator-declared; no physical input attestation or independent oracle fidelity is added. The observer covers only the narrow lure transition, so other manual input deliberately blocks this lane. An old capture cannot be repaired by new producer code. A successful authored test cannot substitute for the next genuine capture.

Rollback uses the previous committed owned service/client artifact, leaving evidence and database rows intact. A signed replacement needs a fresh matching profile/allocation. Never restore retired secrets, reset fences or overwrite the previous live JAR.

## Tracker status

Original Felix task `task_1789960012362_td2qnusp` returns Task not found. Canonical ChromaScape and Micro6 task lists were searched; no replacement SC-274 task was found. Older pending records therefore cannot yet be reconciled to their task. Existing continuation plan/evidence notes remain the durable narrative; no duplicate task, spec waiver or state transition is authorized by that missing metadata. Shortcut was read, not advanced, in this increment.
