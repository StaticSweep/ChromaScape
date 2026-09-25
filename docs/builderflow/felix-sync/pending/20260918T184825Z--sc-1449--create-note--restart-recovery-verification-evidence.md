---
schema_version: 1
offline_record_id: 63196602-e66f-4a51-b46e-1c380735df9b
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1449"
shortcut_tag: shortcut:sc-1449
felix_task_id: task_1789544291561_fyqzr7wil
operation: create_note
target_note_id: null
title: SC-1449 restart and recovery verification evidence - 2026-09-18
note_type: documentation
stable_tags: [shortcut:sc-1449, change:multi-repo, implementation-evidence, verification-evidence, offline-sync:63196602-e66f-4a51-b46e-1c380735df9b]
parent_id: task_1789544291561_fyqzr7wil
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1449"
created_at: 2026-09-18T18:48:25Z
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
---

## Task resolution

Reuse Felix task `task_1789544291561_fyqzr7wil` for Shortcut SC-1449 under canonical project
`C:\Dev\IdeaProjects\ChromaScape`, workflow `shortcut_delivery`, stable tag
`shortcut:sc-1449`, and classification `change:multi-repo`. The completed Micro6 candidate is
commit `e4c212cd7a0594e6cc9b3dfb84879a0deb6c0883` on branch
`codex/sc-1449-closure-20260911`. Felix remains unavailable; replay pending records oldest-first
and never create a duplicate task.

## Note payload

# SC-1449 restart and recovery verification evidence

## Changed files

- `C:\Dev\IdeaProjects\micro6-sc1449-closure\runelite-client\build.gradle.kts` contains the
  canonical packaged-JAR launch, source-bundle generation, compiler/processor classpaths, and
  full-commit artifact identity fixes through commit `e4c212cd7a`.
- `C:\Dev\IdeaProjects\micro6-sc1449-closure\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\tasks\source\FishingTaskSourceProvider.java`
  contains bounded authoritative javac diagnostics.
- This evidence record documents the final isolated restart/recovery verification. It makes no
  production configuration or secret changes.

## Tests

- Focused `TaskSourceProcessorPathTest` and `FishingTaskSourceProviderTest`: passed.
- Combined processor/schema run: eight tests ran; only the two pre-existing
  `TaskSourceManifestSchemaEqualityTest` mirror-pin mismatches remained.
- Signed `generateAllTaskSourceBundles`: passed. Bundle
  `srcbundle_5466e5f21c920b1911be098f`; Fishing manifest
  `sha256:3cf7810b2d7c3e1394534314a5fffdb18327e4ef0b99cb8b6a612750dd274223`.
- Clean packaged executor launched twice on `127.0.0.1:18098`. After restart, `/health` returned
  HTTP 200 with build `e4c212cd7a0594e6cc9b3dfb84879a0deb6c0883`, artifact digest
  `sha256:7a489ed1a9e15e75c0adeb4d0da3ff7c9f45d7d19d165e9042114918d5e1bab1`, and source snapshot
  `srcsnap_3cf7810b2d7c3e1394534314`, identical to the first launch.
- Restart remained on `LOGIN_SCREEN`; `login_state=not_requested`, `auto_login_state=not_requested`,
  and no live Fishing/gameplay was started.
- With gameplay v2 disabled, catalog, observe, reserve, and start failed closed with HTTP 503;
  unknown status returned HTTP 404 and an empty stop request returned HTTP 400. No reservation or
  authority was consumed.
- Persistence after restart remained an empty sequence-0/count-0 checkpoint and inactive
  generation-0 initialization hold. The checkpoint timestamp/hash changed only because the clean
  restart rewrote the equivalent empty checkpoint; the hold file hash was unchanged.
- Isolated ChromaScape on `127.0.0.1:18090` returned HTTP 200 for `/debugger`. Earlier in the same
  verification run, missing and wrong operator keys returned HTTP 401 and the correct temporary
  key with live mode disabled returned HTTP 503 `V2_DISABLED`.
- Exact isolated process trees rooted at PIDs 36180 and 16416 were stopped. Ports 18098 and 18090
  were confirmed closed. Existing primary applications on ports 8098 and 8090 were untouched.

## Remaining work

- Push the Micro6 branch and merge its reviewed pull request if repository policy and remote state
  permit.
- Replay Felix outbox records when transport recovers, transition the existing task only after
  validation succeeds, and then synchronize Shortcut with its idempotency marker.
- Live Fishing remains intentionally unrun because it was not authorized and is not required for
  the verified non-live fail-closed boundary.

## Acceptance results

- PASS: authoritative source bundle generation and exact packaged-artifact identity.
- PASS: clean executor launch and identity-preserving restart/recovery.
- PASS: disabled gameplay routes fail closed without consuming authority.
- PASS: persistence remains empty and valid across restart.
- PASS: ChromaScape debugger and authenticated disabled-boundary behavior.
- PASS: no login request and no live gameplay.
- PASS: isolated verification processes cleaned up; primary applications untouched.
- KNOWN BASELINE: two ChromaScape/Micro6 schema mirror-pin equality tests remain mismatched and are
  unrelated to the SC-1449 implementation.

## Risks

- Felix transport is still unavailable, so no Felix state or checklist mutation is claimed.
- The candidate is local until its branch/PR is pushed and merged.
- Live gameplay behavior was not exercised; safety was instead verified at the disabled boundary.

## Rollback

The implementation is isolated on `codex/sc-1449-closure-20260911`. Reverting commits
`01666e8d91`, `b65cf49ff4`, `430251fad1`, and `e4c212cd7a` removes the candidate changes. No
production runtime was modified during verification.

## Checklist reconciliation

### Implementation checklist

- [x] Resolve credential provisioning without exposing secrets.
- [x] Launch Builder Goal from the exact shaded artifact.
- [x] Generate and pass canonical artifact identity.
- [x] Repair authoritative compiler attribution and generate the signed bundle.
- [x] Complete isolated Micro6 and ChromaScape service verification.
- [x] Verify restart/recovery and persistent fail-closed state.
- [ ] Push/review/merge the Micro6 candidate.
- [ ] Replay Felix and synchronize Shortcut after Felix recovery.

### Test checklist

- [x] Focused processor-path and source-provider tests.
- [x] Signed authoritative source-bundle generation.
- [x] Executor health and artifact identity before and after restart.
- [x] Disabled-route probes without authority consumption.
- [x] Empty persistence recovery.
- [x] ChromaScape debugger and authenticated disabled boundary.
- [x] Exact isolated-process cleanup and closed-port confirmation.
- [ ] Two known schema mirror-pin equality tests require separate baseline reconciliation.

## State reconciliation

Keep Felix in `in_review` until delivery is merged. Replay this record idempotently using
`offline-sync:63196602-e66f-4a51-b46e-1c380735df9b`. Once the merge exists, add that evidence,
validate all acceptance/test checklist items according to the task's approved scope, and transition
to `done` only if Felix accepts the gate.

## Shortcut reconciliation

Keep SC-1449 in review until the merge and Felix `done` transition succeed. Do not post the done
marker from this record alone. After Felix reaches done, check existing comments before posting
`[felix-sync:task_1789544291561_fyqzr7wil:done]` and apply the repository's mapped Shortcut done
state.

## Sync result

Pending. Runtime implementation and non-live restart/recovery verification are complete. Delivery
merge plus Felix replay and Shortcut synchronization remain.
