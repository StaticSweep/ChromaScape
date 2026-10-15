# SC-1976 review of recording_ea942174

Source recording: http://127.0.0.1:18090/debugger?recordingId=recording_ea942174. Existing tasks: SC-1976 `task_1791760667867_s74wdsgf`, shared SC-751 `task_1791840092160_ekr5gv6a`. Existing draft PRs: ChromaScape #59, Micro6 #162. Eating remains excluded.

## Finding: P1 incorrect WALK targets pass the training-label gate

Micro6 `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/CombatSemanticCapture.java:97` passes the WALK callback's `param0/param1` directly to `WorldPoint.fromScene`. In this real capture those operands are canvas coordinates, outside the loaded scene's 0-103 tile range. All five producer-confirmed movement events therefore name impossible destinations, 196-423 tiles from their before-state, despite actual movements of only a few tiles.

Affected source/observation tick pairs: 534/535, 541/541, 545/545, 552/552, 556/557. The complete production quality/row/coverage review currently accepts these as WALK_TO labels. ChromaScape `src/main/java/com/chromascape/web/debug/tickdebugger/service/CaptureQualityContract.java:266` only requires the after-position to be closer to the declared target. Moving toward an invalid distant point can satisfy that comparison accidentally. Syntactic schema validation and a CONFIRMED count do not establish correct movement attribution.

The API's eight confirmed actions are producer-reported counts. Manual review accepts the one attack and two prayer toggles; the five movement labels are unverified and must not enter an accepted training fixture. The 12 ambiguous and seven unconfirmed outcomes remain diagnostic; they were not relabeled or repaired. `acceptanceVerified=false` in the quality report is intentional: that API never certifies the user's entire acceptance matrix from counts alone.

## Passing recording checks

- Finalized and inactive: 101 consecutive ticks 491-591, no missing ticks or duplicates. Every required combat section is present, and 55 NPC target ticks resolve. A private frame visibly shows Giant spider combat.
- The production row builder and schema validate all 101 rows. Direct read-only PostgreSQL comparison of 101 rows by 11 JSON sections has zero mismatches. Schema warnings concern existing non-validation provenance annotations, not failed fields.
- All 101 video endpoints are measured, with no reported clock/alignment quality codes. Maximum combined error is 46.509279 ms; p95 is 26.042806 ms against the unchanged 100 ms limit. All 100 available frame ranges map; terminal tick 591 has no next tick to define a range.
- The complete MP4 decodes. All 1,540 physical frames are indexed against media SHA256 `b97bc198a159fb93c64ce686e19688a4cca25df8c0302a6e8710c4c7c2c19bc5`, independently matched to actual file bytes.
- Saved client build `836b97123a2f2606643c3ee3e3373062da91dd2d`, JAR SHA256 `6dedde2e485df4c6d9a00cec8794701882f96b47bb0ff6e662a9cf6bb68f6b59`, version, observed game/cache namespace revision 241, server build/content identity and packet/combat/quality/video schema versions are present. Independent client JAR hashing matches saved identity. No identity quality codes are reported. Namespace revision is not a cache archive-content checksum; the development server digest has application-class-directory scope.
- After checking no active recording and no pending/queued/incomplete batches, only the owned 18090 verification server was restarted. Session, full ticks, quality and provenance JSON are exactly equal across restart. The saved server identity remains `92b496adebd7bfef8bacaf1f0e3ababa08b4cc13` while the replacement embeds `e3ba21e6902bc1969b4a80b311bcf997089490eb`, demonstrating that API reads do not substitute current build metadata. Original 8090 was untouched. Micro6 reconnected and remains in game with no queued/pending/incomplete/dropped batches.

## Remaining implemented-plan work

1. Acquire the actual intended walking destination from authoritative client scene/destination state, with explicit coordinate provenance. Never reinterpret canvas operands as scene coordinates. Preserve immutable raw callback history.
2. Require corroborated, valid movement targets in the producer and ChromaScape label gate. Missing, stale, out-of-scene, consumed or ambiguous targets must stay diagnostic rather than becoming training labels. Do not rewrite historical recordings to invent corrected destinations.
3. Add regression coverage for this exact real callback failure, valid nearby scene targets, unrelated movement, ambiguous/consumed callbacks and missing authoritative target evidence.
4. Rebuild/relaunch the scoped debugger client after the fix, obtain a short real corrected movement/attack/prayer capture, verify labels and preserve it as the accepted sanitized combat fixture. Eating is excluded. This recording is retained as diagnostic evidence; no accepted fixture was created from its invalid movement labels.

Given a WALK callback whose operands are canvas coordinates, when a position change happens to reduce distance to a fabricated target, then the label gate must reject it. Given a genuine authoritative destination and attributable end-of-tick movement, when validated through ingestion and coverage, then retain the confirmed semantic label. Given missing or conflicting target evidence, when recording/replaying, then keep explicit diagnostic evidence without inventing success.

The complete existing implementation scope, exclusions, schema/API/ERD decisions, earlier tests, risks and rollback remain in `../20261014-final-capture-gaps/README.md` and the authoritative Felix plans. This follow-up reviews the current recording and records the new code finding; it does not implement that fix.

## Changed files and tests

Only this review, sanitized `aggregate.json` and the link from the previous evidence README are changed. No application source edits or build-suite reruns were performed. Checks actually run: production capture-quality contract and row/coverage/schema logic, read-only 101-by-11 database comparison, actual file hashing, complete MP4 decode, private visual inspection, authenticated health and full API equality across a scoped server restart. Earlier native suites certify their stated earlier source; current Micro6 was independently advanced afterward. GitHub Actions remain excluded by user instruction. Four Docker suites and production migration-chain certification are not newly claimed.

## Delivery state, risks and rollback

SC-1976 and its shared SC-751 task remain `spec_ready/in_progress`; the PRs remain draft. Do not transition to review/done or describe all eight actions as accepted. Retain the original capture, raw callbacks and explicit unsafe-label finding. Public aggregate contains counts, build/media digests and relative-distance diagnostics, without account names, absolute positions, screenshots or raw payloads. Raw evidence and private helper artifacts stay outside Git.

No application fix needs rollback from this review. Revert only review documentation if necessary; retain V70, immutable recordings, original 8090, saved profiles and unrelated reservation files. Keep runtime/login/gameplay operations scoped and user authentication manual.
