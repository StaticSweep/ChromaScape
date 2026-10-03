---
schema_version: 1
offline_record_id: "bcd6bf0b-50e9-40a4-9bb4-5d54f39cc451"
sync_status: pending
project: "C:\\Dev\\IdeaProjects\\ChromaScape"
shortcut_story_id: 1173
shortcut_tag: "shortcut:sc-1173"
felix_task_id: null
operation: reconcile_task
target_note_id: null
title: "SC-1173 — Compile Recovery corrective implementation"
note_type: documentation
stable_tags: ["shortcut:sc-1173", "change:multi-repo"]
parent_id: null
entity_links:
  - entity_type: shortcut_story
    entity_id: "1173"
    link_strength: primary
created_at: "2026-09-10T20:27:37Z"
intended_felix_task_status: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: started
intended_shortcut_marker: "[felix-sync:<felix-task-id>:started]"
---

## Task resolution

Resolve a task by stable tag `shortcut:sc-1173` in Felix project `C:\Dev\IdeaProjects\ChromaScape`; create only if absent with title **SC-1173 — Compile Recovery corrective implementation**, workflow `shortcut_delivery`, type `feature`, priority `critical`, source entity `shortcut_story:1173`, and exactly one classification tag `change:multi-repo`. Link this note to the task. Shortcut story SC-1173 is already In Progress. Do not post a marker until the corresponding Felix transition succeeds.

Scope: repair the compile-recovery system in dependency order: PostgreSQL/Flyway and JPA identity/lineage/locking; incident-first evidence capture and safe bounded logs; durable analysis orchestration; immutable proposal/approval review; fenced validation and atomic candidate publication; coordinated Micro6 exact-digest activation and rerun reconciliation; API/UI gates and polling; audit/architecture enforcement; and the full test matrix.

Exclusions: do not enable `BUILDER_GOAL_RECOVERY_MUTATIONS_ENABLED`, `BUILDER_GOAL_RECOVERY_ISOLATED_VALIDATION_ENABLED`, or `BUILDER_GOAL_RECOVERY_CANDIDATE_RERUN_ENABLED`; do not mutate a production source tree; do not rewrite an applied Flyway migration; do not dispatch candidate reruns until Micro6 activation is implemented and verified; do not use a mock/H2 database to prove PostgreSQL constraints.

Linked rules: root and repository AGENTS.md; all MCP paths use the canonical Windows project path; V47 and recovery source are currently untracked and have no Git-history evidence of deployment, so query every shared environment's `flyway_schema_history` before choosing V47 edit or V48.

## Note payload

### Required invariants

1. Evidence always references an existing incident.
2. Immutable proposals bind one incident, snapshot, file preimages, canonical patch hash, and validation-plan hash.
3. Approval binds the exact recomputed proposal and is invalid after any binding change.
4. Validation never edits the working source tree.
5. Only current lease owner may publish a candidate or terminalize a job.
6. Candidate creation and validation success commit together; no candidate is visible otherwise.
7. Reruns execute acknowledged exact candidate digest, never the installed artifact.
8. Services and PostgreSQL both enforce cross-parent lineage.
9. Each nonterminal state has live work or durable recovery.
10. Master mutations=false blocks every recovery mutation.

### Given/When/Then acceptance criteria

- Given a failed Builder Goal compile, when recovery is captured, then persist and flush `INCIDENT_CAPTURED` before artifacts, use its real ID (never `pending`), write an idempotent evidence manifest, and commit `EVIDENCE_READY` together with referenced artifacts.
- Given a provider error/restart/retry, when analysis is requested with a nonblank idempotency key, then persist a leased, retry-bounded analysis job, return 202, recover it after restart, and expose a stable retryable or terminal failure state and allowed action.
- Given a proposal, when an operator approves it, then lock and recompute source commit/tree/manifest, affected-file preimages, canonical diff, policy, validation plan, and complete proposal hashes; reject stale bindings and atomically audit/transition to `APPROVED`.
- Given a validation worker, when its lease/attempt/cancellation state changes, then every stage reports ownership; a stale, cancelled, expired, or superseded worker stops and cannot publish/terminalize.
- Given a successful isolated build, when candidate finalization occurs, then stage bytes privately, sign/verify manifest, atomically insert candidate + job `SUCCEEDED` + incident `BUILD_VALIDATED` + audit, promote only afterward, and terminalize safely without visibility on promotion failure.
- Given a candidate rerun, when activation is requested, then Micro6 verifies signature/digest/key/policy, atomically activates that digest, returns activation ID/digest, and Chroma starts a child only after persisted acknowledgement; lost responses reconcile by `executor_request_id`.
- Given disabled master mutations, when any analysis/review/validation/cancel/rerun POST is invoked, then reject it, regardless of child flags. Given a response URL, GET resolves through its owning run/incident.

