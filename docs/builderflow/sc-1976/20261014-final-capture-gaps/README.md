# SC-1976 final capture gaps

Source: https://app.shortcut.com/leeuwbot/story/1976. Existing Felix task `task_1791760667867_s74wdsgf`, companion shared-combat task `task_1791840092160_ekr5gv6a` (SC-751). Existing PRs: ChromaScape #59 and Micro6 #162. This follow-up preserves the full foundation plan in the adjacent `20261012-settlement-calibration/README.md` and authoritative Felix plans.

## Implemented plan

1. Select a physical video frame by actual image capture time. Prepare an immutable sorted lookup once per index; binary-search it for each tick. Preserve physical frame number/media timestamp, exact media SHA256 binding and presentation extent. Ties prefer the earlier captured image, then its freshest physical copy, then lowest physical index. Out-of-order callbacks are supported. The existing clock uncertainty limit (50 ms) and total error limit (100 ms) remain unchanged.
2. Capture Micro6 identity when starting a recording: running packaged JAR SHA256, embedded build/version and observed `Client.getRevision()`. Store this observed revision as game/cache namespace revision with explicit source `CLIENT_GET_REVISION`; this does not claim a cache archive-content checksum. Directories/missing files never become fake JAR digests. Unknown build/revision/artifact values carry explicit quality codes. Reconnect requests retain the captured runtime identity.
3. Transport optional `SESSION_START.captureIdentity` (`debugger-client-identity/v1`) through the authoritative ownership/transactional start path. Validate an allowlist, value types/ranges, 8 KiB wire bound and producer reasons. Snapshot server embedded build metadata and exact application code-source bytes, packet version and combat/quality/video-index schemas. Server identity is frozen once; API reads never consult the current Git HEAD.
4. Persist this snapshot as nullable `debug_sessions.capture_identity` JSONB using additive V70. Bind identity once under the same session lock as ingestion/closure, before ticks exist. Reject conflicting reconnects and late legacy backfill. Preserve older null rows. Entity access is defensive; heuristic recording relinking cannot move known provenance. The database enforces object shape and a 16 KiB bound.
5. Expose `GET /api/debug/replay/{sessionId}/provenance`: persisted capture identity, exact session/recording IDs and media SHA256/frame count/schema only after the finalized physical index has been verified against actual media bytes. Legacy or missing/corrupt media remains explicit. Client metadata is producer-reported provenance, not signed attestation. Existing behavior-run provenance is separate and preserved.
6. Test actual sanitized timing excerpts, stale copies/ties/out-of-order callbacks, true image gaps, clock/range guards, wire serialization, identity immutability/legacy/malformed cases, media corruption, and real PostgreSQL V70 preservation/constraints/Hibernate reload. Remove the obsolete compile exclusion for session-start tests. Run scoped native regressions, capture contract, style and export. Push only these changes to the existing PR branches with `[skip ci]`.

## Scope and exclusions

Eating is excluded by explicit user instruction; implemented Eat support remains, but eating is not called verified. Full SC-751 Fishing, broader SC-752 readiness/model promotion, unrelated checkout changes, credentials/login/gameplay automation, hosted GitHub Actions, PR merges and force pushes remain excluded. Preserve raw ambiguous/multi-action diagnostic fences; a movement and attack settling on the same tick does not become a valid single movement label.

Canonical rules: `C:\Dev\IdeaProjects\AGENTS.md` and each repository's `AGENTS.md`. Windows native checks use Corretto 21, canonical contract composite and vectors. Only the owned loopback 18090 verification runtime and recorder may restart, after checking no active recording/pending transport; original 8090 is outside this change.

## Acceptance criteria

- Given a valid image near a tick and stale cadence-fill playback frames, mapping selects the nearest actual image within the unchanged error fence.
- Given a genuinely missing image interval, invalid clock/index or tick outside physical presentation extent, mapping stays unavailable.
- Given a real session, immutable client/server/revision/schema provenance survives PostgreSQL reload and recording finalization; media identity is checked against exact finalized bytes.
- Given conflicting identity, malformed/oversized metadata or legacy data, reject the invalid mutation or explicitly report the unavailable historical identity.
- Given genuine final attack/movement/prayer capture, storage/projection/schema/coverage and calibrated video pass; an accepted sanitized provenance-backed fixture and server-restart preservation prove completion. Synthetic/timing-only regression data is not an accepted combat fixture.

