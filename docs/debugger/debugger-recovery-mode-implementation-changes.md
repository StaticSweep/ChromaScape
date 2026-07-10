# Debugger Recovery Mode — Implementation Changes

## Status
Implemented (draft for manual validation)

## Summary

This change implements the structured recovery loop described in
`debugger-recovery-mode-implementation-plan.md` on the existing `/debugger` screen for failed
Micro6 builder-goal runs. The operator now stays on the same replay/debugger surface and switches
that surface into a recovery editing mode instead of opening a separate modal.

The recovery loop supports: load a failed run -> use the existing replay video and scrubber as the
editing surface -> mark important time ranges and notes -> filter and promote evidence -> send a
structured recovery payload to Codex through the backend provider contract -> review the returned
patch plan -> approve patch, restart, republish, and rebind on the same debugger surface.

## Files Changed

### Phase 4 — Deterministic debug-session rebind

- `src/main/java/com/chromascape/web/debug/tickdebugger/dto/SessionStartRequest.java`
  - Added `preferredSessionId` field, getter/setter, and a 5-argument constructor.
  - Serialized as `preferredSessionId` so the Micro6 client can carry a reserved session id on restart.
- `src/main/java/com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandler.java`
  - `handleSessionStart` now passes `request.getPreferredSessionId()` into `DebugSessionService.startSession(...)`.
  - `DebugSessionService.startSession(..., preferredSessionId)` and `resolveOrCreateSession(...)` already
    supported preferred-session reuse; this wires the WebSocket path into that existing support.

### Phase 3 — Structured recovery workspace API

- `src/main/java/com/chromascape/web/debug/buildergoal/controller/BuilderGoalRunnerController.java`
  - Added 4 new endpoints:
    - `POST /api/debug/builder-goals/runs/{iterationId}/recovery-workspace`
    - `POST /api/debug/builder-goals/runs/{iterationId}/recovery-annotations`
    - `POST /api/debug/builder-goals/runs/{iterationId}/recovery-evidence`
    - `GET  /api/debug/builder-goals/runs/{iterationId}/recovery-state`
  - The existing `/analyze` and `/approve-recovery` endpoints are unchanged in routing but now carry
    the expanded recovery payload (annotations) on the service side.
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerService.java`
  - Added `RECOVERY_WAITING_FOR_DEBUG_BIND` constant.
  - Added public methods: `openRecoveryWorkspace`, `saveRecoveryAnnotations`, `resolveRecoveryEvidence`,
    `getRecoveryState`.
  - Extended `analyzeFailureInternal` to parse and persist inline recovery annotations onto the
    `recovery_workspace` metadata and to append an annotation summary to the Codex provider context.
  - Extended `executeApprovedRecovery` to reserve a deterministic `preferredSessionId` before republish,
    stamp the child iteration's `recovery_workspace`, set the parent phase to `WAITING_FOR_DEBUG_BIND`,
    and return `iterationId`/`childIterationId`/`preferredSessionId`/`recoveryPhase`/`status`/`publishStatus`.
  - Extended `syncManualRecoveryStates` to transition `WAITING_FOR_DEBUG_BIND` -> `RELAUNCHED` once the
    child run binds (`shouldMoveToRelaunched` / `moveToRelaunched`).
  - Added private helpers: `readRecoveryWorkspace`, `readStoredSelectedEvidenceIds`, `parseAnnotations`,
    `buildAnnotationsContext`, `intValueOrZero`, `resolveRecoveryTimeRange`, `buildResolvedTimeWindow`,
    `findChildIterationId`, `deriveRelaunchStatus`, `deriveBindStatus`, `reservePreferredDebugSession`,
    `isRecoveryPhaseActive`.
  - Recovery metadata persists on `PatchLoopIteration.taskMetadataJson` under `recovery_workspace`.

### Phase 1 & 2 — Same-screen recovery mode UI

- `src/main/resources/templates/debugger.html`
  - Added `#recoveryModeShell` (hidden by default) on the same `/debugger` page: recovery toolbar,
    active-range editor, annotated ranges list, filtered evidence panel, patch-plan review area, and a
    relaunch & rebind panel.
  - The existing replay video and scrubber remain the canonical editing surface.
  - The existing `#builderGoalAnalysisModal` is retained as legacy but is no longer the primary entry point.
  - Bumped asset cache versions: `debugger.css?v=9`, `debugger.js?v=13`, `account-task-planner.js?v=22`.
