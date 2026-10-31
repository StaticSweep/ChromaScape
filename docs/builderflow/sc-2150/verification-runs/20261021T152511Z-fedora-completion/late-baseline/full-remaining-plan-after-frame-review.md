# SC-2150 — Full remaining implementation and verification plan

Date: 2026-10-21.
Project: C:\Dev\IdeaProjects\ChromaScape.
Felix task: task_1792382767281_cd8dbu69; workflow shortcut_delivery; in_progress / draft.
Shortcut: sc-2150, In Progress (500000008). Preserve classification change:ui.

This is a planning-only update after the frame-index/correction-contract/review-panel increment. It defines remaining work, not implementation performed today. No application change, feature enablement, runtime restart, workflow transition or acceptance pass is implied. New RI/RA/RT items are unchecked.

## 1. Plan of record and preserved delivery

Apply the shared delivery contract, repository AGENTS.md, docs\CHROMASCAPE_RESTART_RUNBOOK.md and .felix\shortcut-sync.json. Retain linked rule rule_1792384572818_kvbix29u and the existing task; no duplicate task.

Preserve:
- docs\builderflow\sc-2150\fedora-implementation-and-qualification-plan.md: architecture, contracts, safety boundaries and frozen qualification gates.
- docs\builderflow\sc-2150\implementation-evidence-frame-index-and-review-contract.md: delivered increment and reported checks.
- note_1792545137990_d62e4b2a: remaining-work assessment.
- Earlier F1–F5/RV1–RV3 fixes and their evidence. Do not reopen/reimplement them without a new reproducible failure.

This plan is authoritative for execution order and remaining gates after that increment. It refines existing FI/FA/FT and PI/PA/PT plans; it does not erase prior scope, content, checked states or evidence.

Current scope boundaries remain: SC-303 catalog/inspection integration, SC-305 quality admission, SC-712 published curation/confirmation, SC-309 movement findings and SC-2151 exact native-tick alignment. No live control, model activation, physical-input reconstruction or training permission inferred from video.

The pending outbox was scanned: no matching SC-2150 record. Preserve unrelated conflicts. Scan again before future writes. Use the documented outage protocol after two actual transport failures; validation responses are not outages.

## 2. What is delivered versus still unqualified

| Surface | Delivered/evidenced | Still required |
|---|---|---|
| Correction contract | corrections/v1, validation, bounds and canonical digest | Integrate exact-preview-backed point/region UX; verify complete real-server behavior |
| Frame-index reads | Owner/analysis/digest-bound paging and string PTS/timebase | Live-server/browser and resource-load qualification |
| Observation reads | Bounded read API and honest absence response | Actual observation producer, quality artifacts and end-to-end integration |
| Review backend | Request digest/version, retry/body conflicts, preserved revision content and lineage | Full real-PostgreSQL/browser races and qualification against new preview/analysis work |
| Review panel | Frame table/stepping, approximate seek label, drafts, source-use, history, conflict handling and pinned export | Exact previews, precise point/region interactions and real-browser qualification |
| Migration | V44 review request identity | Additive analysis/preview metadata/job-kind changes as needed; never rewrite V44 |
| Runtime safety | Existing execution containment and lease/output fixes | Restricted launcher, enforceable resource/filesystem/network policies and attack tests |
| Feature producers | Existing probe/derivatives | Cursor and additional visual observations; durable new scheduling |
| Verification | Supplied report: earlier 187 video tests, 16 PostgreSQL cases, 335 JS tests; current retained focused XML: 55 tests, zero failures/skips | Reproduce from a frozen manifest, boot a server, run actual browser/ownership/lifecycle qualification |

The retained 55-test XML was inspected in the preceding assessment; the earlier 187/335 totals were supplied evidence, not a new independent execution in this planning turn. Counts may grow. Full repository Java tests were not claimed run.

A Playwright Chromium binary was found and its version command succeeded during the preceding assessment. Do not preserve “no installed Chrome” as a blanket blocker. Headless launch/dependencies and actual page behavior still need testing.

Global Spotless was last reported failed on unrelated aiplayer work. Recheck the actual current state; neither changed-file formatting nor successful Checkstyle makes the whole repository green.

## 3. Work order and milestones

