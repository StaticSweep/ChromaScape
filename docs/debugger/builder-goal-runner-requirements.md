# Builder Goal Runner Requirements

## Purpose

ChromaScape's debugger is the operator console for running automation, observing evidence, and recovering from failures.

There are two distinct intents in this UI:

- `Generate Task Plan`: derive tasks from plain-English intent and evidence.
- `Builder Goal Runner`: execute an already-specified Micro6 task payload.

Builder payloads are executable goal specs, not planning prompts. They must not be routed through the task-plan generation flow.

## System Role

The intended end-to-end loop for builder tasks is:

1. An operator imports a concrete Micro6 task payload.
2. ChromaScape validates the task against the Micro6 catalog.
3. ChromaScape launches the task through Micro6.
4. The run produces evidence: logs, tick logs, recording/session context, viewport artifacts.
5. If the run fails, the operator can manually trigger failure analysis.
6. If the diagnosis produces a patch draft, the operator can manually trigger fix-and-relaunch.
7. The fix is applied to Micro6 task source, not to the ChromaScape repo.
8. The task is relaunched from the updated Micro6 task code.

This is a manual recovery loop. Failure must not automatically trigger patching.

## UI Requirements

The `/debugger` Task Planner area must contain a top-level `Builder Goal Runner` section with:

- a multiline JSON payload field
- an `Import` button
- a `Run Task` button
- an import validation/result area
- a recent run list

The section must be visually separate from `Generate Task Plan`.

The UI must communicate the distinction clearly:

- `Generate Task Plan` is for goal synthesis.
- `Builder Goal Runner` is for running a preconfigured executable task.

## Import Behavior

The import flow must accept:

- `builder_execution.task_key`
- `builder_execution.task_metadata`
- `queue_payload_preview.task_key`
- `queue_payload_preview.task_metadata`
- direct `{ task_key, settings }` style payloads

Resolution rules:

- prefer `builder_execution` over `queue_payload_preview`
- require a `task_key`
- validate the task against the Micro6 catalog
- report matched settings and ignored settings

Unknown settings do not block import. Missing `task_key` or missing catalog task does.

## Run Behavior

`Run Task` must:

- create a persisted run record
- preserve the imported payload snapshot
- preserve normalized task metadata
- execute the task via the Micro6 execution path
- not require a preexisting planner item

The initial run path is execution-only. It must not invoke the repo-local BMAD graph patch flow.

## Failure Analysis

When a builder-goal run fails, the run record must become manually analyzable.

The analysis flow must:

- collect linked evidence from the failed run
- call the analysis provider with that evidence
- persist diagnosis, patch draft metadata, and retest summary back onto the run

The analysis result must stay attached to the run so the UI can expose `Fix + Relaunch`.

## Fix + Relaunch

`Fix + Relaunch` must:

- require a stored analysis result with a patch draft
- apply the patch to Micro6 task source
- record the resulting Micro6 revision identifier
- create a child run linked to the failed parent run
- relaunch the same logical task with the normalized settings

The patch target for this flow is `micro6_task_source`.

This flow must not use the repo-local `PatchApplyService`.

## Persistence Requirements

Builder-goal runs are stored as `PatchLoopIteration` records with additional metadata:

- `sourceType`
- `patchTarget`
- `importedPayloadJson`
- `taskMetadataJson`
- `parentIterationId`
- `micro6RevisionId`
- `micro6PatchedAt`

These fields allow a builder-goal execution family to be tracked without inventing a separate run model.

## Integration Contract

Micro6 must expose:

- catalog lookup
- task execution
- task source patch application

The task source patch endpoint is:

- `POST /api/v1/tasks/{taskKey}/apply-patch`

The request carries structured patch changes. The response must include:

- `success`
- `task_key`
- `revision_id`
- `applied_change_count`
- `applied_file_count`
- `message`

## Non-Goals

This feature does not change the purpose of the planner-generated task flow.

This feature does not automatically repair failed tasks.

This feature does not patch the ChromaScape repo when fixing builder-goal runs.
