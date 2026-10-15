# SC-1976 WALK attribution fix

Existing work: Felix `task_1791760667867_s74wdsgf` and shared SC-751 `task_1791840092160_ekr5gv6a`; ChromaScape PR59 and Micro6 PR162. Canonical project identities are `C:\Dev\IdeaProjects\ChromaScape` and `C:\Dev\IdeaProjects\micro6`. Eating and GitHub Actions remain excluded. This does not complete full SC-751 Fishing acceptance.

## Complete delivery plan and status

- Combat tick-row schema, bounded state/input collectors, target join, field provenance and explicit deferrals: implemented in the existing story branches.
- Additive persistence, projections, checksum/packet compatibility and deterministic coverage consumption: implemented; the original real recording's required-field/database/schema checks passed.
- Physical video-frame selection, measured clock alignment, durable finalization and per-recording build/revision/schema/media identity: implemented; the original recording passed video/identity/restart verification.
- Correct movement attribution and conservative training eligibility: implemented by the producer, schema and consumer changes below, with native and historical-data regressions.
- Scoped build/package/relaunch and PR/tracker evidence: recorded in local-checks.json and the existing Felix notes.
- Corrected real movement/attack/prayer capture, accepted sanitized fixture and final review/delivery gates: still required. Eating and hosted Actions are excluded; unrun certification is explicitly listed below.

## Cause and implemented plan

In `recording_ea942174`, five WALK callbacks carry canvas operands `(468,74)`, `(314,55)`, `(120,251)`, `(219,273)` and `(245,122)`. The previous producer converted those pixels with `WorldPoint.fromScene`; the consumer accepted decreasing distance to the resulting fabricated destinations. Lag can delay movement, but cannot explain or repair that coordinate conversion.

1. Preserve raw callback operands, hashes and immutable historical records.
2. Snapshot the top-level selected scene tile and pre-dispatch destination. Check scene bounds, world-view identity, plane and the tile's exact world/scene correspondence. Never use the WALK operands as tiles or infer a target from the resulting player position.
3. Observe the new local destination after dispatch on ClientTick and collection. Require it to match the selected tile and differ from the old destination. Retain this proof through arrival; invalidate it on scene/rebase/plane changes or replacement of a proven route. Missing evidence, consumed or competing callbacks cannot confirm movement.
4. Keep the four-tick outcome horizon. Require actual progress toward the proven destination. Delayed movement can confirm within that horizon without changing any video/alignment threshold.
5. Add optional structured `movementIntent` provenance to capture-quality/v1 state evidence. ChromaScape ingestion and the existing training-label guard validate coordinate conversion, bounds, plane, changed-destination freshness and source/sample/settlement ticks. Historical confirmations without this evidence stay diagnostic and ineligible. No database migration or history rewrite.
6. Add meaningful producer, actual Gson-to-server and training-gate regressions. Rebuild and relaunch the owned verification server/client on 18090, then obtain a fresh real combat recording and accepted sanitized fixture.

## Changed files

Micro6: `CombatSemanticCapture.java`, new `WalkDestinationEvidence.java`, `DebugRecorderPlugin.java` and `CombatSemanticCaptureTest.java`, under the Debug Recorder production/test packages. ChromaScape: `CaptureQualityContract.java`, `capture-quality-v1.schema.json`, `CaptureQualityContractTest.java` and two test resources. This document and local-checks.json provide the handoff.

`capture-quality-walk-current-serializer-v1.json` is produced by the current Gson serializer in a mocked client test. The server test replaces only unavailable clock evidence with a constructed measured clock to exercise the label gate. It is not a real accepted capture.

`capture-quality-rejected-walk-ea942174.json` contains the five actual bad settlements and their raw lineage. IDs and clocks are replaced, and world coordinates are translated to base 3200 while preserving relative state changes and raw canvas operands. It is a negative regression; no corrected destination/proof is invented. The source recording remains unchanged.

## Tests