| Step | Deliverable | Depends on | External approved clips/pins needed? |
|---|---|---|---|
| R0 | Reproducible real-server/browser harness and current smoke evidence | Existing increment | No |
| R1 | Qualified Fedora restricted launcher and enforced budgets | R0 environment | No clips; host provisioning may be needed |
| R2 | Durable analysis/preview queue, identities and migrations | R0; execution requires R1 | No |
| R3 | Exact-frame preview API, cache, media and GC | R1–R2 | No |
| R4 | Complete review UI and real-browser behavior | R0, R3 | No |
| R5 | Cursor/other visual workers and immutable result integration | R1–R2; overlays use R4 | Synthetic development can start now |
| R6 | Lifecycle/resource/security qualification | Relevant R0–R5 paths | No |
| R7 | Held-out accuracy and determinism qualification | R5–R6, approved corpus/templates | Yes |
| R8 | Approved YouTube acquisition qualification | R1–R2, approved tool/clip/egress | Fixture work can start now; live gate needs inputs |
| R9 | Final regressions, operator/rollback and tracker handoff | All required gates | Depends on completion of R7/R8 and required hosts |

First reviewable milestone: existing review panel running against the real backend with an honest capability state and recorded HTTP/browser smoke checks.
Second milestone: isolated exact preview -> precise correction -> immutable review/export.
Third milestone: synthetic visual analysis -> published observations/quality -> browser overlays.
Final milestone: approved-source qualification plus operations/recovery and required-host evidence.

No sub-agent delegation is assumed. Independent coding/fixture preparation may proceed while external prerequisites are pending; missing approved inputs do not block all Fedora work.

## 4. R0 — Freeze, boot and exercise the current increment

1. Record revision/branch, dirty/untracked files and hashes of all relevant code/templates/scripts/config. Preserve user work; no reset, clean or broad formatting.
2. Record Java, PostgreSQL/Docker, Python/FFmpeg, browser and dependency identities. Verify Docker access from the Gradle JVM, not only the shell.
3. Use isolated Gradle output and project cache. Shared output rewriting previously invalidated a test attempt.
4. Reproduce the existing focused/full video selections, JS and native-page tests from exact recorded commands. Retain exit status, XML/HTML, skip reasons and source/tool manifest. Reproduce retained regression probes without putting temporary sources into the repository tree.
5. Boot an isolated Spring instance with a disposable DB/media root and a checked-free loopback port. Do not restart or reuse 8090. Verify effective profile, URL, database and media location; do not assume the Fedora helper loads all video variables.
6. Launch the available Chromium headlessly, load the actual Spring-rendered video page and exercise safe synthetic upload/index/review/source-use/history/export reads. Capture traces/screenshots and actual API responses. Existing static Playwright fixtures remain separate regressions.
7. Add an anonymous protected-endpoint smoke case and a trusted owner-B test instance. Fixtures must not introduce production test-auth routes or trust a caller-supplied owner header.

Before R1 is qualified, use controlled synthetic fixtures for this harness, not untrusted downloaded media.

Pass gate: repeatable actual-server/browser launch, attributable baseline, owner smoke outcomes, recorded capability states and a cleanup manifest for owned instances/data/processes.

## 5. R1 — Enforce Fedora worker boundaries

Keep per-execution systemd cgroup containment and cleanup confirmation. Compose a fixed server-owned restricted launcher using the installed bwrap/systemd facilities only after verifying user namespaces, SELinux, user-session delegation and effective controllers.

Required enforcement:
- Read-only approved input/tool/runtime/script/template mounts; private writable workspace/output.
- Sanitized environment, no home/application secrets or inherited credentials.
- No decoder/analysis network access. Acquisition uses a separate constrained egress path.
- Memory, CPU, PID, wall-clock, output and filesystem bounds enforced independently of cooperative application counters.
- A quota-enforced per-job filesystem/work volume. Required provisioning is an explicit host prerequisite; do not silently replace disk quota with “check size after process exit.”
- Per-launch preflight and truthful capability/readiness errors when enforcement is unavailable.