### Architecture and data design

1. Flyway/JPA: public string IDs are all entity `@Id`; remove generated Long `id`, change eight recovery repositories to `String`. Add `@Version` to mutable incident, proposal, approval, validation job, and rerun. Maintain immutable artifact/candidate/audit content.
2. Migration: if V47 absent from all shared environments, correct V47; otherwise add V48. Add parent composite unique keys before composite FKs: validation→proposal `(proposal_id,incident_id)` and approval `(approval_id,proposal_id)`; candidate→job by `(job_id,incident_id)` and `(job_id,proposal_id)`, candidate→proposal `(proposal_id,incident_id)`; rerun→candidate `(candidate_id,incident_id)`. Scope idempotency by relevant parent/operation. Add an analysis-job table with ID, incident, idempotency, state, attempt/max attempts, lease token/expiry, provider/session binding, failure code/retryability, timestamps, and version.
3. Evidence: artifact identity is incident+kind+sanitized SHA-256+sanitizer version; reuse exact object, reject mismatched storage collision, and clean failed-transaction objects as unreferenced. Allow only configured launcher-owned log roots after canonical path containment; reject symlink escapes/UNC/device/traversal/arbitrary metadata and audit rejection. Use a seekable bounded tail reader with max bytes and lines, captured count and truncation.
4. Workflow: add `ANALYSIS_QUEUED`, `ANALYZING`, `ANALYSIS_FAILED_RETRYABLE`, `ANALYSIS_FAILED_TERMINAL`, `APPROVED`, `VALIDATION_CANCELLED`, `VALIDATION_UNCERTAIN`, `ACTIVATING_CANDIDATE`, and `STARTING_CHILD`. Define retryability/allowed actions and recovery scanner for every nonterminal state. Request-changes stores structured instructions, invalidates approval, locks incident for monotonic proposal version, queues a new analysis job. Reject terminalizes current cycle unless analysis explicitly requested.
5. Proposal: hunk records contain repo path, old SHA (or explicit `ABSENT` for new file), coordinates, cited artifact/diagnostic IDs, diagnostic line range, and rationale. Reject uncited hunks; deleted files bind full previous hash.
6. Validation: workers fence every mutation with lease token plus attempt; use JPA version only within each transaction. Revalidate composite proposal/approval lineage and all hashes immediately before execution. Check cancellation before checkout, apply, build, after build, and publication. Replace state-filtered incident scans with locked `transitionIncident(incidentId, expectedStates, targetState)`. Requeue expired jobs below budget; fail+transition+audit exhausted jobs; never recover cancelled/terminal jobs.
7. Micro6 contract: activation request carries executor request ID, candidate ID/digest, signed manifest/hash/key ID, package location/stream, parent run, nonce and expiry. Micro6 verifies signature/digest/key/policy, stages without overwrite, atomically activates exact digest, and returns activation/digest; support idempotent status by request. Chroma persists request, acknowledgement, activation receipt, child ID and BOUND state. Metadata contains candidate/digest/manifest/activation/parent/rerun/executor request IDs. Missing account/machine data is rejected before insert or nullable only while unbound under a state constraint.
8. API/UI: all work-creating mutations require nonblank idempotency. Add rerun GET `/api/debug/builder-goals/runs/{iterationId}/compile-recovery/reruns/{rerunRequestId}` and validate ownership. Return only routable validation URLs. Server owns canonical actions `START_ANALYSIS`, `RETRY_ANALYSIS`, `APPROVE`, `REJECT`, `REQUEST_CHANGES`, `QUEUE_VALIDATION`, `CANCEL_VALIDATION`, `RERUN_WITH_CANDIDATE`, `RECONCILE`, `NONE`. Poll returned status URL slowly for durable states, stop all terminals, warn/stop on uncertainty, resume persisted state, show active candidate digest, and report success only at BOUND. Repair mojibake.
9. Security/audit: audits belong to protected success transitions; failures retain stable codes only, no secrets/prompts/tokens/host paths. Architectural tests prevent controllers→repositories, recovery→source mutation, unfenced candidate creation, direct patch apply outside isolated worker, and ordinary child run start without activation receipt. Search all legacy mutation callers and make `PRODUCTION_COMPILE_FAILED` return `DIRECT_SOURCE_MUTATION_DISABLED`.

