---
schema_version: 1
offline_record_id: "4dc8b40f-69b4-4284-9f7f-3d5d25f1cf51"
sync_status: pending
project: "C:\\Dev\\IdeaProjects\\ChromaScape"
shortcut_story_id: 728
shortcut_tag: "shortcut:sc-728"
felix_task_id: null
operation: reconcile_task
target_note_id: null
title: "SC-728 revisioned gateway-backed routine editor"
note_type: documentation
stable_tags: ["shortcut:sc-728", "change:multi-repo"]
parent_id: null
entity_links: [{entity_type: shortcut_story, entity_id: "728"}]
created_at: "2026-09-10T22:45:00Z"
intended_felix_state: in_progress
intended_shortcut_transition: started
intended_shortcut_marker: "[felix-sync:<felix-task-id>:started]"
---

## Task resolution

Create or resolve the sole `shortcut_delivery` task under `C:\Dev\IdeaProjects\ChromaScape` using `shortcut:sc-728`, link Shortcut story 728, and classify it only as `change:multi-repo`. Scope: preserve SC-967 definition/diff v1; introduce v2 definition, diff, independent layout, revisioned drafts, immutable publication, strict untrusted frontend decoding, gateway-only transport, autosave/conflict/undo semantics, fail-closed capability display, and trace compatibility. Exclusions: no live control, no authority grant, no fallback to fixtures, no migration/legacy compatibility reader, no auto-merge or canvas polish before persistence.

## Note payload

### Given/When/Then acceptance criteria

- Given a real Fishing draft, when an operator edits graph content or layout, then the browser sends a debounced optimistic-revision request only through `/api/chromascape`, the server validates structurally, recomputes hashes, atomically increments the revision, and reload recovers both documents.
- Given a stale revision/hash, when save or publish is attempted, then local content is retained and a truthful 409 conflict is shown without overwrite.
- Given semantic errors, when a draft is saved, then errors remain visible and publishing is rejected; warnings are displayed and do not grant authority.
- Given an Assistant/backend payload, when any contract, authority, version, hash, placement, enum, or evidence field is malformed, then it is rejected and CONTROL remains OFF.
- Given an acknowledged valid draft and publication authority, when published, then one immutable snapshot/version is created idempotently; current authority remains false so the UI explains why publishing is disabled.

### Implementation checklist

- [ ] Replay this record to Felix; resolve/create one tagged task and persist full planning/checklists.
- [x] Add v2/draft/publication/trace schemas, dispatch, canonicalizers, semantic validator, draft/publication persistence and routes.
- [x] Add gateway draft operations, real-by-default loading, strict decoder boundary, groups, fail-closed badge, atomic store editing, deletion/viewport fixes.
- [ ] Complete autosave/conflict, Assistant transport/preview, publication dialog, trace endpoint/overlay, and co-located interaction/integration coverage if absent after verification.

### Test checklist

- [x] JSON contract files parse; `git diff --check` inspected (unrelated pre-existing whitespace warning remains).
- [ ] `gradlew.bat spotlessCheck test` and migration startup (clean and V49): pending; Gradle compilation did not finish within this session's command window.
- [ ] `npm run test`, typecheck, lint, format, build: blocked: even after `npm ci`, TypeScript standard library files and npm `.bin` shims are missing.

## Checklist reconciliation

Do not check verification or completion items until the commands above pass with retained output. No Felix checklist currently exists because the Felix MCP transport is unavailable in this session.

## State reconciliation

Create/resolve task -> add plan note/checklists -> satisfy planning/spec_ready -> transition in_progress only after validation. Remain in progress; do not transition review/done until implementation and verification evidence notes exist.

## Shortcut reconciliation

After Felix succeeds, transition only to configured started state and post exactly `[felix-sync:<felix-task-id>:started]` if absent. No state/comment mutation has occurred. Remaining blockers: unavailable Felix MCP and incomplete Windows Node dependency installation; backend Gradle verification is also unfinished.

## Sync result

Pending. No Felix or Shortcut ID/state/comment has been fabricated.