Retain agreed caps: 512 MiB upload/download; 520 MiB request; 16 KiB metadata; 20 minutes; 3840x2160; 120 fps; 150,000 indexed/decoded frames; <=60 fps exact-PTS cursor sampling; 4 GiB source/job disk; 2 GiB group memory budget; probe 30 s, acquire 10 min, analysis 20 min, child shutdown 5 s. Qualify the prior plan's proposed CPUQuota=200% and TasksMax=64, including bounded decoder/worker threads. Freeze additional intake/network/output budgets before running the gate.

Attack tests: child changes session, surviving descendants, secret read, outbound connection, disk/memory/PID exhaustion, silent/flooding process, unterminated output and incomplete drain. Inspect effective cgroup/filesystem limits, not only accepted arguments.

Pass gate: boundaries hold under independent child attempts; cleanup/output uncertainty stays explicit and prevents complete publication.

## 6. R2 — Durable scheduling, request identity and schema

Add explicit POST /sources/{sourceId}/analyses and preview creation to existing job admission/registry/heartbeat/finalization. Reuse durable execution generations, unexpired leases and PostgreSQL time; do not create unmanaged background workers.

Analysis identity includes source/original digest, algorithm/version, normalized configuration, template contents/provenance and toolchain pins. Preview identity includes analysis/index digest, frame ordinal and preview method/config. Store request/body identity before dispatch:
- Same request/body -> same job/result.
- Same request/changed body -> 409.
- Concurrent equivalent content work -> one active computation/cache publication.
- Foreign source/analysis/job -> nondisclosing refusal.
- Cancelled/replaced/expired executions -> cannot publish or settle their replacements.

Audit one consistent lock order and atomic cross-instance admission. Keep one active/eight waiting research jobs in the defined scope; verify per-owner budgets and total admission.

Inspect migration inventory and choose the next free version after V44, not an assumed V45. Add preview metadata/reference uniqueness, job-kind constraints and needed indexes. Do not recreate review request-digest/version columns. Fresh/upgrade tests must preserve historical corrections/v0, V44 digests, source identities and old export bytes.

Pass gate: real PostgreSQL duplicate/cancel/finalization/admission races and upgrade tests with no partial metadata or false success.

## 7. R3 — Exact-frame previews and byte lifetime

Implement:
- POST /sources/{sourceId}/analyses/{analysisId}/frame-previews.
- GET /frame-previews/{previewId}.
- GET /frame-previews/{previewId}/media.

POST schedules/coalesces work; GET reads metadata or already-published bytes and never starts FFmpeg. Return the existing/pending receipt truthfully.

Resolve source/analysis/index identity before dispatch. Decode the selected source frame using the frozen frame ordinal and actual PTS; do not substitute an approximate proxy seek. Record rotation/crop/resize transforms, source display-pixel dimensions and preview identity. Preserve integer PTS/rational representation through browser transport.

Stage and content-hash preview bytes; register only after complete owned execution and generation/lease validation. Reuse safe media headers/MIME policy. Add preview references to sweeper protection and retention rules, including shared byte hashes. Failed publication leaves a recoverable orphan/staging outcome, not FINALIZED metadata.

Pass gate: CFR/VFR, repeated/duplicate PTS, rotation/crop, concurrent requests, cancellation/replacement and crash-point tests resolve the correct frame; referenced preview bytes survive GC.

## 8. R4 — Complete the existing review panel

Extend the delivered panel; do not rebuild its contract, history and retry behavior unnecessarily.

- Display exact-preview pending/failed/ready states. Never place a newly selected frame's overlay on an older image.
- Enable visible cursor points and target/camera regions only when exact source-pixel mapping is available. Retain explicit hidden/ambiguous/unsupported states.
- Show original predictions and reviewed overlays separately. Carry selected source, analysis, frame/index identity and revision consistently into save/export.
- Preserve drafts across 409, transient requests, polling, reload-base and source/analysis navigation. Prevent late asynchronous responses from updating the wrong selection. Never submit a draft under another analysis accidentally.
- Maintain idempotent transport retries; use a new request ID after a deliberate changed-body/reapply action.
- Preserve reason, predecessor/history, canonical request identity and immutable earlier content.
- Keep declared origin, evidence provenance and source-use review distinct. TRAINING_REQUESTED requests downstream admission; it never toggles trainingEligible/nativeTelemetry/exactTicks/control.
- Provide keyboard selection, focus/error announcements, narrow-screen layout and safe text rendering.
- Keep corrections/v1 compatibility. If new payload fields are required, version and validate them explicitly rather than silently relaxing unknown-field checks.

