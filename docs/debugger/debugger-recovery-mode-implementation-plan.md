# Debugger Recovery Mode Implementation Plan

## Status
Implemented — see debugger-recovery-mode-implementation-changes.md

## Goal

Implement a structured recovery loop on the existing `/debugger` screen for failed Micro6 builder-goal runs.

This work must not introduce a separate recovery page. The operator must stay on the same replay/debugger surface and switch that surface into a recovery editing mode.

The recovery loop must support:

1. Load a failed run into recovery mode.
2. Use the existing replay video and scrubber as the editing surface.
3. Mark important time ranges and notes.
4. Filter and promote evidence from those ranges.
5. Send a structured recovery payload to Codex through the backend provider contract.
6. Review the returned patch plan.
7. Approve patch, restart, republish, and rebind on the same debugger surface.

## Current Gap

Today the system is split across two incomplete surfaces:

1. The replay/editor surface already exists on `/debugger`.
2. The builder-goal recovery flow exists as a separate modal with prompt, context, clip start/end, and evidence selection.

That means the operator cannot run one coherent `replay -> annotate -> select evidence -> analyze -> apply -> relaunch -> rebind` loop on the same screen.

Codex/provider access is also incomplete today. It only receives backend-packaged evidence from the existing analysis endpoints. It does not receive a structured recovery workspace payload derived from inline debugger editing state.

## Required Product Behavior

### Same-Screen Recovery Mode

`Open Recovery Workspace` must switch the current `/debugger` page into `Recovery Mode`.

Recovery mode must reuse the existing debugger replay surface from:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/js/debugger.js`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/css/debugger.css`

The recovery mode must add editing tools to that surface instead of opening a separate page or relying on the existing modal.

### Recovery Editing Tools

The operator must be able to:

1. Enter recovery mode for a failed builder-goal run.
2. See the replay video and scrubber for that run.
3. Mark one or more time ranges.
4. Add notes and labels to each range.
5. Mark a range as important.
6. Pin important ticks or frames.
7. Filter logs and evidence using the selected ranges.
8. Promote exact evidence items into the Codex request.
9. Generate a patch plan.
10. Approve patch and relaunch without leaving `/debugger`.

### Codex Data Contract

Codex must not depend on direct UI access. The backend must package a structured recovery payload.

Codex should receive:

- failed run metadata
- task metadata
- recording and debug-session metadata
- selected clip bounds
- annotated replay ranges
- operator notes
- promoted evidence ids
- filtered log evidence
- filtered tick evidence
- filtered decision evidence
- relevant session context

Codex should not require raw video upload for this loop.

## Implementation Checklist

### Phase 1: Replace modal-first recovery with same-screen recovery mode

- [x] Add a recovery-mode container and toolbar to `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html`.
- [x] Keep the existing replay video and scrubber as the canonical editing surface in `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html`.
- [x] Add inline controls for `mark start`, `mark end`, `save range`, `clear range`, `important`, and `note`.
- [x] Add an inline evidence panel for filtered and selected evidence in `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html`.
- [x] Deprecate the modal-only workflow in `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html` and `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/js/account-task-planner.js`.

### Phase 2: Add structured frontend recovery state

- [x] Add recovery-mode state, annotation state, and selected-evidence state in `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/js/account-task-planner.js`.
- [x] Reuse current replay state from `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/js/debugger.js`.
- [x] Wire replay current-time and scrubber position into recovery range editing.
- [x] Add functions to enter recovery mode, save annotations, refresh filtered evidence, generate patch plan, and approve recovery.
- [x] Keep the operator on the same run/replay surface after relaunch.

### Phase 3: Add structured recovery workspace API

- [x] Extend `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/buildergoal/controller/BuilderGoalRunnerController.java` with recovery-mode endpoints.
- [x] Extend `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerService.java` with recovery workspace read/write logic.
- [x] Persist recovery annotations in iteration task metadata.
- [x] Expand the current analysis payload to include annotated ranges and promoted evidence.
- [x] Add a recovery-state read endpoint for UI hydration and polling.

### Phase 4: Add deterministic debug-session rebind

- [x] Extend `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/tickdebugger/dto/SessionStartRequest.java` with `preferredSessionId`.
- [x] Update `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandler.java` to pass `preferredSessionId`.
- [x] Reuse the existing preferred-session support in `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/tickdebugger/service/DebugSessionService.java`.
- [x] Reserve and persist preferred debug session metadata before republish in `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerService.java`.
- [x] Keep `WAITING_FOR_DEBUG_BIND` as a nonterminal same-screen recovery state until bind succeeds or times out.

