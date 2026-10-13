# SC-1976 remaining capture code gaps: settlement and measured video

This implements the SC-751 shared capture foundation needed by SC-1976. SC-1976 and SC-751 remain In Progress until genuine acceptance evidence exists. Full SC-751 Fishing delivery is outside this combat dependency change. Existing draft PRs: Micro6 #162 and ChromaScape #59. Hosted GitHub Actions are skipped by explicit user instruction.

## Implemented plan

1. Preserve immutable structured CLIENT_EVENT callbacks, exact session/producer/sequence/tick identity and a SHA256 of sorted numeric operands. Keep acknowledged recording-action/v1 separate.
2. Settle attack, movement, inventory Eat and exact prayer-component events only against captured before/after state, within a four-tick horizon. Consumed, unsupported, competing, unmutated or terminal unresolved inputs remain UNCONFIRMED/AMBIGUOUS. NPC attacks require the same live instance and captured target; bank clicks cannot become eating labels.
3. Keep bounded duplicate detection and restore immutable evidence on synchronous transport rejection with an explicit delivery gap. Force per-tick observation through a process-only combat profile; preserve saved settings.
4. Measure recorder/server clock offset and uncertainty through a four-timestamp probe on the owned socket. Carry immutable epoch, tick capture time, quality codes and raw/settled lineage in checksum-covered captureQuality. Missing/reset/stale/uncertain clocks remain unavailable.
5. Persist optional capture_quality as additive bounded JSONB with V69; preserve legacy NULL. Validate binding, raw hash/lineage/horizon/mutation and compare current outcomes to actual batch state.
6. Timestamp images at capture time rather than backlog processing time; retain encoding lag and duplicate-image age. Join the encoder before finalization and physically decode MP4 frame timestamps into an atomic media-digest-bound index. Corrupt/missing/incomplete timing remains unavailable.
7. Map actual tick capture time through measured clock offset to physical frames. Require absolute image residual plus clock uncertainty <=100ms; clock uncertainty <=50ms and freshness <=30s. Publish measured per-tick timing/quality and max/p95 error through GET /api/debug/replay/{sessionId}/quality and existing sync APIs. Old formula-only data stays uncalibrated.
8. Consume only a single confirmed attributable CLIENT_EVENT settlement per row, preserving the original dispatched-action fence. Emit explicit coverage quality gaps. Commit serializer/schema fixtures only as constructed regressions; genuine sanitized accepted combat fixture remains required.

## Changed files

Micro6 recorder: CaptureClockCalibration.java, CombatSemanticCapture.java, ChromaScapeWebSocketClient.java, DebugRecorderPlugin.java, TickBatchMessage.java, CombatCaptureConfig.java and matching recorder/config/serializer tests.
ChromaScape: CaptureQualityContract.java, CaptureQualityReport.java, MeasuredVideoIndex.java, TickVideoSyncService.java, RecordingService.java, TickBatchMessage.java, TickSnapshot.java, TickStreamIngestionService.java, TickStreamWebSocketHandler.java, TickReplayController.java, CombatTickRowService.java, V69__sc751_capture_quality.sql, combat row/capture-quality schemas and matching contract/transport/media/PostgreSQL tests/constructed fixtures.
Original mixed checkouts, runtime reservations, game credentials, gameplay media and original port8090 remain outside these commits.

## Tests

Native JDK21 checks actually run:

- Micro6 full :client:test:1,700 tests, zero failures/errors/skips; this pass precedes the final inventory-context/slot and health-observability refinements. After those refinements,124 focused recorder/clock/config/serializer/transport tests passed, then61 transport-health tests and12 final settlement/clock tests passed. :client:verifyCaptureContract passed with canonical contracts/vectors. Export packaging is recorded below after completion.
- ChromaScape374 selected combat/debugger/playback/recording tests passed, including real PostgreSQL V68/V69 legacy preservation/limits/Hibernate save/reload and closure locking. Subsequent strict-schema/current-Gson/physical-frame-tail/projection/controller checks passed62 tests; the final schema boundary passed4 tests, and encoder timeout/native ownership/media/index checks passed6 tests. These counts overlap; they are not separate unique test totals.
- Scoped Spotless formatting and Checkstyle10.26.1 using repository rules passed. The default Checkstyle8.3 cannot parse Java21 and the newer Gradle dependency graph is not in verification metadata; the official standalone release was used instead, without changing or disabling dependency verification. SHA256: e41c24433723ba310a30e41da4f449c105ad47cab2ae9e6be5e06606a647dbca.
- Four Docker-only debugger suites, the production migration chain, hosted Actions and genuine new combat acceptance remain unrun/excluded. Raw gameplay and runtime secrets are absent from these fixtures/commits.

The final-frame range uses the decoded container duration, retaining measured image-age/error limits; it does not invent a configured-FPS physical frame. Encoder timeout preserves native recorder ownership, publishes no successful finalization/index and refuses a new recording until recovery/restart.