Pass gate: actual-backend Playwright tests for point/region/unknown corrections, retries/conflicts/history/export plus DB/revision/byte assertions, not mocked API responses.

## 9. R5 — Produce visual observations and quality artifacts

Use a fixed server-owned, versioned worker with pinned interpreter/dependencies/script/templates. No arbitrary executable user templates, runtime package downloads or unrecorded tool updates.

Implement bounded source-frame processing:
- Cursor: finite template/scale/hotspot search with documented confidence/ambiguity rules; no interpolation across hidden spans/cuts.
- Pauses: contiguous visible low-motion intervals in source video time; prior planned initial rule <=1 equivalent 1080p pixel over >=250 ms.
- Cuts: bounded scene-difference detector with source boundary frames and tracker reset.
- Action indicators: supported visible menu/highlight/cursor changes labelled candidates, never physical button events.
- Target regions: visible region bounds only; game entity/world coordinates remain unknown without evidence.
- Camera/activity: supported global/regional visual-change candidates with confidence, not inferred gameplay state.
- Crop/resize/rotation/overlays/compression/VFR/speed changes: record supported transforms or explicit limitations; never infer polling rate, physical velocity, hidden coordinates or native ticks.

Synthetic fixtures may use generated cursors/backgrounds without masquerading as approved held-out data. Cap selected processing at 60 fps using actual indexed PTS; record sampling gaps and avoid unbounded frame extraction.

Write versioned OBSERVATIONS, QUALITY and manifest artifacts compatible with the delivered read API. Validate output schema, finite data, frame references, method/config/tool/template identities and output completeness before atomic fenced publication. Existing reads continue reporting absence until valid artifacts exist.

Quality describes coverage/uncertainty/measurements; admission remains SC-305/SC-712 policy. Exports pin exact original, analysis, review, index, method and tool/template/config identities. Repeated identical inputs yield identical normalized output hashes; changed identities produce distinct analyses.

Pass gate: synthetic golden cases and real durable-job integration produce deterministic source-linked artifacts and honest unknowns.

## 10. R6 — Qualification across APIs, lifecycle and resources

Ownership: real-server anonymous 401 and trusted independent owner 404 across sources, analyses, frame/observation pages, previews/media, jobs, reviews and exports. Operator owner B may use a separate local-development JVM with server-side user.name; no caller owner header. This does not establish an untested production auth provider.

Browser: actual upload/retry/failure, playback/seeking/Range, exact previews, cancellation/reconciliation, review/source-use/conflict/history/export, accessibility and narrow screens. Preserve static native/browser regression suites separately.

Lifecycle: cancellation at acquire/probe/index/analysis/preview/publication; crash before/after bytes and registration; restart/expiry/replacement; reservations; two-instance admission/finalization; sweeper grace/shared hashes/referenced bytes. Verify healthy leases survive reconciliation and stale completions cannot mutate replacements.

Read-path memory: page/encoded-byte caps and a four-entry cache do not themselves prove a safe total heap budget. Measure concurrent frame/observation loads near allowed artifact sizes. Add byte-weighted cache/parse admission or a compatible streaming/indexed artifact path if necessary. Keep large worker artifacts from exhausting the application JVM. Use lowered test limits rather than enormous routine fixtures.

Preserve all prior containment, real-EOF/callback, timing, null-code diagnostic, MIME, revision and immutable-export regressions.

Pass gate: retained HTTP/browser/DB/filesystem/process outcomes for each matrix case, with no required row quietly marked passed from unrelated unit totals.

## 11. R7 — Approved held-out qualification

Obtain rights-approved templates and annotated source clips with visibility, hotspots, event/region/cut/transform labels and provenance. Split whole source videos before tuning; derivatives stay in the same split. Freeze methods/configuration and thresholds before opening held-out results.

Retain existing cursor gates at 1080p-equivalent source-pixel scale:
- Median position error <=4 px; p95 <=12 px.
- Visible-frame recall >=90%.
- Cursor-absent false detections <=1%.