- Focused Micro6 combat tests and capture contract passed. The final full run includes all 26 CombatSemanticCapture tests, including the last two additional regressions.
- ChromaScape: 21 tests, zero failures/errors/skips. These cover actual Gson movement evidence, ingestion/captured-position agreement, missing/stale/out-of-scene/mismatched/future proof, all five real negative cases, label safety and coverage.
- Final full Micro6 suite and capture contract: 1,725 tests passed, zero failures/errors/skips, with the SC967 vectors path configured. Packaging and runtime details are in local-checks.json. The initial full run executed 1,725 tests with four missing-SC967-vector failures and zero errors/skips. A prematurely started configured attempt encountered the still-running test executor's output.bin lock and ran no tests; the final configured rerun runs after the original executor exits. An initial full invocation lacked `SC967_VECTORS_PATH`; its failure is an environment prerequisite and is retained in private logs. The rerun supplies `C:\Dev\IdeaProjects\contracts\sc967\ed25519-vectors.json`.
- Hosted Actions are excluded. Previously unrun Docker debugger and production migration-chain certification remain unrun; this change adds no migration.

- Updated server on 18090: debugger HTTP 200. Full original session, all 101 tick snapshots and provenance are exactly unchanged across the restart. Direct production validation/gating of all 101 original snapshots rejects exactly ticks 535/541/545/552/557 and admits zero WALK_TO labels, retaining one attack and two prayer labels. Original 8090 remains untouched.

- Package export and launcher preparation/capture contract passed. The rebuilt client is open at LOGIN_SCREEN, recorder-ready and connected/recovery ACTIVE to ws://127.0.0.1:18090/ws/micro6/tick-stream, with zero pending/queued/incomplete/dropped evidence and a closed reconnect circuit. Client build 429e32978b and independently hashed packaged JAR SHA256 893c4f6942ac32ad466ba9c5e86fe436ee4ceca783a81743bbced16d41b4a72b match authenticated runtime health. Server build 7f507c8f is running on 18090. This is launch readiness; in-game Start/Stop and new movement capture remain unrun until manual world entry.

## Remaining work

Fresh user-performed movement/attack/prayer capture and an accepted sanitized combat fixture remain required. Passing mocked regressions does not certify live movement attribution. SC-1976 and shared SC-751 stay `spec_ready/in_progress`; Shortcut stays In Progress until the required acceptance evidence and review gates pass.

After the updated client is open, the user logs in and enters the game. Micro6 AGENTS.md says, "Do not handle game credentials or automate authentication dialogs. The user performs login."

Open http://127.0.0.1:18090/debugger. Start recording; move to an unobstructed tile several tiles away, wait about five seconds, attack a spider, wait, toggle a prayer, wait, then perform another separate movement. Stop after1–2minutes and send the recording URL. Eating is optional/excluded. Keep actions separated to avoid ambiguous simultaneous labels. Use this verification client; the normal account launcher restores 8090 routing.

Review must confirm actual destination provenance and correct movement, attack/prayer labels, contiguous ticks, required fields/database comparison, video alignment, immutable saved identities and accepted fixture eligibility. The previous recording's video/ticks/restart checks already passed, but its five incorrect movements cannot be reused as corrected movement acceptance.

## Risks

Conservative attribution can leave legitimate movement unconfirmed when selected-tile state is unavailable, a destination is unchanged, a route is clipped/replaced or lag exceeds the fixed horizon. It must not invent evidence to increase coverage. Unsupported world views and minimap-only movement without a corroborated WALK callback remain outside confirmed labels. Historical faulty confirmations may still appear in producer-reported quality counts; training eligibility is guarded separately.

## Rollback

Revert the focused changes and rebuild/relaunch only the owned verification processes. Keep recordings and database data. Do not re-admit legacy movement targets as training labels. Preserve the original 8090 instance, user reservation snapshot/locks and saved account profiles.

## Tracker and PR handoff

All six existing Felix plans/Implementation Evidence/Verification Evidence notes were updated and read back. Both existing tasks remain spec_ready/in_progress. The movement implementation and new native-regression items are checked; real capture/fixture and composite live acceptance remain unchecked. No duplicate task or review/done transition. Shortcut SC1976 description and comments 2070/2071 (SC1976/shared SC751) were updated successfully with marker `[sc1976-walk-attribution-fix:20261015]`; both stories remain In Progress500000008. Both existing PR descriptions were updated and remain draft. Chroma source 7f507c8f, evidence fbb49590; Micro6 source 429e32978b.