### Phase 5: Add tests

- [x] Add controller tests for new recovery endpoints under `/mnt/f/dev/IdeaProjects/ChromaScape/src/test/java/com/chromascape/web/debug/buildergoal/controller/`.
- [x] Extend `/mnt/f/dev/IdeaProjects/ChromaScape/src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerServiceTest.java` for recovery workspace persistence and relaunch metadata.
- [x] Add websocket/request propagation tests for `preferredSessionId` under `/mnt/f/dev/IdeaProjects/ChromaScape/src/test/java/com/chromascape/web/debug/tickdebugger/`.
- [x] Add UI verification coverage if existing frontend test harness supports it; otherwise document manual validation steps in this file.

## File-Level Ownership

### UI Template

Primary template changes:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html`

This file must gain:

- a recovery mode shell on the same debugger page
- inline recovery toolbar controls
- range editor controls
- annotation list
- filtered evidence list
- inline patch-plan review area
- inline `Approve Patch + Relaunch` control

The existing modal at `#builderGoalAnalysisModal` should be treated as legacy once the inline recovery mode is complete.

### Frontend State and Actions

Primary frontend behavior changes:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/js/account-task-planner.js`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/js/debugger.js`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/resources/static/css/debugger.css`

`account-task-planner.js` should own:

- builder-goal recovery mode state
- recovery API calls
- annotation persistence calls
- evidence selection state
- patch-plan generation
- relaunch approval flow

`debugger.js` should expose or support:

- replay current time
- selected session metadata
- scrubber position
- timeline marker rendering usable by recovery mode

`debugger.css` should style:

- recovery toolbar
- active recovery mode state
- range chips
- annotation cards
- evidence list
- patch-plan review surface

### Backend Controller

Primary backend routing changes:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/buildergoal/controller/BuilderGoalRunnerController.java`

This controller should expose structured recovery endpoints for:

- enter recovery mode
- save recovery annotations
- resolve filtered evidence
- generate patch plan
- approve recovery
- get recovery state

### Backend Service

Primary backend orchestration changes:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerService.java`

This service should own:

- recovery workspace draft construction
- recovery annotation persistence
- range-to-evidence filtering
- analysis payload assembly for Codex/provider
- patch-plan persistence
- relaunch preparation
- preferred debug-session reservation metadata
- child-run linkage after relaunch

### Debug Session Rebind

Primary rebind contract changes:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/tickdebugger/dto/SessionStartRequest.java`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandler.java`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/tickdebugger/service/DebugSessionService.java`

These files must support:

- carrying `preferredSessionId` from client to server
- reusing a reserved debug session on client restart
- preserving run-to-session continuity across patch relaunch

### Tests

Likely test files to update or add:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerServiceTest.java`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/test/java/com/chromascape/web/accounttask/controller/AccountTaskControllerPatchLoopExecutionTest.java`
- `/mnt/f/dev/IdeaProjects/ChromaScape/src/test/java/com/chromascape/web/debug/tickdebugger/`

