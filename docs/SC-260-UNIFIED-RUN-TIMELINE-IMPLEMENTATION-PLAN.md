# SC-260 — Unified Run Timeline Implementation Plan

> Status: implemented as a gap-closing extension of the existing Builder Goal run cards,
> polling, controls, and logs. This document is the design record for the SC-260 changes.

## 1. Problem

SC-260 requires a unified, chronological per-session run timeline with complete/fail,
pause/resume, stop, and risk-trigger events, continuous (≤5s) status updating after a task
reaches RUNNING, and a durable event contract tying failure reasons to the owning
task/script. The existing surface already provides run cards, 2-second launch/run polling,
pause/resume/stop controls, historical run logs, and a one-shot "Script Events Timeline"
(log-replay page, START/STOP only). The gaps:

1. The run tracker stops polling the moment the executor confirms `RUNNING`
   (`account-task-planner.js`, `handleBuilderGoalRunView`).
2. No durable per-run event stream exists for Builder Goal runs
   (`PatchLoopEvent` is only written by the legacy patch-loop orchestrator).
3. `PatchLoopEventType` has no pause/resume/stop/risk vocabulary.
4. No API returns the chronological event stream for a run.
5. No timeline UI on the debugger Builder Goal panel; run cards carry no timeline action.

## 2. Non-goals

- No schema migration: `patch_loop_event` already persists everything needed
  (iteration_id, event_type, timestamp, message, jsonb data, severity).
- No change to Micro6 wire contracts; risk triggers are detected from executor status
  payloads (raw response), never invented client-side.
- The log-replay page and its START/STOP feed remain untouched.

## 3. Backend design

### 3.1 Event vocabulary (`PatchLoopEventType`)

Append-only additions (enum stored as STRING, safe for existing rows):

- `RUN_PAUSED` — executor confirmed the published task is paused.
- `RUN_RESUMED` — executor confirmed the task resumed an active state.
- `RUN_STOP_REQUESTED` — operator stop intent was persisted and delivered.
- `RUN_STOPPED` — executor confirmed cancellation (run finalized as CANCELLED).
- `RUN_RISK_TRIGGERED` — executor status payload carried risk information.
- `RUN_STATUS_DEGRADED` — consecutive executor poll failures crossed the configured
  threshold (durable counterpart of the existing degraded-polling UI messages).

Complete/fail reuse the existing `SCRIPT_COMPLETED` / `SCRIPT_FAILED`; task start reuses
`SCRIPT_STARTED`. One unified vocabulary for both patch-loop and Builder Goal runs.

### 3.2 Durable event contract (`BuilderGoalRunEventService`)

New service in `web/debug/buildergoal/service`:

- `recordRunEvent(iterationId, eventType, message, severity, data)` — stamps the owning
  task/script identity into `data` on every event: `iteration_id`, `task_key`
  (`PatchLoopIteration.scriptName`), `display_name`, `entry_id`, `run_session_id`.
- `recordFailureEvent(iteration, entryId, failureCode, failureReason)` — the SC-260
  failure contract: `SCRIPT_FAILED` events always carry `failure_code` +
  `failure_reason` alongside the task identity.
- `recordRiskEvent(iteration, entryId, riskData)` — `RUN_RISK_TRIGGERED` with the raw
  executor risk payload.
- `getTimelineEvents(iterationId)` — chronological DTO list
  (`id, iterationId, eventType, timestamp, message, severity, data`), ordered by
  timestamp then id.
- Idempotency/dedupe: a new event whose type + message equal the iteration's latest
  event is skipped, so the 3-second status sync cannot spam the timeline.
- Recording failures are caught and logged — event persistence can never break run
  control or status sync.

### 3.3 Mutation-service hooks (`BuilderGoalRunStateMutationService`)

Events are scheduled while the state row is locked, then written after the state transaction
commits. Each write uses an explicit `REQUIRES_NEW` transaction whose entire lifecycle—including
commit—is inside the recorder's failure boundary. This prevents ghost events for rolled-back state
changes and prevents an event failure from marking run control rollback-only. The recorder is an
optional collaborator; existing 3-arg constructors (used by focused unit tests) keep working with
a no-op recorder.