Retain the prior Fedora plan's additional targets: cut F1 >=0.90 within one indexed frame; advertised action/target/camera/activity candidate precision >=0.95 and recall >=0.80; pause-boundary p95 within two selected-frame intervals. Golden unsupported cases must not fabricate physical/native evidence.

Report sample counts, duration/coverage, per-source/case error distributions, uncertainty/confidence intervals, sampling gaps and failure breakdowns. Do not pass by emitting unknown for every supported case or by tuning against held-out labels. Distinct toolchains/configurations have distinct identities.

Pass gate: every advertised required method meets its frozen gate on approved source-disjoint data. Missing rights/labels/templates remains an explicit external blocker.

## 12. R8 — Approved YouTube acquisition

The existing adapter is a starting point, not proof of qualified acquisition. Obtain approved executable/runtime/dependency pins and hashes, public clip provenance and required egress provisioning.

Verify actual version/argv compatibility, including existing flags; no assumption that an arbitrary latest downloader accepts them. Separate acquisition egress from no-network decode/analysis.

Enforce public-address, protocol, redirect and DNS-rebinding policy through a constrained path the downloader cannot bypass. Cover downloader subrequests/CDNs, private/loopback/link-local/metadata targets, bounded redirects, size/time/output/disk limits and cancellation/descendant cleanup. No playlists, cookies, authentication bypass, runtime plugins or silent updates.

Use fixtures for removed/private/unavailable/auth/rate-limit/redirect/timeout/oversize/failure cases, and the approved live clip for actual acquire -> analysis -> browser -> immutable export. Record canonical source/video ID, selected format, acquisition time, pins and final byte digest.

Pass gate: fixture and approved live paths pass with reproducible provenance and cleanup; keep YouTube off otherwise.

## 13. R9 — Final regressions, operations and delivery

Run the expanded video and real-PostgreSQL selections, full required native regression, full JS/Python, static and real-backend browser suites, retained probes, Spotless and Checkstyle. Record exact commands, exits, counts/skips and tool/input/source hashes. New counts replace the old baseline; do not assert all-repository Java success from video-only results.

Resolve current global formatting failures through the owner of unrelated work; do not broadly format dirty files or disguise a global failure as a scoped pass. Fix formatting introduced by this feature.

Write and rehearse Fedora preflight/pins/capabilities, admission/lease inspection, private diagnostics, reconciliation, GC/retention, disable/new-intake stop, owned cleanup, restart/recovery and rollback. Preserve immutable media/reviews/exports; reverse only isolated changes and use compatible forward migrations.

Windows containment stays a separate required-host decision/qualification. Fedora success does not prove Windows support. Do not silently narrow a required release-host scope.

Before in_review: check implementation items only with linked Implementation Evidence containing ## Changed files, ## Tests, ## Remaining work. Before done: acceptance/test evidence must contain ## Acceptance results, ## Tests, ## Risks, ## Rollback and all required gates must actually pass. Resolve draft/spec validation normally; no skip_validation or waivers.

This planning action performs no transition. Future Shortcut synchronization follows .felix\shortcut-sync.json only after successful Felix transitions and an idempotency-marker search:
- [felix-sync:task_1792382767281_cd8dbu69:planned]
- [felix-sync:task_1792382767281_cd8dbu69:started]
- [felix-sync:task_1792382767281_cd8dbu69:review]
- [felix-sync:task_1792382767281_cd8dbu69:done]

Blocked/cancelled mappings remain null; never invent their states or claim a failed mutation succeeded.

## 14. Design and evidence artifacts

~~~mermaid
flowchart LR
  Current[Delivered read and review layer] --> Harness[Real-server/browser harness]
  Harness --> Launcher[Qualified restricted launcher]
  Launcher --> Queue[Durable analysis/preview queue]
  Queue --> Preview[Exact source-frame preview]
  Preview --> Review[Precise correction and immutable history/export]
  Queue --> Visual[Visual producer and quality artifacts]
  Visual --> Review
  Visual --> Heldout[Approved held-out qualification]
  Launcher --> Provider[Approved constrained YouTube acquisition]
  Review --> Final[Lifecycle/regression/operations handoff]
  Heldout --> Final
  Provider --> Final
~~~