## Acceptance results

Constructed state/serializer/MP4 fixtures prove implementation behavior only. recording_885d8b93 retains146 consecutive ticks1434-1579 and Giant spider combat video, but lacks captured NPC scene data, confirmed semantic outcomes and measured calibration. Its old raw inputs remain diagnostics and old video is uncalibrated. It cannot become the complete accepted fixture retroactively.

## Risks

Only the listed structured combat families are supported. Repeated attacks on an already selected target without an interaction change remain unconfirmed. Multiple competing inputs remain ambiguous; unsupported spell/wait families stay explicit. NPC movement/missing scene data can prevent target confirmation. Clock/transport/frame defects block labels/calibration rather than inventing successful outcomes. Full SC667 identity/isolation and full SC751 Fishing acceptance remain open. Loopback verification schema is not production migration-chain certification.

## Rollback

Revert only these scoped commits/profile overrides and restart the owned verification client/server. Preserve additive quality columns, raw recordings and timing sidecars. Omitting debugger-only combat profile flags restores saved settings. Never reset mixed checkouts/reservations, delete gameplay, force push, merge PRs or shut WSL down.

## Remaining work and user recording steps

After the rebuilt client opens, the user logs in and clicks into the game world. Open http://127.0.0.1:18090/debugger, wait for the recorder to connect, then record 1–2 minutes with a fresh NPC attack, visible movement and a prayer-tab toggle. Allow a few ticks between these actions; Stop and send the recording URL. Live eating is excluded by the user's 2026-10-13 instruction; retain implemented Eat support without claiming a live eating pass. Codex verifies target fields, confirmed raw-source lineage, measured beginning/combat/end video alignment, no tick gaps, final bounds after 60 seconds/restart, storage/projection round-trip, sanitized accepted fixture and deterministic coverage. No authentication or gameplay automation.

## Felix and Shortcut

SC-751 task task_1791840092160_ekr5gv6a, canonical C:\Dev\IdeaProjects\micro6, shortcut_delivery, spec_ready/in_progress. SC-1976 task task_1791760667867_s74wdsgf, canonical C:\Dev\IdeaProjects\ChromaScape, spec_ready/in_progress. Existing tasks and linked plans/artifacts are reused. SC751 planned/started markers were synchronized successfully; no review/done state is claimed. Micro6 code commit:6857250b8a3057cf6fd6a878f6f388eb8f1d8630. Final progress synchronization and runtime verification are added below.

## Actual idle capture and clock race follow-up

The user entered the game world. Actual smoke recording `recording_96450ab1`, session `debug_f4ed64a24ff543d48e8cb0da1a4cd83b`, finalized 30 consecutive ticks409-438 with no missing ticks. All30 persisted/served rows contain captureQuality, nearby NPCs and the required inputs/tiles/hazards/phase; the inventory is empty. Video fully decodes490 physical frames and an inspected frame confirms the game world. Final bounds/count/timestamps remain identical after more than60seconds. Safe aggregate results are in `idle-smoke-results.json`; gameplay pixels and raw payloads remain outside Git.

28ticks have measured physical-frame alignment, maximum61.2723ms and p9526.1362ms. Beginning/middle/end examples at ticks410/423/438 map to physical frames33/244/486 with image age0. Two ticks409/429 were unavailable because a socket clock reply arrived after the GameTick capture timestamp but before serialization, replacing the earlier valid sample. This is a clock-snapshot race; no tick was lost. Micro6 now keeps a bounded immutable history and chooses the newest measurement at or before capture time. Future-only measurements, stale or uncertain clocks, clock resets and disconnected sockets remain unavailable. Regression coverage includes the actual race and these negative cases.

This idle capture proves runtime quality transport/storage, NPC collection, measured media lookup and finalization stability. It contains no attack, movement, eating or prayer events and is not the accepted combat fixture. Genuine combat, exact target/settlement lineage, storage/projection/coverage comparison, restart stability and a provenance-backed accepted sanitized fixture remain required before completion.

The paired ChromaScape code is `f9db7133e1484d0fb81ca0a61cbbf9eba2c8b1f2`, running only on loopback18090. ExplicitV68/V69 applied successfully in retained verification schema `sc1976_live_20261012`; Flyway/Hibernate update are disabled. This is not production migration-chain certification. Original8090 remains untouched. Runtime packaging and the final clock fix are recorded below after verification.

## Published clock fix and current runtime

Micro6 clock-race fix `8717d3ee34cc2de49df9e18952475fcd6f6eb769` is fast-forward pushed to PR162. After the fix,81 focused recorder/clock/config/transport tests pass with zero failures/errors/skips; repository Checkstyle10.26.1 passes for the changed files. Capture contract and native runtime export pass. Chroma checks above remain at their listed revisions; the final complete client run is documented below.

