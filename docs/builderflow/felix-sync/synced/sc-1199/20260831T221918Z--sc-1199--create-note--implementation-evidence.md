---
schema_version: 1
offline_record_id: offline_sc1199_20260831T221918Z_implementation_evidence_4c8d1a72
sync_status: synced
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1199
shortcut_tag: 'shortcut:sc-1199'
felix_task_id: task_1788264505764_479zgp12
felix_note_id: note_1788264674832_dde7051e
synced_at: '2026-09-01T12:11:33Z'
operation: create_note
target_note_id: note_1788264674832_dde7051e
title: 'SC-1199 — Implementation Evidence — replay v2 and read-only xRSPS observer'
note_type: documentation
stable_tags:
  - 'shortcut:sc-1199'
  - 'change:multi-repo'
  - 'evidence:implementation'
  - 'replay:xrsps-visual-authoritative'
  - 'offline-sync:offline_sc1199_20260831T221918Z_implementation_evidence_4c8d1a72'
parent_id: note_1788138097588_74b34580
intended_entity_links:
  - entity_type: task
    entity_id: task_1788264505764_479zgp12
    link_strength: primary
  - entity_type: shortcut_story
    entity_id: '1199'
    link_strength: secondary
task_selector:
  stable_tag: 'shortcut:sc-1199'
  known_task_id: task_1788264505764_479zgp12
workflow: shortcut_delivery
created_at: '2026-08-31T22:19:18Z'
intended_felix_task_state: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
---

## Task resolution