Data model: existing Source -> Analysis -> ArtifactRef and immutable ReviewRevision/Export lineage remain. Preview metadata references analysis/index/frame identity and bytes; Job references execution generation/lease. GC must include new references. V44 review identity is reused. New migrations affect scheduling/preview metadata only as required.

API/UX contract: explicit POST schedules analysis/previews; GET reads only immutable results. Exact source-frame metadata and source-pixel transforms govern corrections; approximate player seeking stays labelled approximate. Existing validated v1 payloads and legacy rows remain supported.

Security/observability: owner scope before work/bytes, nonce-protected writes, fixed commands/pins, no secrets or decoder egress, constrained acquisition, enforced resources and public-safe errors. Retain private correlation diagnostics and bounded stage/result metrics; avoid unbounded owner/source/job metric labels.

Evidence per phase: manifest, exact commands/exits, JUnit/HTML/style results, separate HTTP headers/body/status, DB/job snapshots, owned process/cgroup outcomes, browser traces/screenshots, artifact hashes, and PASS/FAIL/NOT RUN/MISSING/BLOCKED matrix. Private diagnostics are restricted; public artifacts contain no server paths/secrets.

Expected touched surfaces: existing video controller/services/properties/jobs/repositories/finalization/GC/export and review template/JS/CSS; new scheduling/preview/visual-worker contracts/classes/scripts, additive migration(s), real-backend browser config/spec, synthetic/approved fixture manifests and operator docs. These are proposed changes, not files claimed created by this plan.

## 15. Remaining implementation checklist

- [ ] RI0 — Freeze the current increment and establish an isolated Fedora real-server/browser harness with anonymous and independent-owner smoke checks.
- [ ] RI1 — Implement and qualify the restricted Fedora launcher, enforced resource/disk budgets and fail-closed readiness.
- [ ] RI2 — Add durable analysis/preview scheduling, request/content identities, admission and additive schema changes after V44.
- [ ] RI3 — Implement exact-frame preview creation/read/media, immutable cache coalescing, source transforms and GC reference protection.
- [ ] RI4 — Complete precise correction/region UI, observation overlays and conflict/history/export behavior against the real backend.
- [ ] RI5 — Implement pinned cursor and other visual observation workers, quality artifacts and fenced immutable publication.
- [ ] RI6 — Complete lifecycle, read-path memory, quota, crash/restart, expiry/sweep and multi-instance qualification; fix observed failures.
- [ ] RI7 — Obtain approved corpus/templates and complete source-disjoint held-out accuracy and determinism qualification.
- [ ] RI8 — Qualify approved pinned YouTube acquisition with constrained egress, actual public clip and complete failure/cleanup coverage.
- [ ] RI9 — Complete final regression, operator/rollback evidence, required-host qualification and truthful Felix/Shortcut handoff.

## 16. Given/When/Then acceptance checklist

- [ ] RA1 — Given the current dirty Fedora checkout, when verification starts, then isolated build/database/media/ports and exact manifests protect the existing 8090 service and make each result attributable.
- [ ] RA2 — Given untrusted media workers, when they attempt secret/network/process escape or exceed CPU/memory/PID/disk/time/output limits, then enforced boundaries hold and readiness/publication fail closed.
- [ ] RA3 — Given repeated or changed analysis/preview requests and competing workers, when dispatched or settled, then identities coalesce identical work, changed bodies conflict and only the current unexpired execution may publish.
- [ ] RA4 — Given a source frame, when an exact preview is requested and read, then frame ordinal/PTS/index digest/transforms are pinned, pending state is honest, GET creates no work and referenced bytes survive sweeping.
- [ ] RA5 — Given real browser corrections and concurrent revisions, when saving/retrying/reapplying/exporting, then drafts survive conflicts, original predictions and old revisions/exports remain immutable and training admission is not implied.
- [ ] RA6 — Given declared supported visual cases, when analyzed, then cursor/action/pause/target-region/camera/activity/cut observations retain exact source evidence, confidence and unknowns without invented physical/native telemetry.
- [ ] RA7 — Given anonymous/foreign owners, large artifacts, crashes, expiry, cancellation or competing instances, when APIs and lifecycle operations run, then access is nondisclosing, reads remain bounded and stale or partial results never become complete.
- [ ] RA8 — Given approved source-disjoint held-out clips, when evaluated against frozen methods, then the existing cursor and additional-observation gates pass with coverage, counts and uncertainty reported.
- [ ] RA9 — Given approved downloader pins/public clip, when acquisition succeeds or fails, then constrained egress, provenance, quotas, stable outcomes and cleanup are verified without authentication bypass.
- [ ] RA10 — Given final evidence and all required hosts, when release is considered, then no required gate is waived, global check failures are resolved honestly and Felix/Shortcut reflect only successful validated transitions.

