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

Micro6 full native `:client:test :client:verifyCaptureContract` with canonical `SC967_VECTORS_PATH` and contract composite: **1,707 tests, zero failures/errors/skips**, one clean invocation on the final source. Initial focused 313-test run had one ACK-timeout failure under concurrent load; all 62 transport tests passed on isolated retry and the final full 1,707-test invocation passed. No transport semantics or timeout fences were relaxed. New producer identity/JAR-byte hash/serializer tests are included.

Scoped code pushed normally to existing draft PRs: ChromaScape `0a6cbaf6`, Micro6 `93aa4810a0`. No reservations, secrets, raw gameplay or unrelated original-checkout changes were staged.

## Runtime handoff status

V70 is applied to the retained verification schema. After the user explicitly requested the relaunch again, the separate, ownership-checked stop and hidden launch commands succeeded. The updated server is running on loopback 18090 (PID 132368), with embedded revision `92b496adebd7bfef8bacaf1f0e3ababa08b4cc13` and `dirty=false`. Original 8090 remains PID 69980 and was untouched. Debugger and the new provenance endpoint both return HTTP 200. No live new-identity capture is claimed yet.

If a manual restart is needed: stop only the worktree verification server on port 18090, then from `C:\Dev\IdeaProjects\.worktrees\sc1976-chromascape` run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Dev\IdeaProjects\.worktrees\felix-native-runtime\launch-chroma-gaps.ps1`. This private local helper loads existing `.env.local` settings without embedding secrets, uses only loopback18090 and retained `sc1976_live_20261012`, disables Flyway/Hibernate schema update, and writes `sc751-live-server.log`. Do not use the canonical 8090 restart defaults for this verification instance.

Runtime export/launch results and any user-enabled restart proof are appended below when completed.

## Remaining work and user procedure

After the updated runtimes are ready:
1. Open http://127.0.0.1:18090/debugger, log into Micro6 manually and enter the game world.
2. Start Recording. Record roughly 1?2 minutes. Walk on a separate tick, wait a few seconds, attack a Giant spider, and toggle a prayer. Leave a few seconds between movement, attack and prayer actions so each can settle unambiguously. Eating is unnecessary.
3. Stop Recording and send the recording URL. Do not restart anything before stop completes.
4. The agent then verifies no missing/duplicate ticks, target joins, all required fields, persisted build/revision/schema/media identity, actual video mapping, semantic labels, storage/projection/coverage, restart-stable finalization and a sanitized accepted fixture. It updates the existing Felix/Shortcut/PR evidence and transitions only when all gates actually pass.

## Risks and rollback

Old recordings cannot acquire provenance they never captured. Development server identity scopes its digest to the application class directory; a packaged runtime advertises a JAR digest. Build revision and dirty state are embedded; exact byte digest distinguishes a dirty build. Producer-reported client identity is not signature certification. Revision identifies the observed game/cache namespace, not every archive byte. Real missing images remain unavailable. The retained verification schema is not production migration-chain certification. Four existing Docker debugger suites and hosted Actions are not represented as passed.
Rollback source/collector activation if needed, retain additive V70 and all existing recordings. Do not delete reservations or mutate unrelated user work.


## Final native package and publication

Micro6 `:client:exportRsproxMicro6Runtime` passed at commit `93aa4810a0`; packaged shaded JAR SHA256 `9ba0f10b82db69fce41f4429d43f8fab035c38d738217613e169c57c11263802`. Only the explicitly scoped new recorder/server changes were pushed to existing PR #162/#59; both remain draft. No source changes followed the final 1,707-test Micro6 invocation. Source revision/build resource regeneration for packaging does not change Java behavior.

The rebuilt client is running in the temporary debugger profile and reconnected automatically after the verification server restart. Authentication and world entry remain manual. Chroma18090 now runs the updated server. A new live metadata capture and accepted sanitized fixture remain unrun.


## Earlier client verification and durable status

Runtime health readback confirms Micro6 build `93aa4810a0aa972b44a0c6e2467fa2e4a647031f` and the exact packaged digest above, `source_mode=disabled`, `LOGIN_SCREEN`, recorder ready, connected/ACTIVE with zero queued, pending, incomplete or dropped batches. Clock calibration is stale while the user is on the login screen; this is not a calibrated game-world acceptance claim.

Existing Felix authoritative plans, Architecture/ERD/API refinements and Implementation/Verification Evidence are updated while preserving their earlier content. Tasks `task_1791760667867_s74wdsgf` and `task_1791840092160_ekr5gv6a` remain `spec_ready/in_progress`. Official Shortcut progress comments [2063](https://app.shortcut.com/leeuwbot/story/1976#activity-2063)/[2064](https://app.shortcut.com/leeuwbot/story/751#activity-2064) and SC-1976 delivery-description refresh succeeded; both external states stay In Progress. The new implementation item is complete; restart/real fixture acceptance items remain unchecked. No review/done transition is claimed.

## Verification server restart, 2026-10-14

The old owned PID 57284 was stopped only after checking recording status and exact listener/application/worktree ownership. The prepared launcher retained `sc1976_live_20261012`, disabled Flyway/Hibernate schema updates and replay workers, and started the replacement hidden. No Micro6 restart or authentication input was performed.

For `recording_eeed6066`, the complete session and ticks API JSON are exactly equal before and after restart: 102 consecutive ticks, bounds 34-135, finalized and inactive. The new runtime reports all 102 calibrated endpoints with no calibration quality codes, maximum combined alignment error 98.694327 ms and p95 71.418798 ms, inside the unchanged 100 ms fence. Provenance verifies all 1,159 physical frames and media SHA256 `a93d42a93b0fdc349489fca395e72ef0f483e9ad83f20eec9a42aad863cba3ac`, independently compared with the actual MP4 bytes. Legacy capture identity remains explicitly unavailable (`LEGACY_CAPTURE_IDENTITY_MISSING`), without backfill.

Micro6 health confirms the existing packaged build `93aa4810a0aa972b44a0c6e2467fa2e4a647031f` reconnected, recovery ACTIVE, zero queued/pending/incomplete/dropped batches. It remains at LOGIN_SCREEN. Transport clock measurement resumed; this does not qualify game-world capture. User must manually enter the world and record separate movement, attack and prayer actions before fresh identity persistence and the accepted sanitized fixture can be verified. Existing test checklist entries that also require that capture remain unchecked. No full suite was rerun for this runtime-only verification; Actions remain excluded by user instruction.

## Recorder routing recovery, 2026-10-14

The subsequent Start Recording failure was `RECORDER_UNAVAILABLE`: the replacement Micro6 client had connected to `ws://192.168.1.6:8090/ws/micro6/tick-stream`, while the verification page uses port 18090. This was a different client process from the one verified above. Its server-confirmed session was tick-only, without a recording ID.

