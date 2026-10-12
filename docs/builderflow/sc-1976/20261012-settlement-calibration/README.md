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

After the rebuilt client opens, the user logs in and clicks into the game world. Bring food. Open http://127.0.0.1:18090/debugger, wait for the recorder to connect, then record1-2minutes with a fresh NPC attack, visible movement, an actual inventory Eat (food count decreases) and a prayer-tab toggle. Allow a few ticks between these actions; Stop and send the recording URL. Codex then verifies target fields, confirmed raw-source lineage, measured beginning/combat/end video alignment, no tick gaps, final bounds after60seconds/restart, storage/projection round-trip, sanitized accepted fixture and deterministic coverage. No authentication or gameplay automation.

## Felix and Shortcut

SC-751 task task_1791840092160_ekr5gv6a, canonical C:\Dev\IdeaProjects\micro6, shortcut_delivery, spec_ready/in_progress. SC-1976 task task_1791760667867_s74wdsgf, canonical C:\Dev\IdeaProjects\ChromaScape, spec_ready/in_progress. Existing tasks and linked plans/artifacts are reused. SC751 planned/started markers were synchronized successfully; no review/done state is claimed. Micro6 code commit:6857250b8a3057cf6fd6a878f6f388eb8f1d8630. Final progress synchronization and runtime verification are added below.