- `src/main/resources/static/js/debugger.js`
  - Appended `window.ChromaScapeReplay` accessor: `getReplayState()`, `getCurrentTimeSeconds()`,
    `getCurrentTick()`, `getCurrentFrame()`, `getReplayDebugSessionId()`, `getReplayVideo()`.
- `src/main/resources/static/js/account-task-planner.js`
  - Added recovery-mode state fields, element bindings, and event listeners.
  - Switched the "Open Recovery Workspace" action from the modal `openBuilderGoalAnalysisEditor` to
    `enterRecoveryMode` (same-screen recovery mode).
  - Added ~20 recovery methods (enter/exit, mark/clear/important/pin, save/render/remove/persist
    annotations, refresh/render/select evidence, generate/render patch plan, approve, poll state).
- `src/main/resources/static/css/debugger.css`
  - Added styles for `.recovery-mode-shell`, toolbar, body, annotation cards, evidence, patch plan,
    relaunch panel, and a recovery-active highlight on the replay surface.

### Phase 5 — Tests

- `src/test/java/com/chromascape/web/debug/buildergoal/controller/BuilderGoalRunnerControllerTest.java` (new)
- `src/test/java/com/chromascape/web/debug/tickdebugger/dto/SessionStartRequestTest.java` (new)
- `src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerServiceTest.java` (extended)

## API Contract (implemented)

| # | Method | Route | Purpose |
|---|--------|-------|---------|
| 1 | POST | `/api/debug/builder-goals/runs/{iterationId}/recovery-workspace` | Enter recovery mode, hydrate UI |
| 2 | POST | `/api/debug/builder-goals/runs/{iterationId}/recovery-annotations` | Persist annotations + active range + promoted evidence |
| 3 | POST | `/api/debug/builder-goals/runs/{iterationId}/recovery-evidence` | Resolve filtered evidence for current ranges |
| 4 | POST | `/api/debug/builder-goals/runs/{iterationId}/analyze` | Generate patch plan (now accepts `annotations`) |
| 5 | POST | `/api/debug/builder-goals/runs/{iterationId}/approve-recovery` | Approve + relaunch + reserve preferred session |
| 6 | GET  | `/api/debug/builder-goals/runs/{iterationId}/recovery-state` | Poll recovery phase / bind status / child linkage |

## Metadata Persistence

Recovery metadata is stored on `PatchLoopIteration.taskMetadataJson` under:

- `recovery` — `{ phase, message, updated_at }` (extended with `WAITING_FOR_DEBUG_BIND`)
- `recovery_workspace` — `{ annotations, selected_evidence_ids, active_start_seconds, active_end_seconds,
  preferred_session_id, recording_session_id, recording_id, debug_session_id, last_loaded_at,
  updated_at, reserved_at, parent_iteration_id }`

No database schema migration is required; `taskMetadataJson` is a JSON/JSONB column.

## Rebind Flow

1. Operator approves patch -> `executeApprovedRecovery` reserves `preferredSessionId` and stamps the
   child iteration's `recovery_workspace.preferred_session_id`.
2. Parent recovery phase becomes `WAITING_FOR_DEBUG_BIND` (nonterminal).
3. The restarted Micro6 client sends `SESSION_START` with `preferredSessionId`; the existing
   `DebugSessionService.resolveOrCreateSession` reuses the active reserved session.
4. `syncManualRecoveryStates` polls; once the child run reports a bound `runSessionId`, the parent
   transitions to `RELAUNCHED`.

## Build / Verification Notes

- The project uses Gradle (`build.gradle.kts`) with a Java 17 toolchain. The enhanced `switch`
  expression added in `deriveRelaunchStatus` is valid for Java 17.
- Environment limitation: in this WSL workspace, `./gradlew compileJava` fails during build
  initialization with `java.io.IOException: Input/output error` from Gradle's `FileHasher` reading
  files on the `/mnt/f` Windows mount. This is an environment/filesystem limitation, not a code
  error. Java changes were verified by careful review against existing patterns. Run the build on a
  native filesystem (or WSL `/home`) to compile and execute the tests:
  `./gradlew test --tests "com.chromascape.web.debug.buildergoal.*" --tests "com.chromascape.web.debug.tickdebugger.dto.*"`.