- `applyExecutorStatus`: on publish-status transitions — `SCRIPT_STARTED` (first active
  state after dispatch), `RUN_PAUSED`, `RUN_RESUMED` (PAUSED → active).
- `finalizeAsCompleted` → `SCRIPT_COMPLETED` (run success).
- `finalizeAsFailed` → `SCRIPT_FAILED` with `failure_code` + `failure_reason`.
- Lost-target branch → `SCRIPT_FAILED` with `TARGET_UNREACHABLE`.
- `finalizeAsCancelled` → `RUN_STOPPED` with the stop reason.
- Risk inspection: executor status raw payloads containing `risk` fields emit
  `RUN_RISK_TRIGGERED` (deduped).
- Poll degradation: consecutive-failure threshold crossing emits `RUN_STATUS_DEGRADED`.

### 3.4 Runner-service hooks (`BuilderGoalRunnerService`)

- `stopRun`: `RUN_STOP_REQUESTED` recorded once stop intent is persisted. A pre-publish cancellation
  then records `RUN_STOPPED`, preserving request-before-result ordering.
- Pause/resume transitions are covered by the mutation-service hooks (executor status
  authority flows through `applyExecutorStatus`).

### 3.5 API

- `GET /api/debug/builder-goals/runs/{iterationId}/events` → chronological event list;
  404 for unknown runs. Repository gains `findByIterationIdOrderByTimestampAscIdAsc` and
  `findTopByIterationIdOrderByIdDesc` (dedupe lookup).

## 4. Frontend design (`account-task-planner.js`, `debugger.html`)

- **Continuous ≤5s updating**: the `RUNNING && taskStarted` branch keeps polling
  (2-second cadence) instead of stopping; only terminal states and readiness failures
  stop the tracker. A static helper `shouldContinueBuilderGoalRunTracking(run)`
  centralizes the decision and is unit-tested.
- **Unified timeline panel**: `#builderGoalRunTimeline` in the Builder Goal section —
  chronological events with per-type icons/tones; failure events render the durable
  `failure_code`/`failure_reason`; refreshed on every tracker poll while active.
- **Run cards**: each card gains a "Timeline" action (`view-timeline`), opening the
  panel for historical runs too (events are durable).
- **Degraded polling** already renders reconnection copy; the durable
  `RUN_STATUS_DEGRADED` event now appears in the timeline as well.
- Planner bundle version bumped (40 → 41) in both files.

## 5. Tests

- `BuilderGoalRunEventServiceTest` — identity stamping, failure-reason contract, dedupe,
  chronological DTO shape.
- `BuilderGoalTimelineControllerTest` — endpoint 200 (ordered), 404 (unknown run).
- `BuilderGoalRunStateMutationServiceEventTest` — completed/failed/cancelled/paused
  transitions emit the contract events; risk payloads emit `RUN_RISK_TRIGGERED`.
- `src/test/js/sc260-run-timeline.test.js` — polling continuation decision, event
  mapping/labels, chronological sort, failure-reason rendering/escaping.

## 6. Acceptance criteria mapping

| SC-260 criterion | Where |
| --- | --- |
| Unified chronological per-session timeline | Timeline panel + `GET /runs/{id}/events` (timestamp+id order) |
| Complete/fail events | `SCRIPT_COMPLETED` / `SCRIPT_FAILED` in `finalizeAs*` |
| Pause/resume events | `RUN_PAUSED` / `RUN_RESUMED` in `applyExecutorStatus` |
| Stop events | `RUN_STOP_REQUESTED` (stopRun) + `RUN_STOPPED` (finalizeAsCancelled or pre-publish stop) |
| Risk-trigger events | `RUN_RISK_TRIGGERED` from executor status payloads |
| Continuous ≤5s updating after RUNNING | Tracker keeps polling at 2s; timeline refresh rides the poll |
| Durable failure-reason → task/script contract | `recordFailureEvent` stamps identity + reason in jsonb `data` |
| Tests covering acceptance criteria | Section 5 |