### Implementation checklist

- [ ] Keep all three flags false and document hierarchy in `.env.example`.
- [ ] Query shared Flyway histories; select V47 correction or immutable V48.
- [ ] Implement entity/repository ID, version, composite lineage, analysis-job, idempotency, and schema corrections.
- [ ] Implement incident-first idempotent artifacts, safe paths, bounded tail reader, cleanup.
- [ ] Implement durable analysis queue/scanner/failure/retry/request-changes/reject semantics.
- [ ] Implement immutable hunk evidence, fresh approval binding, locks, audit/state transitions.
- [ ] Implement lease+attempt fenced validation, cancellation boundaries, atomic private candidate finalization and recovery.
- [ ] Deliver and verify Micro6 activation/status API; only then wire exact-digest reruns/reconciliation.
- [ ] Enforce master and capability gates, idempotency, status routing, server-owned actions, UI polling/digest rendering.
- [ ] Add audit/architecture/legacy-mutation protections and complete evidence before review.

### Test checklist

- [ ] Unit: bounded tail; containment/symlink; diagnostics/diff/hunk citations; policy/canonicalization; approval hash; state/failure mapping.
- [ ] PostgreSQL Testcontainers: fresh Flyway V47/V48; Flyway-only schema; `ddl-auto=validate`; ordering; composite FK negatives; proposal and approval races; validation idempotency; lease reclaim/stale completion; atomic candidate/job; missing rerun metadata; reconciliation.
- [ ] Worker SC-967 fixtures: clean isolation/preimages/strict patch; compile/test/timeout/cancel/cleanup uncertainty; signed candidate; stale publication rejection.
- [ ] Micro6 contract: valid/wrong digest, invalid signature/unknown key, repeated activation, lost response, child start retry, discrepant active digest.
- [ ] Controller/UI: auth, flags, ownership, status URLs, actions, escaping, polling terminals/uncertainty, candidate digest, success only BOUND.
- [ ] Windows: `gradlew.bat clean compileJava test --no-daemon`, separated PostgreSQL suites, restart via `scripts\restart-chromascape.bat`, inspect `C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log`, and run real PostgreSQL + real Micro6 end-to-end with flags still disabled until all gates pass.

## Checklist reconciliation

Create `Implementation` and `Tests` checklists with the unchecked items above. No item has evidence or may be checked yet. Existing untracked SC-1173 source and V47 must be preserved and reconciled rather than assumed authored by this session.

## State reconciliation

Create/resolve task, attach this full documentation note, meet `planning -> spec_ready` gates without waiver, then transition to `in_progress`. Required sequence after implementation: link an `Implementation Evidence` note with headings `## Changed files`, `## Tests`, `## Remaining work`, complete implementation checklist, transition `in_progress -> in_review`; then link `Verification Evidence` with `## Acceptance results`, `## Tests`, `## Risks`, `## Rollback`, complete acceptance/test checklist, transition `in_review -> done` only after all evidence passes.

## Shortcut reconciliation

After Felix `spec_ready`/`in_progress` succeeds, set SC-1173 to mapped started state 500000008 only if needed, first searching comments for `[felix-sync:<felix-task-id>:started]`, then post one marker with task ID, outcome, evidence references, and blockers. Later use review/done mappings only after the matching Felix transition. Current external story is already In Progress; no marker has been posted in this outage.

## Sync result

Pending. Felix task creation/list requests timed out twice. No Felix task/note/checklist/state write and no Shortcut update/comment is asserted successful.