- Frontend changes are static assets; verify by loading `/debugger` and following the Manual
  Validation Checklist in `debugger-recovery-mode-implementation-plan.md`.

## Non-Goals (preserved)

- Recovery is not a separate standalone page.
- Codex does not inspect the page directly; it receives a backend-packaged structured payload.
- Raw video upload is not required for this loop.
- The Micro6 recovery loop is not routed through the repo-local generic patch-loop apply path.

## Follow-up Fixes (review pass)

Four gaps identified in a static review pass were fixed:

### Fix 1 — Approve-button inverted semantics
`setRecoveryApproveState(disabled, label)` treats its first argument as `disabled`, but callers
were passing `approvalAvailable`/`patchId` directly, which disabled the button when approval was
available and enabled it while loading / after failures. Every caller was corrected:
- Loading: `setRecoveryApproveState(true)` (was `false`).
- Workspace open: `setRecoveryApproveState(!result.approvalAvailable)` (was un-negated).
- Patch generated: `setRecoveryApproveState(!result.patchId)` (was un-negated).
- Relaunching: `setRecoveryApproveState(true, 'Relaunching...')` (was `false`).
- Poll: `setRecoveryApproveState(!state.approvalAvailable)` (was un-negated).

### Fix 2 — Same-screen replay driving + child-run following
- `enterRecoveryMode` now calls the existing replay surface via `window.ChromaScapeReplay`:
  `switchMode('replay')` + `loadRecording(recordingId)` (or `loadDebugSession`), loading the failed
  run's recording/scrubber into the editing surface.
- `pollRecoveryState` now follows the child run: on `BOUND`/`RELAUNCHED`, it calls
  `loadRecordingQuietly(state.childRun.runRecordingId)` once (guarded by `recoveryChildLoaded`)
  and updates the run label to "Following child run: ...".
- `debugger.js` `window.ChromaScapeReplay` gained `switchMode`, `loadRecording`, `loadDebugSession`,
  and a poll-safe `loadRecordingQuietly` (probes `/api/recordings/{id}` first; no alert spam).
- `BuilderGoalRunnerService.getRecoveryState` now includes a `childRun` summary (with
  `runRecordingId`/`runSessionId`) when a child iteration exists.

### Fix 3 — Preferred-session producer contract
The backend reserved `preferred_session_id` but only nested it under `recovery_workspace`, which
is buried in the published `task_metadata` blob. `executeApprovedRecovery` now also sets a
top-level `preferred_session_id` key on the child's task metadata so `publishMicro6TaskInternal`
(forwards the whole blob) surfaces it at a discoverable location for the out-of-repo Micro6 client.

Producer contract: the Micro6 client must read `task_metadata.preferred_session_id` and echo it
as `preferredSessionId` in its `SESSION_START` WebSocket message. The in-repo consumer
(`TickStreamWebSocketHandler` -> `DebugSessionService.resolveOrCreateSession`) reuses the active
reserved session. (No new automated test — the producer->client hop is cross-repo; the consumer
is covered by `SessionStartRequestTest`.)

### Fix 4 — Faithful workspace restore on reopen
- `openRecoveryWorkspace` now returns `activeRangeStartSeconds`/`activeRangeEndSeconds` from the
  persisted `recovery_workspace`.
- `enterRecoveryMode` restores those into the inline range editor via
  `restoreRecoveryActiveRange`, and applies the persisted `selectedEvidenceIds` as the single
  source of truth via `applyRecoverySelectedEvidenceIds` (so saved checkboxes reopen correctly
even if `analysis_input` drifted from `recovery_workspace`).

### Tests added
- `BuilderGoalRunnerServiceTest`: `openRecoveryWorkspace_restoresPersistedActiveRangeAndSelectedEvidence`,
  `getRecoveryState_includesChildRunSummary`.
- `BuilderGoalRunnerControllerTest`: `getRecoveryState_returnsOkWithPhaseAndChild` now asserts
  `childRun.runRecordingId` passthrough.
