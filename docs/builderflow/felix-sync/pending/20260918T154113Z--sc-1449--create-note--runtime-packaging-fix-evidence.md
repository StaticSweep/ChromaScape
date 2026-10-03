---
schema_version: 1
offline_record_id: 56e2d384-16e6-43b1-8740-e3f13226e8c1
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1449"
shortcut_tag: shortcut:sc-1449
felix_task_id: task_1789544291561_fyqzr7wil
operation: create_note
target_note_id: null
title: SC-1449 runtime packaging fix evidence - 2026-09-18
note_type: documentation
stable_tags: [shortcut:sc-1449, change:multi-repo, implementation-evidence, verification-evidence, offline-sync:56e2d384-16e6-43b1-8740-e3f13226e8c1]
parent_id: task_1789544291561_fyqzr7wil
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1449"
created_at: 2026-09-18T15:41:13Z
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
---

## Task resolution

Reuse Felix task `task_1789544291561_fyqzr7wil` for Shortcut SC-1449 under canonical project
`C:\Dev\IdeaProjects\ChromaScape`, workflow `shortcut_delivery`, stable tag
`shortcut:sc-1449`, classification `change:multi-repo`. The implementation is committed on
Micro6 branch `codex/sc-1449-closure-20260911` as `01666e8d91`. Felix remains unavailable;
replay oldest-first and never create a duplicate task.

## Note payload

# SC-1449 runtime packaging fix evidence

## Changed files

- `C:\Dev\IdeaProjects\micro6-sc1449-closure\runelite-client\build.gradle.kts`
  now makes `runBuilderGoal` build an artifact identity, launch the exact shaded JAR, embed the
  canonical Git commit as `Micro6-Build-Id`, and write the same commit identity to the sidecar.
- The committed SC-1449 executor, governor, durable dispatch-hold store, and focused tests are
  included in commit `01666e8d91` (seven files total).

## Tests

- Clean detached worktree build of `:client:micro6ArtifactIdentity`: passed in 9m55s.
- Canonical credentials were discovered in existing git-ignored Micro6 environment files and
  loaded without printing or copying values into the repository.
- Runtime fail-closed sequence verified: missing credentials -> missing sidecar -> running artifact
  mismatch -> build identity mismatch -> promoted bundle mismatch. Each earlier defect was resolved
  without bypassing validation.
- Clean candidate source-bundle generation with explicit Lombok processor path and shaded-JAR
  compiler classpath still fails with `SOURCE_INDEX_PARTIAL`: authoritative compiler attribution
  or diagnostics failed. This matches the previously reproduced baseline
  `TaskSourceProcessorPathTest` failures.

## Remaining work

- Repair the authoritative source compiler attribution/processor-path configuration and generate
  a promoted bundle bound to commit `01666e8d91` and its exact shaded JAR.
- Start the executor on 18098 and finish ChromaScape 18090 boundary/restart verification.
- Push/review/merge only after those runtime checks pass; then replay Felix and synchronize
  Shortcut. Live Fishing remains separately authorization-gated.

## Acceptance results

- PASS: credentials are provisioned and can be securely consumed.
- PASS: production Builder Goal now launches the attested packaged artifact rather than loose
  classes and uses one canonical commit identity across JAR and sidecar.
- PASS: clean candidate packaging and identity generation.
- BLOCKED: promoted authoritative bundle generation fails in the known compiler-indexer baseline.
- NOT RUN: executor HTTP and ChromaScape cross-service verification because no verified bundle can
  yet be promoted.

## Risks

- Disabling bundle validation or reusing the canonical checkout's older bundle would create a
  false artifact identity and is prohibited.
- Commit `01666e8d91` is local and unpushed.
- Felix remains unavailable, so no task/checklist/state write is claimed.

## Rollback

The implementation is isolated on branch `codex/sc-1449-closure-20260911`. Existing elevated
applications on ports 8090 and 8098 were not changed. All alternate launch processes exited or
were stopped; no listener remains on 18098.

## Checklist reconciliation

### Implementation checklist

- [x] Resolve credential provisioning without exposing secrets.
- [x] Launch Builder Goal from the exact shaded artifact.
- [x] Generate and pass an artifact identity sidecar.
- [x] Align JAR, sidecar, and bundle build identity to the Git commit.
- [x] Commit the isolated SC-1449 candidate as `01666e8d91`.
- [ ] Repair authoritative compiler attribution and promote the candidate bundle.
- [ ] Complete 18098/18090 service verification.
- [ ] Push/review/merge and synchronize trackers.

### Test checklist

- [x] Clean packaged artifact build.
- [x] Credential presence and safe loading.
- [x] Artifact/sidecar fail-closed checks.
- [ ] Promoted source-bundle generation.
- [ ] Executor health and disabled-route probes.
- [ ] ChromaScape cross-service restart/recovery verification.

## State reconciliation

Keep Felix in `in_review`. Replay this record idempotently using
`offline-sync:56e2d384-16e6-43b1-8740-e3f13226e8c1`. Do not transition to done until the compiler
blocker and service checks are resolved.

## Shortcut reconciliation

Keep SC-1449 In Review. No transition or marker is intended by this record.

## Sync result

Pending. Packaging and identity defects are fixed and committed; authoritative source compiler
attribution remains the concrete blocker.