Only the idle owned client49420 was stopped after verifying no active recording or pending/queued evidence. The replacement client6248 is open, with matching build_id8717d3ee34cc2de49df9e18952475fcd6f6eb769, source_mode disabled and packaged JAR SHA256 `2e5e6cfa606a97c9ad3b3f233ffa95b950fbaafba774e4f243fd2f1ec7ef3ddd`. Recorder health confirms ready, connected, ACTIVE and zero queued/pending/incomplete/dropped batches. The client is at the login screen; manual login/world entry is requested so the repaired timing path can be rechecked. Authentication and gameplay remain user performed.

Official Shortcut MCP successfully posted progress comments [SC1976 activity2053](https://app.shortcut.com/leeuwbot/story/1976#activity-2053) and [SC751 activity2054](https://app.shortcut.com/leeuwbot/story/751#activity-2054), with idempotent progress tags. Both stories remain In Progress500000008, matching Felix spec_ready/in_progress. Planned/started markers are preserved without duplication; no review/done transition or marker was emitted. Both PR descriptions now include the actual idle smoke, clock-race fix,81 focused checks and outstanding genuine acceptance.

## Final complete client regression and setup recovery

At commit8717d3ee34 the full native `:client:test` run executed1,704 tests:1,700 passed, four failed solely because this worktree invocation omitted the shared contract-vector path, zero errors/skips. The failures were the four mandatory shared-vector cases in RemoteAccountBuilderManagerTest; the fail-closed prerequisite correctly remained enforced. XML evidence from this full run is archived locally outside Git.

After setting `SC967_VECTORS_PATH=C:\Dev\IdeaProjects\contracts\sc967\ed25519-vectors.json`, the entire affected RemoteAccountBuilderManagerTest class reran successfully:20 tests, zero failures/errors/skips, including all four failed cases. No source change, skipped test, waiver or dependency-verification bypass was used for this recovery. All observed failures are resolved, but this evidence is not presented as one clean1,704-test invocation. Counts overlap with the prior81 focused and full1,700 runs.

The repaired client remains connected at the login screen. Repeat actual idle calibration and genuine combat acceptance are still unrun, awaiting user login/world entry. Four Docker debugger suites, production migration-chain certification and hosted Actions remain excluded/unverified as described above.

Final test reconciliation was successfully posted through official Shortcut MCP in [SC1976 activity2055](https://app.shortcut.com/leeuwbot/story/1976#activity-2055) and [SC751 activity2056](https://app.shortcut.com/leeuwbot/story/751#activity-2056), with distinct idempotent validation tags. SC1976's delivery description was refreshed successfully. Existing Felix Implementation/Verification notes and task descriptions contain the exact final full-run/setup-recovery results; both tasks remain spec_ready/in_progress. Neither story is completed or submitted for review.

## Latest actual combat verification

User recording [recording_3ba1931c](recording-3ba1931c-review.md) verifies 92 consecutive ticks, zero missing required fields/schema errors, direct PostgreSQL equality, 35 resolved target ticks, 11 confirmed attack/move/prayer events, measured clocks on all 92 ticks, full 1,008-frame video decode and stable finalized snapshots. It remains partial: no food/Eat event, seven unavailable video-mapping ticks and 15 nullable per-tick frame ranges. Detailed actual checks, safe aggregates, remaining acceptance, risks and rollback are linked above. No source/runtime change, restart or completed story/accepted fixture is claimed.

Existing Felix evidence notes and task descriptions were updated and read back successfully. Official Shortcut MCP posted [SC-1976 activity 2057](https://app.shortcut.com/leeuwbot/story/1976#activity-2057) and [SC-751 activity 2058](https://app.shortcut.com/leeuwbot/story/751#activity-2058) with the unique recording-review marker. Both Felix tasks remain `spec_ready/in_progress`; both stories remain In Progress, with no review/done transition.

## Latest review after client restart

[recording_eeed6066](recording-eeed6066-review.md) has 102 consecutive ticks, all required fields, zero PostgreSQL/schema mismatches, 36 resolved NPC-interaction ticks and 11 confirmed attack/move/prayer settlements. Video fully decodes 1,159 physical frames. Current mapping still rejects 11 ticks, although a read-only capture-time audit finds genuine physical frames within 100 ms for all 102 ticks. The remaining mapping change is therefore concrete; the audit does not change the service or acceptance status. Per-recording build/version provenance and accepted fixture/restart proof also remain open. Eating is excluded. Both stories remain In Progress and both PRs draft.

Existing Felix Implementation/Verification notes and task descriptions were saved/read back successfully. Official Shortcut review comments [2061 (SC-1976)](https://app.shortcut.com/leeuwbot/story/1976#activity-2061) and [2062 (SC-751)](https://app.shortcut.com/leeuwbot/story/751#activity-2062) succeeded; SC-1976's delivery description now reflects actual combat and the eating exclusion. Both Felix tasks remain `spec_ready/in_progress`, both Shortcut states remain In Progress, and no review/done marker was emitted.