## 17. Remaining test checklist

- [ ] RT0 — Reproduce scoped Java/PostgreSQL, JS and retained-probe baselines; launch actual Spring/Chromium and verify anonymous/second-owner smoke cases.
- [ ] RT1 — Attack the Fedora launcher and independent cgroup/filesystem/network limits with bounded hostile fixtures and actual effective-limit inspection.
- [ ] RT2 — Test analysis/preview request identities, migrations from V44, cross-instance admission, generation/lease fences and cancellation races using real PostgreSQL.
- [ ] RT3 — Test exact preview ordinal/PTS/rotation/crop identity, repeated/concurrent requests, media headers, failed publication and GC protection.
- [ ] RT4 — Run real-backend Playwright corrections/source-use/conflict/history/export flows with keyboard/narrow-screen checks, traces and persisted-state assertions.
- [ ] RT5 — Run deterministic synthetic cursor/observation golden cases, VFR/cuts/occlusion/transforms and malformed/incomplete worker-output regressions.
- [ ] RT6 — Exercise lifecycle crash points, resource/frame cap plus one, bounded concurrent artifact reads, sweeper grace and multi-instance recovery.
- [ ] RT7 — Evaluate approved held-out cursor/other observations against all frozen gates, per-source coverage and identical-input output hashes.
- [ ] RT8 — Verify pinned downloader CLI compatibility, approved live import and address/redirect/DNS/timeout/limit/cancellation failure corpus.
- [ ] RT9 — Run final native/video/JS/Python/browser/style checks, rehearse disable/recovery/rollback and reconcile required-host and delivery evidence.

## 18. External inputs and truthful completion

Available to work on now: R0–R6 using synthetic fixtures, plus R7/R8 manifest/fixture preparation and operator docs.
External dependencies: approved template/corpus rights and annotations, approved downloader/public clip, host quota/cgroup/egress provisioning if missing, and required release-host scope.

A permanent callback blocked in user code remains an explicitly incomplete execution, not silently complete output. Database ownership uncertainty remains fail-closed. Host isolation unavailability remains a readiness failure. Missing clips/pins do not justify marking required gates passed.

Planning deliverable: this file, the complete linked Felix note and unchecked RI/RA/RT task amendments. Application code/runtime and prior evidence/checklist states stay unchanged while writing it.


## 19. Implementation increment 2026-10-21

Code now exists for the restricted launcher, durable analysis/preview scheduling, exact-frame
previews, the visual producer and the exact-preview review panel. Evidence:
`docs\builderflow\sc-2150\implementation-evidence-analysis-preview-visual.md`.

Qualified in this increment:
- V42-V45 applied to a fresh real PostgreSQL database; V45 preview identity uniqueness and the
  PREVIEW job kind/stage were exercised.
- 200 video-tree Java tests passed with 0 failures; 16 Testcontainers cases were skipped because
  Docker was unavailable to that test JVM. 13 JS tests passed.
- A real Spring instance processed upload -> import -> visual analysis -> observations -> exact
  preview (including coalescing) -> review -> export with pinned previews.
- Anonymous 401 and independent-owner 404 were observed on real instances.
- A restricted-launcher instance ran an import successfully under bwrap/systemd limits; its
  readiness endpoint reported the effective budget and network isolation.
- Headless Chromium exercised the page, exact preview, point selection and narrow layout with no
  console errors.

Not yet qualified: Testcontainers PostgreSQL races, cancellation/crash/expiry/sweeper/quota and
competing-instance matrices, browser conflict/history/export interaction and accessibility, VFR/
rotation/crop previews, a real per-job disk quota, approved held-out accuracy, YouTube acquisition,
final regressions/rollback and Windows containment. RI/RA/RT checkboxes remain unchecked where the
gate is partial or not run.
