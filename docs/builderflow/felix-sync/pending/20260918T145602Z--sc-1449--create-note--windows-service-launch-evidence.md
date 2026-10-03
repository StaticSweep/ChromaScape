---
schema_version: 1
offline_record_id: 34066a7f-deb8-4491-a114-299ff52ee9d8
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1449"
shortcut_tag: shortcut:sc-1449
felix_task_id: task_1789544291561_fyqzr7wil
operation: create_note
target_note_id: null
title: SC-1449 Windows isolated service launch evidence - 2026-09-18
note_type: documentation
stable_tags: [shortcut:sc-1449, change:multi-repo, verification-evidence, offline-sync:34066a7f-deb8-4491-a114-299ff52ee9d8]
parent_id: task_1789544291561_fyqzr7wil
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1449"
created_at: 2026-09-18T14:56:02Z
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
---

## Task resolution

Reuse Felix task `task_1789544291561_fyqzr7wil`, workflow `shortcut_delivery`, canonical
project `C:\Dev\IdeaProjects\ChromaScape`, source `shortcut_story:1449`, stable tag
`shortcut:sc-1449`, and authoritative classification `change:multi-repo`. Never create a
duplicate. This record supplements the 2026-09-18 non-live verification evidence. Felix
remained unavailable; replay this record oldest-first after earlier pending SC-1449 records.

The exact Micro6 candidate was launched from
`C:\Dev\IdeaProjects\micro6-sc1449-closure`, branch
`codex/sc-1449-closure-20260911`, base HEAD
`0e95a10d5b1aadfc3e30e315f5d41642008baceb`. The candidate used isolated port 18098,
isolated user home/reservation storage, and `MICRO6_GAMEPLAY_V2_ENABLED=false`.

## Note payload

# SC-1449 Windows isolated service launch evidence

## Acceptance results

1. **PASS - candidate launch and safety configuration.** The exact review candidate compiled
   through `:client:prepareBuilderGoal` and reached full RuneLite initialization. Logs prove
   `MICRO6_GAMEPLAY_V2_ENABLED=false`, `micro6.gameplay.v2.enabled=false`, isolated reservation
   storage, and requested executor bind address `127.0.0.1:18098`.
2. **PASS - fail-closed signed-source enforcement.** AccountBuilder attempted the production
   local executor startup and refused it with
   `source_startup_failed:MICRO6_SOURCE_METADATA_TOKEN and MICRO6_SOURCE_CONTENT_TOKEN are required`.
   Neither credential exists in the current process, user, or machine environment. Port 18098
   never bound.
3. **PASS - no gameplay or authority consumption.** The live gate remained false. No reserve,
   execute, stop, or gameplay/Fishing request was sent. The isolated store contains only its
   sequence-0 empty checkpoint plus an inactive generation-0 initialization hold; it acquired no
   reservation or active authority record.
4. **PASS - cleanup.** The exact isolated PowerShell/Gradle/RuneLite process tree was terminated
   successfully after evidence capture. The pre-existing elevated applications on ports 8090
   and 8098 were not modified.
5. **BLOCKED - cross-service probe.** ChromaScape was not launched on 18090 because the required
   Micro6 candidate dependency could not expose `/health` without its two required source
   credentials. Starting ChromaScape would not make reserve/status/stop or restart verification
   possible and would only repeat an unavailable-dependency result.

## Tests

- Production launcher contract path: `launch-buildergoal-client.ps1` ->
  `:client:runBuilderGoal`; preparation passed and the client fully initialized.
- Micro6 `/health` on `http://127.0.0.1:18098/health`: unavailable as expected after the
  signed-source startup refusal.
- Environment presence check (values never printed): both required source tokens absent at
  Process, User, and Machine scope.
- Exact isolated process-tree cleanup: passed; no listener remained on 18098.
- Isolated persistence inspection: empty sequence-0 reservation checkpoint and inactive
  generation-0 initialization hold only.

## Risks

- The service boundary remains unverified until an authorized session supplies both required
  signed-source credentials to the isolated candidate process.
- Positive reserve/status/stop and application-restart evidence cannot be collected while the
  executor correctly refuses startup.
- Bounded Fishing remains unrun and requires separate explicit live-gameplay authorization.
- Felix remains unavailable; no Felix write or state transition success is claimed.

## Rollback

The isolated runtime process tree was stopped. No source, commit, staging, push, merge, database,
existing application, or Shortcut state was changed. Runtime logs remain under
`C:\Dev\IdeaProjects\micro6-sc1449-closure\.sc1449-runtime` for evidence. Remove only that exact
isolated runtime directory after Felix evidence is safely synchronized if cleanup is desired.

## Checklist reconciliation

### Implementation checklist

- [x] Use isolated ports and keep the SC-1449 gameplay gate false.
- [x] Launch the exact Micro6 review candidate through its production launcher.
- [x] Verify signed-source startup enforcement fails closed when credentials are absent.
- [x] Stop the exact isolated process tree after capture.
- [ ] Supply `MICRO6_SOURCE_METADATA_TOKEN` and `MICRO6_SOURCE_CONTENT_TOKEN` to the isolated
  runtime without persisting or disclosing their values.
- [ ] Run Micro6 `/health` identity and disabled authenticated-route probes.
- [ ] Launch ChromaScape candidate on 18090 pointing all Micro6 gateways to 18098.
- [ ] Run cross-process reserve/status/stop, restart, timer, corruption, and kill-switch proof.
- [ ] Run separately authorized bounded Fishing acceptance if still required.
- [ ] Freeze exact final staged set, commit, push, review/merge, replay Felix, and synchronize
  Shortcut.

### Test checklist

- [x] Production candidate launch with isolated home/reservations.
- [x] Gameplay-v2 false configuration confirmed in process arguments and environment.
- [x] Missing signed-source credentials reject executor startup.
- [x] No listener or authority consumption after refusal and cleanup.
- [ ] Disabled-route live HTTP probe.
- [ ] Real ChromaScape-to-Micro6 boundary verification.
- [ ] Service restart/recovery verification.
- [ ] Bounded Fishing/live kill-switch restoration.

## State reconciliation

Keep Felix in `in_review`. On recovery, search for
`offline-sync:34066a7f-deb8-4491-a114-299ff52ee9d8` before creating this note, then reconcile
only evidence-backed checklist items. Do not transition to `done` while the credential-blocked
service boundary and any required live acceptance remain open.

## Shortcut reconciliation

Keep Shortcut SC-1449 in In Review. No Shortcut transition or idempotency marker is intended by
this record. The remaining blocker is the absence of both signed-source credentials in the
isolated Windows runtime session.

## Sync result

Pending. The isolated service launch and fail-closed behavior are verified. Cross-service and
positive runtime acceptance remain blocked on the two required signed-source credentials.