Only that owned Micro6 client (PID 182528) was stopped. The existing `launch-sc1976-debug-capture.ps1` was launched through the private local helper, with the loopback 18090 origin, process-only combat collectors, SC-968 capture disabled and no account/login arguments. Saved profiles, reservation files, original ChromaScape 8090 and its existing recording were preserved. No source changes were made in this recovery.

Startup log and authenticated health now verify PID 20816 connected to `ws://127.0.0.1:18090/ws/micro6/tick-stream`, recorder ready, recovery ACTIVE, zero pending/queued/incomplete/dropped batches and no transport failure. The existing worktree had advanced independently to build `836b97123a2f2606643c3ee3e3373062da91dd2d`; its packaged SHA256 is `6dedde2e485df4c6d9a00cec8794701882f96b47bb0ff6e662a9cf6bb68f6b59`. Launch preparation and capture-contract verification passed. The full suite results above certify their stated earlier source, not this newer build; no full suite was rerun here.

The replacement is at LOGIN_SCREEN. Transport clock calibration becomes stale without game ticks; do not infer game-world readiness from connection alone. Start/Stop and fresh identity capture still require manual world entry. For this acceptance run, use the already opened debugger client and the 18090 page; launching a replacement through the ordinary Builder Goal/account flow can restore its saved 8090 route. The user was asked to enter the world and stay idle so the agent can verify Start/Stop before another combat recording.

## In-game Start/Stop smoke, 2026-10-15

After the user logged in and entered the world, authenticated health confirmed LOGGED_IN and a measured clock. The agent started a new manual idle recording through the same debugger API, left gameplay untouched and stopped only that session. Start and Stop both succeeded: `recording_0442e08a`, session `debug_0bf5f5150c5d401f8b814abfb503b482`, finalized and replayable with 19 consecutive ticks 155-173, all required fields, no gaps/duplicates and 19 position frames. All 19 video endpoints are measured, with no quality codes and maximum combined error 28.677297 ms against the unchanged 100 ms limit.

The new provenance API persists client build `836b97123a2f2606643c3ee3e3373062da91dd2d`, version `1.13.3-SNAPSHOT`, actual JAR SHA256 `6dedde2e485df4c6d9a00cec8794701882f96b47bb0ff6e662a9cf6bb68f6b59`, game/cache namespace revision 241 from `CLIENT_GET_REVISION`, server build `92b496adebd7bfef8bacaf1f0e3ababa08b4cc13`, version `0.2.0-SNAPSHOT`, dirty=false and application-class-directory SHA256 `2c677e8e5d898a678f28f98276de2ed190e8592c57ce9855d496fb19568052f9`. Client/server identity quality codes are empty. Tick batch version 2 and combat/quality/video schemas are recorded. This is producer-reported identity with the documented development-directory scope, not packaged-server qualification or a cache archive-content checksum.

The finalized media verifies 394 physical frames and SHA256 `c1722e1df51806e79a5131485400a74fb1c1aabd2e5f0699d5cb4cac5040ffc9`. Independent hashes match the actual client JAR and MP4 bytes. Full MP4 decode passes and a private extracted frame visibly shows the game world. Session, ticks and provenance JSON are unchanged on a repeat read. No raw account/position/image payload is committed. No source edits or full-suite rerun were needed for this runtime verification; earlier suite results retain their stated source scope.

The routing blocker is resolved and the recorder is idle after the smoke. The user may now record separate movement, attack and prayer actions, then Stop and send the URL. Eating remains excluded. This idle smoke contains no combat actions and is not the accepted combat fixture. Remaining: genuine action coverage, accepted sanitized real-capture fixture, and restart survival of a recording carrying the new identity. Do not restart while the user is recording. Felix/Shortcut remain In Progress and the PRs remain draft until their actual gates pass.