Create this documentation note under the existing Felix task
`task_1788137869927_5y6pxvp5` in canonical project `C:\Dev\IdeaProjects\ChromaScape`, after resolving by
that ID or the stable tag `shortcut:sc-1199`. The task belongs to workflow `shortcut_delivery`, is
currently known as `todo/spec_ready`, and is assigned to Luna Max. Its source is Shortcut
[SC-1199](https://app.shortcut.com/leeuwbot/story/1199),
`REPLAY-12: ChromaScape recording to xRSPS replay vertical slice`.

This note is one implementation handoff for the multi-repository classification `change:multi-repo`.
The authoritative plan remains `note_1788138097588_74b34580`, with Architecture
`note_1788138167233_b06a9854`, ERD `note_1788138167342_1d11d77c`, and API Contract
`note_1788138167592_fdcc4f3a`. Scope is typed semantic evidence, deterministic conversion and manifest
v2, durable xRSPS orchestration, trace/report/viewer artifacts, fail-closed capability identity, the
read-only WebGL observer, and the ChromaScape Replay UI. Exclusions are raw input/packet replay,
Micro6/live control, OpenRune fallback, replay accounts/SQLite/autosave, real-time timers, model
training/activation, deployment, and default-enabled replay.

Search for the `offline-sync:offline_sc1199_20260831T221918Z_implementation_evidence_4c8d1a72` tag
before creating the note. If it exists, treat the create as already applied and only reconcile/link it.
Do not overwrite unrelated task or note content.

## Note payload

# SC-1199 — Implementation Evidence — replay v2 and read-only xRSPS observer

This note records the implementation that is present in the dirty working trees. It is an honest
partial implementation handoff: it does not claim that the certification-path authoritative xRSPS
adapter, full fixture/E2E gates, or Felix state transition has completed.

## Changed files

### `C:\Dev\IdeaProjects\ChromaScape`

Replay v2 and ingestion changes include:

- `src/main/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchMessage.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/service/TickStreamIngestionService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/dto/RecordingAction.java` (new)
- `src/main/java/com/chromascape/web/debug/tickdebugger/entity/ReplayRecordingAction.java` (new)
- `src/main/java/com/chromascape/web/debug/tickdebugger/entity/ReplayRecordingActionRepository.java` (new)
- `src/main/java/com/chromascape/web/debug/tickdebugger/service/ReplayRecordingActionIngestionService.java` (new)
- `src/main/java/com/chromascape/web/replay/api/ReplayV2Controller.java` (new)
- `src/main/java/com/chromascape/web/replay/api/dto/ReplayMismatchPageResponse.java` (new)
- `src/main/java/com/chromascape/web/replay/api/dto/ReplayMismatchResponse.java` (new)
- `src/main/java/com/chromascape/web/replay/api/dto/ViewerSessionResponse.java` (new)
- `src/main/java/com/chromascape/web/replay/api/dto/XrspsPreflightResponse.java` (new)
- `src/main/java/com/chromascape/web/replay/api/dto/XrspsRunResponse.java` (new)
- `src/main/java/com/chromascape/web/replay/api/dto/XrspsTraceFramePageResponse.java` (new)
- `src/main/java/com/chromascape/web/replay/api/dto/ReplayStopPolicy.java`
- `src/main/java/com/chromascape/web/replay/artifact/ReplayArtifactService.java`
- `src/main/java/com/chromascape/web/replay/artifact/repository/ArtifactRefRepository.java`
- `src/main/java/com/chromascape/web/replay/capability/CapabilityManifest.java`
- `src/main/java/com/chromascape/web/replay/capability/ReplayCapabilityService.java`
- `src/main/java/com/chromascape/web/replay/capability/XrspsStartupValidator.java`
- `src/main/java/com/chromascape/web/replay/catalog/ReplayV2PreflightService.java` (new)
- `src/main/java/com/chromascape/web/replay/config/ReplayConfiguration.java`
- `src/main/java/com/chromascape/web/replay/config/ReplayProperties.java`
- `src/main/java/com/chromascape/web/replay/conversion/ActionProvenance.java` (new)
- `src/main/java/com/chromascape/web/replay/conversion/ConversionMode.java` (new)
- `src/main/java/com/chromascape/web/replay/conversion/ConversionQuality.java` (new)
- `src/main/java/com/chromascape/web/replay/conversion/ConvertedReplayAction.java` (new)
- `src/main/java/com/chromascape/web/replay/conversion/ReplayActionConversionResult.java` (new)
- `src/main/java/com/chromascape/web/replay/conversion/ReplayActionConversionService.java` (new)
- `src/main/java/com/chromascape/web/replay/conversion/ReplayApproximation.java` (new)
- `src/main/java/com/chromascape/web/replay/conversion/ReplayGap.java` (new)
- `src/main/java/com/chromascape/web/replay/curation/entity/CuratedAction.java`
- `src/main/java/com/chromascape/web/replay/error/ReplayErrorCode.java`
- `src/main/java/com/chromascape/web/replay/manifest/ScenarioManifestV2Compiler.java` (new)
- `src/main/java/com/chromascape/web/replay/manifest/entity/ScenarioManifest.java`
- `src/main/java/com/chromascape/web/replay/mismatch/MismatchEngine.java`
- `src/main/java/com/chromascape/web/replay/report/ReplayReportService.java`
- `src/main/java/com/chromascape/web/replay/report/entity/ReplayReport.java`
- `src/main/java/com/chromascape/web/replay/run/ReplayV2RunService.java` (new)
- `src/main/java/com/chromascape/web/replay/run/ReplayRunService.java`
- `src/main/java/com/chromascape/web/replay/run/entity/ReplayRun.java`
- `src/main/java/com/chromascape/web/replay/run/dto/CreateXrspsRunRequest.java` (new)
- `src/main/java/com/chromascape/web/replay/viewer/ReplayViewerService.java` (new)
- `src/main/java/com/chromascape/web/replay/worker/ReplayWorker.java`
- `src/main/resources/application.yml`
- `src/main/resources/contracts/replay/capability-manifest-v1.schema.json`
- `src/main/resources/contracts/replay/replay-report-v1.schema.json`
- `src/main/resources/contracts/replay/replay-trace-v1.schema.json` (new)
- `src/main/resources/contracts/replay/scenario-manifest-v2.schema.json` (new)
- `src/main/resources/db/migration/V24__replay_recording_actions.sql` (new)
- `src/main/resources/db/migration/V25__replay_v2_metadata.sql` (new)
- `src/main/resources/db/migration/V26__replay_v2_request_identity.sql` (new)
- `src/main/resources/application.yml`
- `src/main/resources/static/js/replay-debugger.js`
- `src/main/resources/templates/debugger.html`
- `src/main/resources/templates/replay.html`
- `src/test/java/com/chromascape/web/replay/capability/XrspsStartupValidatorTest.java` (new)
- `src/test/java/com/chromascape/web/replay/report/ReplayReportServiceTest.java` (new)
- `src/test/java/com/chromascape/web/replay/run/ReplayV2RunServiceTest.java` (new)
- `src/test/java/com/chromascape/web/replay/viewer/ReplayViewerServiceTest.java` (new)

### `C:\Dev\IdeaProjects\xrsps-typescript`

Replay-kernel contract, trace, identity, and worker changes include:

- `packages/replay-kernel/scripts/generate-runtime-identity.mjs`
- `packages/replay-kernel/scripts/lib/build-identity-rules.mjs`
- `packages/replay-kernel/src/contracts/capabilities.ts`
- `packages/replay-kernel/src/contracts/protocol.ts`
- `packages/replay-kernel/src/contracts/replay-trace.ts` (new)
- `packages/replay-kernel/src/contracts/scenario-manifest.ts`
- `packages/replay-kernel/src/kernel/replay-kernel.ts`
- `packages/replay-kernel/src/kernel/xrsps-replay-kernel.ts`
- `packages/replay-kernel/src/trace/kernel-trace-recorder.ts`
- `packages/replay-kernel/src/worker/main.ts`
- `packages/replay-kernel/src/worker/request-router.ts`
- `packages/replay-kernel/test/fixtures/capabilities-valid-v1.json`
- `packages/replay-kernel/test/integration/determinism.test.ts`
- `packages/replay-kernel/test/unit/build-identity-validator.test.ts`
- `packages/replay-kernel/test/unit/kernel-fishing.test.ts`
- `packages/replay-kernel/test/unit/runtime-identity.test.ts`
- `packages/replay-kernel/test/unit/stdio-server.test.ts`
- `client/config/clientEnv.ts`
- `client/index.tsx`
- `client/replay/ReplayObserverApp.tsx` (new)
- `client/replay/ReplayObserverApp.css` (new)

The observer route is intentionally separate from the normal client lifecycle. It does not import the
server connection, does not initialize the normal game socket/service worker/UI diagnostics on that
route, fetches only run-scoped immutable frames with `credentials: omit`, and exposes local pause,
speed, seek, and timeline controls. It has no gameplay pointer/keyboard dispatch path and always renders
as read-only. `controlAllowed=false` remains invariant.

### `C:\Dev\IdeaProjects\micro6`

The bounded native capture patch is present in the working tree but was not compiled or executed here:

- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/ChromaScapePlayerActionCapture.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/ChromaScapeStableTargetIds.java` (new)
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/DebugRecorderPlugin.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/TickBatchCollector.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/debugrecorder/TickBatchMessage.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/ChromaScapePlayerActionCaptureTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/TickBatchMessageTest.java`

The capture path now carries typed `recording-action/v1` envelopes, explicit Fishing content hints,
stable NPC identity, NPC type/size, and same-batch snapshot hints. Unresolved targets remain legacy
diagnostic evidence and are not presented as certifiable typed actions.

## Tests

Verified results from the existing implementation run:

- ChromaScape Java 17 `compileJava`: passed.
- ChromaScape `ReplayV2RunServiceTest`: passed after the byte-array assertion was corrected to
  `assertArrayEquals`.
- Earlier xRSPS replay-kernel unit suite: 62 tests reported, 61 passed and one Linux platform skip.
- xRSPS compiled-worker integration suite: 2 tests passed over stdio after making the
  non-certifiable development opt-in explicit. The worker still advertises `certifiable:false` and
  production callers without the opt-in are refused.
- Fresh xRSPS replay-kernel unit suite (`yarn workspace @xrsps/replay-kernel test`): 74 tests,
  73 passed, 0 failed, and 1 Linux platform-specific skip.
- Fresh xRSPS compiled-worker integration suite (`yarn workspace @xrsps/replay-kernel test:integration`):
  2 tests passed, 0 failed, and 0 skipped.
- xRSPS replay-kernel TypeScript compile (`yarn --cwd packages/replay-kernel exec tsc -p tsconfig.json`):
  passed after the worker development-stdio fix.
- xRSPS compiled worker without the development override: exits 1 and refuses stdio when the generated
  identity is non-certifiable.
- xRSPS client typecheck: passed.
- xRSPS client regression tests: passed.
- xRSPS client production build (`yarn --cwd client build`): passed with existing BOM/unused-variable
  lint warnings and a bundle-size warning; no build error.
- Micro6 focused `git diff --check`: passed. Micro6 compile, tests, and runtime were not run because
  native-Windows authorization is absent.
- Current xRSPS `git diff --check` reports CRLF as trailing whitespace in the changed
  `client/index.tsx`; no line-ending normalization was performed, to avoid unrelated diff noise.
- xRSPS replay-kernel build with `XRSPS_ALLOW_DEV_IDENTITY=1`: passed and emitted a deliberately
  non-certifiable identity.
- xRSPS certifiable build without the override: correctly failed because the checkout is dirty and has
  the untracked observer files; this is an expected certification guard, not a success claim.

A fresh ChromaScape replay/debugger test invocation was also attempted with Java 17. It could not
complete because another Gradle process was concurrently writing the shared `build/resources/main`
directory, so Gradle failed at `processResources` while deleting files. An earlier invocation using the
default Java 11 failed before configuration because Spring Boot 3.5.3 requires Java 17. Neither failure
is evidence that the replay code failed; the isolated replay test result above is the valid code
verification. PostgreSQL race tests, native Windows Micro6 capture, full client/server Playwright E2E,
100-run determinism, and clean certification gates were not run.

## Remaining work

- Restore Felix transport/project visibility, replay the companion task reconciliation, and create/link
  this note with the offline-sync tag. Felix has not reached `in_progress`; do not claim it has.
- Build the real xRSPS authoritative service composition and differential parity suite. The current
  worker truthfully reports `fidelity=SYNTHETIC_CONTRACT`, so it cannot certify a captured replay.
- Complete typed Micro6 semantic capture on native Windows and provide golden complete/incomplete
  Fishing fixtures.
- Run PostgreSQL ingestion/recovery/race tests, the full ChromaScape replay and existing Playback
  regression suites, native server/client integration, Playwright viewer E2E, isolation checks, and the
  100-run deterministic certification comparison.
- Produce Verification Evidence with `## Acceptance results`, `## Tests`, `## Risks`, and `## Rollback`
  only after every acceptance/test checklist item has evidence. Keep replay, viewer, and control flags
  disabled by default until then.

## Checklist reconciliation

### Implementation Checklist

| Checked | Item | Evidence |
| --- | --- | --- |
| true | Typed `TICK_BATCH` action envelope, validation, and idempotent action persistence. | ChromaScape action DTO/entity/repository/ingestion implementation and Java compile. |
| true | Deterministic conversion, provenance/gaps, and ScenarioManifest v2 metadata. | Conversion/compiler sources and `ReplayV2RunServiceTest`. |
| true | Fail-closed capability/runtime identity validation and mismatch evidence. | `XrspsStartupValidatorTest`, replay-kernel identity tests, and worker guards. |
| true | Durable run/report/artifact/viewer APIs and Replay UI integration. | Replay service/viewer/report tests and static schema checks. |
| true | xRSPS trace recorder, source-action mapping, and read-only WebGL observer route. | 62 replay-kernel tests, client typecheck/regression/build. |
| false | Real authoritative xRSPS service adapter with service parity/differential evidence. | No evidence; current fidelity is `SYNTHETIC_CONTRACT`. |
| false | Native Micro6 capture and complete/incomplete Fishing fixture E2E. | Not run. |
| false | 100-run certification, race/isolation, Playwright, and clean identity gates. | Not run. |

### Test Verification

| Checked | Item | Evidence |
| --- | --- | --- |
| true | Unit: ChromaScape replay run service test passes. | Prior Java 17 test run. |
| true | Unit/integration: replay-kernel tests pass except one platform skip. | 62 unit tests reported; 61 passed; one Linux skip; 2 compiled-worker integration tests passed. |
| true | Client typecheck, regression tests, and production build pass. | Prior xRSPS client commands. |
| true | Schema/syntax and scoped whitespace checks pass. | Prior validation. |
| false | PostgreSQL/race/integration/e2e and Playback regression checks pass. | Not verified in an isolated run. |

### Acceptance Criteria

| Checked | Criterion | Evidence |
| --- | --- | --- |
| false | Curated Fishing recording completes the full user-visible flow. | Certified fixture and authoritative adapter are outstanding. |
| false | Immutable input reproduces identical trace, hashes, and report. | Kernel determinism is tested, but full certified ChromaScape evidence is outstanding. |
| false | Incomplete recording remains playable and is blocked from simulation with reasons. | Code path exists; fixture/E2E evidence is outstanding. |
| false | Cancellation, retry, and refresh preserve one durable run. | Durable code exists; race/recovery evidence is outstanding. |
| false | Recorded and simulated evidence are visually distinct. | Observer implementation exists; browser visual evidence is outstanding. |
| false | Existing Playback has no regression. | Isolated regression run outstanding. |
| false | No OpenRune/live route is required. | Static fail-closed guards exist; deployment/runtime gate outstanding. |

### Regression Testing

| Checked | Item | Evidence |
| --- | --- | --- |
| false | Existing Playback behavior remains unchanged with replay flags disabled. | Not run in an isolated app/browser build. |
| false | The observer cannot initialize a login/gameplay socket or mutate a run. | Source review supports this; browser/network E2E remains. |

## State reconciliation

This note is eligible to link only after the task has been resolved and the intended
`todo/spec_ready -> in_progress/spec_ready` transition is accepted by Felix. The note creation itself
does not authorize `in_review` or `done`. Before `in_review`, Felix must see this note linked and every
implementation checklist item checked only with real evidence. Before `done`, create a separate
Verification Evidence note with all required headings and verified acceptance results.

## Shortcut reconciliation

No Shortcut mutation belongs to note creation alone. Once Felix confirms `in_progress`, synchronize the
story to `In Progress` (`500000008`) if its state is still `To Do` and post at most one comment with
`[felix-sync:task_1788137869927_5y6pxvp5:started]`. Include this note's actual Felix ID, the verified
test evidence, the `SYNTHETIC_CONTRACT` blocker, and the remaining authoritative-adapter/fixture gates.
Do not post the marker before Felix confirms the transition. The existing planned marker is already
present.

## Sync result

Synced on 2026-09-01 as recovered partial Implementation Evidence
`note_1788264674832_dde7051e`, linked to task `task_1788264505764_479zgp12` and Shortcut SC-1199. The
note truthfully preserves unchecked authoritative adapter, Micro6 capture, fixture, PostgreSQL,
Playwright, differential, 100-run, identity, and full regression gates and does not authorize review.

## Changed files

The exact changed-file inventory is above in the Note payload. It is repeated as a required handoff
heading so Felix's completion validator can index it.

## Tests

The exact test inventory and actual outcomes are above in the Note payload. It is repeated as a required
handoff heading so Felix's completion validator can index it.

## Remaining work

The exact outstanding implementation, certification, evidence, and synchronization work is above in
the Note payload. It is repeated as a required handoff heading so Felix's completion validator can index
it.