If controller tests for `BuilderGoalRunnerController` do not exist yet, add them under:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/test/java/com/chromascape/web/debug/buildergoal/controller/`

## Recovery Mode UI Contract

### Entry

The operator clicks `Open Recovery Workspace` from a failed builder-goal run entry.

Expected result:

1. The same `/debugger` page enters `Recovery Mode`.
2. Replay mode is activated if needed.
3. The failed run becomes the active recovery context.
4. Existing replay video, scrubber, and evidence panels remain visible.
5. Recovery editing controls become visible.

### Editing

The operator uses the replay video and scrubber to:

1. move to an interesting point
2. mark start
3. mark end
4. save the range
5. add notes
6. mark the range important
7. pin a tick or frame if needed

### Evidence Promotion

The selected ranges must filter the evidence shown to the operator.

The operator then promotes exact evidence items to include in the Codex request.

### Patch Plan

`Generate Patch Plan` must submit a structured payload that includes:

- iteration id
- prompt
- context
- selected evidence ids
- selected time range
- annotated ranges
- pins
- important flags
- run metadata snapshot

### Relaunch

`Approve Patch + Relaunch` must:

1. apply the patch to Micro6 task source
2. stop or replace the old client process if required
3. start the client again
4. wait for runnable heartbeat
5. republish the task
6. wait for debug bind
7. keep the operator on the same debugger surface

## Proposed Recovery API Contract

### 1. Enter Recovery Mode

Route:

- `POST /api/debug/builder-goals/runs/{iterationId}/recovery-workspace`

Response should include:

- `run`
- `prompt`
- `context`
- `timeRangeStartSeconds`
- `timeRangeEndSeconds`
- `timeRangeMaxSeconds`
- `timeRangeStartDisplay`
- `timeRangeEndDisplay`
- `timeRangeMaxDisplay`
- `recordingSessionId`
- `recordingId`
- `debugSessionId`
- `annotations`
- `evidenceGroups`
- `selectedEvidenceIds`
- `selectedEvidenceCount`
- `totalEvidenceCount`
- `recoveryState`
- `analysisAvailable`
- `approvalAvailable`

### 2. Save Recovery Annotations

Route:

- `POST /api/debug/builder-goals/runs/{iterationId}/recovery-annotations`

Request body should include:

- `annotations`
- `activeRangeStartSeconds`
- `activeRangeEndSeconds`
- `selectedEvidenceIds`

Each annotation should support:

- `id`
- `label`
- `note`
- `important`
- `startSeconds`
- `endSeconds`
- `startTick`
- `endTick`
- `startFrame`
- `endFrame`

### 3. Resolve Recovery Evidence

Route:

- `POST /api/debug/builder-goals/runs/{iterationId}/recovery-evidence`

Request body should include:

- `annotations`
- `selectedEvidenceIds`

Response should include:

- `evidenceGroups`
- `selectedEvidenceIds`
- `selectedEvidenceCount`
- `totalEvidenceCount`
- `resolvedTimeWindow`

### 4. Generate Recovery Patch Plan

Route:

- `POST /api/debug/builder-goals/runs/{iterationId}/analyze`

Request body should be expanded to include:

- `prompt`
- `context`
- `selectedEvidenceIds`
- `timeRangeStartSeconds`
- `timeRangeEndSeconds`
- `annotations`

Response should include:

- `analysisId`
- `patchId`
- `diagnosisSummary`
- `rootCause`
- `confidence`
- `patchDraftSummary`
- `retestPlanSummary`
- `approvalAvailable`
- `recoveryPhase`

### 5. Approve Recovery

Route:

- `POST /api/debug/builder-goals/runs/{iterationId}/approve-recovery`

Response should include:

- `iterationId`
- `childIterationId`
- `preferredSessionId`
- `recoveryPhase`
- `status`
- `publishStatus`

### 6. Get Recovery State

Route:

- `GET /api/debug/builder-goals/runs/{iterationId}/recovery-state`

Response should include current:

- run summary
- recovery phase
- relaunch status
- bind status
- child iteration linkage

## Metadata Persistence

Recovery metadata should stay on the existing iteration record in:

- `/mnt/f/dev/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/debug/patchloop/entity/PatchLoopIteration.java`

Persist inside `taskMetadataJson` using a dedicated nested structure such as:

- `recovery`
- `recovery_workspace`
- `micro6_publish`
- `analysis_input`

Recommended additions under `recovery_workspace`:

- `annotations`
- `selected_evidence_ids`
- `active_start_seconds`
- `active_end_seconds`
- `preferred_session_id`
- `recording_session_id`
- `recording_id`
- `last_loaded_at`

## Manual Validation Checklist

- [ ] Launch a Micro6 builder-goal run from `/debugger`.
- [ ] Force or reproduce a failed run with evidence.
- [ ] Click `Open Recovery Workspace`.
- [ ] Confirm the current `/debugger` screen enters recovery mode instead of opening a separate page.
- [ ] Confirm the replay video and scrubber remain the active editing surface.
- [ ] Mark at least one time range and save it.
- [ ] Add a note and mark the range important.
- [ ] Confirm evidence filters down to the chosen range.
- [ ] Promote exact evidence items.
- [ ] Generate a patch plan.
- [ ] Confirm the request payload includes annotated ranges and selected evidence.
- [ ] Approve patch and relaunch.
- [ ] Confirm the restarted client rebinds to the reserved debug session.
- [ ] Confirm the UI remains on the same debugger surface and follows the child run.

## Non-Goals

- Do not move recovery into a separate standalone page.
- Do not make Codex inspect the page directly.
- Do not depend on raw video upload as the primary analysis input.
- Do not route this Micro6 recovery loop through the repo-local generic patch-loop apply path.