## Changed files

ChromaScape: MeasuredVideoIndex and tests; DebuggerCaptureIdentity/DebuggerServerBuildIdentity; DebugSession entity, service, request DTO and WebSocket forwarding; TickReplayController provenance API; V70; embedded build metadata generation; identity/session/ownership/API/PostgreSQL tests; sanitized timing-only regression and this evidence.
Micro6: DebuggerClientIdentity and tests; recorder/client-thread revision snapshot; SESSION_START serializer and transport; transport/serialization tests. Reservation files and unrelated working changes are excluded.

## Tests

Actual native checks, 2026-10-14:

- ChromaScape selected combat/debugger/playback/recording suite: **408 tests, zero failures/errors/skips**. This includes real PostgreSQL V70 migration idempotency, legacy nulls, object/16 KiB constraints, Hibernate reload/defensive identity and session-row closure locking. The previously excluded session-start tests now compile and run; their stale missing-schema packet fixture was corrected. Initial 406-test run had that one test failure; the corrected 408-test run is clean.
- Final identity canonicalization/formatting refinement: **23 tests, zero failures/errors/skips**, covering identity validation, session binding/reconnect/legacy and real PostgreSQL. Counts overlap the 408-test pass.
- Actual timing-only regressions: latest `recording_eeed6066` maps **102/102** tick endpoints; older `recording_3ba1931c` maps **89/92**, with **three actual image gaps** still unavailable. All use the original 100 ms fence. This is a timing regression, not an accepted combat fixture.
- Production row/storage review of eeed6066: **102 rows ? 11 sections**, zero PostgreSQL mismatches, zero combat-row schema errors, 36 resolved NPC-target ticks, **101/101 available frame ranges**. Terminal tick 135 has no next tick to define a range. Coverage preserves its safe nine labels (one attack/eight prayer); simultaneous movement+attack remains ineligible.
- Scoped Google Java Format applied with original per-file line endings preserved. Standalone Checkstyle 10.26.1 passes without warnings for new Chroma identity source/tests and Micro identity source/tests. The wider Chroma changed-file audit exits successfully with existing style warnings; it is not described as a warning-free repository audit.
- V68/V69/V70 explicitly applied successfully to retained loopback verification schema `sc1976_live_20261012`. This is not production Flyway-chain certification. GitHub Actions are skipped by user instruction. Four existing Docker debugger suites remain unrun.

Micro6 full-client, runtime export, push and restart results are appended below when complete.

## Remaining work and user procedure

After the updated runtimes are ready:
1. Open http://127.0.0.1:18090/debugger, log into Micro6 manually and enter the game world.
2. Start Recording. Record roughly 1?2 minutes. Walk on a separate tick, wait a few seconds, attack a Giant spider, and toggle a prayer. Leave a few seconds between movement, attack and prayer actions so each can settle unambiguously. Eating is unnecessary.
3. Stop Recording and send the recording URL. Do not restart anything before stop completes.
4. The agent then verifies no missing/duplicate ticks, target joins, all required fields, persisted build/revision/schema/media identity, actual video mapping, semantic labels, storage/projection/coverage, restart-stable finalization and a sanitized accepted fixture. It updates the existing Felix/Shortcut/PR evidence and transitions only when all gates actually pass.

## Risks and rollback

Old recordings cannot acquire provenance they never captured. Development server identity scopes its digest to the application class directory; a packaged runtime advertises a JAR digest. Build revision and dirty state are embedded; exact byte digest distinguishes a dirty build. Producer-reported client identity is not signature certification. Revision identifies the observed game/cache namespace, not every archive byte. Real missing images remain unavailable. The retained verification schema is not production migration-chain certification. Four existing Docker debugger suites and hosted Actions are not represented as passed.
Rollback source/collector activation if needed, retain additive V70 and all existing recordings. Do not delete reservations or mutate unrelated user work.
